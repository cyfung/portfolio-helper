package com.portfoliohelper.web

import com.portfoliohelper.service.IbkrInterestSnapshot
import com.portfoliohelper.service.IbkrMarginRateService
import kotlinx.html.*
import kotlinx.html.stream.createHTML

internal fun FlowContent.buildIbkrRatesSection() {
    val lastFetchMillis = IbkrMarginRateService.getLastFetchMillis()

    div(classes = "ibkr-rates-wrapper") {
        div { id = "ibkr-display" }
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
    }
}

internal fun renderIbkrDisplayHtml(snap: IbkrInterestSnapshot): String = createHTML().div {
    if (snap.perCurrency.isNotEmpty()) {
        table(classes = "ibkr-rates-table") {
            thead {
                tr {
                    th { +"CCY" }
                    th { +"IBKR Pro Rate" }
                }
            }
            tbody {
                snap.perCurrency.forEach { ci ->
                    tr {
                        td(classes = "ibkr-rate-currency") { +ci.currency }
                        td(classes = "ibkr-rate-value") { +ci.displayRateText }
                    }
                }
            }
        }

        val savingsUsd = snap.savingsUsd
        val showSavings = savingsUsd >= 0.005

        table(classes = "ibkr-interest-summary") {
            tbody {
                tr {
                    td { +"Current Daily Interest" }
                    td(classes = "ibkr-value-muted") {
                        if (snap.currentDailyUsd > 0) +formatDisplayCurrency(snap.currentDailyUsd) else +"\u2014"
                    }
                }
                tr {
                    td {
                        +"Cheapest"
                        if (snap.cheapestCcy != null) {
                            +" "
                            span { +"(${snap.cheapestCcy})" }
                        }
                    }
                    td(classes = "ibkr-value-muted") {
                        if (snap.cheapestCcy != null) +formatDisplayCurrency(snap.cheapestDailyUsd) else +"\u2014"
                    }
                }
                tr {
                    td { +snap.label }
                    td {
                        if (showSavings) {
                            classes = setOf("ibkr-rate-diff")
                            +formatDisplayCurrency(savingsUsd)
                        } else {
                            +"\u2014"
                        }
                    }
                }
            }
        }
    }
}

private fun formatDisplayCurrency(value: Double): String =
    "$%,.2f".format(value)
