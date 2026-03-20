package com.portfoliohelper.web

import com.portfoliohelper.AppConfig
import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.service.ManagedPortfolio
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.util.appJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
private data class PortfolioOption(val slug: String, val name: String)

internal suspend fun ApplicationCall.renderPortfolioPage(
    entry: ManagedPortfolio,
    allPortfolios: Collection<ManagedPortfolio>,
    activePortfolioId: String
) {
    val portfolio = YahooMarketDataService.getCurrentPortfolio(entry.getStocks())
    val cashEntries = entry.getCash()

    // Build per-stock currency map from cached Yahoo quotes
    val stockCurrenciesMap: Map<String, String> = portfolio.stocks.associate { s ->
        s.label to (YahooMarketDataService.getQuote(s.label)?.currency ?: "USD")
    }
    val showStockDisplayCurrency = AppConfig.showStockDisplayCurrency

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
                    ManagedPortfolio.getBySlug(e.portfolioRef ?: return null)
                        ?: return null
                e.amount * YahooMarketDataService.getCurrentPortfolio(ref.getStocks()).stockGrossValue
            }

            else -> fxRateMap[e.currency]?.let { e.amount * it }
        }
    }

    // Load per-portfolio config from SQLite (PortfolioCfgTable)
    val portfolioConf: Map<String, String> = entry.getAllConfig()
    val savedRebalTargetUsd = portfolioConf["rebalTarget"]?.toDoubleOrNull() ?: 0.0
    val savedMarginTargetPct = portfolioConf["marginTarget"]?.toDoubleOrNull() ?: 0.0
    val savedAllocAddMode = portfolioConf["allocAddMode"] ?: "PROPORTIONAL"
    val savedAllocReduceMode = portfolioConf["allocReduceMode"] ?: "PROPORTIONAL"
    val virtualBalanceEnabled = portfolioConf["virtualBalance"] == "true"

    // Inject dividend total as a virtual cash entry when virtualBalance is enabled and total is available
    val dividendTotal = if (virtualBalanceEnabled) portfolioConf["dividendTotal"]?.toDoubleOrNull() else null
    val effectiveCashEntries = if (dividendTotal != null) {
        cashEntries + CashEntry(label = "Dividend", currency = "USD", marginFlag = false, amount = dividendTotal)
    } else {
        cashEntries
    }

    // Compute totals and display currencies at function level so they can be used in both head and body
    val cashTotalUsd = effectiveCashEntries.sumOf { ce -> resolveEntryUsd(ce) ?: 0.0 }
    val displayCurrencies: List<String> = buildList {
        add("USD")
        allPortfolios.asSequence().flatMap { it.getCash() }
            .map { it.currency.uppercase() }
            .distinct().filter { it != "P" && it != "USD" }
            .sorted().toList().forEach { add(it) }
    }

    respondHtml(HttpStatusCode.OK) {
        head {
            title { +"Stock Portfolio Viewer" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

            renderCommonHeadElements()

            // Inline data block: server-rendered dynamic values consumed by JS
            script {
                unsafe {
                    raw(
                        """
                        var portfolioId = "${entry.slug}";
                        var portfolioName = "${entry.name}";
                        var fxRates = ${appJson.encodeToString(buildMap { put("USD", 1.0); putAll(fxRateMap) })};
                        var displayCurrencies = ${appJson.encodeToString(displayCurrencies)};
                        var savedRebalTargetUsd = ${"%.2f".format(savedRebalTargetUsd)};
                        var savedMarginTargetPct = ${"%.4f".format(savedMarginTargetPct)};
                        var savedAllocAddMode = "$savedAllocAddMode";
                        var savedAllocReduceMode = "$savedAllocReduceMode";
                        var virtualBalanceEnabled = $virtualBalanceEnabled;
                        var dividendCalcUpToDate = "${portfolioConf["dividendCalcUpToDate"] ?: ""}";
                        var stockCurrencies = ${appJson.encodeToString(stockCurrenciesMap)};
                        var savedShowStockDisplayCurrency = $showStockDisplayCurrency;
                        var allPortfolioOptions = ${appJson.encodeToString(allPortfolios.map { p -> PortfolioOption(p.slug, p.name) })};
                        """.trimIndent()
                    )
                }
            }

            script { src = "/static/common/theme.js" }
            script { src = "/static/viewer/globals.js" }
            script { src = "/static/viewer/utils.js" }
            script { src = "/static/viewer/ui-helpers.js" }
            script { src = "/static/viewer/letf.js" }
            script { src = "/static/viewer/cash.js" }
            script { src = "/static/viewer/rebalance.js" }
            script { src = "/static/viewer/display-worker.js" }
            script { src = "/static/viewer/portfolio.js" }
            script { src = "/static/viewer/rebalance-ga.js" }
            script { src = "/static/viewer/groups.js" }
            script { src = "/static/viewer/edit-mode.js" }
            script { src = "/static/viewer/controls.js" }
            script { src = "/static/viewer/backup.js" }
            script { src = "/static/viewer/sse.js" }
            script { src = "/static/viewer/main.js" }
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        renderPageNavTabs(AppPage.PORTFOLIO)
                        span(classes = "header-timestamp") {
                            id = "last-update-time"
                            val now = java.time.LocalTime.now()
                            +java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(now)
                        }
                        button(classes = "tws-sync-btn") {
                            attributes["id"] = "tws-sync-btn"
                            attributes["type"] = "button"
                            attributes["title"] = "Sync Qty and Cash from Interactive Brokers TWS"
                            +"Sync TWS"
                        }
                    }

                    renderHeaderRight {
                        button(classes = "restore-backup-btn") {
                            attributes["id"] = "restore-backup-btn"
                            attributes["type"] = "button"
                            attributes["title"] = "Backup and restore portfolio"
                            span(classes = "toggle-label") { +"Backups" }
                        }
                        input(type = InputType.file) {
                            id = "import-file-input"
                            attributes["accept"] = ".csv,.txt,.zip,.json"
                            style = "display:none"
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

                        button(classes = "more-info-toggle") {
                            attributes["id"] = "more-info-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Show/Hide Last NAV, Last, and Mkt Val columns"
                            span(classes = "toggle-label") { +"More Info" }
                        }

                        button(classes = "groups-toggle") {
                            attributes["id"] = "groups-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Switch between stock view and group view"
                            span(classes = "toggle-label") { +"Groups" }
                        }

                        if (virtualBalanceEnabled) {
                            button(classes = "virtual-rebal-btn") {
                                attributes["id"] = "virtual-rebal-btn"
                                attributes["type"] = "button"
                                attributes["title"] =
                                    "Apply rebalancing quantities to the portfolio (virtual — requires Save to persist)"
                                span(classes = "toggle-label") { +"Virtual Rebalance" }
                            }
                        }

                        button(classes = "rebal-toggle") {
                            attributes["aria-label"] = "Toggle rebalancing columns"
                            attributes["id"] = "rebal-toggle"
                            attributes["type"] = "button"
                            attributes["title"] = "Show/Hide Weight and Rebalancing columns"
                            span(classes = "toggle-label") { +"Rebal" }
                        }

                        if (displayCurrencies.size == 1) {
                            span(classes = "currency-pill") { +displayCurrencies[0] }
                        } else if (displayCurrencies.size <= 3) {
                            button(classes = "currency-toggle") {
                                attributes["id"] = "currency-toggle"
                                attributes["type"] = "button"
                                attributes["data-currencies"] =
                                    displayCurrencies.joinToString(",")
                                attributes["title"] = "Switch display currency"
                                span(classes = "currency-toggle-icon") { +"\u21C4" }
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

                        renderConfigButton()
                        renderThemeToggle()
                    }
                }

                // Tab bar — always rendered
                div(classes = "portfolio-tabs") {
                    for (p in allPortfolios) {
                        val href = if (p.slug == "main") "/" else "/portfolio/${p.slug}"
                        a(
                            href = href,
                            classes = "tab-link${if (p.slug == activePortfolioId) " active" else ""}"
                        ) { +p.name }
                    }
                    button(classes = "save-portfolio-btn") {
                        attributes["id"] = "save-to-backtest-btn"
                        attributes["type"] = "button"
                        attributes["title"] = "Save current portfolio as a backtest preset"
                        +"Save to Backtest"
                    }
                }

                div(classes = "portfolio-tables-wrapper") {
                    div(classes = "summary-and-rates") {
                        table(classes = "portfolio-cash-table") {
                            tbody {
                                buildSummaryRows(
                                    effectiveCashEntries,
                                    ::resolveEntryUsd,
                                    portfolio,
                                    cashTotalUsd
                                )
                            }
                        }

                        buildCashEditTable(cashEntries.sortedBy { it.label.lowercase() }, allPortfolios.toList())

                        if (virtualBalanceEnabled) {
                            div(classes = "dividend-from-section") {
                                label { attributes["for"] = "dividend-from-input"; +"Dividend From" }
                                input(type = InputType.date, classes = "dividend-from-input") {
                                    id = "dividend-from-input"
                                    value = portfolioConf["dividendStartDate"] ?: ""
                                    attributes["data-original-value"] = portfolioConf["dividendStartDate"] ?: ""
                                    attributes["autocomplete"] = "off"
                                }
                            }
                        }

                        if (effectiveCashEntries.any { it.marginFlag }) {
                            buildIbkrRatesTable()
                        }
                    }

                    div(classes = "stock-section") {
                        div(classes = "alloc-controls") {
                            span(classes = "alloc-controls-label") { +"Alloc Strategy" }
                            div(classes = "alloc-mode-group") {
                                label(classes = "alloc-mode-label alloc-mode-label-deposit") {
                                    attributes["for"] = "alloc-add-mode"
                                    +"+"
                                }
                                allocMode("alloc-add-mode")
                            }
                            div(classes = "alloc-mode-group") {
                                label(classes = "alloc-mode-label alloc-mode-label-withdraw") {
                                    attributes["for"] = "alloc-reduce-mode"
                                    +"−"
                                }
                                allocMode("alloc-reduce-mode")
                            }
                        }

                        buildStockTable(portfolio)

                        div {
                            id = "group-table-container"
                            style = "display: none;"
                        }
                    }

                    div(classes = "edit-add-buttons") {
                        button(classes = "add-stock-btn") {
                            attributes["type"] = "button"
                            id = "add-stock-btn"
                            +"+ Add Stock"
                        }
                    }
                }


            }
        }
    }
}

