package com.portfoliohelper.service.yahoo

import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.PollingService
import com.portfoliohelper.service.nav.NavService

object YahooMarketDataService : PollingService<YahooQuote>("Yahoo Finance") {

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

    fun onPriceUpdate(callback: (String, YahooQuote) -> Unit) = onUpdate(callback)

    override fun shutdown() {
        super.shutdown()
        YahooFinanceClient.shutdown()
    }

    fun getQuote(symbol: String): YahooQuote? = get(symbol)

    fun isConnected(): Boolean = isInitialized
}
