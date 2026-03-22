package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CashEntryDisplay(
    val entryId: String,
    val valueUsd: Double?,       // null = not ready (FX unknown or P-ref not ready)
    val isMarginEntry: Boolean
)

@Serializable
data class CashDisplaySnapshot(
    val portfolioId: String,
    val entries: List<CashEntryDisplay>,
    val totalUsd: Double,
    val totalKnown: Boolean,
    val marginUsd: Double,
    val marginKnown: Boolean
)

class CashDisplayService(
    private val portfolioId: String,
    private val cashEntries: StateFlow<List<CashEntry>>,
    private val privacyScalePct: StateFlow<Double?>
) {

    private val _updates =
        MutableSharedFlow<CashDisplaySnapshot>(replay = 1, extraBufferCapacity = 8)
    val updates: SharedFlow<CashDisplaySnapshot> = _updates.asSharedFlow()

    private val stockGrossCache = ConcurrentHashMap<String, StockGrossSnapshot>()

    fun initialize(scope: CoroutineScope) {
        scope.launch { YahooMarketDataService.batchComplete.collect { computeAndEmit() } }
        scope.launch { cashEntries.collect { computeAndEmit() } }
        scope.launch { privacyScalePct.collect { computeAndEmit() } }
        scope.launch {
            PortfolioMasterService.stockGrossFlow.collect { snap ->
                stockGrossCache[snap.portfolioId] = snap
                if (cashEntries.value.any { it.currency == "P" && it.portfolioRef == snap.portfolioId }) {
                    computeAndEmit()
                }
            }
        }
    }

    private fun computeAndEmit() {
        _updates.tryEmit(compute())
    }

    fun compute(): CashDisplaySnapshot {
        val entries = cashEntries.value.map { entry ->
            val valueUsd = resolveEntryUsd(entry)
            CashEntryDisplay(
                entryId = "${entry.label}-${entry.currency}",
                valueUsd = valueUsd,
                isMarginEntry = entry.marginFlag
            )
        }
        var totalUsd = 0.0
        var marginUsd = 0.0
        var totalKnown = true
        for (e in entries) {
            if (e.valueUsd == null) totalKnown = false
            else {
                totalUsd += e.valueUsd
                if (e.isMarginEntry) marginUsd += e.valueUsd
            }
        }
        return CashDisplaySnapshot(
            portfolioId = portfolioId,
            entries = entries,
            totalUsd = totalUsd,
            totalKnown = totalKnown,
            marginUsd = marginUsd,
            marginKnown = totalKnown
        )
    }

    /**
     * Returns the portfolio's total cash (USD) for use in server-side page rendering
     * (PortfolioRenderer.resolveEntryUsd for P-ref entries).
     */
    fun computeTotal(): Double = compute().totalUsd

    private fun resolveEntryUsd(entry: CashEntry): Double? {
        val scale = privacyScalePct.value
        val amount = if (scale != null) entry.amount * scale / 100.0 else entry.amount
        return when (entry.currency) {
            "USD" -> amount
            "P" -> {
                // entry.amount is a multiplier (1.0 / -1.0), not a monetary value; stockGrossUsd is already scaled
                val portfolioRef = entry.portfolioRef ?: return null
                val snap = stockGrossCache[portfolioRef] ?: return null
                if (!snap.stockGrossKnown) return null
                entry.amount * snap.stockGrossUsd
            }
            else -> {
                val rate = YahooMarketDataService.getQuote("${entry.currency}USD=X")?.regularMarketPrice
                    ?: return null
                amount * rate
            }
        }
    }

}
