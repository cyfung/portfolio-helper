package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun ApplicationCall.renderBacktestPage() {
    respondHtml(HttpStatusCode.OK) {
        head {
            renderChartToolPageHead("Portfolio Backtester", listOf(
                "/static/backtest/backtest-chart.js",
                "/static/backtest/backtest-run.js",
                "/static/backtest/backtest-main.js"
            ))
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        renderPageNavTabs(AppPage.BACKTEST)
                    }
                    renderHeaderRight {
                        renderConfigButton()
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
