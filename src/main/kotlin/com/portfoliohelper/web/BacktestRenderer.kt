package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

private fun FlowContent.dateFieldWithQuickSelect(labelText: String, inputId: String) {
    val quickSelectId = "$inputId-quick"
    val years = (1..10).toList() + listOf(15, 20, 25, 30)

    div(classes = "backtest-date-field") {
        label {
            attributes["for"] = inputId
            +labelText
        }
        div(classes = "date-input-row") {
            div(classes = "date-field-box") {
                input(type = InputType.date) {
                    id = inputId
                }
                button(classes = "date-clear-btn") {
                    attributes["type"] = "button"
                    attributes["data-target"] = inputId
                    attributes["title"] = "Clear"
                    attributes["style"] = "visibility:hidden"
                    +"×"
                }
            }
            select {
                id = quickSelectId
                attributes["aria-label"] = "Years back"
                option {
                    value = ""
                    +"Yrs"
                }
                years.forEach { y ->
                    option { value = "$y"; +"${y}Y" }
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

            renderCommonHeadElements()

            script {
                src = "https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"
                async = true
            }
            script { src = "/static/common/theme.js" }
            script { src = "/static/backtest/backtest-blocks.js" }
            script { src = "/static/backtest/backtest-saved.js" }
            script { src = "/static/backtest/backtest-chart.js" }
            script { src = "/static/backtest/backtest-run.js" }
            script { src = "/static/backtest/backtest-main.js" }
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
                            label {
                                attributes["for"] = "backtest-import-code"
                                +"Config Code"
                            }
                            div(classes = "backtest-config-group") {
                                input(type = InputType.text) {
                                    id = "backtest-import-code"
                                    attributes["placeholder"] = "Paste code…"
                                    attributes["spellcheck"] = "false"
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
                button(classes = "clear-portfolio-btn") {
                    attributes["type"] = "button"
                    attributes["title"] = "Clear portfolio"
                    +"✕"
                }
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
