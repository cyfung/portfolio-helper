package com.portfoliohelper.service

import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class StockDisplay(
    val symbol: String,
    val qty: Double,
    val markPrice: Double?,
    val closePrice: Double?,
    val dayChangeNative: Double?,    // per share, in stock's native currency
    val dayChangePct: Double?,
    val positionValueUsd: Double?,   // (mark or close) × qty × fxRate
    val positionChangeUsd: Double?,  // (mark - close) × qty × fxRate
    val currency: String,
    val currentWeightPct: Double?,
    val targetWeightPct: Double?,
    val estPriceNative: Double?,     // LETF estimated per-share price (native ccy); null if N/A or stale
    val lastNav: Double?,
    val isMarketClosed: Boolean,
    val tradingPeriodEnd: Long?,
    val localDate: String?
)

@Serializable
data class StockDisplaySnapshot(
    val portfolioId: String,
    val stocks: List<StockDisplay>,
    val stockGrossUsd: Double,
    val stockGrossKnown: Boolean,
    val dayChangeUsd: Double,
    val prevDayUsd: Double
)

class StockDisplayService(
    private val portfolioId: String,
    private val stocks: StateFlow<List<Stock>>,
    private val privacyScalePct: StateFlow<Double?>
) {
    private val _updates = MutableSharedFlow<StockDisplaySnapshot>(replay = 1, extraBufferCapacity = 8)
    val updates: SharedFlow<StockDisplaySnapshot> = _updates.asSharedFlow()

    fun initialize(scope: CoroutineScope) {
        scope.launch { YahooMarketDataService.batchComplete.collect { computeAndEmit() } }
        scope.launch { NavService.updates.collect { computeAndEmit() } }
        scope.launch { stocks.collect { computeAndEmit() } }
        scope.launch { privacyScalePct.collect { computeAndEmit() } }
    }

    private fun computeAndEmit() { _updates.tryEmit(compute()) }

    fun compute(): StockDisplaySnapshot {
        val baseStocks = stocks.value
        val nowMs = System.currentTimeMillis()

        // Intermediate data per stock
        data class StockWork(
            val symbol: String,
            val qty: Double,
            val markPrice: Double?,
            val closePrice: Double?,
            val fxRateToUsd: Double?,
            val currency: String,
            val targetWeightPct: Double?,
            val positionValueUsd: Double?,
            val positionChangeUsd: Double?,
            val dayChangeNative: Double?,
            val dayChangePct: Double?,
            val estPriceNative: Double?,
            val isMarketClosed: Boolean,
            val tradingPeriodEnd: Long?,
            val localDate: String?,
            val sessionStarted: Boolean
        )

        val scale = privacyScalePct.value
        val work = baseStocks.map { stock ->
            val qty = if (scale != null) Math.round(stock.amount * scale / 100.0).toDouble() else stock.amount
            val quote = YahooMarketDataService.getQuote(stock.label)
            val ccy = quote?.currency ?: "USD"
            val fxRate = getFxRateToUsd(ccy)
            val markPrice = quote?.regularMarketPrice
            val closePrice = quote?.previousClose
            val effectivePrice = markPrice ?: closePrice

            val positionValueUsd = if (effectivePrice != null && fxRate != null)
                effectivePrice * qty * fxRate else null
            val positionChangeUsd = if (markPrice != null && closePrice != null && fxRate != null)
                (markPrice - closePrice) * qty * fxRate else null
            val dayChangeNative = if (markPrice != null && closePrice != null) markPrice - closePrice else null
            val dayChangePct = if (dayChangeNative != null && closePrice != null && closePrice != 0.0)
                dayChangeNative / closePrice * 100.0 else null

            val localDate = computeLocalDate(quote?.tradingPeriodEnd, quote?.gmtoffset)
            val sessionStarted = quote?.tradingPeriodStart?.let { nowMs / 1000 >= it } ?: false
            val estPriceNative = if (!sessionStarted) null else computeLetfEstVal(
                stock.letfComponents, quote, NavService.getNav(stock.label),
                qty, fxRate
            )

            StockWork(
                symbol = stock.label, qty = qty,
                markPrice = markPrice, closePrice = closePrice,
                fxRateToUsd = fxRate, currency = ccy,
                targetWeightPct = stock.targetWeight,
                positionValueUsd = positionValueUsd,
                positionChangeUsd = positionChangeUsd,
                dayChangeNative = dayChangeNative, dayChangePct = dayChangePct,
                estPriceNative = estPriceNative,
                isMarketClosed = quote?.isMarketClosed ?: false,
                tradingPeriodEnd = quote?.tradingPeriodEnd,
                localDate = localDate,
                sessionStarted = sessionStarted
            )
        }

        val stockGrossUsd = work.sumOf { it.positionValueUsd ?: 0.0 }
        val stockGrossKnown = baseStocks.isEmpty() || work.all { it.positionValueUsd != null }

        // Day change: only include stocks whose session has started today
        var dayMarkTotal = 0.0
        var dayPrevTotal = 0.0
        for (w in work) {
            if (!w.sessionStarted) continue
            val fx = w.fxRateToUsd ?: continue
            w.markPrice?.let  { dayMarkTotal += it * w.qty * fx }
            w.closePrice?.let { dayPrevTotal += it * w.qty * fx }
        }

        val stocks = work.map { w ->
            val currentWeightPct = if (stockGrossKnown && stockGrossUsd > 0 && w.positionValueUsd != null)
                w.positionValueUsd / stockGrossUsd * 100.0 else null
            StockDisplay(
                symbol = w.symbol, qty = w.qty,
                markPrice = w.markPrice, closePrice = w.closePrice,
                dayChangeNative = w.dayChangeNative, dayChangePct = w.dayChangePct,
                positionValueUsd = w.positionValueUsd,
                positionChangeUsd = w.positionChangeUsd,
                currency = w.currency,
                currentWeightPct = currentWeightPct,
                targetWeightPct = w.targetWeightPct,
                estPriceNative = w.estPriceNative,
                lastNav = NavService.getNav(w.symbol),
                isMarketClosed = w.isMarketClosed,
                tradingPeriodEnd = w.tradingPeriodEnd,
                localDate = w.localDate
            )
        }

        val fullPrevUsd = work.sumOf { (it.closePrice ?: 0.0) * it.qty * (it.fxRateToUsd ?: 0.0) }
        return StockDisplaySnapshot(
            portfolioId = portfolioId,
            stocks = stocks,
            stockGrossUsd = stockGrossUsd,
            stockGrossKnown = stockGrossKnown,
            dayChangeUsd = dayMarkTotal - dayPrevTotal,
            prevDayUsd = fullPrevUsd
        )
    }

    /** FX rate from stock's native currency to USD. Handles sub-unit currencies (GBp → GBP/100). */
    private fun getFxRateToUsd(currency: String): Double? {
        if (currency == "USD") return 1.0
        // Sub-unit: 2 uppercase + 1 lowercase (e.g. GBp, ILa, ZAc) → parent / 100
        if (currency.length == 3 && currency[0].isUpperCase() && currency[1].isUpperCase() && currency[2].isLowerCase()) {
            val rate = YahooMarketDataService.getQuote("${currency.uppercase()}USD=X")?.regularMarketPrice
            return if (rate != null) rate / 100.0 else null
        }
        return YahooMarketDataService.getQuote("${currency}USD=X")?.regularMarketPrice
    }

    private fun computeLetfEstVal(
        components: List<Pair<Double, String>>?,
        quote: com.portfoliohelper.service.yahoo.YahooQuote?,
        nav: Double?,
        @Suppress("UNUSED_PARAMETER") qty: Double,
        @Suppress("UNUSED_PARAMETER") fxRateToUsd: Double?
    ): Double? {
        if (components.isNullOrEmpty()) return null
        val closePrice = quote?.previousClose ?: return null
        val basePrice = nav ?: closePrice
        var sumComponent = 0.0
        for ((mult, sym) in components) {
            val compQuote = YahooMarketDataService.getQuote(sym) ?: return null
            val compMark = compQuote.regularMarketPrice ?: return null
            val compClose = compQuote.previousClose ?: return null
            if (compClose == 0.0) return null
            sumComponent += mult * (compMark - compClose) / compClose
        }
        return (1.0 + sumComponent) * basePrice  // per-share estimated price in native currency
    }

    private fun computeLocalDate(tradingPeriodEndSec: Long?, gmtoffset: Int?): String? {
        if (tradingPeriodEndSec == null || gmtoffset == null) return null
        return Instant.ofEpochSecond(tradingPeriodEndSec + gmtoffset)
            .atOffset(ZoneOffset.UTC).toLocalDate().toString()
    }
}
