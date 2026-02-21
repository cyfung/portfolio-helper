package com.portfoliohelper

import com.portfoliohelper.service.*
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.CashReader
import com.portfoliohelper.web.configureRouting
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

fun String.toPortfolioSlug() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
fun String.toPortfolioDisplayName() = replaceFirstChar { it.uppercase() }

fun main() {
    val logger = LoggerFactory.getLogger("Application")

    // ---------------------------------------------------------------
    // 1. Ensure data directory exists and copy default CSV if missing
    // ---------------------------------------------------------------
    val mainCsvPath = "data/stocks.csv"
    val mainCsvFilesystemPath = Paths.get(mainCsvPath)
    Files.createDirectories(mainCsvFilesystemPath.parent)
    if (!Files.exists(mainCsvFilesystemPath)) {
        val resourceStream = object {}::class.java.classLoader.getResourceAsStream("data/stocks.csv")
        if (resourceStream != null) {
            resourceStream.use { Files.copy(it, mainCsvFilesystemPath) }
            logger.info("Created default CSV file at ${mainCsvFilesystemPath.toAbsolutePath()} from bundled template")
        }
    }

    // ---------------------------------------------------------------
    // 2. Register main portfolio
    // ---------------------------------------------------------------
    val mainPortfolio = ManagedPortfolio(
        name = "Main",
        id = "main",
        csvPath = mainCsvPath,
        cashPath = "data/cash.txt"
    )
    PortfolioRegistry.register(mainPortfolio)

    // ---------------------------------------------------------------
    // 3. Discover subfolder portfolios under data/
    // ---------------------------------------------------------------
    val dataDir = Paths.get("data")
    if (Files.isDirectory(dataDir)) {
        Files.list(dataDir)
            .filter { Files.isDirectory(it) }
            .filter { Files.exists(it.resolve("stocks.csv")) }
            .sorted(compareBy { it.fileName.toString() })
            .forEach { subDir ->
                val folderName = subDir.fileName.toString()
                val portfolio = ManagedPortfolio(
                    name = folderName.toPortfolioDisplayName(),
                    id = folderName.toPortfolioSlug(),
                    csvPath = "data/$folderName/stocks.csv",
                    cashPath = "data/$folderName/cash.txt"
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
                .filter { it != "USD" }
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
    val navIntervalSeconds = System.getenv("NAV_UPDATE_INTERVAL")?.toLongOrNull() ?: 300L

    fun initializeNavData() {
        try {
            logger.info("Initializing NAV service...")
            NavService.initialize()
            val symbols = PortfolioRegistry.entries.flatMap { it.getStocks().map { s -> s.label } }.distinct()
            NavService.requestNavForSymbols(symbols, navIntervalSeconds)
        } catch (e: Exception) {
            logger.error("Failed to initialize NAV service", e)
            logger.warn("Application will continue without NAV data")
        }
    }

    initializeNavData()

    // ---------------------------------------------------------------
    // 8. File watchers — one CSV + one cash per portfolio
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
        if (Files.exists(cashFilePath)) {
            logger.info("Setting up cash file watcher for '${entry.name}': ${cashFilePath.toAbsolutePath()}")
            val watcher = CsvFileWatcher(cashFilePath, debounceMillis = 500)
            watcher.onFileChanged {
                logger.info("Cash file changed for '${entry.name}', reloading...")
                loadCash(entry)
                initializeMarketData()
                PortfolioUpdateBroadcaster.broadcastReload()
                logger.info("Cash '${entry.name}' reloaded")
            }
            watcher.start(appScope)
            fileWatchers += watcher
        } else {
            logger.warn("Cash watcher disabled for '${entry.name}' (file not found at ${cashFilePath.toAbsolutePath()})")
        }
    }

    // ---------------------------------------------------------------
    // 9. Shutdown hook
    // ---------------------------------------------------------------
    val port = System.getenv("PORTFOLIO_HELPER_PORT")?.toIntOrNull() ?: 8080
    var stopServer: () -> Unit = {}

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down application (shutdown hook)...")
        runCatching { stopServer() }
        SystemTrayService.shutdown()
        fileWatchers.forEach { it.stop() }
        NavService.shutdown()
        YahooMarketDataService.shutdown()
        logger.info("Cleanup completed")
    })

    // ---------------------------------------------------------------
    // 10. System tray
    // ---------------------------------------------------------------
    val traySupported = SystemTrayService.initialize(
        serverUrl = "http://localhost:$port",
        onExit = {
            logger.info("Exit requested from system tray")
            System.exit(0)
        }
    )

    if (traySupported) {
        logger.info("System tray initialized successfully")
    } else {
        logger.warn("Running without system tray (not supported on this platform)")
    }

    // ---------------------------------------------------------------
    // 11. Start web server
    // ---------------------------------------------------------------
    logger.info("Starting web server on port $port...")
    try {
        val server = embeddedServer(Netty, port = port) {
            configureRouting()
        }.start(wait = false)
        stopServer = { server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000) }
    } catch (e: Exception) {
        logger.error("Failed to start web server: ${e.message}", e)
        System.exit(1)
        return
    }

    if (traySupported) {
        SystemTrayService.showNotification(
            "Portfolio Helper",
            "Server started on http://localhost:$port"
        )
    }

    logger.info("Application ready. Access at http://localhost:$port (press Ctrl+C or use tray menu to exit)")
    try {
        Thread.currentThread().join()
    } catch (_: InterruptedException) {
        // Normal during JVM shutdown
    }
}
