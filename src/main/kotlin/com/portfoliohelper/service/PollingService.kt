package com.portfoliohelper.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

abstract class PollingService<TData : Any>(private val serviceName: String) {
    protected val logger = LoggerFactory.getLogger(this::class.java)
    protected val cache = ConcurrentHashMap<String, TData>()
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _updates = MutableSharedFlow<Pair<String, TData>>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<Pair<String, TData>> = _updates

    private val _batchComplete = MutableSharedFlow<Unit>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val batchComplete: SharedFlow<Unit> = _batchComplete

    fun snapshotAll(): Map<String, TData> = HashMap(cache)

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
                        _updates.tryEmit(symbol to data)
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch $symbol: ${e.message}, retrying once...")
                        delay(3_000)
                        try {
                            val data = fetchItem(symbol) ?: return@async
                            cache[symbol] = data
                            _updates.tryEmit(symbol to data)
                            logger.info("Retry succeeded for $symbol")
                        } catch (e2: Exception) {
                            logger.warn("Retry also failed for $symbol: ${e2.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        _batchComplete.tryEmit(Unit)
        logger.info("Completed fetching for ${symbols.size} symbols")
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
