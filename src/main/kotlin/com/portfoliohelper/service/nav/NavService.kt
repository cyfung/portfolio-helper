package com.portfoliohelper.service.nav

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object NavService {
    private val logger = LoggerFactory.getLogger(NavService::class.java)

    private val providers: Map<String, NavProvider> = listOf(
        CtapNavProvider,
        CtaNavProvider
    ).associateBy { it.symbol }

    private val navCache = ConcurrentHashMap<String, NavData>()
    private val updateCallbacks = mutableListOf<(String, NavData) -> Unit>()
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        logger.info("Initializing NAV service with ${providers.size} providers: ${providers.keys}")
        isInitialized = true
    }

    fun requestNavForSymbols(symbols: List<String>, intervalSeconds: Long = 300) {
        val supportedSymbols = symbols.filter { it in providers }

        if (supportedSymbols.isEmpty()) {
            logger.info("No symbols with NAV providers found in portfolio")
            return
        }

        logger.info("Starting NAV polling for ${supportedSymbols.size} symbols: $supportedSymbols (interval: ${intervalSeconds}s)")

        // Fetch immediately
        serviceScope.launch { fetchAllNavs(supportedSymbols) }

        // Start periodic updates
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            delay(intervalSeconds * 1000)
            while (isActive) {
                fetchAllNavs(supportedSymbols)
                delay(intervalSeconds * 1000)
            }
        }
    }

    private suspend fun fetchAllNavs(symbols: List<String>) {
        coroutineScope {
            symbols.map { symbol ->
                async {
                    val provider = providers[symbol] ?: return@async
                    try {
                        val navData = provider.fetchNav()
                        if (navData != null) {
                            navCache[symbol] = navData

                            synchronized(updateCallbacks) {
                                updateCallbacks.forEach { it(symbol, navData) }
                            }

                            logger.debug("Updated NAV for $symbol: ${navData.nav}")
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch NAV for $symbol: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        logger.info("Completed fetching NAVs for ${symbols.size} symbols")
    }

    fun getNav(symbol: String): Double? = navCache[symbol]?.nav

    fun onNavUpdate(callback: (String, NavData) -> Unit) {
        synchronized(updateCallbacks) {
            updateCallbacks.add(callback)
        }
    }

    fun shutdown() {
        logger.info("Shutting down NAV service...")
        updateJob?.cancel()
        serviceScope.cancel()
        SimplifyEtfNavProvider.shutdown()
        isInitialized = false
    }
}
