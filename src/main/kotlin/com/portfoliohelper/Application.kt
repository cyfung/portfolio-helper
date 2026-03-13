package com.portfoliohelper

import com.portfoliohelper.service.*
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.web.configureRouting
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun String.toPortfolioSlug() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
fun String.toPortfolioDisplayName() = replaceFirstChar { it.uppercase() }

fun main() {
    // Force IPv4 to avoid JmDNS issues on Windows (SocketException: setsockopt)
    System.setProperty("java.net.preferIPv4Stack", "true")

    val logger = LoggerFactory.getLogger("Application")

    // ---------------------------------------------------------------
    // 0. Resolve data directory (env > config file > OS default)
    // ---------------------------------------------------------------
    AppDirs.dataDir = System.getenv("PORTFOLIO_HELPER_DATA_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: AppConfig.get(AppConfig.KEY_DATA_DIR).takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: AppDirs.osDefaultDataDir
    logger.info("Active data directory: ${AppDirs.dataDir.toAbsolutePath()}")

    // ---------------------------------------------------------------
    // 1. Ensure data directory exists; seed all bundled files if new
    // ---------------------------------------------------------------
    val dataDir = AppDirs.dataDir
    val mainCsvPath = dataDir.resolve("stocks.csv").toString()
    logger.info("Data directory: ${dataDir.toAbsolutePath()}")
    if (!Files.exists(dataDir)) {
        Files.createDirectories(dataDir)
        val cl = object {}::class.java.classLoader
        listOf("stocks.csv", "cash.txt", "README.md").forEach { name ->
            cl.getResourceAsStream("data/$name")?.use { Files.copy(it, dataDir.resolve(name)) }
        }
        logger.info("Created data/ directory and seeded bundled default files")
    } else if (!Files.exists(dataDir.resolve("stocks.csv"))) {
        // data/ exists but stocks.csv is missing — copy just that
        val cl = object {}::class.java.classLoader
        cl.getResourceAsStream("data/stocks.csv")
            ?.use { Files.copy(it, dataDir.resolve("stocks.csv")) }
        logger.info("Created default stocks.csv from bundled template")
    }

    // ---------------------------------------------------------------
    // 2. Register main portfolio
    // ---------------------------------------------------------------
    val mainPortfolio = ManagedPortfolio(
        name = "Main",
        id = "main",
        csvPath = mainCsvPath,
        cashPath = dataDir.resolve("cash.txt").toString()
    )
    PortfolioRegistry.register(mainPortfolio)

    // ---------------------------------------------------------------
    // 3. Discover subfolder portfolios under data/
    // ---------------------------------------------------------------
    if (Files.isDirectory(dataDir)) {
        Files.list(dataDir)
            .filter { Files.isDirectory(it) }
            .filter { !it.fileName.toString().startsWith(".") }
            .filter { Files.exists(it.resolve("stocks.csv")) }
            .sorted(compareBy { it.fileName.toString() })
            .forEach { subDir ->
                val folderName = subDir.fileName.toString()
                val portfolio = ManagedPortfolio(
                    name = folderName.toPortfolioDisplayName(),
                    id = folderName.toPortfolioSlug(),
                    csvPath = dataDir.resolve("$folderName/stocks.csv").toString(),
                    cashPath = dataDir.resolve("$folderName/cash.txt").toString()
                )
                PortfolioRegistry.register(portfolio)
                logger.info("Discovered portfolio: '${portfolio.name}' (id=${portfolio.id}) at ${portfolio.csvPath}")
            }
    }

    logger.info("Registered ${PortfolioRegistry.entries.size} portfolio(s): ${PortfolioRegistry.entries.map { it.id }}")

    // ---------------------------------------------------------------
    // 4. Load all portfolios
    // ---------------------------------------------------------------
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun loadPortfolio(entry: ManagedPortfolio) {
        try {
            val portfolio = CsvStockReader.readPortfolio(entry.csvPath)
            logger.info("Loaded ${portfolio.stocks.size} stocks from ${entry.csvPath}")
            entry.updateStocks(portfolio.stocks)
        } catch (e: Exception) {
            logger.error("Failed to load CSV for '${entry.name}': ${e.message}", e)
        }
    }

    fun loadCash(entry: ManagedPortfolio) {
        try {
            entry.updateCash(CashReader.readCash(entry.cashPath))
        } catch (e: Exception) {
            logger.warn("Could not load cash for '${entry.name}' (${entry.cashPath}): ${e.message}. Continuing without cash data.")
        }
    }

    PortfolioRegistry.entries.forEach { entry ->
        loadPortfolio(entry)
        loadCash(entry)
    }

    // ---------------------------------------------------------------
    // 4b. Backup portfolios (on startup + daily)
    // ---------------------------------------------------------------
    BackupService.start(appScope)
    UpdateService.initialize(appScope)

    // ---------------------------------------------------------------
    // 5. Compute union of all symbols across all portfolios
    // ---------------------------------------------------------------
    fun allSymbols(): List<String> {
        val symbols = mutableListOf<String>()
        for (entry in PortfolioRegistry.entries) {
            val stocks = entry.getStocks()
            symbols += stocks.map { it.label }
            symbols += stocks.flatMap { it.letfComponents?.map { c -> c.second } ?: emptyList() }
            symbols += entry.getCash()
                .map { it.currency }.distinct()
                .filter { it != "USD" && it != "P" }
                .map { "${it}USD=X" }
        }
        return symbols.distinct()
    }

    // ---------------------------------------------------------------
    // 6. Initialize Yahoo Finance
    // ---------------------------------------------------------------
    val updateIntervalSeconds = System.getenv("PRICE_UPDATE_INTERVAL")?.toLongOrNull() ?: 60L

    fun initializeMarketData() {
        try {
            logger.info("Initializing Yahoo Finance market data service...")
            YahooMarketDataService.initialize()
            val symbols = allSymbols()
            YahooMarketDataService.requestMarketDataForSymbols(symbols, updateIntervalSeconds)
            logger.info("Market data requests started for ${symbols.size} symbols (every ${updateIntervalSeconds}s)")
        } catch (e: Exception) {
            logger.error("Failed to initialize Yahoo Finance service", e)
            logger.warn("Application will continue without live market data")
        }
    }

    initializeMarketData()

    // ---------------------------------------------------------------
    // 7. Initialize NAV service
    // ---------------------------------------------------------------
    fun initializeNavData() {
        try {
            logger.info("Initializing NAV service...")
            NavService.initialize()
            val symbols =
                PortfolioRegistry.entries.flatMap { it.getStocks().map { s -> s.label } }.distinct()
            val fixedNavInterval = AppConfig.navUpdateInterval
            if (fixedNavInterval != null) NavService.requestNavForSymbols(symbols, fixedNavInterval)
            else NavService.requestNavForSymbols(symbols)
        } catch (e: Exception) {
            logger.error("Failed to initialize NAV service", e)
            logger.warn("Application will continue without NAV data")
        }
    }

    initializeNavData()

    // ---------------------------------------------------------------
    // 8. Initialize IBKR margin rate service
    // ---------------------------------------------------------------
    try {
        logger.info("Initializing IBKR margin rate service...")
        IbkrMarginRateService.initialize()
    } catch (e: Exception) {
        logger.error("Failed to initialize IBKR margin rate service", e)
        logger.warn("Application will continue without IBKR margin rate data")
    }

    // ---------------------------------------------------------------
    // 9. File watchers — one CSV + one cash per portfolio
    // ---------------------------------------------------------------
    val fileWatchers = mutableListOf<CsvFileWatcher>()

    for (entry in PortfolioRegistry.entries) {
        val csvFilePath = Paths.get(entry.csvPath)
        if (Files.exists(csvFilePath)) {
            logger.info("Setting up CSV file watcher for '${entry.name}': ${csvFilePath.toAbsolutePath()}")
            val watcher = CsvFileWatcher(csvFilePath, debounceMillis = 500)
            watcher.onFileChanged {
                logger.info("CSV changed for '${entry.name}', reloading...")
                loadPortfolio(entry)
                initializeMarketData()
                initializeNavData()
                PortfolioUpdateBroadcaster.broadcastReload()
                logger.info("Portfolio '${entry.name}' reloaded")
            }
            watcher.start(appScope)
            fileWatchers += watcher
        } else {
            logger.warn("CSV watcher disabled for '${entry.name}' (file not found at ${csvFilePath.toAbsolutePath()})")
        }

        val cashFilePath = Paths.get(entry.cashPath)
        val cashWatcher = CsvFileWatcher(cashFilePath, debounceMillis = 500)
        cashWatcher.onFileChanged {
            logger.info("Cash file changed for '${entry.name}', reloading...")
            loadCash(entry)
            initializeMarketData()
            PortfolioUpdateBroadcaster.broadcastReload()
            logger.info("Cash '${entry.name}' reloaded")
        }
        cashWatcher.start(appScope)
        fileWatchers += cashWatcher
        logger.info("Cash file watcher started for '${entry.name}': ${cashFilePath.toAbsolutePath()}")
    }

    // ---------------------------------------------------------------
    // 10. Shutdown hook
    // ---------------------------------------------------------------
    val httpsPort = System.getenv("PORTFOLIO_HELPER_PORT")?.toIntOrNull() ?: 8443
    val httpPort  = System.getenv("PORTFOLIO_HELPER_HTTP_PORT")?.toIntOrNull() ?: 8080
    var stopServer: () -> Unit = {}

    // Load or generate TLS certificate — no hostname binding, Android trusts by fingerprint
    val (keyStore, certFingerprint) = CertificateManager.loadOrGenerate(dataDir.toFile())
    logger.info("TLS fingerprint (show in settings): $certFingerprint")

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down application (shutdown hook)...")
        runCatching { stopServer() }
        fileWatchers.forEach { it.stop() }
        IbkrMarginRateService.shutdown()
        NavService.shutdown()
        YahooMarketDataService.shutdown()
        SyncDiscoveryService.stop()
        logger.info("Cleanup completed")
    })

    val url = "https://localhost:$httpsPort"

    // ---------------------------------------------------------------
    // 11. System tray
    // ---------------------------------------------------------------
    NewTrayService.createTray(url, appScope)

    // ---------------------------------------------------------------
    // 12. Start web server
    // ---------------------------------------------------------------
    logger.info("Starting HTTPS server on port $httpsPort, HTTP redirect on port $httpPort...")
    try {
        val server = embeddedServer(Netty, configure = {
            // Increase worker threads so long-lived SSE connections don't starve
            // normal page/API requests. Default is 2×CPU which is too small.
            workerGroupSize = 32
            callGroupSize  = 32

            // HTTPS — all real traffic
            sslConnector(
                keyStore = keyStore,
                keyAlias = "ibviewer",
                keyStorePassword = { charArrayOf() },
                privateKeyPassword = { charArrayOf() }
            ) {
                port = httpsPort
                host = AppConfig.bindHost
            }
            // HTTP — redirect to HTTPS only, no data served
            connector {
                port = httpPort
                host = AppConfig.bindHost
            }
        }) {
            configureRouting()
        }.start(wait = false)
        stopServer = { server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000) }

        // Advertise HTTPS port for Android mDNS discovery
        SyncDiscoveryService.start(httpsPort)
    } catch (e: Exception) {
        logger.error("Failed to start web server: ${e.message}", e)
        exitProcess(1)
    }

    logger.info("Application ready. Access at https://localhost:$httpsPort (press Ctrl+C or use tray menu to exit)")

    runBlocking {
        delay(1.seconds)
        if (AppConfig.openBrowser) BrowserService.openBrowser(url)
    }

    try {
        Thread.currentThread().join()
    } catch (_: InterruptedException) {
        // Normal during JVM shutdown
    }
}
