package com.portfoliohelper.web

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.ManagedPortfolio
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*
import kotlin.math.abs

private const val COPY_ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>"""

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
                val ref =
                    com.portfoliohelper.service.PortfolioRegistry.get(e.portfolioRef ?: return null)
                        ?: return null
                e.amount * YahooMarketDataService.getCurrentPortfolio(ref.getStocks()).totalValue
            }

            else -> fxRateMap[e.currency]?.let { e.amount * it }
        }
    }

    // Load per-portfolio config (portfolio.conf); fall back to legacy rebal-target.txt
    val portfolioConf: Map<String, String> = runCatching {
        val f = java.io.File(entry.csvPath).resolveSibling("portfolio.conf")
        if (f.exists()) f.readLines()
            .filter { '=' in it && !it.startsWith('#') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        else emptyMap()
    }.getOrDefault(emptyMap())
    val savedRebalTargetUsd = portfolioConf["rebalTarget"]?.toDoubleOrNull()
        ?: runCatching {
            val legacy = java.io.File(entry.csvPath).resolveSibling("rebal-target.txt")
            if (legacy.exists()) legacy.readText().trim().toDoubleOrNull() else null
        }.getOrNull() ?: 0.0
    val savedMarginTargetPct = portfolioConf["marginTarget"]?.toDoubleOrNull() ?: 0.0
    val savedAllocAddMode = portfolioConf["allocAddMode"] ?: "PROPORTIONAL"
    val savedAllocReduceMode = portfolioConf["allocReduceMode"] ?: "PROPORTIONAL"

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

            renderCommonHeadElements()

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
                        let lastPortfolioVal =${"%.2f".format(portfolio.totalValue)};
                        let lastPrevPortfolioVal = ${"%.2f".format(portfolio.previousTotalValue)};
                        let lastPortfolioDayChangeUsd = ${"%.2f".format(portfolio.dailyChangeDollars)};
                        let lastCashTotalUsd = ${"%.2f".format(cashTotalUsd)};
                        let savedRebalTargetUsd = ${"%.2f".format(savedRebalTargetUsd)};
                        let savedMarginTargetPct = ${"%.4f".format(savedMarginTargetPct)};
                        let savedAllocAddMode = "${savedAllocAddMode}";
                        let savedAllocReduceMode = "${savedAllocReduceMode}";
                        """.trimIndent()
                    )
                }
            }

            script {
                src = "/static/portfolio.js"
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
                        a(href = "/backtest", classes = "loan-calc-link") { +"Backtester" }
                        a(href = "/loan", classes = "loan-calc-link") { +"Loan Calc" }

                        button(classes = "restore-backup-btn") {
                            attributes["id"] = "restore-backup-btn"
                            attributes["type"] = "button"
                            attributes["title"] = "Restore from a previous backup"
                            span(classes = "toggle-label") { +"Restore" }
                        }

                        button(classes = "save-btn") {
                            attributes["id"] = "save-btn"
                            attributes["type"] = "button"
                            attributes["title"] = "Save changes to CSV"
                            span(classes = "toggle-label") { +"Save" }
                        }

                        button(classes = "edit-toggle") {
                            attributes["aria-label"] = "Toggle edit mode"
                            attributes["id"] = "edit-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Edit Qty and Target Weight"
                            span(classes = "toggle-label") { +"Edit" }
                        }

                        button(classes = "virtual-rebal-btn") {
                            attributes["id"] = "virtual-rebal-btn"
                            attributes["type"] = "button"
                            attributes["title"] =
                                "Apply rebalancing quantities to the portfolio (virtual — requires Save to persist)"
                            span(classes = "toggle-label") { +"Virtual Rebalance" }
                        }

                        button(classes = "rebal-toggle") {
                            attributes["aria-label"] = "Toggle rebalancing columns"
                            attributes["id"] = "rebal-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Show/Hide Weight and Rebalancing columns"
                            span(classes = "toggle-label") { +"Rebal" }
                        }

                        if (displayCurrencies.size > 1) {
                            if (displayCurrencies.size <= 3) {
                                button(classes = "currency-toggle") {
                                    attributes["id"] = "currency-toggle"
                                    attributes["type"] = "button"
                                    attributes["data-currencies"] =
                                        displayCurrencies.joinToString(",")
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

                        renderThemeToggle()
                    }
                }

                // Tab bar — only shown when multiple portfolios exist
                if (allPortfolios.size > 1) {
                    div(classes = "portfolio-tabs") {
                        for (p in allPortfolios) {
                            val href = if (p.id == "main") "/" else "/portfolio/${p.id}"
                            a(
                                href = href,
                                classes = "tab-link${if (p.id == activePortfolioId) " active" else ""}"
                            ) {
                                +p.name
                            }
                        }
                    }
                }

                div(classes = "portfolio-tables-wrapper") {
                    div(classes = "summary-and-rates") {
                        table(classes = "summary-table") {
                            tbody {
                                buildSummaryRows(
                                    cashEntries,
                                    ::resolveEntryUsd,
                                    portfolio,
                                    cashTotalUsd
                                )
                            }
                        }

                        buildCashEditTable(cashEntries.sortedBy { it.label.lowercase() })

                        if (cashEntries.any { it.marginFlag }) {
                            buildIbkrRatesTable(cashEntries, ::resolveEntryUsd, fxRateMap)
                        }
                    }

                    div(classes = "stock-section") {
                        div(classes = "alloc-controls") {
                            span(classes = "alloc-controls-label") { +"Alloc" }
                            div(classes = "alloc-mode-group") {
                                label(classes = "alloc-mode-label") {
                                    attributes["for"] = "alloc-add-mode"
                                    +"Add"
                                }
                                select {
                                    id = "alloc-add-mode"
                                    option { value = "PROPORTIONAL"; +"Target Wt" }
                                    option { value = "CURRENT_WEIGHT"; +"Current Wt" }
                                    option { value = "UNDERVALUED_PRIORITY"; +"Underval First" }
                                }
                            }
                            div(classes = "alloc-mode-group") {
                                label(classes = "alloc-mode-label") {
                                    attributes["for"] = "alloc-reduce-mode"
                                    +"Reduce"
                                }
                                select {
                                    id = "alloc-reduce-mode"
                                    option { value = "PROPORTIONAL"; +"Target Wt" }
                                    option { value = "CURRENT_WEIGHT"; +"Current Wt" }
                                    option { value = "UNDERVALUED_PRIORITY"; +"Underval First" }
                                }
                            }
                        }

                        buildStockTable(portfolio)
                    }

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
                +"$%,.2f".format(portfolio.totalValue + cashTotalUsd)
            }
            div(classes = "summary-subvalue") {
                id = "total-day-change"
                span(classes = "change-dollars ${portfolio.dailyChangeDirection}") {
                    +"$grandTotalDaySign$%,.2f".format(abs(portfolio.dailyChangeDollars))
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
            if (entry.portfolioRef != null) {
                attributes["data-portfolio-ref"] = entry.portfolioRef
                attributes["data-portfolio-multiplier"] = entry.amount.toString()
            }

            td { +displayLabel }
            td(classes = "cash-raw-col") {
                if (entry.portfolioRef != null) {
                    val resolvedUsd = resolveEntryUsd(entry)
                    if (resolvedUsd != null) +"${"%,.2f".format(resolvedUsd)} USD" else +"--- USD"
                } else {
                    +"${"%,.2f".format(entry.amount)} ${entry.currency}"
                }
            }
            td(classes = "cash-converted-col") {
                span {
                    id = "cash-usd-${entry.label}-${entry.currency}"
                    val resolvedUsd = resolveEntryUsd(entry)
                    if (resolvedUsd != null) {
                        +"$%,.2f".format(resolvedUsd)
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
                    +"$%,.2f".format(cashTotalUsd)
                }
            }
        }

        // Margin row (only when M-flagged entries exist)
        val hasMarginEntries = cashEntries.any { it.marginFlag }
        if (hasMarginEntries) {
            val marginUsd = cashEntries.filter { it.marginFlag }
                .sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
            val marginDenominator = portfolio.totalValue + marginUsd
            tr(classes = "margin-row") {
                attributes["data-margin-row"] = "true"
                if (marginUsd >= 0) style = "display:none;"
                td { +"Margin" }
                td {}
                td {
                    span {
                        id = "margin-total-usd"
                        +"$%,.2f".format(abs(marginUsd))
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
                    +"$%,.2f".format(portfolio.totalValue)
                }
                div(classes = "summary-subvalue") {
                    id = "portfolio-day-change"
                    span(classes = "change-dollars ${portfolio.dailyChangeDirection}") {
                        +"$daySign$%,.2f".format(abs(portfolio.dailyChangeDollars))
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
        val mDenom = portfolio.totalValue + mUsd
        val mPct = if (mDenom != 0.0 && mUsd < 0) (mUsd / mDenom) * 100.0 else 0.0
        tr(classes = "margin-row") {
            attributes["id"] = "margin-target-row"
            td { +"Margin Target" }
            td {
                span { id = "margin-target-usd" }
            }
            td {
                input(type = InputType.text, classes = "margin-target-input") {
                    attributes["id"] = "margin-target-input"
                    attributes["placeholder"] = "%.1f".format(abs(mPct))
                    attributes["autocomplete"] = "off"
                }
                +" %"
            }
        }
    }
}

private fun FlowContent.buildStockTable(portfolio: Portfolio) {
    table(classes = "portfolio-table") {
        id = "stock-view-table"
        thead {
            tr {
                th { +"Symbol" }
                th(classes = "col-num") { +"Qty" }
                th(classes = "col-num col-market-data") { +"Last NAV" }
                th(classes = "col-num col-market-data") { +"Est Val" }
                th(classes = "col-num col-market-data") { +"Last" }
                th(classes = "col-num col-market-data") { +"Mark" }
                th(classes = "col-num col-market-data") { +"Day Chg" }
                th(classes = "col-num col-market-data") { +"Day %" }
                th(classes = "col-num col-market-data") { +"Mkt Val" }
                th(classes = "col-num col-market-data") { +"Mkt Val Chg" }
                th(classes = "rebal-column") { +"Weight" }
                th(classes = "rebal-column") { +"Rebal $" }
                th(classes = "rebal-column") { +"Rebal Qty" }
                th(classes = "alloc-column") { +"Alloc \$" }
                th(classes = "alloc-column") { +"Alloc Qty" }
            }
        }
        tbody {
            for (stock in portfolio.stocks) {
                val effectiveTarget = stock.targetWeight ?: 0.0
                tr {
                    attributes["data-symbol"] = stock.label
                    attributes["data-qty"] = formatQty(stock.amount)
                    attributes["data-weight"] = effectiveTarget.toString()
                    if (stock.letfComponents != null) {
                        attributes["data-letf"] =
                            stock.letfComponents.joinToString(",") { "${it.first},${it.second}" }
                    }

                    // Symbol
                    td { +stock.label }

                    // Qty (Amount)
                    td(classes = "amount") {
                        id = "amount-${stock.label}"
                        +formatQty(stock.amount)
                    }

                    // Last NAV
                    td(classes = if (stock.lastNav != null) "col-market-data price loaded muted" else "col-market-data price muted") {
                        id = "nav-${stock.label}"
                        if (stock.lastNav != null) {
                            +"$%.2f".format(stock.lastNav)
                        } else {
                            +"—"
                        }
                    }

                    // Est Val (Estimated Value from LETF components)
                    val estValText: String? =
                        estVal(stock)

                    td(classes = if (estValText != null) "col-market-data price loaded" else "col-market-data price") {
                        id = "est-val-${stock.label}"
                        +(estValText ?: "—")
                    }

                    // Last Close Price
                    td(classes = if (stock.lastClosePrice != null) "col-market-data price loaded" else "col-market-data price") {
                        id = "close-${stock.label}"
                        if (stock.lastClosePrice != null) {
                            +"$%.2f".format(stock.lastClosePrice)
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Mark Price
                    td(classes = if (stock.markPrice != null) "col-market-data price loaded" else "col-market-data price") {
                        id = "mark-${stock.label}"
                        if (stock.markPrice != null) {
                            +"$%.2f".format(stock.markPrice)
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Day Chg ($ change)
                    val isZeroChange = stock.priceChangeDollars?.let { abs(it) < 0.001 } ?: false
                    val changeDirection =
                        if (isZeroChange) "neutral" else stock.priceChangeDirection
                    val afterHoursClass = if (stock.isMarketClosed) "after-hours" else ""

                    td(classes = "col-market-data price-change $changeDirection $afterHoursClass") {
                        id = "day-change-${stock.label}"
                        if (stock.priceChangeDollars != null) {
                            if (isZeroChange) {
                                +"—"
                            } else {
                                val sign = if (stock.priceChangeDollars!! >= 0) "+" else "-"
                                +"$sign$%.2f".format(abs(stock.priceChangeDollars!!))
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Day % (% change)
                    td(classes = "col-market-data price-change $changeDirection $afterHoursClass") {
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
                    td(classes = if (stock.value != null) "col-market-data value loaded" else "col-market-data value") {
                        id = "value-${stock.label}"
                        if (stock.value != null) {
                            +"$%,.2f".format(stock.value)
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Mkt Val Chg (Position value change)
                    td(classes = "col-market-data price-change $changeDirection $afterHoursClass") {
                        id = "position-change-${stock.label}"
                        if (stock.positionChangeDollars != null) {
                            if (isZeroChange) {
                                +"—"
                            } else {
                                val sign = if (stock.positionChangeDollars!! >= 0) "+" else "-"
                                +"$sign$%,.2f".format(abs(stock.positionChangeDollars!!))
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

                            val diff = currentWeight - effectiveTarget
                            val sign = if (diff >= 0) "-" else "+"
                            val diffClass = when {
                                abs(diff) > 2.0 -> "alert"
                                abs(diff) > 1.0 -> "warning"
                                else -> "good"
                            }
                            +"%.1f%% ".format(currentWeight)
                            span(classes = "weight-diff $diffClass") {
                                +"($sign%.1f%%)".format(abs(diff))
                            }
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Rebalance $ (dollar amount to add/reduce)
                    td(classes = "price-change ${stock.rebalanceDirection(portfolio.totalValue)} rebal-column") {
                        id = "rebal-dollars-${stock.label}"
                        val rebalDollars = stock.rebalanceDollars(portfolio.totalValue)
                        if (rebalDollars != null) {
                            val sign = if (rebalDollars >= 0) "+" else "-"
                            +"$sign$%,.2f".format(abs(rebalDollars))
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Rebalance Qty (number of shares to buy/sell)
                    td(classes = "price-change ${stock.rebalanceDirection(portfolio.totalValue)} rebal-column") {
                        id = "rebal-qty-${stock.label}"
                        val rebalShares = stock.rebalanceShares(portfolio.totalValue)
                        if (rebalShares != null) {
                            val sign = if (rebalShares >= 0) "+" else "-"
                            +"$sign%.2f".format(abs(rebalShares))
                        } else {
                            span(classes = "loading") { +"—" }
                        }
                    }

                    // Alloc $ (allocation adjustment per stock)
                    td(classes = "price-change neutral alloc-column") {
                        id = "alloc-dollars-${stock.label}"
                    }
                    // Alloc Qty
                    td(classes = "price-change neutral alloc-column") {
                        id = "alloc-qty-${stock.label}"
                    }
                }
            }
        }
    }
    div(classes = "rebal-weight-warning") {
        attributes["id"] = "rebal-weight-warning"
        style = "display: none;"
    }
}

private fun estVal(stock: Stock): String? {
    if (stock.letfComponents == null) return null
    val basePrice = stock.lastNav ?: stock.lastClosePrice ?: return null
    val baseQuote = YahooMarketDataService.getQuote(stock.label)
    val nowSeconds = System.currentTimeMillis() / 1000
    val pastCloseTime =
        baseQuote?.tradingPeriodEnd?.takeIf { it <= nowSeconds }
    val isMarketClosed = baseQuote?.isMarketClosed ?: true
    val stale =
        isMarketClosed && (
                pastCloseTime == null ||
                        nowSeconds - pastCloseTime > 12 * 3600
                )

    return if (stale) {
        null
    } else {
        val sumComponent = stock.letfComponents.sumOf { (multi, sym) ->
            getPriceChange(sym)?.times(multi) ?: return null
        }
        "$%.2f".format((1.0 + sumComponent / 100.0) * basePrice)
    }
}

private fun getPriceChange(
    sym: String
): Double? {
    val quote = YahooMarketDataService.getQuote(sym) ?: return null
    val previousClose = quote.previousClose
    val regularMarketPrice = quote.regularMarketPrice
    return if (regularMarketPrice == null || previousClose == null || previousClose == 0.0) {
        null
    } else {
        ((regularMarketPrice - previousClose) / previousClose) * 100.0
    }
}
