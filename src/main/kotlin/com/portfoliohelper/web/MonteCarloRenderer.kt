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
            script { src = "/static/common/stats-formatters.js" }
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
                        renderConfigButton()
                        renderThemeToggle()
                    }
                }

                div(classes = "backtest-form-card") {
                    // Date range + config code
                    div(classes = "backtest-section backtest-grid-2") {
                        dateFieldWithQuickSelect("From Date (pool)", "mc-from-date")
                        dateFieldWithQuickSelect("To Date (pool)", "mc-to-date")
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
                            portfolioBlock(idx)
                        }
                    }

                    div {
                        style = "display:flex; align-items:center; gap:0.5rem;"
                        button(classes = "run-backtest-btn") {
                            id = "run-mc-btn"
                            attributes["type"] = "button"
                            +"Run Simulation"
                        }
                        button(classes = "run-backtest-btn") {
                            id = "rerun-mc-btn"
                            attributes["type"] = "button"
                            style = "display:none; opacity:0.75;"
                            +"Rerun (same seed)"
                        }
                        span {
                            id = "mc-progress"
                            style = "display:none; margin-left:0.25rem; font-size:0.85em; opacity:0.7;"
                        }
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
