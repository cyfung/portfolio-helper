package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun ApplicationCall.renderMonteCarloPage() {
    respondHtml(HttpStatusCode.OK) {
        head {
            renderChartToolPageHead("Monte Carlo Simulator", listOf(
                "/static/montecarlo/montecarlo-chart.js",
                "/static/montecarlo/montecarlo-run.js",
                "/static/montecarlo/montecarlo-main.js"
            ))
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        renderPageNavTabs(AppPage.MONTE_CARLO)
                    }
                    renderHeaderRight {
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

                // Description + percentile tab bar (hidden until results arrive)
                div {
                    id = "mc-metrics-desc"
                    style = "display:none; opacity:0.7; margin:0.5rem 0 1rem; line-height:1.5;"
                    p { style = "font-size:var(--font-size-md); margin:0;"; +"⚠\uFE0E Each metric is independently ranked across all simulations." }
                    p { style = "font-size:0.82em; margin:0;"; +"At P50, CAGR shows the median CAGR outcome, Max DD shows the median worst drawdown (ranked by drawdown), and so on." }
                    p { style = "font-size:0.82em; margin:0;"; +"The chart always shows the path at the selected percentile when simulations are ranked by CAGR." }
                }
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
