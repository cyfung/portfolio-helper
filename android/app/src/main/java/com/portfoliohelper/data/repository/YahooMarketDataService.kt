package com.portfoliohelper.data.repository

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object YahooMarketDataService {
    private const val TAG = "YahooMarketData"
    private val cache = ConcurrentHashMap<String, YahooQuote>()
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var onBatchUpdateCallback: ((List<YahooQuote>) -> Unit)? = null

    fun start(symbols: List<String>, updateIntervalSeconds: Long = 60) {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                try {
                    val quotes = YahooFinanceClient.fetchQuotes(symbols)
                    if (quotes.isNotEmpty()) {
                        quotes.forEach { cache[it.symbol] = it }
                        onBatchUpdateCallback?.invoke(quotes)
                        Log.d(TAG, "Batch updated ${quotes.size} symbols")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch quotes", e)
                }
                delay(updateIntervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        updateJob?.cancel()
    }

    fun setOnBatchUpdateListener(callback: (List<YahooQuote>) -> Unit) {
        onBatchUpdateCallback = callback
    }

    fun updateCache(symbol: String, quote: YahooQuote) {
        cache[symbol] = quote
        onBatchUpdateCallback?.invoke(listOf(quote))
    }

    fun getQuote(symbol: String): YahooQuote? = cache[symbol]
}
