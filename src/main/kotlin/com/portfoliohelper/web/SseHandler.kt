package com.portfoliohelper.web

import com.portfoliohelper.service.IbkrCurrencyInterest
import com.portfoliohelper.service.PortfolioMasterService
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.CashEntryDisplay
import com.portfoliohelper.service.StockDisplay
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.RebalAllocSnapshot
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
    val totalBaseUsd: Double,
    val totalKnown: Boolean,
    val marginBaseUsd: Double
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
    val lastFetch: Long,
    val currentDailyUsd: Double,
    val cheapestCcy: String?,
    val cheapestDailyUsd: Double,
    val savingsUsd: Double,
    val label: String,
    val perCurrency: List<IbkrCurrencyInterest>
) : SseEvent()

@Serializable
@SerialName("rebal-alloc")
private data class RebalAllocSseEvent(
    val portfolioId: String,
    val perSymbolAllocUsd: Map<String, Double>
) : SseEvent()

@Serializable
@SerialName("reload")
private data class ReloadSseEvent(
    val timestamp: Long
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
                totalBaseUsd = snap.totalBaseUsd,
                totalKnown = snap.totalKnown,
                marginBaseUsd = snap.marginBaseUsd
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
                lastFetch = snap.lastFetch,
                currentDailyUsd = snap.currentDailyUsd,
                cheapestCcy = snap.cheapestCcy,
                cheapestDailyUsd = snap.cheapestDailyUsd,
                savingsUsd = snap.savingsUsd,
                label = snap.label,
                perCurrency = snap.perCurrency
            )))
        }
    }

    launch {
        PortfolioMasterService.rebalAllocFlow.collect { snap ->
            channel.trySend(appJson.encodeToString<SseEvent>(
                RebalAllocSseEvent(snap.portfolioId, snap.perSymbolAllocUsd)
            ))
        }
    }

    launch {
        PortfolioUpdateBroadcaster.reloadEvents.collect {
            channel.trySend(appJson.encodeToString<SseEvent>(ReloadSseEvent(timestamp = it.timestamp)))
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


