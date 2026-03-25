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
    val label: String,
    val currency: String,              // original currency code; "P" for portfolio-ref entries
    val rawAmount: Double,             // privacy-scaled; for "P": resolved USD value (0 if unknown)
    val valueUsd: Double?,             // null = not ready (FX unknown or P-ref not ready)
    val isMarginEntry: Boolean,
    val portfolioRef: String? = null,
    val portfolioMultiplier: Double? = null  // non-null for all "P" entries (incl. broken refs)
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
    private val privacyScalePct: StateFlow<Double?>,
    private val dividendFlow: StateFlow<DividendSnapshot?>
) {

    private val _updates =
        MutableSharedFlow<CashDisplaySnapshot>(replay = 1, extraBufferCapacity = 8)
    val updates: SharedFlow<CashDisplaySnapshot> = _updates.asSharedFlow()

    private val stockGrossCache = ConcurrentHashMap<String, StockGrossSnapshot>()

    fun initialize(scope: CoroutineScope) {
        scope.launch { YahooMarketDataService.batchComplete.collect { computeAndEmit() } }
        scope.launch { cashEntries.collect { computeAndEmit() } }
        scope.launch { privacyScalePct.collect { computeAndEmit() } }
        scope.launch { dividendFlow.collect { computeAndEmit() } }
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
        val scale = privacyScalePct.value
        val entries = cashEntries.value.map { entry ->
            val valueUsd = resolveEntryUsd(entry)
            val rawAmount = when (entry.currency) {
                "P"  -> valueUsd ?: 0.0
                else -> if (scale != null) entry.amount * scale / 100.0 else entry.amount
            }
            CashEntryDisplay(
                entryId = "${entry.label}-${entry.currency}",
                label = entry.label,
                currency = entry.currency,
                rawAmount = rawAmount,
                valueUsd = valueUsd,
                isMarginEntry = entry.marginFlag,
                portfolioRef = entry.portfolioRef,
                portfolioMultiplier = if (entry.currency == "P") entry.amount else null
            )
        }.toMutableList()
        val divSnap = dividendFlow.value
        if (divSnap != null) {
            val scaledTotal = if (scale != null) divSnap.total * scale / 100.0 else divSnap.total
            entries += CashEntryDisplay(
                entryId = "Dividend-USD",
                label = "Dividend",
                currency = "USD",
                rawAmount = scaledTotal,
                valueUsd = scaledTotal,
                isMarginEntry = false
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
