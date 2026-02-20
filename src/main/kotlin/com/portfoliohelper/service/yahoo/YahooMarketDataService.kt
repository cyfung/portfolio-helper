package com.portfoliohelper.service.yahoo

import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.nav.NavService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object YahooMarketDataService {
    private val logger = LoggerFactory.getLogger(YahooMarketDataService::class.java)
    private val quoteCache = ConcurrentHashMap<String, YahooQuote>()
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val updateCallbacks = mutableListOf<(String, YahooQuote) -> Unit>()

    @Volatile
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        logger.info("Initializing Yahoo Finance market data service...")
        isInitialized = true
    }

    fun requestMarketDataForSymbols(symbols: List<String>, updateIntervalSeconds: Long = 60) {
        logger.info("Requesting market data for ${symbols.size} symbols (update interval: ${updateIntervalSeconds}s)...")

        symbols.forEach { symbol ->
            quoteCache.putIfAbsent(symbol, YahooQuote(symbol, null, null))
        }

        // Fetch immediately
        serviceScope.launch { fetchAllQuotes(symbols) }

        // Start periodic updates
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            delay(updateIntervalSeconds * 1000)
            while (isActive) {
                fetchAllQuotes(symbols)
                delay(updateIntervalSeconds * 1000)
            }
        }
    }

    private suspend fun fetchAllQuotes(symbols: List<String>) {
        coroutineScope {
            symbols.map { symbol ->
                async {
                    try {
                        val quote = YahooFinanceClient.fetchQuote(symbol)
                        quoteCache[symbol] = quote

                        // Notify callbacks
                        synchronized(updateCallbacks) {
                            updateCallbacks.forEach { it(symbol, quote) }
                        }

                        logger.debug("Updated quote for $symbol: mark=${quote.regularMarketPrice}, close=${quote.previousClose}")
                    } catch (e: YahooFinanceException) {
                        logger.warn("Failed to fetch $symbol: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        logger.info("Completed fetching quotes for ${symbols.size} symbols")
    }

    fun getCurrentPortfolio(baseStocks: List<Stock>): Portfolio {
        val enrichedStocks = baseStocks.map { stock ->
            val quote = quoteCache[stock.label]
            stock.copy(
                markPrice = quote?.regularMarketPrice,
                lastClosePrice = quote?.previousClose,
                isMarketClosed = quote?.isMarketClosed ?: false,
                lastNav = NavService.getNav(stock.label)
            )
        }
        return Portfolio(enrichedStocks)
    }

    fun onPriceUpdate(callback: (String, YahooQuote) -> Unit) {
        synchronized(updateCallbacks) {
            updateCallbacks.add(callback)
        }
    }

    fun shutdown() {
        logger.info("Shutting down Yahoo Finance service...")
        updateJob?.cancel()
        serviceScope.cancel()
        YahooFinanceClient.shutdown()
        isInitialized = false
    }

    fun getQuote(symbol: String): YahooQuote? = quoteCache[symbol]

    fun isConnected(): Boolean = isInitialized
}
