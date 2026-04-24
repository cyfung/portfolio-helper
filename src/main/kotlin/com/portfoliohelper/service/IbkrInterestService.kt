package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

@Serializable
data class IbkrCurrencyInterest(
    val currency: String,
    val currentLoanNative: Double,           // absolute value of margin borrowed in native ccy
    val blendedRatePct: Double?,             // null if loan is below minimum tier
    val dailyInterestUsd: Double,            // actual daily interest for current loan
    val hypotheticalDailyUsd: Double,        // daily interest if all debt moved to this ccy
    val displayRateText: String,             // e.g. "5.123% (4.830%)" or "4.830%"
    val nativeBalance: Double,               // actual signed balance (positive = holding, negative = borrowing)
    val fxRateUsd: Double                    // price of 1 unit of this ccy in USD (USD itself = 1.0)
)

@Serializable
data class IbkrInterestSnapshot(
    val portfolioId: String,
    val currentDailyUsd: Double,
    val cheapestCcy: String?,
    val cheapestDailyUsd: Double,
    val savingsUsd: Double,
    val label: String,                       // "Saving" or "Saving (Buy/Sell USD.XXX)"
    val perCurrency: List<IbkrCurrencyInterest>,
    val lastFetch: Long
)

class IbkrInterestService(
    private val portfolioId: String,
    cashEntries: StateFlow<List<CashEntry>>,
    cashSvc: CashDisplayService,
    private val privacyScalePct: StateFlow<Double?>,
    scope: CoroutineScope
) {
    val updates: StateFlow<IbkrInterestSnapshot?> = combine(
        cashEntries,
        cashSvc.updates,
        IbkrMarginRateService.ratesFlow,
        privacyScalePct
    ) { entries, cash, ratesSnap, scale ->
        compute(entries, cash, ratesSnap, scale)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun compute(entries: List<CashEntry>, cashSnap: CashDisplaySnapshot, ratesSnap: IbkrMarginRateService.RatesSnapshot, scale: Double?): IbkrInterestSnapshot? {
        val allRates = ratesSnap.rates
        if (allRates.isEmpty()) return null

        val marginCurrencies = mutableSetOf("USD")
        val nativeMargin = mutableMapOf<String, Double>()
        for (entry in entries) {
            if (!entry.marginFlag) continue
            val ccy = if (entry.currency == "USD" || entry.currency == "P") "USD" else entry.currency.uppercase()
            if (ccy != "P") {
                nativeMargin[ccy] = (nativeMargin[ccy] ?: 0.0) + entry.amount  // signed net per currency
                marginCurrencies.add(ccy)
            }
        }

        // cashSnap.marginBaseUsd is already the NET of all margin entries; negative = net borrowing
        val totalMarginUsd = if (cashSnap.marginBaseUsd < 0) -cashSnap.marginBaseUsd else 0.0
        val perCurrency = mutableListOf<IbkrCurrencyInterest>()
        var currentDailyUsd = 0.0
        var cheapestCcy: String? = null
        var cheapestDailyUsd = 0.0

        for (ccy in marginCurrencies) {
            // Fall back to USD rates for currencies that have no IBKR pro rate
            val usingUsdFallback = allRates[ccy] == null
            val rates = allRates[ccy] ?: allRates["USD"] ?: continue
            val tiers = rates.tiers
            val baseRate = tiers.firstOrNull()?.rate ?: continue

            val fxRate: Double = when (ccy) {
                "USD" -> 1.0
                else -> com.portfoliohelper.service.yahoo.YahooMarketDataService
                    .getQuote("${ccy}USD=X")?.regularMarketPrice ?: continue
            }

            // Interest charged on net loan per currency only (positive balance = no interest)
            val scaleFactor = (scale ?: 100.0) / 100.0
            val nativeLoan = maxOf(0.0, -(nativeMargin[ccy] ?: 0.0)) * scaleFactor
            // For USD-fallback CCYs: convert to USD so tier thresholds (expressed in USD) apply correctly
            val loanForRate = if (usingUsdFallback) nativeLoan * fxRate else nativeLoan
            val blended = if (nativeLoan > 0) blendedRate(tiers, loanForRate) else null
            val effectiveRate = blended ?: baseRate
            val days = if (usingUsdFallback) CurrencyConventions.getDaysInYear("USD")
                       else CurrencyConventions.getDaysInYear(ccy)

            val dailyInterestUsd = if (usingUsdFallback)
                if (nativeLoan > 0) loanForRate * effectiveRate / 100.0 / days else 0.0
            else {
                val nativeDaily = if (nativeLoan > 0) nativeLoan * effectiveRate / 100.0 / days else 0.0
                nativeDaily * fxRate
            }
            currentDailyUsd += dailyInterestUsd

            // Use 0.0 when totalMarginUsd == 0 so blendedRate returns null (base tier shown)
            val hypotheticalNative = if (totalMarginUsd > 0) totalMarginUsd / fxRate else 0.0
            val hypotheticalLoanForRate = if (usingUsdFallback) totalMarginUsd else hypotheticalNative
            val hypotheticalBlended = blendedRate(tiers, hypotheticalLoanForRate)
            val hypotheticalRate = hypotheticalBlended ?: baseRate
            val hypotheticalDaily = if (usingUsdFallback)
                hypotheticalLoanForRate * hypotheticalRate / 100.0 / days
            else
                hypotheticalNative * hypotheticalRate / 100.0 / days * fxRate

            if (totalMarginUsd > 0 && (cheapestCcy == null || hypotheticalDaily < cheapestDailyUsd)) {
                cheapestDailyUsd = hypotheticalDaily
                cheapestCcy = ccy
            }

            if (usingUsdFallback) continue

            val displayRateText = if (hypotheticalBlended != null)
                "%.3f%% (%.3f%%)".format(hypotheticalBlended, baseRate)
            else
                "%.3f%%".format(baseRate)

            perCurrency += IbkrCurrencyInterest(
                currency = ccy,
                currentLoanNative = nativeLoan,
                blendedRatePct = blended,
                dailyInterestUsd = dailyInterestUsd,
                hypotheticalDailyUsd = hypotheticalDaily,
                displayRateText = displayRateText,
                nativeBalance = (nativeMargin[ccy] ?: 0.0) * scaleFactor,
                fxRateUsd = fxRate
            )
        }

        if (perCurrency.isEmpty()) return null

        // When net borrowing is zero but individual-currency interest exists, converting
        // positive-balance currencies to loan currencies eliminates all interest — savings = full cost
        if (totalMarginUsd == 0.0 && currentDailyUsd > 0) {
            val fxByKey = perCurrency.associateBy({ it.currency }, { it.fxRateUsd })
            cheapestDailyUsd = 0.0
            cheapestCcy = nativeMargin.filter { it.value > 0 }
                .maxByOrNull { (k, v) -> v * (fxByKey[k] ?: 1.0) }
                ?.key
        }

        val savingsUsd = if (currentDailyUsd > 0) currentDailyUsd - cheapestDailyUsd else 0.0
        val label = if (savingsUsd < 0.01) "Saving" else buildLabel(cheapestCcy, perCurrency)

        return IbkrInterestSnapshot(
            portfolioId = portfolioId,
            currentDailyUsd = currentDailyUsd,
            cheapestCcy = cheapestCcy,
            cheapestDailyUsd = cheapestDailyUsd,
            savingsUsd = savingsUsd,
            label = label,
            perCurrency = perCurrency,
            lastFetch = ratesSnap.lastFetch
        )
    }

    private fun blendedRate(tiers: List<IbkrMarginRateService.RateTier>, amount: Double): Double? {
        if (amount <= 0 || tiers.isEmpty()) return null
        val baseCap = tiers.first().upTo
        if (baseCap == null || amount <= baseCap) return null
        var remaining = amount
        var totalInterest = 0.0
        var prevUpTo = 0.0
        for (tier in tiers) {
            val capacity = if (tier.upTo != null) tier.upTo - prevUpTo else Double.MAX_VALUE
            val inTier = minOf(remaining, capacity)
            totalInterest += inTier * tier.rate / 100.0
            remaining -= inTier
            if (remaining <= 0) break
            prevUpTo = tier.upTo ?: 0.0
        }
        return (totalInterest / amount) * 100.0
    }

    private fun buildLabel(cheapestCcy: String?, perCurrency: List<IbkrCurrencyInterest>): String {
        if (cheapestCcy == null || perCurrency.size != 2) return "Saving"
        val usdEntry = perCurrency.firstOrNull { it.currency.uppercase() == "USD" } ?: return "Saving"
        val otherEntry = perCurrency.firstOrNull { it.currency.uppercase() != "USD" } ?: return "Saving"
        val other = otherEntry.currency

        val usdLoan = usdEntry.currentLoanNative
        val hkdLoan = otherEntry.currentLoanNative

        // Symmetric action logic:
        // "Buy USD.X"  when reducing USD loan — use existing X cash (USD cheapest) or borrow X (X cheapest)
        // "Sell USD.X" when reducing X loan   — use existing USD cash (X cheapest) or borrow USD (USD cheapest)
        val action = when {
            usdLoan > 0 && (otherEntry.nativeBalance > 0 || cheapestCcy.uppercase() != "USD") -> "Buy"
            hkdLoan > 0 && (usdEntry.nativeBalance > 0 || cheapestCcy.uppercase() == "USD") -> "Sell"
            else -> return "Saving"
        }

        val fxRate = otherEntry.fxRateUsd  // 1 unit of other = fxRate USD
        val hkdLoanUsd = hkdLoan * fxRate

        val amountStr = if (action == "Buy") {
            // Full conversion when other ccy is cheapest (borrow it); cash-only netting when USD is cheapest
            val buyUsd = if (cheapestCcy.uppercase() != "USD") usdLoan
                         else minOf(usdLoan, otherEntry.nativeBalance.coerceAtLeast(0.0) * fxRate)
            val sellOther = if (fxRate > 0) buyUsd / fxRate else 0.0
            if (buyUsd > 0) ": %s%.0f → \$%.2f".format(other, sellOther, buyUsd) else return "Saving"
        } else {
            // Full conversion when USD is cheapest (borrow USD); cash-only netting when other ccy is cheapest
            val sellUsd = if (cheapestCcy.uppercase() == "USD") hkdLoanUsd
                          else minOf(usdEntry.nativeBalance.coerceAtLeast(0.0), hkdLoanUsd)
            val buyOther = if (fxRate > 0) sellUsd / fxRate else 0.0
            if (sellUsd > 0) ": \$%.2f → %s%.0f".format(sellUsd, other, buyOther) else return "Saving"
        }

        return "Saving ($action USD.$other$amountStr)"
    }
}
