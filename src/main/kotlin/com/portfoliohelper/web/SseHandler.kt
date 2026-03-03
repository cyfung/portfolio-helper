package com.portfoliohelper.web

import com.portfoliohelper.service.PortfolioRegistry
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.nav.NavData
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.yahoo.YahooQuote
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal suspend fun ApplicationCall.handleSseStream() {
    val scope = CoroutineScope(coroutineContext)
    response.cacheControl(CacheControl.NoCache(null))
    response.headers.append(HttpHeaders.ContentType, "text/event-stream")
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append(HttpHeaders.Connection, "keep-alive")

    val channel = Channel<String>(Channel.BUFFERED)

    // Register callback for price updates; replay cached quotes immediately so
    // clients that connect after the first fetch don't wait for the next poll cycle
    val callback: (String, YahooQuote) -> Unit = { symbol, quote ->
        if (quote.regularMarketPrice != null) {
            val json = buildString {
                append("{")
                append("\"symbol\":\"$symbol\",")
                append("\"markPrice\":${quote.regularMarketPrice},")
                append("\"lastClosePrice\":${quote.previousClose},")
                append("\"isMarketClosed\":${quote.isMarketClosed},")
                append("\"tradingPeriodEnd\":${quote.tradingPeriodEnd},")
                append("\"timestamp\":${quote.lastUpdateTime}")
                append("}")
            }
            channel.trySend("data: $json\n\n")
        }
    }

    val unregisterPrice = YahooMarketDataService.onUpdateWithReplay(callback)

    // Register callback for NAV updates; replay cached NAV immediately so
    // clients that connect after a slow NAV fetch don't miss it until next poll
    val navCallback: (String, NavData) -> Unit = { symbol, navData ->
        val json = buildString {
            append("{")
            append("\"type\":\"nav\",")
            append("\"symbol\":\"$symbol\",")
            append("\"nav\":${navData.nav},")
            append("\"timestamp\":${navData.lastFetchTime}")
            append("}")
        }
        channel.trySend("data: $json\n\n")
    }

    val unregisterNav = NavService.onUpdateWithReplay(navCallback)

    // Register callback to emit each portfolio's total value after every price poll batch
    val portfolioValueCallback: () -> Unit = {
        for (p in PortfolioRegistry.entries) {
            val pTotal = YahooMarketDataService.getCurrentPortfolio(p.getStocks()).totalValue
            val pvJson = buildString {
                append("{\"type\":\"portfolio-value\",")
                append("\"portfolioId\":\"${p.id}\",")
                append("\"value\":${"%.2f".format(pTotal)}")
                append("}")
            }
            channel.trySend("data: $pvJson\n\n")
        }
    }
    val unregisterPortfolioValue = YahooMarketDataService.onBatchComplete(portfolioValueCallback)
    portfolioValueCallback()  // replay initial values on connect

    // Listen for portfolio reload events
    val collectJob = scope.launch {
        PortfolioUpdateBroadcaster.reloadEvents.collect {
            val json = "{\"type\":\"reload\",\"timestamp\":${it.timestamp}}"
            channel.send("data: $json\n\n")
        }
    }

    // Stream updates to client
    try {
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            writeFully(":keepalive\n\n".toByteArray(Charsets.UTF_8))
            flush()

            for (message in channel) {
                writeFully(message.toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    } finally {
        collectJob.cancel()
        unregisterPrice()
        unregisterNav()
        unregisterPortfolioValue()
        channel.close()
    }
}
