package com.portfoliohelper.data.repository

import com.portfoliohelper.data.model.CashEntry

data class IbkrCurrencyInterest(
    val currency: String,
    val displayRateText: String,
    val dailyInterestUsd: Double,
    val hypotheticalDailyUsd: Double
)

data class IbkrInterestResult(
    val currentDailyUsd: Double,
    val cheapestCcy: String?,
    val cheapestDailyUsd: Double,
    val savingsUsd: Double,
    val label: String,
    val perCurrency: List<IbkrCurrencyInterest>
)

object IbkrInterestCalculator {

    // Days-in-year per currency (money market convention, matches backend currency-conventions.properties)
    private val daysInYear = mapOf(
        "AUD" to 365, "CAD" to 365, "CNH" to 365, "CNY" to 365, "GBP" to 365,
        "HKD" to 365, "ILS" to 365, "INR" to 365, "KRW" to 365, "NZD" to 365,
        "RUB" to 365, "SGD" to 365
        // All others (CHF, EUR, JPY, USD, etc.) default to 360
    )

    fun compute(
        entries: List<CashEntry>,
        fxRates: Map<String, Double>,  // ccy -> USD rate e.g. {"EUR": 1.08, "GBP": 1.27}
        ratesSnap: IbkrRatesSnapshot
    ): IbkrInterestResult? {
        val allRates = ratesSnap.rates
        if (allRates.isEmpty()) return null

        val marginCurrencies = mutableSetOf("USD")
        val nativeMargin = mutableMapOf<String, Double>()
        var totalMarginUsd = 0.0

        for (entry in entries) {
            if (!entry.isMargin) continue
            val ccy = if (entry.currency == "USD") "USD" else entry.currency.uppercase()
            val fxRate = if (ccy == "USD") 1.0 else fxRates[ccy] ?: 1.0
            nativeMargin[ccy] = (nativeMargin[ccy] ?: 0.0) + entry.amount
            totalMarginUsd += entry.amount * fxRate
            marginCurrencies.add(ccy)
        }

        // Negative totalMarginUsd means net borrowing; positive means net long (no borrowing needed)
        val effectiveBorrowingUsd = maxOf(0.0, -totalMarginUsd)

        val perCurrency = mutableListOf<IbkrCurrencyInterest>()
        var currentDailyUsd = 0.0
        var cheapestCcy: String? = null
        var cheapestDailyUsd = 0.0

        for (ccy in marginCurrencies) {
            val rates = allRates[ccy] ?: continue
            val tiers = rates.tiers
            val baseRate = tiers.firstOrNull()?.rate ?: continue
            val fxRate = if (ccy == "USD") 1.0 else (fxRates[ccy] ?: continue)
            val days = daysInYear[ccy] ?: 360

            // Interest is charged on the net loan per currency only (positive balance = no interest)
            val nativeLoan = maxOf(0.0, -(nativeMargin[ccy] ?: 0.0))
            val blended = if (nativeLoan > 0) blendedRate(tiers, nativeLoan) else null
            val effectiveRate = blended ?: baseRate
            val dailyInterestUsd = if (nativeLoan > 0) nativeLoan * effectiveRate / 100.0 / days * fxRate else 0.0
            currentDailyUsd += dailyInterestUsd

            // Hypothetical: cost if all NET borrowing were consolidated into this currency.
            // Use 0.0 when effectiveBorrowingUsd == 0 so blendedRate always returns null (base tier).
            val hypotheticalNative = if (effectiveBorrowingUsd > 0) effectiveBorrowingUsd / fxRate else 0.0
            val hypotheticalBlended = blendedRate(tiers, hypotheticalNative)
            val hypotheticalRate = hypotheticalBlended ?: baseRate
            val hypotheticalDaily = hypotheticalNative * hypotheticalRate / 100.0 / days * fxRate

            if (effectiveBorrowingUsd > 0 && (cheapestCcy == null || hypotheticalDaily < cheapestDailyUsd)) {
                cheapestDailyUsd = hypotheticalDaily
                cheapestCcy = ccy
            }

            val displayRateText = if (hypotheticalBlended != null)
                "%.3f%% (%.3f%%)".format(hypotheticalBlended, baseRate)
            else
                "%.3f%%".format(baseRate)

            perCurrency += IbkrCurrencyInterest(ccy, displayRateText, dailyInterestUsd, hypotheticalDaily)
        }

        if (perCurrency.isEmpty()) return null

        // When net borrowing is zero, converting positive-balance currencies to cover loan currencies
        // eliminates all interest — cheapest cost is 0, savings = full current cost
        if (effectiveBorrowingUsd == 0.0 && currentDailyUsd > 0) {
            cheapestDailyUsd = 0.0
            cheapestCcy = nativeMargin
                .filter { it.value > 0 }
                .maxByOrNull { it.value * (if (it.key == "USD") 1.0 else fxRates[it.key] ?: 1.0) }
                ?.key
        }

        val savingsUsd = if (currentDailyUsd > 0) currentDailyUsd - cheapestDailyUsd else 0.0

        return IbkrInterestResult(
            currentDailyUsd = currentDailyUsd,
            cheapestCcy = cheapestCcy,
            cheapestDailyUsd = cheapestDailyUsd,
            savingsUsd = savingsUsd,
            label = buildLabel(cheapestCcy, perCurrency),
            perCurrency = perCurrency
        )
    }

    private fun blendedRate(tiers: List<IbkrRateTier>, amount: Double): Double? {
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
        return if (cheapestCcy == "USD") {
            val other = perCurrency.firstOrNull { it.currency != "USD" }?.currency
            if (other != null) "Saving (Sell USD.$other)" else "Saving"
        } else "Saving (Buy USD.$cheapestCcy)"
    }
}