private fun DIV.allocMode(id: String) {
    select {
        this.id = id
        option { value = "PROPORTIONAL"; +"Target Wt" }
        option { value = "CURRENT_WEIGHT"; +"Current Wt" }
        option { value = "UNDERVALUED_PRIORITY"; +"Underval First" }
        option { value = "WATERFALL"; +"Waterfall" }
    }
}

private fun TBODY.buildSummaryRows(
    cashEntries: List<CashEntry>,
    resolveEntryUsd: (CashEntry) -> Double?,
    portfolio: Portfolio,
    cashTotalUsd: Double
) {
    // Portfolio Value row — at the top of the summary table (stocks + cash grand total)
    // Day change innerHTML is fully owned by JS (updateTotalValue); render empty
    tr(classes = "grand-total-row") {
        td { +"Portfolio Value" }
        td {}
        td {}
        td {
            span {
                id = "portfolio-total"
                +"%,.2f".format(portfolio.stockGrossValue + cashTotalUsd)
            }
            div(classes = "summary-subvalue") { id = "total-day-change" }
        }
    }

    if (cashEntries.isNotEmpty()) {
        tr(classes = "summary-section-break") {
            td { attributes["colspan"] = "4" }
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
            classes = buildSet {
                if (entry.marginFlag) add("cash-margin-entry")
                if (entry.portfolioRef != null) add("cash-ref-entry")
            }

            td { +displayLabel }
            td(classes = "cash-badge-col") {
                if (entry.marginFlag) {
                    span(classes = "cash-type-badge cash-badge-margin") { +"M" }
                }
                if (entry.portfolioRef != null) {
                    span(classes = "cash-type-badge cash-badge-ref") { +"\u2197" }
                }
            }
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
                    if (resolvedUsd != null) +"%,.2f".format(resolvedUsd) else +"---"
                }
            }
        }
    }

    if (cashEntries.isNotEmpty()) {
        tr(classes = "summary-divider") {
            td { attributes["colspan"] = "4" }
        }
        tr(classes = "total-cash-row") {
            td { +"Total Cash" }
            td {}
            td {}
            td {
                span {
                    id = "cash-total-usd"
                    +"%,.2f".format(cashTotalUsd)
                }
            }
        }

        // Margin row (only when M-flagged entries exist)
        val hasMarginEntries = cashEntries.any { it.marginFlag }
        if (hasMarginEntries) {
            val marginUsd = cashEntries.filter { it.marginFlag }
                .sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
            val marginDenominator = portfolio.stockGrossValue + marginUsd
            tr(classes = "margin-row") {
                attributes["data-margin-row"] = "true"
                if (marginUsd >= 0) style = "display:none;"
                td { +"Margin" }
                td {}
                td {}
                td {
                    span {
                        id = "margin-total-usd"
                        +"%,.2f".format(abs(marginUsd))
                    }
                    val marginPct = if (marginDenominator != 0.0 && marginUsd < 0)
                        (marginUsd / marginDenominator) * 100.0 else 0.0
                    span(classes = "margin-percent") {
                        id = "margin-percent"
                        if (marginUsd >= 0) style = "display:none;"
                        else +" (${"%.1f%%".format(abs(marginPct))})"
                    }
                }
            }
        }

        tr(classes = "summary-section-break") {
            td { attributes["colspan"] = "4" }
        }

        // Stock Gross Value row — day change innerHTML owned by JS (updateTotalValue); render empty
        tr {
            td { +"Stock Gross Value" }
            td {}
            td {}
            td {
                div {
                    id = "stock-gross-total"
                    +"%,.2f".format(portfolio.stockGrossValue)
                }
                div(classes = "summary-subvalue") { id = "portfolio-day-change" }
            }
        }
    }

    // Rebalance Target input row — always shown
    tr(classes = "rebal-target-row") {
        td { +"Rebalance Target" }
        td {}
        td {}
        td {
            input(type = InputType.text, classes = "rebal-target-input") {
                attributes["id"] = "rebal-target-input"
                attributes["placeholder"] = "%,.2f".format(portfolio.stockGrossValue)
                attributes["autocomplete"] = "off"
            }
        }
    }

    // Margin Target row — only rendered when margin entries are configured
    if (cashEntries.any { it.marginFlag }) {
        val mUsd = cashEntries.filter { it.marginFlag }.sumOf { e -> resolveEntryUsd(e) ?: 0.0 }
        val mDenom = portfolio.stockGrossValue + mUsd
        val mPct = if (mDenom != 0.0 && mUsd < 0) (mUsd / mDenom) * 100.0 else 0.0
        tr(classes = "margin-row") {
            attributes["id"] = "margin-target-row"
            td { +"Margin Target" }
            td {}
            td { span { id = "margin-target-usd" } }
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
                th(classes = "col-num col-market-data col-moreinfo") { +"Last NAV" }
                th(classes = "col-num col-market-data") {
                    id = "th-est-val"
                    +"EST "
                    span(classes = "col-info-hint") {
                        title = "Hover a cell to see price targets"
                        +"ⓘ"
                    }
                }
                th(classes = "col-num col-market-data col-moreinfo") { +"Last" }
                th(classes = "col-num col-market-data") { +"Mark" }
                th(classes = "col-num col-market-data") { +"CHG" }
                th(classes = "col-num col-market-data") { +"P&L" }
                th(classes = "col-num col-market-data col-moreinfo") { +"Mkt Val" }
                th(classes = "col-num") {
                    +"Weight "
                    span(classes = "th-sub") { +"Cur / Tgt / Dev" }
                }
                th(classes = "rebal-column") { +"Rebal" }
                th(classes = "rebal-column col-moreinfo") { +"Rebal Qty" }
                th(classes = "alloc-column") { +"Alloc" }
                th(classes = "alloc-column col-moreinfo") { +"Alloc Qty" }
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
                    if (stock.groups.isNotEmpty()) {
                        attributes["data-groups"] =
                            stock.groups.joinToString(";") { "${it.first} ${it.second}" }
                    }

                    // Symbol
                    td { +stock.label }

                    // Qty (Amount)
                    td(classes = "amount") {
                        id = "amount-${stock.label}"
                        +formatQty(stock.amount)
                    }

                    // Last NAV — kept server-side as it comes from a separate NAV feed, not SSE
                    td(classes = if (stock.lastNav != null) "col-market-data price loaded muted col-moreinfo" else "col-market-data price muted col-moreinfo") {
                        id = "nav-${stock.label}"
                        +(if (stock.lastNav != null) "%.2f".format(stock.lastNav) else "—")
                    }

                    // EST — always blank; fully owned by letf.js via SSE component prices
                    td(classes = "col-market-data price") {
                        id = "est-val-${stock.label}"
                        +"—"
                    }

                    // Last Close Price — kept for first paint; SSE overwrites immediately
                    td(classes = if (stock.lastClosePrice != null) "col-market-data price loaded col-moreinfo" else "col-market-data price col-moreinfo") {
                        id = "close-${stock.label}"
                        +(if (stock.lastClosePrice != null) "%.2f".format(stock.lastClosePrice) else "—")
                    }

                    // Mark Price — kept for first paint; SSE overwrites immediately
                    td(classes = if (stock.markPrice != null) "col-market-data price loaded" else "col-market-data price") {
                        id = "mark-${stock.label}"
                        span(classes = "mark-price-value") {
                            +(if (stock.markPrice != null) "%.2f".format(stock.markPrice) else "—")
                        }
                        span(classes = "mark-day-pct") {
                            id = "day-percent-${stock.label}"
                        }
                    }

                    // CHG / P&L — owned by JS (updatePriceInUI); render empty
                    td(classes = "col-market-data price-change neutral") {
                        id = "day-change-${stock.label}"
                    }

                    // P&L — owned by JS; render empty
                    td(classes = "col-market-data price-change neutral") {
                        id = "position-change-${stock.label}"
                    }

                    // Mkt Val — kept for first paint; SSE overwrites immediately
                    td(classes = if (stock.value != null) "col-market-data value loaded col-moreinfo" else "col-market-data value col-moreinfo") {
                        id = "value-${stock.label}"
                        +(if (stock.value != null) "%,.2f".format(stock.value) else "—")
                    }

                    // Weight — owned by JS (updateCurrentWeights); render empty
                    td(classes = "weight-display col-num") {
                        id = "current-weight-${stock.label}"
                    }

                    // Rebal / Rebal Qty — owned by JS (updateRebalancingColumns); render empty
                    td(classes = "action-neutral rebal-column") {
                        id = "rebal-dollars-${stock.label}"
                    }
                    td(classes = "action-neutral rebal-column col-moreinfo") {
                        id = "rebal-qty-${stock.label}"
                    }

                    // Alloc / Alloc Qty — owned by JS (updateAllocColumns); always empty
                    td(classes = "action-neutral alloc-column") {
                        id = "alloc-dollars-${stock.label}"
                    }
                    td(classes = "action-neutral alloc-column col-moreinfo") {
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
