package com.portfoliohelper.web

import com.portfoliohelper.service.DividendService
import com.portfoliohelper.service.PortfolioMasterService
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.CashEntryDisplay
import com.portfoliohelper.service.StockDisplay
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.util.appJson
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private sealed class SseEvent

@Serializable
@SerialName("fx-rates")
private data class FxRatesEvent(
    val rates: Map<String, Double>
) : SseEvent()

@Serializable
@SerialName("stock-display")
private data class StockDisplayEvent(
    val portfolioId: String,
    val stocks: List<StockDisplay>,
    val stockGrossUsd: Double,
    val stockGrossKnown: Boolean,
    val dayChangeUsd: Double,
    val prevDayUsd: Double
) : SseEvent()

@Serializable
@SerialName("cash-display")
private data class CashDisplayEvent(
    val portfolioId: String,
    val entries: List<CashEntryDisplay>,
    val totalUsd: Double,
    val totalKnown: Boolean,
    val marginUsd: Double
) : SseEvent()

@Serializable
@SerialName("portfolio-totals")
private data class PortfolioTotalsEvent(
    val portfolioId: String,
    val stockGrossUsd: Double,
    val stockGrossKnown: Boolean,
    val cashTotalUsd: Double,
    val cashKnown: Boolean,
    val grandTotalUsd: Double,
    val grandTotalKnown: Boolean,
    val marginUsd: Double,
    val dayChangeUsd: Double,
    val prevDayUsd: Double
) : SseEvent()

@Serializable
@SerialName("ibkr-display")
private data class IbkrDisplayEvent(
    val portfolioId: String,
    val html: String,
    val lastFetch: Long
) : SseEvent()

@Serializable
@SerialName("reload")
private data class ReloadSseEvent(
    val timestamp: Long
) : SseEvent()

@Serializable
@SerialName("dividend")
private data class DividendSseEvent(
    val portfolioId: String,
    val total: Double,
    val calcUpToDate: String
) : SseEvent()

internal suspend fun ServerSSESession.handleSseStream() {
    val channel = Channel<String>(Channel.BUFFERED)

    launch {
        YahooMarketDataService.fxRates.collect { rates ->
            if (rates.isNotEmpty()) channel.trySend(appJson.encodeToString<SseEvent>(FxRatesEvent(rates)))
        }
    }

    launch {
        PortfolioMasterService.stockFlow.collect { snap ->
            channel.trySend(appJson.encodeToString<SseEvent>(StockDisplayEvent(
                portfolioId = snap.portfolioId,
                stocks = snap.stocks,
                stockGrossUsd = snap.stockGrossUsd,
                stockGrossKnown = snap.stockGrossKnown,
                dayChangeUsd = snap.dayChangeUsd,
                prevDayUsd = snap.prevDayUsd
            )))
        }
    }

    launch {
        PortfolioMasterService.cashFlow.collect { snap ->
            channel.trySend(appJson.encodeToString<SseEvent>(CashDisplayEvent(
                portfolioId = snap.portfolioId,
                entries = snap.entries,
                totalUsd = snap.totalUsd,
                totalKnown = snap.totalKnown,
                marginUsd = snap.marginUsd
            )))
        }
    }

    launch {
        PortfolioMasterService.totalsFlow.collect { snap ->
            channel.trySend(appJson.encodeToString<SseEvent>(PortfolioTotalsEvent(
                portfolioId = snap.portfolioId,
                stockGrossUsd = snap.stockGrossUsd,
                stockGrossKnown = snap.stockGrossKnown,
                cashTotalUsd = snap.cashTotalUsd,
                cashKnown = snap.cashKnown,
                grandTotalUsd = snap.grandTotalUsd,
                grandTotalKnown = snap.grandTotalKnown,
                marginUsd = snap.marginUsd,
                dayChangeUsd = snap.dayChangeUsd,
                prevDayUsd = snap.prevDayUsd
            )))
        }
    }

    launch {
        PortfolioMasterService.interestFlow.collect { snap ->
            channel.trySend(appJson.encodeToString<SseEvent>(IbkrDisplayEvent(
                portfolioId = snap.portfolioId,
                html = renderIbkrDisplayHtml(snap),
                lastFetch = snap.lastFetch
            )))
        }
    }

    launch {
        PortfolioUpdateBroadcaster.reloadEvents.collect {
            channel.trySend(appJson.encodeToString<SseEvent>(ReloadSseEvent(timestamp = it.timestamp)))
        }
    }

    launch {
        DividendService.updates.collect { update ->
            channel.trySend(appJson.encodeToString<SseEvent>(DividendSseEvent(
                portfolioId = update.portfolioSlug,
                total = update.total,
                calcUpToDate = update.calcUpToDate
            )))
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


