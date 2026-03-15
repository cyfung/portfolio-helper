package com.portfoliohelper

import com.portfoliohelper.service.*
import org.jetbrains.exposed.sql.Database
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.MarketDataCoordinator
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
    // 0. Resolve data directory (env > OS default)
    // ---------------------------------------------------------------
    AppDirs.dataDir = System.getenv("PORTFOLIO_HELPER_DATA_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: AppDirs.osDefaultDataDir
    logger.info("Active data directory: ${AppDirs.dataDir.toAbsolutePath()}")

    // ---------------------------------------------------------------
    // 1. Ensure data directory exists and seed bundled app.db if new
    // ---------------------------------------------------------------
    val dataDir = AppDirs.dataDir
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Seed bundled app.db from resources if no database exists yet.
    // Note: 0-byte file can occur if app.db is deleted while the program is running —
    // Database.connect() will have already created an empty placeholder at that path.
    Files.createDirectories(dataDir)
    val dbFile = dataDir.resolve("app.db")
    logger.info("DB file: ${dbFile.toAbsolutePath()} (exists=${Files.exists(dbFile)}, size=${if (Files.exists(dbFile)) Files.size(dbFile) else -1})")
    if (!Files.exists(dbFile) || Files.size(dbFile) == 0L) {
        logger.info("Seeding bundled app.db...")
        val cl = object {}::class.java.classLoader
        cl.getResourceAsStream("data/app.db")!!.use { Files.copy(it, dbFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        logger.info("Seeded app.db (size=${Files.size(dbFile)})")
    } else {
        logger.info("Using existing app.db (size=${Files.size(dbFile)})")
    }

    Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
    logger.info("Connected to database at $dbFile")

    val allPortfolios = ManagedPortfolio.getAll()
    logger.info("Loaded ${allPortfolios.size} portfolio(s): ${allPortfolios.map { it.slug }}")

    // ---------------------------------------------------------------
    // 2. Backup portfolios (on startup + daily)
    // ---------------------------------------------------------------
    BackupService.start(appScope)
    UpdateService.initialize(appScope)

    // ---------------------------------------------------------------
    // 3–5. Initialize Yahoo Finance + NAV service
    // ---------------------------------------------------------------
    MarketDataCoordinator.updateIntervalSeconds = 60L

    try {
        logger.info("Initializing Yahoo Finance market data service...")
        YahooMarketDataService.initialize()
        logger.info("Initializing NAV service...")
        NavService.initialize()
        MarketDataCoordinator.setupAutoFxDiscovery()
        MarketDataCoordinator.refresh()
    } catch (e: Exception) {
        logger.error("Failed to initialize market data services", e)
        logger.warn("Application will continue without live market data")
    }

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
    NewTrayService.createTray(url)

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
                keyAlias = "portfolio-helper",
                keyStorePassword = { charArrayOf() },
                privateKeyPassword = { charArrayOf() }
            ) {
                port = httpsPort
                host = "0.0.0.0"
                enableHttp2 = false
            }
            connector {
                port = httpPort
                host = "0.0.0.0"
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
