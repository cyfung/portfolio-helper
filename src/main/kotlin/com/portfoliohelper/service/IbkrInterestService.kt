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
    scope: CoroutineScope
) {
    val updates: StateFlow<IbkrInterestSnapshot?> = combine(
        cashEntries,
        cashSvc.updates,
        IbkrMarginRateService.ratesFlow
    ) { entries, cash, ratesSnap ->
        compute(entries, cash, ratesSnap)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun compute(entries: List<CashEntry>, cashSnap: CashDisplaySnapshot, ratesSnap: IbkrMarginRateService.RatesSnapshot): IbkrInterestSnapshot? {
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

        // cashSnap.marginUsd is already the NET of all margin entries; negative = net borrowing
        val totalMarginUsd = if (cashSnap.marginUsd < 0) -cashSnap.marginUsd else 0.0
        val perCurrency = mutableListOf<IbkrCurrencyInterest>()
        var currentDailyUsd = 0.0
        var cheapestCcy: String? = null
        var cheapestDailyUsd = 0.0

        for (ccy in marginCurrencies) {
            val rates = allRates[ccy] ?: continue
            val tiers = rates.tiers
            val baseRate = tiers.firstOrNull()?.rate ?: continue

            val fxRate: Double = when (ccy) {
                "USD" -> 1.0
                else -> com.portfoliohelper.service.yahoo.YahooMarketDataService
                    .getQuote("${ccy}USD=X")?.regularMarketPrice ?: continue
            }

            // Interest charged on net loan per currency only (positive balance = no interest)
            val nativeLoan = maxOf(0.0, -(nativeMargin[ccy] ?: 0.0))
            val blended = if (nativeLoan > 0) blendedRate(tiers, nativeLoan) else null
            val effectiveRate = blended ?: baseRate
            val days = CurrencyConventions.getDaysInYear(ccy)

            val nativeDaily = if (nativeLoan > 0) nativeLoan * effectiveRate / 100.0 / days else 0.0
            val dailyInterestUsd = nativeDaily * fxRate
            currentDailyUsd += dailyInterestUsd

            // Use 0.0 when totalMarginUsd == 0 so blendedRate returns null (base tier shown)
            val hypotheticalNative = if (totalMarginUsd > 0) totalMarginUsd / fxRate else 0.0
            val hypotheticalBlended = blendedRate(tiers, hypotheticalNative)
            val hypotheticalRate = hypotheticalBlended ?: baseRate
            val hypotheticalDaily = hypotheticalNative * hypotheticalRate / 100.0 / days * fxRate

            if (totalMarginUsd > 0 && (cheapestCcy == null || hypotheticalDaily < cheapestDailyUsd)) {
                cheapestDailyUsd = hypotheticalDaily
                cheapestCcy = ccy
            }

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
                nativeBalance = nativeMargin[ccy] ?: 0.0,
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

        return IbkrInterestSnapshot(
            portfolioId = portfolioId,
            currentDailyUsd = currentDailyUsd,
            cheapestCcy = cheapestCcy,
            cheapestDailyUsd = cheapestDailyUsd,
            savingsUsd = savingsUsd,
            label = buildLabel(cheapestCcy, perCurrency),
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

        // Buy USD only when actively borrowing USD AND the other currency is cheapest (switch to borrowing other).
        // In all other cases (positive USD, or USD is cheapest), sell USD to pay off non-USD borrowing.
        val action = if (usdEntry.currentLoanNative > 0 && cheapestCcy.uppercase() != "USD") "Buy" else "Sell"

        val fxRate = otherEntry.fxRateUsd  // 1 unit of other = fxRate USD
        val amountStr = if (action == "Sell") {
            // Sell USD, receive other currency; sell min(positive USD balance, value of other loan)
            val otherLoanInUsd = otherEntry.currentLoanNative * fxRate
            val sellUsd = minOf(usdEntry.nativeBalance.coerceAtLeast(0.0), otherLoanInUsd)
            val buyOther = if (fxRate > 0) sellUsd / fxRate else 0.0
            if (sellUsd > 0) ": \$%.2f → %s%.0f".format(sellUsd, other, buyOther) else ""
        } else {
            // Buy USD, spend other currency; buy min(USD loan, value of positive other balance)
            val otherBalInUsd = otherEntry.nativeBalance.coerceAtLeast(0.0) * fxRate
            val buyUsd = minOf(usdEntry.currentLoanNative, otherBalInUsd)
            val sellOther = if (fxRate > 0) buyUsd / fxRate else 0.0
            if (buyUsd > 0) ": %s%.0f → \$%.2f".format(other, sellOther, buyUsd) else ""
        }

        return "Saving ($action USD.$other$amountStr)"
    }
}
