package com.portfoliohelper.web

import com.portfoliohelper.service.CurrencyConventions
import com.portfoliohelper.service.DividendService
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

    launch {
        YahooMarketDataService.snapshotAll().forEach { (symbol, quote) ->
            if (quote.regularMarketPrice != null) channel.trySend(buildPriceJson(symbol, quote))
        }
        YahooMarketDataService.updates.collect { (symbol, quote) ->
            if (quote.regularMarketPrice != null) channel.trySend(buildPriceJson(symbol, quote))
        }
    }

    launch {
        NavService.snapshotAll().forEach { (symbol, navData) ->
            channel.trySend(buildNavJson(symbol, navData))
        }
        NavService.updates.collect { (symbol, navData) ->
            channel.trySend(buildNavJson(symbol, navData))
        }
    }

    launch {
        IbkrMarginRateService.updates.collect {
            channel.trySend(buildIbkrJson())
        }
    }

    launch {
        YahooMarketDataService.batchComplete.collect {
            ManagedPortfolio.getAll().forEach { p ->
                val total = YahooMarketDataService.getCurrentPortfolio(p.getStocks()).stockGrossValue
                channel.trySend("""{"type":"portfolio-value","portfolioId":"${p.slug}","value":${"%.2f".format(total)}}""")
            }
        }
    }

    launch {
        PortfolioUpdateBroadcaster.reloadEvents.collect {
            channel.trySend("{\"type\":\"reload\",\"timestamp\":${it.timestamp}}")
        }
    }

    launch {
        DividendService.updates.collect { update ->
            channel.trySend("{\"type\":\"dividend\",\"portfolioId\":\"${update.portfolioSlug}\",\"total\":${update.total},\"calcUpToDate\":\"${update.calcUpToDate}\"}")
        }
    }

    try {
        for (json in channel) {
            send(ServerSentEvent(json))
        }
    } finally {
        channel.close()
    }
}

private fun buildPriceJson(symbol: String, quote: YahooQuote): String = buildString {
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

private fun buildNavJson(symbol: String, navData: NavData): String = buildString {
    append("{")
    append("\"type\":\"nav\",")
    append("\"symbol\":\"$symbol\",")
    append("\"nav\":${navData.nav},")
    append("\"timestamp\":${navData.lastFetchTime}")
    append("}")
}

private fun buildIbkrJson(): String = buildString {
    append("{\"type\":\"ibkr-rates\",\"currencies\":[")
    val entries = IbkrMarginRateService.getAllRates().map { (ccy, r) ->
        val tiers = r.tiers.joinToString(",", "[", "]") { t ->
            if (t.upTo != null) "{\"upTo\":${t.upTo},\"rate\":${t.rate}}"
            else "{\"upTo\":null,\"rate\":${t.rate}}"
        }
        "{\"currency\":\"$ccy\",\"baseRate\":${r.baseRate},\"days\":${CurrencyConventions.getDaysInYear(ccy)},\"tiers\":$tiers}"
    }
    append(entries.joinToString(","))
    append("],\"lastFetch\":${IbkrMarginRateService.getLastFetchMillis()}}")
}
