package com.portfoliohelper.service

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

abstract class PollingService<TData : Any>(private val serviceName: String) {
    protected val logger = LoggerFactory.getLogger(this::class.java)
    protected val cache = ConcurrentHashMap<String, TData>()
    private val updateCallbacks = mutableListOf<(String, TData) -> Unit>()
    private val postBatchCallbacks = mutableListOf<() -> Unit>()
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

    /**
     * Like [startPolling] but the delay before each subsequent fetch is computed
     * dynamically by [nextDelayMs]. The first fetch still runs immediately.
     */
    protected fun startPollingWithSchedule(symbols: List<String>, nextDelayMs: () -> Long) {
        serviceScope.launch { fetchAll(symbols) }
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                val delayMs = nextDelayMs()
                val nextAt = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + delayMs)
                logger.info("$serviceName: next fetch scheduled at $nextAt (in ${delayMs / 60_000} min)")
                delay(delayMs)
                if (isActive) fetchAll(symbols)
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
                        logger.warn("Failed to fetch $symbol: ${e.message}, retrying once...")
                        delay(3_000)
                        try {
                            val data = fetchItem(symbol) ?: return@async
                            cache[symbol] = data
                            synchronized(updateCallbacks) {
                                updateCallbacks.forEach { it(symbol, data) }
                            }
                            logger.info("Retry succeeded for $symbol")
                        } catch (e2: Exception) {
                            logger.warn("Retry also failed for $symbol: ${e2.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        synchronized(postBatchCallbacks) { postBatchCallbacks.toList() }.forEach { it() }
        logger.info("Completed fetching for ${symbols.size} symbols")
    }

    fun onUpdate(callback: (String, TData) -> Unit): () -> Unit {
        synchronized(updateCallbacks) { updateCallbacks.add(callback) }
        return { synchronized(updateCallbacks) { updateCallbacks.remove(callback) } }
    }

    fun onBatchComplete(callback: () -> Unit): () -> Unit {
        synchronized(postBatchCallbacks) { postBatchCallbacks.add(callback) }
        return { synchronized(postBatchCallbacks) { postBatchCallbacks.remove(callback) } }
    }

    /** Registers [callback] and immediately replays all cached entries so new clients
     *  don't miss data that was fetched before their SSE connection was established.
     *  Returns an unregister function — call it when the SSE connection closes. */
    fun onUpdateWithReplay(callback: (String, TData) -> Unit): () -> Unit {
        synchronized(updateCallbacks) { updateCallbacks.add(callback) }
        cache.forEach { (symbol, data) -> callback(symbol, data) }
        return { synchronized(updateCallbacks) { updateCallbacks.remove(callback) } }
    }

    fun get(symbol: String): TData? = cache[symbol]

    fun cachedSymbols(): Set<String> = cache.keys

    open fun shutdown() {
        logger.info("Shutting down $serviceName...")
        updateJob?.cancel()
        serviceScope.cancel()
        isInitialized = false
    }
}
