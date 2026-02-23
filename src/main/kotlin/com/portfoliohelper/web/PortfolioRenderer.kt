package com.portfoliohelper.web

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.service.CurrencyConventions
import com.portfoliohelper.service.IbkrMarginRateService
import com.portfoliohelper.service.ManagedPortfolio
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*
import kotlin.math.abs

private fun formatQty(amount: Double) =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

internal suspend fun ApplicationCall.renderPortfolioPage(
    entry: ManagedPortfolio,
    allPortfolios: Collection<ManagedPortfolio>,
    activePortfolioId: String
) {
    val portfolio = YahooMarketDataService.getCurrentPortfolio(entry.getStocks())
    val cashEntries = entry.getCash()

    // Build FX rate map for non-USD currencies from cached Yahoo quotes (e.g. HKDUSD=X → 0.1286)
    val fxRateMap: Map<String, Double> = cashEntries
        .map { it.currency }.distinct()
        .filter { it != "USD" && it != "P" }
        .mapNotNull { ccy ->
            val rate = YahooMarketDataService.getQuote("${ccy}USD=X")?.regularMarketPrice
            if (rate != null) ccy to rate else null
        }
        .toMap()

    // Helper to resolve a cash entry to its USD value (handles regular, P-reference, and FX entries)
    fun resolveEntryUsd(e: CashEntry): Double? {
        return when (e.currency) {
            "USD" -> e.amount
            "P" -> {
                val ref = com.portfoliohelper.service.PortfolioRegistry.get(e.portfolioRef ?: return null) ?: return null
                e.amount * YahooMarketDataService.getCurrentPortfolio(ref.getStocks()).totalValue
            }
            else -> fxRateMap[e.currency]?.let { e.amount * it }
        }
    }

    // Compute totals and display currencies at function level so they can be used in both head and body
    val cashTotalUsd = cashEntries.sumOf { ce -> resolveEntryUsd(ce) ?: 0.0 }
    val displayCurrencies: List<String> = buildList {
        add("USD")
        cashEntries.map { it.currency.uppercase() }
            .distinct().filter { it != "P" && it != "USD" }
            .sorted().forEach { add(it) }
    }

    respondHtml(HttpStatusCode.OK) {
        head {
            title { +"Stock Portfolio Viewer" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

            // Inline script to prevent flash of wrong theme
            script {
                unsafe {
                    raw(
                        """
                        (function(){
                            const t=localStorage.getItem('ib-viewer-theme')||
                                    (window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
                            document.documentElement.setAttribute('data-theme',t);
                        })();
                    """.trimIndent()
                    )
                }
            }

            link(rel = "stylesheet", href = "/static/styles.css")

            // Inline data block: server-rendered dynamic values consumed by portfolio.js
            script {
                unsafe {
                    raw(
                        """
                        const portfolioId = "${entry.id}";
                        const fxRates = ${
                            buildString {
                                append("{ USD: 1.0")
                                fxRateMap.forEach { (ccy, rate) -> append(", $ccy: $rate") }
                                append(" }")
                            }
                        };
                        const displayCurrencies = ${
                            buildString {
                                append("[")
                                displayCurrencies.joinTo(this, ",") { "\"$it\"" }
                                append("]")
                            }
                        };
                        let lastMarginUsd = 0;
                        let lastEquityUsd = 0;
                        let lastPortfolioVal = ${"%.2f".format(portfolio.totalValue)};
                        let lastPrevPortfolioVal = ${"%.2f".format(portfolio.previousTotalValue)};
                        let lastPortfolioDayChangeUsd = ${"%.2f".format(portfolio.dailyChangeDollars)};
                        let lastCashTotalUsd = ${"%.2f".format(cashTotalUsd)};
                        """.trimIndent()
                    )
                }
            }

            script {
                src = "/static/portfolio.js"
                defer = true
            }

            script {
                src = "/static/theme-switcher.js"
                defer = true
            }
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        h1 { +(if (allPortfolios.size > 1) entry.name else "Stock Portfolio") }
                        span(classes = "header-timestamp") {
                            id = "last-update-time"
                            val now = java.time.LocalTime.now()
                            +java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(now)
                        }
                    }

                    div(classes = "header-buttons") {
                        a(href = "/loan", classes = "loan-calc-link") { +"Loan Calc" }

                        button(classes = "edit-toggle") {
                            attributes["aria-label"] = "Toggle edit mode"
                            attributes["id"] = "edit-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Edit Qty and Target Weight"
                            span(classes = "toggle-label") { +"Edit" }
                        }

                        button(classes = "save-btn") {
                            attributes["id"] = "save-btn"
                            attributes["type"] = "button"
                            attributes["title"] = "Save changes to CSV"
                            span(classes = "toggle-label") { +"Save" }
                        }

                        button(classes = "rebal-toggle") {
                            attributes["aria-label"] = "Toggle rebalancing columns"
                            attributes["id"] = "rebal-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Show/Hide Weight and Rebalancing columns"
                            span(classes = "toggle-label") { +"Rebalancing" }
                        }

                        if (displayCurrencies.size > 1) {
                            if (displayCurrencies.size <= 3) {
                                button(classes = "currency-toggle") {
                                    attributes["id"] = "currency-toggle"
                                    attributes["type"] = "button"
                                    attributes["data-currencies"] = displayCurrencies.joinToString(",")
                                    attributes["title"] = "Switch display currency"
                                    span(classes = "toggle-label") { +"USD" }
                                }
                            } else {
                                select(classes = "currency-select") {
                                    attributes["id"] = "currency-select"
                                    for (ccy in displayCurrencies) {
                                        option {
                                            attributes["value"] = ccy
                                            +ccy
                                        }
                                    }
                                }
                            }
                        }

                        button(classes = "theme-toggle") {
                            attributes["aria-label"] = "Toggle theme"
                            attributes["id"] = "theme-toggle"
                            attributes["type"] = "button"

                            // Sun icon (shown in dark mode)
                            span(classes = "icon-sun") {
                                unsafe {
                                    raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>""")
                                }
                            }

                            // Moon icon (shown in light mode)
                            span(classes = "icon-moon") {
                                unsafe {
                                    raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>""")
                                }
                            }
                        }
                    }
                }

                // Tab bar — only shown when multiple portfolios exist
                if (allPortfolios.size > 1) {
                    div(classes = "portfolio-tabs") {
                        for (p in allPortfolios) {
                            val href = if (p.id == "main") "/" else "/portfolio/${p.id}"
                            a(href = href, classes = "tab-link${if (p.id == activePortfolioId) " active" else ""}") {
                                +p.name
                            }
                        }
                    }
                }

                div(classes = "portfolio-tables-wrapper") {
                    div(classes = "summary-and-rates") {
                        table(classes = "summary-table") {
                            tbody {
                                buildSummaryRows(cashEntries, ::resolveEntryUsd, portfolio, cashTotalUsd)
                            }
                        }

                        buildCashEditTable(cashEntries.sortedBy { it.label.lowercase() })

                        if (cashEntries.any { it.marginFlag }) {
                            buildIbkrRatesTable(cashEntries, ::resolveEntryUsd, fxRateMap)
                        }
                    }

                    buildStockTable(portfolio)

                    div(classes = "edit-add-buttons") {
                        button(classes = "add-stock-btn") {
                            attributes["type"] = "button"
                            id = "add-stock-btn"
                            +"+ Add Stock"
                        }
                    }
                }

                if (portfolio.stocks.isNotEmpty()) {
                    p(classes = "info") {
                        +"Showing ${portfolio.stocks.size} stock(s)"
                    }
                }
            }
        }
    }
}

