package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun ApplicationCall.renderMonteCarloPage() {
    respondHtml(HttpStatusCode.OK) {
        head {
            title { +"Monte Carlo Simulator" }
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
            script { src = "/static/montecarlo/montecarlo-chart.js" }
            script { src = "/static/montecarlo/montecarlo-run.js" }
            script { src = "/static/montecarlo/montecarlo-main.js" }
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        renderPageNavTabs(AppPage.MONTE_CARLO)
                    }
                    div(classes = "header-buttons") {
                        renderThemeToggle()
                    }
                }

                div(classes = "backtest-form-card") {
                    // Date range + config code
                    div(classes = "backtest-section backtest-grid-2") {
                        mcDateField("From Date (pool)", "mc-from-date")
                        mcDateField("To Date (pool)", "mc-to-date")
                        div(classes = "backtest-config-controls") {
                            label { +"Config Code" }
                            div(classes = "backtest-config-group") {
                                input(type = InputType.text) {
                                    id = "mc-import-code"
                                    placeholder = "Paste code…"
                                    attributes["spellcheck"] = "false"
                                }
                                button(classes = "backtest-config-btn") { id = "mc-import-btn"; +"Import" }
                                button(classes = "backtest-config-btn") { id = "mc-export-btn"; +"Export" }
                                div(classes = "backtest-config-error") { id = "mc-config-error" }
                            }
                        }
                    }

                    div(classes = "backtest-section mc-params-grid") {
                        mcNumberField("Min Chunk Years", "mc-min-chunk", "3")
                        mcNumberField("Max Chunk Years", "mc-max-chunk", "8")
                        mcNumberField("Simulated Years", "mc-sim-years", "20")
                        mcNumberField("Simulations", "mc-num-sims", "500")
                        div(classes = "backtest-date-field") {
                            label { attributes["for"] = "mc-sort-metric"; +"Sort Target" }
                            select {
                                id = "mc-sort-metric"
                                option { value = "END_VALUE"; +"End Value" }
                                option { value = "CAGR"; +"CAGR" }
                                option { value = "MAX_DD"; +"Max DD" }
                                option { value = "SHARPE"; +"Sharpe" }
                                option { value = "ULCER_INDEX"; +"Ulcer Index" }
                                option { value = "UPI"; +"UPI" }
                            }
                        }
                    }

                    div {
                        id = "saved-portfolios-bar"
                        style = "display:none;"
                    }

                    // 3-column portfolio blocks (reuse from backtest)
                    div(classes = "portfolio-blocks") {
                        for (idx in 0..2) {
                            mcPortfolioBlock(idx)
                        }
                    }

                    button(classes = "run-backtest-btn") {
                        id = "run-mc-btn"
                        attributes["type"] = "button"
                        +"Run Simulation"
                    }
                }

                div {
                    id = "error-msg"
                    style = "display:none;"
                    classes = setOf("backtest-error")
                }

                // Percentile tab bar (hidden until results arrive)
                div {
                    id = "mc-percentile-bar"
                    classes = setOf("mc-percentile-tabs")
                    style = "display:none;"
                    for (pct in listOf(5, 10, 25, 50, 75, 90, 95)) {
                        button(classes = "mc-pct-tab${if (pct == 50) " active" else ""}") {
                            attributes["type"] = "button"
                            attributes["data-pct"] = pct.toString()
                            +"${pct}th"
                        }
                    }
                }

                div {
                    id = "stats-container"
                    style = "display:none;"
                }

                div(classes = "backtest-chart-container") {
                    id = "chart-container"
                    style = "display:none;"
                    canvas { id = "mc-chart" }
                }
            }
        }
    }
}

private fun FlowContent.mcDateField(labelText: String, inputId: String) {
    val quickId = "$inputId-quick"
    val years = (1..10).toList() + listOf(15, 20, 25, 30)
    div(classes = "backtest-date-field") {
        label { attributes["for"] = inputId; +labelText }
        div(classes = "date-input-row") {
            div(classes = "date-field-box") {
                input(type = InputType.date) { id = inputId }
                button(classes = "date-clear-btn") {
                    attributes["type"] = "button"
                    attributes["data-target"] = inputId
                    attributes["title"] = "Clear"
                    attributes["style"] = "visibility:hidden"
                    +"×"
                }
            }
            select {
                id = quickId
                attributes["aria-label"] = "Years back"
                option { value = ""; +"Yrs" }
                years.forEach { y -> option { value = "$y"; +"${y}Y" } }
            }
        }
    }
}

private fun FlowContent.mcNumberField(labelText: String, inputId: String, defaultVal: String) {
    div(classes = "backtest-date-field") {
        label { attributes["for"] = inputId; +labelText }
        input(type = InputType.text) {
            id = inputId
            value = defaultVal
            attributes["inputmode"] = "decimal"
        }
    }
}

private fun DIV.mcPortfolioBlock(idx: Int) {
    div(classes = "portfolio-block") {
        attributes["data-portfolio-index"] = idx.toString()

        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                input(type = InputType.text, classes = "portfolio-label") {
                    placeholder = "Label"
                }
                button(classes = "overwrite-portfolio-btn save-portfolio-btn") {
                    disabled = true; +"Save"
                }
                button(classes = "save-portfolio-btn") {
                    disabled = true; +"Save New"
                }
                button(classes = "clear-portfolio-btn") {
                    attributes["type"] = "button"
                    attributes["title"] = "Clear portfolio"
                    +"✕"
                }
            }
        }

        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                span { +"Tickers & Weights" }
                button(classes = "add-ticker-btn") {
                    attributes["type"] = "button"; +"+ Add Ticker"
                }
            }
            div(classes = "backtest-weight-hint") {}
            div(classes = "ticker-rows") {
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

        div(classes = "backtest-section") {
            label { +"Rebalance Strategy" }
            select(classes = "rebalance-select") {
                option { value = "NONE"; +"None" }
                option { value = "MONTHLY"; +"Monthly" }
                option { value = "QUARTERLY"; +"Quarterly" }
                option { value = "YEARLY"; selected = true; +"Yearly" }
            }
        }

        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                span { +"Margin" }
                button(classes = "add-margin-btn") {
                    attributes["type"] = "button"; +"+ Add Margin"
                }
            }
            div(classes = "margin-col-headers") {
                span {}; span { +"Ratio%" }; span { +"Spread%" }
                span { +"Dev%↑" }; span { +"Dev%↓" }
                span { +"Mode↑" }; span { +"Mode↓" }; span {}
            }
            div(classes = "margin-config-rows") {}
        }
    }
}
