package com.portfoliohelper.service.yahoo

import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.PollingService
import com.portfoliohelper.service.nav.NavService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object YahooMarketDataService : PollingService<YahooQuote>("Yahoo Finance") {

    private val fxScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _fxRates = MutableStateFlow<Map<String, Double>>(emptyMap())
    val fxRates: StateFlow<Map<String, Double>> = _fxRates

    init {
        fxScope.launch {
            batchComplete.collect { _fxRates.value = buildFxMap() }
        }
    }

    private fun buildFxMap(): Map<String, Double> =
        cache.entries
            .filter { it.key.endsWith("USD=X") }
            .mapNotNull { (key, quote) ->
                val rate = quote.regularMarketPrice ?: return@mapNotNull null
                key.removeSuffix("USD=X") to rate
            }
            .toMap()

    fun requestMarketDataForSymbols(symbols: List<String>, updateIntervalSeconds: Long = 60) {
        logger.info("Requesting market data for ${symbols.size} symbols (update interval: ${updateIntervalSeconds}s)...")
        symbols.forEach { symbol ->
            cache.putIfAbsent(symbol, YahooQuote(symbol, null, null))
        }
        startPolling(symbols, updateIntervalSeconds)
    }

    override suspend fun fetchItem(symbol: String): YahooQuote {
        val quote = YahooFinanceClient.fetchQuote(symbol)
        logger.debug("Updated quote for $symbol: mark=${quote.regularMarketPrice}, close=${quote.previousClose}")
        return quote
    }

    fun getCurrentPortfolio(baseStocks: List<Stock>): Portfolio {
        val enrichedStocks = baseStocks.map { stock ->
            val quote = cache[stock.label]
            stock.copy(
                markPrice = quote?.regularMarketPrice,
                lastClosePrice = quote?.previousClose,
                isMarketClosed = quote?.isMarketClosed ?: false,
                lastNav = NavService.getNav(stock.label)
            )
        }
        return Portfolio(enrichedStocks)
    }

    override fun shutdown() {
        fxScope.cancel()
        super.shutdown()
        YahooFinanceClient.shutdown()
    }

    fun getQuote(symbol: String): YahooQuote? = get(symbol)

}
