package com.portfoliohelper.data.repository

import com.portfoliohelper.data.model.CashEntry

data class IbkrCurrencyInterest(
    val currency: String,
    val displayRateText: String,
    val dailyInterestUsd: Double,
    val hypotheticalDailyUsd: Double,
    val nativeBalance: Double,   // actual signed balance (positive = holding, negative = borrowing)
    val fxRateUsd: Double        // price of 1 unit of this ccy in USD (USD itself = 1.0)
)

data class IbkrInterestResult(
    val currentDailyUsd: Double,
    val cheapestCcy: String?,
    val cheapestDailyUsd: Double,
    val savingsUsd: Double,
    val label: String,
    val labelAction: String?,    // e.g. "Sell USD.HKD: $10.00 → HKD78", shown below the Saving row
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

            perCurrency += IbkrCurrencyInterest(
                currency = ccy,
                displayRateText = displayRateText,
                dailyInterestUsd = dailyInterestUsd,
                hypotheticalDailyUsd = hypotheticalDaily,
                nativeBalance = nativeMargin[ccy] ?: 0.0,
                fxRateUsd = fxRate
            )
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

        val (label, labelAction) = buildLabel(cheapestCcy, perCurrency)
        val effectiveLabelAction = if (savingsUsd < 0.01) null else labelAction
        return IbkrInterestResult(
            currentDailyUsd = currentDailyUsd,
            cheapestCcy = cheapestCcy,
            cheapestDailyUsd = cheapestDailyUsd,
            savingsUsd = savingsUsd,
            label = label,
            labelAction = effectiveLabelAction,
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

    private fun buildLabel(cheapestCcy: String?, perCurrency: List<IbkrCurrencyInterest>): Pair<String, String?> {
        if (cheapestCcy == null || perCurrency.size != 2) return Pair("Saving", null)
        val usdEntry = perCurrency.firstOrNull { it.currency == "USD" } ?: return Pair("Saving", null)
        val otherEntry = perCurrency.firstOrNull { it.currency != "USD" } ?: return Pair("Saving", null)
        val other = otherEntry.currency

        val usdLoan = (-usdEntry.nativeBalance).coerceAtLeast(0.0)
        val hkdLoan = (-otherEntry.nativeBalance).coerceAtLeast(0.0)

        // Symmetric action logic:
        // "Buy USD.HKD"  when reducing USD loan — use existing HKD cash (USD cheapest) or borrow HKD (HKD cheapest)
        // "Sell USD.HKD" when reducing HKD loan — use existing USD cash (HKD cheapest) or borrow USD (USD cheapest)
        val action = when {
            usdLoan > 0 && (otherEntry.nativeBalance > 0 || cheapestCcy != "USD") -> "Buy"
            hkdLoan > 0 && (usdEntry.nativeBalance > 0 || cheapestCcy == "USD") -> "Sell"
            else -> return Pair("Saving", null)
        }

        val fxRate = otherEntry.fxRateUsd  // 1 unit of other = fxRate USD
        val hkdLoanUsd = hkdLoan * fxRate

        val amountStr = if (action == "Buy") {
            // Full conversion when HKD is cheapest (borrow HKD); cash-only netting when USD is cheapest
            val buyUsd = if (cheapestCcy != "USD") usdLoan
                         else minOf(usdLoan, otherEntry.nativeBalance.coerceAtLeast(0.0) * fxRate)
            val sellOther = if (fxRate > 0) buyUsd / fxRate else 0.0
            if (buyUsd > 0) "%s%.0f → \$%.2f".format(other, sellOther, buyUsd) else null
        } else {
            // Full conversion when USD is cheapest (borrow USD); cash-only netting when HKD is cheapest
            val sellUsd = if (cheapestCcy == "USD") hkdLoanUsd
                          else minOf(usdEntry.nativeBalance.coerceAtLeast(0.0), hkdLoanUsd)
            val buyOther = if (fxRate > 0) sellUsd / fxRate else 0.0
            if (sellUsd > 0) "\$%.2f → %s%.0f".format(sellUsd, other, buyOther) else null
        }

        val actionStr = amountStr?.let { "$action USD.$other: $it" }
        return Pair("Saving", actionStr)
    }
}
