package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

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
                        div {
                            label {
                                attributes["for"] = "from-date"
                                +"From Date"
                            }
                            input(type = InputType.date) {
                                id = "from-date"
                                attributes["placeholder"] = "YYYY-MM-DD"
                            }
                        }
                        div {
                            label {
                                attributes["for"] = "to-date"
                                +"To Date"
                            }
                            input(type = InputType.date) {
                                id = "to-date"
                                attributes["placeholder"] = "YYYY-MM-DD"
                            }
                        }
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

                div(classes = "backtest-chart-container") {
                    id = "chart-container"
                    style = "display:none;"
                    canvas { id = "backtest-chart" }
                }

                div {
                    id = "stats-container"
                    style = "display:none;"
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
            label { +"Label" }
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
            div(classes = "ticker-rows") {}
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
                span { +"Ratio%" }
                span { +"Spread%" }
                span { +"Dev%" }
                span {}
            }
            div(classes = "margin-config-rows") {}
        }
    }
}
