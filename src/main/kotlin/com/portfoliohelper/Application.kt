package com.portfoliohelper

import com.portfoliohelper.service.*
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.CashReader
import com.portfoliohelper.service.CashState
import com.portfoliohelper.web.configureRouting
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    val logger = LoggerFactory.getLogger("Application")
    val csvPath = "data/stocks.csv"

    // Ensure data directory and CSV file exist on the filesystem
    val csvFilesystemPath = Paths.get(csvPath)
    Files.createDirectories(csvFilesystemPath.parent)
    if (!Files.exists(csvFilesystemPath)) {
        val resourceStream = object {}::class.java.classLoader.getResourceAsStream("data/stocks.csv")
        if (resourceStream != null) {
            resourceStream.use { Files.copy(it, csvFilesystemPath) }
            logger.info("Created default CSV file at ${csvFilesystemPath.toAbsolutePath()} from bundled template")
        }
    }

    logger.info("Using CSV file path: $csvPath")

    // Create coroutine scope for background tasks
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Function to load CSV and update state
    fun loadPortfolio() {
        try {
            val portfolio = CsvStockReader.readPortfolio(csvPath)
            logger.info("Successfully loaded ${portfolio.stocks.size} stocks from CSV: $csvPath")
            PortfolioState.updateStocks(portfolio.stocks)
        } catch (e: Exception) {
            logger.error("Failed to load CSV file: ${e.message}", e)
            throw e
        }
    }

    // Initial load of portfolio structure from CSV
    loadPortfolio()

    // Load cash entries from cash.txt (non-fatal if missing)
    val cashPath = "data/cash.txt"
    fun loadCash() {
        try {
            CashState.update(CashReader.readCash(cashPath))
        } catch (e: Exception) {
            logger.warn("Failed to load cash file: ${e.message}. Continuing without cash data.")
        }
    }
    loadCash()

    // Initialize Yahoo Finance service
    fun initializeMarketData() {
        try {
            logger.info("Initializing Yahoo Finance market data service...")
            YahooMarketDataService.initialize()

            // Request market data for all symbols + LETF component symbols + FX pairs for cash currencies
            val stocks = PortfolioState.getStocks()
            val portfolioSymbols = stocks.map { it.label }
            val componentSymbols = stocks.flatMap { it.letfComponents?.map { c -> c.second } ?: emptyList() }
            val fxSymbols = CashState.getEntries()
                .map { it.currency }.distinct()
                .filter { it != "USD" }
                .map { "${it}USD=X" }
            val symbols = (portfolioSymbols + componentSymbols + fxSymbols).distinct()
            val updateIntervalSeconds = System.getenv("PRICE_UPDATE_INTERVAL")?.toLongOrNull() ?: 60L
            YahooMarketDataService.requestMarketDataForSymbols(symbols, updateIntervalSeconds)

            logger.info("Market data requests started for ${symbols.size} symbols (updating every ${updateIntervalSeconds}s)")
        } catch (e: Exception) {
            logger.error("Failed to initialize Yahoo Finance service", e)
            logger.warn("Application will continue without live market data")
        }
    }

    initializeMarketData()

    // Initialize NAV service for fund NAV data
    fun initializeNavData() {
        try {
            logger.info("Initializing NAV service...")
            NavService.initialize()

            val symbols = PortfolioState.getStocks().map { it.label }
            val navIntervalSeconds = System.getenv("NAV_UPDATE_INTERVAL")?.toLongOrNull() ?: 300L
            NavService.requestNavForSymbols(symbols, navIntervalSeconds)
        } catch (e: Exception) {
            logger.error("Failed to initialize NAV service", e)
            logger.warn("Application will continue without NAV data")
        }
    }

    initializeNavData()

    // Set up CSV file watcher for hot-reload
    val csvFilePath = Paths.get(csvPath)
    val fileWatcher = if (Files.exists(csvFilePath)) {
        logger.info("Setting up CSV file watcher for hot-reload: ${csvFilePath.toAbsolutePath()}")
        val watcher = CsvFileWatcher(csvFilePath, debounceMillis = 500)

        watcher.onFileChanged {
            logger.info("CSV file changed, reloading portfolio...")

            // Reload portfolio from CSV
            loadPortfolio()

            // Update Yahoo Finance with new symbols
            initializeMarketData()

            // Update NAV service with new symbols
            initializeNavData()

            // Broadcast reload event to SSE clients
            PortfolioUpdateBroadcaster.broadcastReload()

            logger.info("Portfolio reloaded successfully")
        }

        watcher.start(appScope)
        watcher
    } else {
        logger.warn("CSV file watcher disabled (file not found at ${csvFilePath.toAbsolutePath()})")
        logger.warn("Hot-reload will not be available. Create $csvPath to enable it.")
        null
    }

    // Set up cash file watcher for hot-reload
    val cashFilePath = Paths.get(cashPath)
    val cashFileWatcher = if (Files.exists(cashFilePath)) {
        logger.info("Setting up cash file watcher for hot-reload: ${cashFilePath.toAbsolutePath()}")
        val watcher = CsvFileWatcher(cashFilePath, debounceMillis = 500)

        watcher.onFileChanged {
            logger.info("Cash file changed, reloading cash entries...")
            loadCash()
            initializeMarketData()
            PortfolioUpdateBroadcaster.broadcastReload()
            logger.info("Cash entries reloaded successfully")
        }

        watcher.start(appScope)
        watcher
    } else {
        logger.warn("Cash file watcher disabled (file not found at ${cashFilePath.toAbsolutePath()})")
        null
    }

    val port = System.getenv("PORTFOLIO_HELPER_PORT")?.toIntOrNull() ?: 8080

    // Placeholder for server stop — set after successful startup, invoked by shutdown hook
    var stopServer: () -> Unit = {}

    // Register shutdown hook to cleanup resources on any exit path:
    // normal tray exit, Ctrl+C, or startup failure
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down application (shutdown hook)...")
        runCatching { stopServer() }
        SystemTrayService.shutdown()
        fileWatcher?.stop()
        cashFileWatcher?.stop()
        NavService.shutdown()
        YahooMarketDataService.shutdown()
        logger.info("Cleanup completed")
    })

    // Initialize system tray (if supported)
    val traySupported = SystemTrayService.initialize(
        serverUrl = "http://localhost:$port",
        onExit = {
            logger.info("Exit requested from system tray")
            System.exit(0)  // Shutdown hook handles all cleanup
        }
    )

    if (traySupported) {
        logger.info("System tray initialized successfully")
    } else {
        logger.warn("Running without system tray (not supported on this platform)")
    }

    // Start web server — if this fails (e.g. port in use), exit immediately so the
    // tray icon doesn't linger with no working server behind it
    logger.info("Starting web server on port $port...")
    try {
        val server = embeddedServer(Netty, port = port) {
            configureRouting()
        }.start(wait = false)
        stopServer = { server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000) }
    } catch (e: Exception) {
        logger.error("Failed to start web server: ${e.message}", e)
        System.exit(1)  // Shutdown hook cleans up tray and services
        return
    }

    // Show startup notification if tray is active
    if (traySupported) {
        SystemTrayService.showNotification(
            "Portfolio Helper",
            "Server started on http://localhost:$port"
        )
    }

    // Block main thread indefinitely — System.exit() (tray or Ctrl+C) will terminate the JVM
    logger.info("Application ready. Access at http://localhost:$port (press Ctrl+C or use tray menu to exit)")
    try {
        Thread.currentThread().join()
    } catch (_: InterruptedException) {
        // Normal during JVM shutdown
    }
}
