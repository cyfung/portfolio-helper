package com.portfoliohelper.web

import com.portfoliohelper.service.CurrencyConventions
import com.portfoliohelper.service.IbkrMarginRateService
import com.portfoliohelper.service.ManagedPortfolio
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.nav.NavData
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.yahoo.YahooQuote
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal suspend fun ServerSSESession.handleSseStream() {
    val channel = Channel<String>(Channel.BUFFERED)

    val callback: (String, YahooQuote) -> Unit = { symbol, quote ->
        if (quote.regularMarketPrice != null) {
            val json = buildString {
                append("{")
                append("\"symbol\":\"$symbol\",")
                append("\"markPrice\":${quote.regularMarketPrice},")
                append("\"lastClosePrice\":${quote.previousClose},")
                append("\"isMarketClosed\":${quote.isMarketClosed},")
                append("\"tradingPeriodEnd\":${quote.tradingPeriodEnd},")
                if (quote.currency != null) append("\"currency\":\"${quote.currency}\",")
                append("\"timestamp\":${quote.lastUpdateTime}")
                append("}")
            }
            channel.trySend(json)
        }
    }
    val unregisterPrice = YahooMarketDataService.onUpdateWithReplay(callback)

    val navCallback: (String, NavData) -> Unit = { symbol, navData ->
        val json = buildString {
            append("{")
            append("\"type\":\"nav\",")
            append("\"symbol\":\"$symbol\",")
            append("\"nav\":${navData.nav},")
            append("\"timestamp\":${navData.lastFetchTime}")
            append("}")
        }
        channel.trySend(json)
    }
    val unregisterNav = NavService.onUpdateWithReplay(navCallback)

    val ibkrCallback: () -> Unit = {
        val rates = buildString {
            append("{\"type\":\"ibkr-rates\",\"currencies\":[")
            val entries = IbkrMarginRateService.getAllRates().map { (ccy, r) ->
                val tiers = r.tiers.joinToString(",", "[", "]") { t ->
                    if (t.upTo != null) "{\"upTo\":${t.upTo},\"rate\":${t.rate}}"
                    else "{\"upTo\":null,\"rate\":${t.rate}}"
                }
                "{\"currency\":\"$ccy\",\"baseRate\":${r.baseRate},\"days\":${
                    CurrencyConventions.getDaysInYear(
                        ccy
                    )
                },\"tiers\":$tiers}"
            }
            append(entries.joinToString(","))
            append("],\"lastFetch\":${IbkrMarginRateService.getLastFetchMillis()}}")
        }
        channel.trySend(rates)
    }
    val unregisterIbkr = IbkrMarginRateService.onUpdateWithReplay(ibkrCallback)

    val portfolioValueCallback: () -> Unit = {
        for (p in ManagedPortfolio.getAll()) {
            val pTotal = YahooMarketDataService.getCurrentPortfolio(p.getStocks()).stockGrossValue
            val pvJson = buildString {
                append("{\"type\":\"portfolio-value\",")
                append("\"portfolioId\":\"${p.slug}\",")
                append("\"value\":${"%.2f".format(pTotal)}")
                append("}")
            }
            channel.trySend(pvJson)
        }
    }
    val unregisterPortfolioValue = YahooMarketDataService.onBatchComplete(portfolioValueCallback)

    launch {
        PortfolioUpdateBroadcaster.reloadEvents.collect {
            val json = "{\"type\":\"reload\",\"timestamp\":${it.timestamp}}"
            channel.trySend(json)
        }
    }
    try {
        for (json in channel) {
            send(ServerSentEvent(json))
        }
    } finally {
        unregisterPrice()
        unregisterNav()
        unregisterIbkr()
        unregisterPortfolioValue()
        channel.close()
    }
}
