package com.portfoliohelper

import com.portfoliohelper.service.*
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.web.configureRouting
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import kotlin.io.path.absolute

fun main() {
    val logger = LoggerFactory.getLogger("Application")
    val csvPath = System.getenv("CSV_FILE_PATH")
        ?: System.getProperty("csv.file.path")
        ?: "data/stocks.csv"

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

    // Initialize Yahoo Finance service
    fun initializeMarketData() {
        try {
            logger.info("Initializing Yahoo Finance market data service...")
            YahooMarketDataService.initialize()

            // Request market data for all symbols + LETF component symbols (starts background polling)
            val stocks = PortfolioState.getStocks()
            val portfolioSymbols = stocks.map { it.label }
            val componentSymbols = stocks.flatMap { it.letfComponents?.map { c -> c.second } ?: emptyList() }
            val symbols = (portfolioSymbols + componentSymbols).distinct()
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
    val fileWatcher = if (java.nio.file.Files.exists(csvFilePath)) {
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

    // Initialize system tray (if supported)
    val shutdownLatch = CountDownLatch(1)
    val traySupported = SystemTrayService.initialize(
        serverUrl = "http://localhost:8080",
        onExit = {
            logger.info("Exit requested from system tray")
            shutdownLatch.countDown()
        }
    )

    if (traySupported) {
        logger.info("System tray initialized successfully")
    } else {
        logger.warn("Running without system tray (not supported on this platform)")
    }

    // Register shutdown hook to cleanup resources (for Ctrl+C or external termination)
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down application (shutdown hook)...")
        SystemTrayService.shutdown()
        fileWatcher?.stop()
        NavService.shutdown()
        YahooMarketDataService.shutdown()
        logger.info("Cleanup completed")
    })

    // Start web server (non-blocking when system tray is active)
    logger.info("Starting web server on port 8080...")
    val server = embeddedServer(Netty, port = 8080) {
        configureRouting()
    }.start(wait = false)

    // Show startup notification if tray is active
    if (traySupported) {
        SystemTrayService.showNotification(
            "Portfolio Helper",
            "Server started on http://localhost:8080"
        )
    }

    // Keep application running (wait for shutdown signal)
    logger.info("Application ready. Access at http://localhost:8080 (press Ctrl+C or use tray menu to exit)")
    shutdownLatch.await()

    // Stop server gracefully
    logger.info("Stopping server...")
    server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)

    // Clean up resources
    logger.info("Cleaning up resources...")
    SystemTrayService.shutdown()
    fileWatcher?.stop()
    NavService.shutdown()
    YahooMarketDataService.shutdown()

    logger.info("Application stopped successfully")

    // Exit JVM to ensure complete shutdown
    System.exit(0)
}
