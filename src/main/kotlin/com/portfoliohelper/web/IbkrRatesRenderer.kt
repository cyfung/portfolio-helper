package com.portfoliohelper.web

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.service.CurrencyConventions
import com.portfoliohelper.service.IbkrMarginRateService
import kotlinx.html.*

internal fun FlowContent.buildIbkrRatesTable(
    cashEntries: List<CashEntry>,
    resolveEntryUsd: (CashEntry) -> Double?,
    fxRateMap: Map<String, Double>
) {
    // Always show USD; add non-USD, non-P margin currencies sorted alphabetically
    val marginCurrencies: List<String> = buildList {
        add("USD")
        cashEntries.asSequence().filter { it.marginFlag }
            .map { it.currency.uppercase() }
            .filter { it != "USD" && it != "P" }
            .distinct().sorted().toList()
            .forEach { add(it) }
    }

    // Net margin in USD — negative means the user is actually borrowing
    val netMarginUsd = cashEntries
        .filter { it.marginFlag }
        .sumOf { resolveEntryUsd(it) ?: 0.0 }
    val totalMarginLoanUsd = if (netMarginUsd < 0) -netMarginUsd else 0.0

    // Native loan amount per currency (positive = borrowed)
    val nativeLoanByCurrency: Map<String, Double> = cashEntries
        .filter { it.marginFlag && it.currency.uppercase() != "P" }
        .groupBy { it.currency.uppercase() }
        .mapValues { (_, entries) -> entries.sumOf { e -> -(e.amount).coerceAtMost(0.0) } }

    data class RateRow(
        val currency: String,
        val rateDisplay: String,
        val nativeDailyInterest: Double,
        val effectiveRate: Double,
        val daysInYear: Int,
        val tiersJson: String
    )

    val rows = marginCurrencies.mapNotNull { ccy ->
        val currencyRates = IbkrMarginRateService.getRates(ccy) ?: return@mapNotNull null
        val fxRate: Double? = if (ccy == "USD") 1.0 else fxRateMap[ccy]
        // If FX rate not yet available, use 0 so blended falls back to base rate;
        // JS will recalculate the summary once the FX rate arrives via SSE.
        val loanAmount = if (fxRate != null && fxRate > 0) totalMarginLoanUsd / fxRate else 0.0
        val blended = if (loanAmount > 0) currencyRates.blendedRateIfMultiTier(loanAmount) else null
        val effectiveRate = blended ?: currencyRates.baseRate
        val daysInYear = CurrencyConventions.getDaysInYear(ccy)
        val nativeLoan = nativeLoanByCurrency[ccy] ?: 0.0
        val nativeRate = if (nativeLoan > 0)
            currencyRates.blendedRateIfMultiTier(nativeLoan) ?: currencyRates.baseRate
        else currencyRates.baseRate
        val nativeDailyInterest = nativeLoan * nativeRate / 100.0 / daysInYear
        val rateDisplay = if (blended != null)
            "%.3f%% (%.3f%%)".format(blended, currencyRates.baseRate)
        else
            "%.3f%%".format(currencyRates.baseRate)
        val tiersJson = currencyRates.tiers.joinToString(",", "[", "]") { t ->
            if (t.upTo != null) "{\"upTo\":${t.upTo},\"rate\":${t.rate}}"
            else "{\"upTo\":null,\"rate\":${t.rate}}"
        }
        RateRow(ccy, rateDisplay, nativeDailyInterest, effectiveRate, daysInYear, tiersJson)
    }

    if (rows.isEmpty()) {
        div(classes = "ibkr-rates-wrapper") {
            div(classes = "ibkr-rates-footer") {
                span(classes = "ibkr-last-fetch") { +"—" }
                button(classes = "ibkr-reload-btn") {
                    id = "ibkr-reload-btn"
                    attributes["type"] = "button"
                    attributes["data-last-fetch"] = "0"
                    attributes["title"] = "Reload IBKR margin rates"
                    +"↻"
                }
            }
        }
        return
    }

    val lastFetchMillis = IbkrMarginRateService.getLastFetchMillis()

    // Pre-compute summary values for initial server-side render
    val currentInterestUsd = rows.sumOf { row ->
        val fxRate = if (row.currency == "USD") 1.0 else (fxRateMap[row.currency] ?: 0.0)
        row.nativeDailyInterest * fxRate
    }
    val cheapestRow =
        rows.minByOrNull { totalMarginLoanUsd * it.effectiveRate / 100.0 / it.daysInYear }
    val cheapestInterestUsd =
        cheapestRow?.let { totalMarginLoanUsd * it.effectiveRate / 100.0 / it.daysInYear }
    val interestDiff = if (cheapestInterestUsd != null && currentInterestUsd > 0)
        currentInterestUsd - cheapestInterestUsd else null

    div(classes = "ibkr-rates-wrapper") {
        table(classes = "ibkr-rates-table") {
            thead {
                tr {
                    th { +"CCY" }
                    th { +"IBKR Pro Rate" }
                }
            }
            tbody {
                for (row in rows) {
                    tr {
                        attributes["data-ibkr-rate"] = "%.8f".format(row.effectiveRate)
                        attributes["data-ibkr-days"] = row.daysInYear.toString()
                        attributes["data-native-daily"] = "%.8f".format(row.nativeDailyInterest)
                        attributes["data-ibkr-tiers"] = row.tiersJson
                        td(classes = "ibkr-rate-currency") { +row.currency }
                        td(classes = "ibkr-rate-value") { +row.rateDisplay }
                    }
                }
            }
        }
        table(classes = "ibkr-interest-summary") {
            tbody {
                tr {
                    td { +"Current Daily Interest" }
                    td {
                        id = "ibkr-current-interest"
                        classes = setOf("ibkr-value-muted")
                        if (currentInterestUsd > 0) +"$%,.2f".format(currentInterestUsd) else +"—"
                    }
                }
                tr {
                    td {
                        +"Cheapest "
                        span {
                            id =
                                "ibkr-cheapest-ccy"; if (cheapestRow != null) +"(${cheapestRow.currency})"
                        }
                    }
                    td {
                        id = "ibkr-cheapest-interest"
                        classes = setOf("ibkr-value-muted")
                        if (cheapestInterestUsd != null) +"$%,.2f".format(cheapestInterestUsd) else +"—"
                    }
                }
                tr {
                    if (interestDiff != null && interestDiff >= 0.005) {
                        val action = if (cheapestRow!=null && rows.size == 2) {
                            if (cheapestRow.currency == "USD") {
                                val ccy =
                                    rows.first { it.currency != "USD" }.currency
                                " (Sell USD.$ccy)"
                            } else {
                                " (Buy USD.${cheapestRow.currency})"
                            }
                        } else {
                            ""
                        }
                        td {
                            id= "ibkr-saving-label"
                            +"Saving$action"
                        }
                        td {
                            id = "ibkr-interest-diff"
                            classes = setOf("ibkr-rate-diff")
                            +"$%,.2f".format(interestDiff)
                        }
                    } else {
                        td {
                            id= "ibkr-saving-label"
                            +"Saving"
                        }
                        td {
                            id = "ibkr-interest-diff"
                            +"—"
                        }
                    }
                }
            }
        }
        div(classes = "ibkr-rates-footer") {
            span(classes = "ibkr-last-fetch") {
                id = "ibkr-last-fetch"
                if (lastFetchMillis > 0L) {
                    val time = java.time.Instant.ofEpochMilli(lastFetchMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                    +java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(time)
                } else {
                    +"—"
                }
            }
            button(classes = "ibkr-reload-btn") {
                id = "ibkr-reload-btn"
                attributes["type"] = "button"
                attributes["data-last-fetch"] = lastFetchMillis.toString()
                attributes["title"] = "Reload IBKR margin rates"
                +"↻"
            }
        }
    } // end ibkr-rates-wrapper
}
