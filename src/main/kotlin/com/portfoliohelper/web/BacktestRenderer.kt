package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

private fun FlowContent.dateFieldWithQuickSelect(labelText: String, inputId: String) {
    val quickSelectId = "$inputId-quick"
    val years = (1..10).toList() + listOf(15, 20, 25, 30)

    div {
        label {
            attributes["for"] = inputId
            +labelText
        }
        div(classes = "date-input-row") {
            input(type = InputType.date) {
                id = inputId
            }
            select {
                id = quickSelectId
                attributes["aria-label"] = "Quick select $labelText"
                option {
                    value = ""
                    +"Quick select"
                }
                option { value = "0"; +"Today" }
                years.forEach { y ->
                    option { value = "$y"; +"${y}Y ago" }
                }
            }
        }
    }
}

internal suspend fun ApplicationCall.renderBacktestPage() {
    respondHtml(HttpStatusCode.OK) {
        head {
            title { +"Portfolio Backtester" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

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

            script {
                src = "https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"
                async = true
            }
            script {
                src = "/static/backtest.js"
                defer = true
            }
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        h1 { +"Portfolio Backtester" }
                        a(href = "/", classes = "loan-back-link") { +"← Portfolio" }
                    }
                    div(classes = "header-buttons") {
                        renderThemeToggle()
                    }
                }

                div(classes = "backtest-form-card") {
                    // Shared date range
                    div(classes = "backtest-section backtest-grid-2") {
                        dateFieldWithQuickSelect("From Date", "from-date")
                        dateFieldWithQuickSelect("To Date", "to-date")

                        div(classes = "backtest-config-controls") {
                            div(classes = "backtest-config-group") {
                                label {
                                    attributes["for"] = "backtest-import-code"
                                    +"Config Code"
                                }
                                input(type = InputType.text) {
                                    id = "backtest-import-code"
                                    attributes["placeholder"] = "Paste code…"
                                    attributes["spellcheck"] = "false"
                                }
                            }
                            button(classes = "backtest-config-btn") {
                                id = "backtest-import-btn"
                                +"Import"
                            }
                            button(classes = "backtest-config-btn") {
                                id = "backtest-export-btn"
                                +"Export"
                            }
                            div {
                                id = "backtest-config-error"
                                classes = setOf("backtest-config-error")
                            }
                        }
                    }

                    div {
                        id = "saved-portfolios-bar"
                        style = "display:none;"
                    }

                    // 3-column portfolio blocks
                    div(classes = "portfolio-blocks") {
                        for (idx in 0..2) {
                            portfolioBlock(idx)
                        }
                    }

                    button(classes = "run-backtest-btn") {
                        id = "run-backtest-btn"
                        attributes["type"] = "button"
                        +"Run Backtest"
                    }
                }

                div {
                    id = "error-msg"
                    style = "display:none;"
                    classes = setOf("backtest-error")
                }

                div {
                    id = "stats-container"
                    style = "display:none;"
                }

                div(classes = "backtest-chart-container") {
                    id = "chart-container"
                    style = "display:none;"
                    canvas { id = "backtest-chart" }
                }
            }
        }
    }
}

private fun DIV.portfolioBlock(idx: Int) {
    div(classes = "portfolio-block") {
        attributes["data-portfolio-index"] = idx.toString()

        // Label
        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                input(type = InputType.text, classes = "portfolio-label") {
                    placeholder = "Label"
                }
                button(classes = "overwrite-portfolio-btn save-portfolio-btn") {
                    disabled = true
                    +"Save"
                }
                button(classes = "save-portfolio-btn") {
                    disabled = true
                    +"Save New"
                }
            }
            input(type = InputType.text) {
                classes = setOf("portfolio-label")
                placeholder = "Portfolio ${idx + 1}"
            }
        }

        // Tickers
        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                span { +"Tickers & Weights" }
                button(classes = "add-ticker-btn") {
                    attributes["type"] = "button"
                    +"+ Add Ticker"
                }
            }
            div(classes = "backtest-weight-hint") {}
            div(classes = "ticker-rows") {
                // Seed default tickers for block 0 server-side so they appear instantly
                if (idx == 0) {
                    for ((ticker, weight) in listOf("VT" to "60", "KMLM" to "40")) {
                        div(classes = "backtest-ticker-row") {
                            input(type = InputType.text) {
                                classes = setOf("ticker-input")
                                placeholder = "e.g. VT or: 1 KMLM 1 VT S=1.5"
                                value = ticker
                            }
                            input(type = InputType.text) {
                                classes = setOf("weight-input")
                                placeholder = "Weight %"
                                value = weight
                            }
                            span(classes = "weight-unit") { +"%" }
                            button(classes = "remove-ticker-btn") {
                                attributes["type"] = "button"
                                attributes["title"] = "Remove"
                                +"✕"
                            }
                        }
                    }
                }
            }
        }

        // Rebalance strategy
        div(classes = "backtest-section") {
            label { +"Rebalance Strategy" }
            select(classes = "rebalance-select") {
                option { value = "NONE"; +"None" }
                option { value = "MONTHLY"; +"Monthly" }
                option { value = "QUARTERLY"; +"Quarterly" }
                option { value = "YEARLY"; selected = true; +"Yearly" }
            }
        }

        // Margin
        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                span { +"Margin" }
                button(classes = "add-margin-btn") {
                    attributes["type"] = "button"
                    +"+ Add Margin"
                }
            }
            div(classes = "margin-col-headers") {
                span {}          // aligns with drag-handle column
                span { +"Ratio%" }
                span { +"Spread%" }
                span { +"Dev%↑" }
                span { +"Dev%↓" }
                span { +"Mode↑" }
                span { +"Mode↓" }
                span {}          // remove-btn spacer
            }
            div(classes = "margin-config-rows") {}
        }
    }
}