private fun TBODY.buildSummaryRows(
    cashEntries: List<CashEntry>,
    resolveEntryUsd: (CashEntry) -> Double?,
    portfolio: Portfolio,
    cashTotalUsd: Double
) {
    // Total Value row — at the top of the summary table
    val grandTotalDaySign = if (portfolio.dailyChangeDollars >= 0) "+" else "-"
    val grandTotalPrevTotal = portfolio.previousTotalValue + cashTotalUsd
    val grandTotalDayChangePercent = if (grandTotalPrevTotal != 0.0) {
        (portfolio.dailyChangeDollars / abs(grandTotalPrevTotal)) * 100.0
    } else 0.0
    tr(classes = "grand-total-row") {
        td { +"Total Value" }
        td {}
        td {
            span {
                id = "grand-total-value"
                +"${'$'}%,.2f".format(portfolio.totalValue + cashTotalUsd)
            }
            div(classes = "summary-subvalue") {
                id = "total-day-change"
                span(classes = "change-dollars ${portfolio.dailyChangeDirection}") {
                    +"$grandTotalDaySign${'$'}%,.2f".format(abs(portfolio.dailyChangeDollars))
                }
                +" "
                span(classes = "change-percent ${portfolio.dailyChangeDirection}") {
                    +"($grandTotalDaySign%.2f%%)".format(abs(grandTotalDayChangePercent))
                }
            }
        }
    }

    // Section break after Total Value
    if (cashEntries.isNotEmpty()) {
        tr(classes = "summary-section-break") {
            td { attributes["colspan"] = "3" }
        }
    }

    // Cash entry rows — sorted by label, duplicate labels suppressed
    val sortedCashEntries = cashEntries.sortedBy { it.label.lowercase() }
    var prevLabel: String? = null
    for (entry in sortedCashEntries) {
        val displayLabel = if (entry.label == prevLabel) "" else entry.label
        prevLabel = entry.label
        tr {
            attributes["data-cash-entry"] = "true"
            // P entries: JS treats them as USD (rate=1.0) with pre-resolved amount
            attributes["data-currency"] = if (entry.portfolioRef != null) "USD" else entry.currency
            attributes["data-amount"] = if (entry.portfolioRef != null) {
                resolveEntryUsd(entry)?.toString() ?: "0"
            } else {
                entry.amount.toString()
            }
            attributes["data-entry-id"] = "${entry.label}-${entry.currency}"
            attributes["data-margin-flag"] = entry.marginFlag.toString()
            attributes["data-equity-flag"] = entry.equityFlag.toString()

            td { +displayLabel }
            td(classes = "cash-raw-col") {
                if (entry.portfolioRef != null) {
                    val resolvedUsd = resolveEntryUsd(entry)
                    if (resolvedUsd != null) +"${"%,.2f".format(resolvedUsd)} USD" else +"--- USD"
                } else {
                    +"${"%,.2f".format(entry.amount)} ${entry.currency}"
                }
            }
            td {
                span {
                    id = "cash-usd-${entry.label}-${entry.currency}"
                    val resolvedUsd = resolveEntryUsd(entry)
                    if (resolvedUsd != null) {
                        +"${'$'}%,.2f".format(resolvedUsd)
                    } else {
                        +"---"
                    }
                }
            }
        }
    }

    // Divider + Total Cash (only shown when cash entries exist)
    if (cashEntries.isNotEmpty()) {
        tr(classes = "summary-divider") {
            td { attributes["colspan"] = "3" }
        }
        tr(classes = "total-cash-row") {
            td { +"Total Cash" }
            td {}
            td {
                span {
                    id = "cash-total-usd"
                    +"${'$'}%,.2f".format(cashTotalUsd)
                }
            }
        }

        // Margin row (only when M-flagged entries exist)
        val hasMarginEntries = cashEntries.any { it.marginFlag }
        if (hasMarginEntries) {
            val marginUsd = cashEntries.filter { it.marginFlag }
                .sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
            val equityEntriesUsd = cashEntries.filter { it.equityFlag }
                .sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
            val marginDenominator = portfolio.totalValue + equityEntriesUsd + marginUsd
            tr(classes = "margin-row") {
                attributes["data-margin-row"] = "true"
                if (marginUsd >= 0) style = "display:none;"
                td { +"Margin" }
                td {}
                td {
                    span {
                        id = "margin-total-usd"
                        +"${'$'}%,.2f".format(abs(marginUsd))
                    }
                    val marginPct =
                        if (marginDenominator != 0.0 && marginUsd < 0)
                            (marginUsd / marginDenominator) * 100.0 else 0.0
                    span(classes = "margin-percent") {
                        id = "margin-percent"
                        if (marginUsd >= 0) {
                            style = "display:none;"
                        } else {
                            +" (${"%.1f%%".format(abs(marginPct))})"
                        }
                    }
                }
            }
        }

        // Section break + Portfolio Value row
        tr(classes = "summary-section-break") {
            td { attributes["colspan"] = "3" }
        }

        val daySign = if (portfolio.dailyChangeDollars >= 0) "+" else "-"
        tr {
            td { +"Portfolio Value" }
            td {}
            td {
                div {
                    id = "portfolio-total"
                    +"${'$'}%,.2f".format(portfolio.totalValue)
                }
                div(classes = "summary-subvalue") {
                    id = "portfolio-day-change"
                    span(classes = "change-dollars ${portfolio.dailyChangeDirection}") {
                        +"$daySign${'$'}%,.2f".format(abs(portfolio.dailyChangeDollars))
                    }
                    +" "
                    span(classes = "change-percent ${portfolio.dailyChangeDirection}") {
                        +"($daySign%.2f%%)".format(abs(portfolio.dailyChangePercent))
                    }
                }
            }
        }
    }

    // Rebalance Target input row — always shown
    tr(classes = "rebal-target-row") {
        td { +"Rebalance Target" }
        td {}
        td {
            input(type = InputType.text, classes = "rebal-target-input") {
                attributes["id"] = "rebal-target-input"
                attributes["placeholder"] = "%,.2f".format(portfolio.totalValue)
                attributes["autocomplete"] = "off"
            }
        }
    }

    // Margin Target row — only rendered when margin entries are configured
    val hasMarginForTarget = cashEntries.any { it.marginFlag }
    if (hasMarginForTarget) {
        val mUsd = cashEntries.filter { it.marginFlag }.sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
        val eUsd = cashEntries.filter { it.equityFlag }.sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
        val mDenom = portfolio.totalValue + eUsd + mUsd
        val mPct = if (mDenom != 0.0 && mUsd < 0) (mUsd / mDenom) * 100.0 else 0.0
        tr(classes = "margin-row") {
            attributes["id"] = "margin-target-row"
            if (mUsd >= 0) style = "display:none;"
            td { +"Margin Target" }
            td {}
            td {
                span {
                    id = "margin-target-usd"
                    +"${'$'}%,.2f".format(abs(mUsd))
                }
                span(classes = "margin-percent") {
                    id = "margin-target-percent"
                    if (mUsd >= 0) {
                        style = "display:none;"
                    } else {
                        +" (${"%.1f%%".format(abs(mPct))})"
                    }
                }
            }
        }
    }
}

