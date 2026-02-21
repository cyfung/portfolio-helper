package com.portfoliohelper.service

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

abstract class PollingService<TData : Any>(private val serviceName: String) {
    protected val logger = LoggerFactory.getLogger(this::class.java)
    protected val cache = ConcurrentHashMap<String, TData>()
    private val updateCallbacks = mutableListOf<(String, TData) -> Unit>()
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    protected var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        logger.info("Initializing $serviceName...")
        isInitialized = true
    }

    protected fun startPolling(symbols: List<String>, intervalSeconds: Long) {
        serviceScope.launch { fetchAll(symbols) }
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            delay(intervalSeconds * 1000)
            while (isActive) {
                fetchAll(symbols)
                delay(intervalSeconds * 1000)
            }
        }
    }

    protected abstract suspend fun fetchItem(symbol: String): TData?

    private suspend fun fetchAll(symbols: List<String>) {
        coroutineScope {
            symbols.map { symbol ->
                async {
                    try {
                        val data = fetchItem(symbol) ?: return@async
                        cache[symbol] = data
                        synchronized(updateCallbacks) {
                            updateCallbacks.forEach { it(symbol, data) }
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch $symbol: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        logger.info("Completed fetching for ${symbols.size} symbols")
    }

    fun onUpdate(callback: (String, TData) -> Unit) {
        synchronized(updateCallbacks) { updateCallbacks.add(callback) }
    }

    fun get(symbol: String): TData? = cache[symbol]

    open fun shutdown() {
        logger.info("Shutting down $serviceName...")
        updateJob?.cancel()
        serviceScope.cancel()
        isInitialized = false
    }
}
