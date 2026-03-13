package com.portfoliohelper

import com.portfoliohelper.service.*
import com.portfoliohelper.service.db.AppDatabase
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
    // 1. Ensure data directory exists and seed bundled app.db if new
    // ---------------------------------------------------------------
    val dataDir = AppDirs.dataDir
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Seed bundled app.db from resources if no database exists yet.
    Files.createDirectories(dataDir)
    val dbFile = dataDir.resolve("app.db")
    if (!Files.exists(dbFile)) {
        val cl = object {}::class.java.classLoader
        cl.getResourceAsStream("data/app.db")!!.use { Files.copy(it, dbFile) }
    }

    AppDatabase.init(dataDir.toFile())
    AppConfig.initDbMode()

    val allPortfolios = ManagedPortfolio.getAll()
    logger.info("Loaded ${allPortfolios.size} portfolio(s): ${allPortfolios.map { it.slug }}")

    // ---------------------------------------------------------------
    // 2. Backup portfolios (on startup + daily)
    // ---------------------------------------------------------------
    BackupService.start(appScope)
    UpdateService.initialize(appScope)

    // ---------------------------------------------------------------
    // 3. Compute union of all symbols across all portfolios
    // ---------------------------------------------------------------
    fun allSymbols(): List<String> {
        val symbols = mutableListOf<String>()
        for (entry in ManagedPortfolio.getAll()) {
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
    // 4. Initialize Yahoo Finance
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
    // 5. Initialize NAV service
    // ---------------------------------------------------------------
    fun initializeNavData() {
        try {
            logger.info("Initializing NAV service...")
            NavService.initialize()
            val symbols = ManagedPortfolio.getAll()
                .flatMap { it.getStocks().map { s -> s.label } }.distinct()
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
    // 6. Initialize IBKR margin rate service
    // ---------------------------------------------------------------
    try {
        logger.info("Initializing IBKR margin rate service...")
        IbkrMarginRateService.initialize()
    } catch (e: Exception) {
        logger.error("Failed to initialize IBKR margin rate service", e)
        logger.warn("Application will continue without IBKR margin rate data")
    }

    // ---------------------------------------------------------------
    // 7. Shutdown hook
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
        IbkrMarginRateService.shutdown()
        NavService.shutdown()
        YahooMarketDataService.shutdown()
        SyncDiscoveryService.stop()
        logger.info("Cleanup completed")
    })

    val url = "https://localhost:$httpsPort"

    // ---------------------------------------------------------------
    // 8. System tray
    // ---------------------------------------------------------------
    NewTrayService.createTray(url, appScope)

    // ---------------------------------------------------------------
    // 9. Start web server
    // ---------------------------------------------------------------
    logger.info("Starting HTTPS server on port $httpsPort, HTTP redirect on port $httpPort...")
    try {
        val server = embeddedServer(Netty, configure = {
            workerGroupSize = 32
            callGroupSize  = 32

            sslConnector(
                keyStore = keyStore,
                keyAlias = "ibviewer",
                keyStorePassword = { charArrayOf() },
                privateKeyPassword = { charArrayOf() }
            ) {
                port = httpsPort
                host = AppConfig.bindHost
                enableHttp2 = false
            }
            connector {
                port = httpPort
                host = AppConfig.bindHost
            }
        }) {
            configureRouting()
        }.start(wait = false)
        stopServer = { server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000) }

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