private fun FlowContent.buildIbkrRatesTable(
    cashEntries: List<CashEntry>,
    resolveEntryUsd: (CashEntry) -> Double?,
    fxRateMap: Map<String, Double>
) {
    // Always show USD; add non-USD, non-P margin currencies sorted alphabetically
    val marginCurrencies: List<String> = buildList {
        add("USD")
        cashEntries.filter { it.marginFlag }
            .map { it.currency.uppercase() }
            .filter { it != "USD" && it != "P" }
            .distinct().sorted()
            .forEach { add(it) }
    }

    // Net margin in USD — negative means the user is actually borrowing
    val netMarginUsd = cashEntries
        .filter { it.marginFlag }
        .sumOf { resolveEntryUsd(it) ?: 0.0 }
    val totalMarginLoanUsd = if (netMarginUsd < 0) -netMarginUsd else 0.0

    data class RateRow(val currency: String, val rateDisplay: String, val dailyInterestUsd: Double?)
    val rows = marginCurrencies.mapNotNull { ccy ->
        val currencyRates = IbkrMarginRateService.getRates(ccy) ?: return@mapNotNull null
        val fxRate = if (ccy == "USD") 1.0 else (fxRateMap[ccy] ?: return@mapNotNull null)
        val loanAmount = totalMarginLoanUsd / fxRate
        val blended = if (loanAmount > 0) currencyRates.blendedRateIfMultiTier(loanAmount) else null
        val effectiveRate = blended ?: currencyRates.baseRate
        val daysInYear = CurrencyConventions.getDaysInYear(ccy)
        val dailyInterestUsd = if (totalMarginLoanUsd > 0)
            totalMarginLoanUsd * effectiveRate / 100.0 / daysInYear
        else null
        val rateDisplay = if (blended != null)
            "%.3f%% (%.3f%%)".format(blended, currencyRates.baseRate)
        else
            "%.3f%%".format(currencyRates.baseRate)
        RateRow(ccy, rateDisplay, dailyInterestUsd)
    }

    if (rows.isEmpty()) return

    val minDailyInterest = rows.mapNotNull { it.dailyInterestUsd }.minOrNull()
    val lastFetchMillis = IbkrMarginRateService.getLastFetchMillis()

    div(classes = "ibkr-rates-wrapper") {
    table(classes = "ibkr-rates-table") {
        thead {
            tr {
                th { +"CCY" }
                th { +"IBKR Pro Rate" }
                th { +"Daily Interest (USD)" }
            }
        }
        tbody {
            for (row in rows) {
                tr {
                    td(classes = "ibkr-rate-currency") { +row.currency }
                    td(classes = "ibkr-rate-value") { +row.rateDisplay }
                    td(classes = "ibkr-rate-daily") {
                        val interest = row.dailyInterestUsd
                        if (interest == null) {
                            +"—"
                        } else {
                            +"${"$"}%,.2f".format(interest)
                            val diff = interest - (minDailyInterest ?: 0.0)
                            if (diff >= 0.005) {
                                span(classes = "ibkr-rate-diff") {
                                    +" (+${"$"}%,.2f)".format(diff)
                                }
                            }
                        }
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

private fun FlowContent.buildCashEditTable(sortedEntries: List<CashEntry>) {
    div(classes = "cash-edit-table-wrapper") {
        table(classes = "cash-edit-table") {
            tbody {
                for (entry in sortedEntries) {
                    val valueStr = if (entry.portfolioRef != null) {
                        if (entry.amount < 0) "-${entry.portfolioRef}" else entry.portfolioRef
                    } else {
                        entry.amount.toString()
                    }
                    tr {
                        attributes["data-cash-edit-row"] = "true"
                        td {
                            input(type = InputType.text, classes = "edit-input cash-edit-key") {
                                attributes["data-original-key"] = entry.key
                                attributes["data-column"] = "cash-key"
                                value = entry.key
                            }
                        }
                        td {
                            input(type = InputType.text, classes = "edit-input cash-edit-value") {
                                attributes["data-original-value"] = valueStr
                                attributes["data-column"] = "cash-value"
                                value = valueStr
                            }
                        }
                        td {
                            button(classes = "delete-cash-btn") {
                                attributes["type"] = "button"
                                +"×"
                            }
                        }
                    }
                }
            }
        }
        button(classes = "add-cash-btn") {
            attributes["type"] = "button"
            id = "add-cash-btn"
            +"+ Add Entry"
        }
    }
}

private fun FlowContent.buildStockTable(portfolio: Portfolio) {
    table(classes = "portfolio-table") {
        thead {
            tr {
                th {
                    +"Symbol"
                    button(classes = "copy-col-btn") {
                        attributes["data-column"] = "symbol"
                        attributes["type"] = "button"
                        attributes["title"] = "Copy Symbol column to clipboard"
                        unsafe { raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>""") }
                    }
                }
                th {
                    +"Qty"
                    button(classes = "copy-col-btn") {
                        attributes["data-column"] = "qty"
                        attributes["type"] = "button"
                        attributes["title"] = "Copy Qty column to clipboard"
                        unsafe { raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>""") }
                    }
                }
                th { +"Last NAV" }
                th { +"Est Val" }
                th { +"Last" }
                th { +"Mark" }
                th { +"Day Chg" }
                th { +"Day %" }
                th { +"Mkt Val" }
                th { +"Mkt Val Chg" }
                th(classes = "rebal-column") { +"Weight" }
                th(classes = "rebal-column") { +"Rebal $" }
                th(classes = "rebal-column") { +"Rebal Shares" }
                th(classes = "edit-column") {
                    +"Target %"
                    button(classes = "copy-col-btn") {
                        attributes["data-column"] = "weight"
                        attributes["type"] = "button"
                        attributes["title"] = "Copy Target % column to clipboard"
                        unsafe { raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>""") }
                    }
                }
                th(classes = "edit-column") { +"Letf" }
                th(classes = "edit-column") { }
            }
        }
        tbody {
            for (stock in portfolio.stocks) {
                tr {
                    if (stock.letfComponents != null) {
                        attributes["data-letf"] =
                            stock.letfComponents.joinToString(",") { "${it.first},${it.second}" }
                    }

                    // Symbol (editable in edit mode)
                    td {
                        span(classes = "display-value") { +stock.label }
                        input(type = InputType.text, classes = "edit-input edit-symbol") {
                            attributes["data-original-symbol"] = stock.label
                            attributes["data-column"] = "symbol"
                            value = stock.label
                        }
                    }

                    // Qty (Amount)
                    td(classes = "amount") {
                        id = "amount-${stock.label}"
                        span(classes = "display-value") { +formatQty(stock.amount) }
                        input(type = InputType.number, classes = "edit-input edit-qty") {
                            attributes["data-symbol"] = stock.label
                            attributes["data-column"] = "qty"
                            value = formatQty(stock.amount)
                            attributes["min"] = "0"
                            attributes["step"] = "any"
                        }
                    }

                    // Last NAV
                    td(classes = if (stock.lastNav != null) "price loaded" else "price") {
                        id = "nav-${stock.label}"
                        if (stock.lastNav != null) {
                            +"${'$'}%.2f".format(stock.lastNav)
                        } else {
                            +"—"
                        }
                    }

                    // Est Val (Estimated Value from LETF components)
                    val estValText: String? =
                        if (stock.letfComponents != null) {
                            val basePrice = stock.lastNav ?: stock.lastClosePrice
                            if (basePrice != null) {
                                val anyCompQuote = stock.letfComponents
                                    .mapNotNull { (_, sym) -> YahooMarketDataService.getQuote(sym) }
                                    .firstOrNull()
                                val stale =
                                    anyCompQuote?.isMarketClosed == true &&
                                            anyCompQuote.tradingPeriodEnd != null &&
                                            System.currentTimeMillis() / 1000 - anyCompQuote.tradingPeriodEnd > 12 * 3600

                                if (stale) null
                                else {
                                    val sumComponent = stock.letfComponents.sumOf { (mult, sym) ->
                                        val quote = YahooMarketDataService.getQuote(sym)
                                        if (quote?.regularMarketPrice != null && quote.previousClose != null && quote.previousClose != 0.0) {
                                            mult * ((quote.regularMarketPrice - quote.previousClose) / quote.previousClose) * 100.0
                                        } else 0.0
                                    }
                                    "${'$'}%.2f".format((1.0 + sumComponent / 100.0) * basePrice)
                                }
                            } else null
                        } else null

                    td(classes = if (estValText != null) "price loaded" else "price") {
                        id = "est-val-${stock.label}"
                        +(estValText ?: "—")
                    }

                    // Last Close Price
                    td(classes = if (stock.lastClosePrice != null) "price loaded" else "price") {
                        id = "close-${stock.label}"
                        if (stock.lastClosePrice != null) {
                            +"${'$'}%.2f".format(stock.lastClosePrice)
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Mark Price
                    td(classes = if (stock.markPrice != null) "price loaded" else "price") {
                        id = "mark-${stock.label}"
                        if (stock.markPrice != null) {
                            +"${'$'}%.2f".format(stock.markPrice)
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Day Chg ($ change)
                    val isZeroChange = stock.priceChangeDollars?.let { abs(it) < 0.001 } ?: false
                    val changeDirection = if (isZeroChange) "neutral" else stock.priceChangeDirection
                    val afterHoursClass = if (stock.isMarketClosed) "after-hours" else ""

                    td(classes = "price-change $changeDirection $afterHoursClass") {
                        id = "day-change-${stock.label}"
                        if (stock.priceChangeDollars != null) {
                            if (isZeroChange) {
                                +"—"
                            } else {
                                val sign = if (stock.priceChangeDollars!! >= 0) "+" else "-"
                                +"$sign${'$'}%.2f".format(abs(stock.priceChangeDollars!!))
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Day % (% change)
                    td(classes = "price-change $changeDirection $afterHoursClass") {
                        id = "day-percent-${stock.label}"
                        if (stock.priceChangePercent != null) {
                            if (isZeroChange) {
                                +"—"
                            } else {
                                val sign = if (stock.priceChangePercent!! >= 0) "+" else "-"
                                +"$sign%.2f%%".format(abs(stock.priceChangePercent!!))
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Mkt Val (Total Value)
                    td(classes = if (stock.value != null) "value loaded" else "value") {
                        id = "value-${stock.label}"
                        if (stock.value != null) {
                            +"${'$'}%,.2f".format(stock.value)
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Mkt Val Chg (Position value change)
                    td(classes = "price-change $changeDirection $afterHoursClass") {
                        id = "position-change-${stock.label}"
                        if (stock.positionChangeDollars != null) {
                            if (isZeroChange) {
                                +"—"
                            } else {
                                val sign = if (stock.positionChangeDollars!! >= 0) "+" else "-"
                                +"$sign${'$'}%,.2f".format(abs(stock.positionChangeDollars!!))
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Weight (Current vs Target)
                    td(classes = "weight-display rebal-column") {
                        id = "current-weight-${stock.label}"
                        val stockValue = stock.value
                        if (stockValue != null && portfolio.totalValue > 0) {
                            val currentWeight = (stockValue / portfolio.totalValue) * 100

                            if (stock.targetWeight != null) {
                                val diff = currentWeight - stock.targetWeight
                                val sign = if (diff >= 0) "+" else "-"
                                val diffClass = when {
                                    abs(diff) > 2.0 -> "alert"
                                    abs(diff) > 1.0 -> "warning"
                                    else -> "good"
                                }
                                +"%.1f%% ".format(currentWeight)
                                span(classes = "weight-diff $diffClass") {
                                    +"($sign%.1f%%)".format(abs(diff))
                                }
                                span(classes = "target-weight-hidden") {
                                    style = "display:none;"
                                    +stock.targetWeight.toString()
                                }
                            } else {
                                +"%.1f%%".format(currentWeight)
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Rebalance $ (dollar amount to add/reduce)
                    td(classes = "price-change ${stock.rebalanceDirection(portfolio.totalValue)} rebal-column") {
                        id = "rebal-dollars-${stock.label}"
                        if (stock.targetWeight != null) {
                            val rebalDollars = stock.rebalanceDollars(portfolio.totalValue)
                            if (rebalDollars != null) {
                                val sign = if (rebalDollars >= 0) "+" else "-"
                                +"$sign${'$'}%,.2f".format(abs(rebalDollars))
                            } else {
                                span(classes = "loading") { +"—" }
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Rebalance Shares (number of shares to buy/sell)
                    td(classes = "price-change ${stock.rebalanceDirection(portfolio.totalValue)} rebal-column") {
                        id = "rebal-shares-${stock.label}"
                        if (stock.targetWeight != null) {
                            val rebalShares = stock.rebalanceShares(portfolio.totalValue)
                            if (rebalShares != null) {
                                val sign = if (rebalShares >= 0) "+" else "-"
                                +"$sign%.2f".format(abs(rebalShares))
                            } else {
                                span(classes = "loading") { +"—" }
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Target % (edit-only column)
                    td(classes = "edit-column") {
                        input(type = InputType.number, classes = "edit-input edit-weight") {
                            attributes["data-symbol"] = stock.label
                            attributes["data-column"] = "weight"
                            value = (stock.targetWeight ?: 0.0).toString()
                            attributes["min"] = "0"
                            attributes["max"] = "100"
                            attributes["step"] = "0.1"
                        }
                    }

                    // Letf (edit-only column)
                    val letfRaw = stock.letfComponents
                        ?.joinToString(" ") { (mult, sym) ->
                            "${if (mult == mult.toLong().toDouble()) mult.toLong() else mult} $sym"
                        } ?: ""
                    td(classes = "edit-column") {
                        input(type = InputType.text, classes = "edit-input edit-letf") {
                            attributes["data-symbol"] = stock.label
                            attributes["data-column"] = "letf"
                            value = letfRaw
                            style = "text-align:left;width:120px"
                        }
                    }

                    // Delete (edit-only column)
                    td(classes = "edit-column") {
                        button(classes = "delete-row-btn") {
                            attributes["type"] = "button"
                            +"×"
                        }
                    }
                }
            }
        }
    }
}
