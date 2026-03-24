package com.portfoliohelper.web

import com.portfoliohelper.service.IbkrMarginRateService
import kotlinx.html.*

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

