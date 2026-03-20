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
import com.portfoliohelper.util.appJson
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private data class PriceEvent(
    val symbol: String,
    val markPrice: Double?,
    val lastClosePrice: Double?,
    val isMarketClosed: Boolean?,
    val tradingPeriodEnd: Long?,
    val currency: String?,
    val timestamp: Long?
)

@Serializable
private data class NavEvent(
    val type: String = "nav",
    val symbol: String,
    val nav: Double,
    val timestamp: Long
)

@Serializable
private data class IbkrRateTier(val upTo: Double?, val rate: Double)

@Serializable
private data class IbkrRateCurrencyEntry(
    val currency: String,
    val baseRate: Double,
    val days: Int,
    val tiers: List<IbkrRateTier>
)

@Serializable
private data class IbkrRatesEvent(
    val type: String = "ibkr-rates",
    val currencies: List<IbkrRateCurrencyEntry>,
    val lastFetch: Long
)

@Serializable
private data class PortfolioValueEvent(
    val type: String = "portfolio-value",
    val portfolioId: String,
    val value: Double
)

@Serializable
private data class ReloadSseEvent(
    val type: String = "reload",
    val timestamp: Long
)

@Serializable
private data class DividendSseEvent(
    val type: String = "dividend",
    val portfolioId: String,
    val total: Double,
    val calcUpToDate: String
)

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
                channel.trySend(appJson.encodeToString(PortfolioValueEvent(portfolioId = p.slug, value = total)))
            }
        }
    }

    launch {
        PortfolioUpdateBroadcaster.reloadEvents.collect {
            channel.trySend(appJson.encodeToString(ReloadSseEvent(timestamp = it.timestamp)))
        }
    }

    launch {
        DividendService.updates.collect { update ->
            channel.trySend(appJson.encodeToString(DividendSseEvent(
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

private fun buildPriceJson(symbol: String, quote: YahooQuote): String =
    appJson.encodeToString(PriceEvent(
        symbol = symbol,
        markPrice = quote.regularMarketPrice,
        lastClosePrice = quote.previousClose,
        isMarketClosed = quote.isMarketClosed,
        tradingPeriodEnd = quote.tradingPeriodEnd,
        currency = quote.currency,
        timestamp = quote.lastUpdateTime
    ))

private fun buildNavJson(symbol: String, navData: NavData): String =
    appJson.encodeToString(NavEvent(symbol = symbol, nav = navData.nav, timestamp = navData.lastFetchTime))

private fun buildIbkrJson(): String = appJson.encodeToString(
    IbkrRatesEvent(
        currencies = IbkrMarginRateService.getAllRates().map { (ccy, r) ->
            IbkrRateCurrencyEntry(
                currency = ccy,
                baseRate = r.baseRate,
                days = CurrencyConventions.getDaysInYear(ccy),
                tiers = r.tiers.map { IbkrRateTier(it.upTo, it.rate) }
            )
        },
        lastFetch = IbkrMarginRateService.getLastFetchMillis()
    )
)