package com.ibviewer.data.repository

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object YahooMarketDataService {
    private const val TAG = "YahooMarketData"
    private val cache = ConcurrentHashMap<String, YahooQuote>()
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var onUpdateCallback: ((String, YahooQuote) -> Unit)? = null

    fun start(symbols: List<String>, updateIntervalSeconds: Long = 60) {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                symbols.forEach { symbol ->
                    try {
                        val quote = YahooFinanceClient.fetchQuote(symbol)
                        cache[symbol] = quote
                        onUpdateCallback?.invoke(symbol, quote)
                        Log.d(TAG, "Updated $symbol: ${quote.regularMarketPrice}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch $symbol", e)
                    }
                }
                delay(updateIntervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        updateJob?.cancel()
    }

    fun setOnUpdateListener(callback: (String, YahooQuote) -> Unit) {
        onUpdateCallback = callback
    }

    fun getQuote(symbol: String): YahooQuote? = cache[symbol]
}
