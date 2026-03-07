package com.portfoliohelper.web

import kotlinx.html.*

enum class AppPage(val line1: String, val line2: String, val href: String) {
    PORTFOLIO("Portfolio", "Viewer", "/"),
    LOAN("Loan", "Calculator", "/loan"),
    BACKTEST("Portfolio", "Backtest", "/backtest"),
    MONTE_CARLO("Monte Carlo", "Simulation", "/montecarlo")
}

fun FlowContent.renderPageNavTabs(activePage: AppPage) {
    div(classes = "page-nav-tabs") {
        for (page in AppPage.entries) {
            val isActive = page == activePage
            a(href = page.href, classes = "page-nav-tab${if (isActive) " active" else ""}") {
                span(classes = "page-nav-tab-line1") { +page.line1 }
                span(classes = "page-nav-tab-line2") { +page.line2 }
            }
        }
    }
}

internal fun formatQty(amount: Double) =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

fun HEAD.renderCommonHeadElements() {
    link(rel = "stylesheet", href = "/static/styles.css")
    link(rel = "icon", type = "image/png", href = "/static/favicon-96x96.png") {
        attributes["sizes"] = "96x96"
    }
    link(rel = "icon", type = "image/svg+xml", href = "/static/favicon.svg")
}

fun DIV.renderThemeToggle() {
    button(classes = "theme-toggle") {
        attributes["aria-label"] = "Toggle theme"
        attributes["id"] = "theme-toggle"
        attributes["type"] = "button"

        span(classes = "icon-sun") {
            unsafe {
                raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>""")
            }
        }

        span(classes = "icon-moon") {
            unsafe {
                raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>""")
            }
        }
    }
}

fun FlowContent.dateFieldWithQuickSelect(labelText: String, inputId: String) {
    val quickSelectId = "$inputId-quick"
    val years = (1..10).toList() + listOf(15, 20, 25, 30)
    div(classes = "backtest-date-field") {
        label {
            attributes["for"] = inputId
            +labelText
        }
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
                id = quickSelectId
                attributes["aria-label"] = "Years back"
                option { value = ""; +"Yrs" }
                years.forEach { y -> option { value = "$y"; +"${y}Y" } }
            }
        }
    }
}

fun DIV.portfolioBlock(idx: Int) {
    div(classes = "portfolio-block") {
        attributes["data-portfolio-index"] = idx.toString()

        // Label
        div(classes = "backtest-section") {
            div(classes = "backtest-section-header") {
                input(type = InputType.text, classes = "portfolio-label") { placeholder = "Label" }
                button(classes = "overwrite-portfolio-btn save-portfolio-btn") { disabled = true; +"Save" }
                button(classes = "save-portfolio-btn") { disabled = true; +"Save New" }
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
                button(classes = "add-ticker-btn") { attributes["type"] = "button"; +"+ Add Ticker" }
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
                div(classes = "margin-header-btns") {
                    button(classes = "include-no-margin-btn") {
                        attributes["type"] = "button"
                        attributes["data-include"] = "true"
                        +"Unlevered: On"
                    }
                    button(classes = "add-margin-btn") { attributes["type"] = "button"; +"+ Add Margin" }
                }
            }
            div(classes = "margin-col-headers") {
                span {}; span { +"Ratio%" }; span { +"Spread%" }
                span {
                    attributes["title"] = "Upper deviation band: if the margin ratio rises above target + this %, a rebalance is triggered (market fell → over-leveraged)"
                    +"Dev%↑"
                }
                span {
                    attributes["title"] = "Lower deviation band: if the margin ratio falls below target − this %, a rebalance is triggered (market rose → under-leveraged)"
                    +"Dev%↓"
                }
                span {
                    attributes["title"] = "What to do when the upper band is breached (market fell, margin ratio too high)"
                    +"Mode↑"
                }
                span {
                    attributes["title"] = "What to do when the lower band is breached (market rose, margin ratio too low)"
                    +"Mode↓"
                }
                span {}
            }
            div(classes = "margin-config-rows") {}
        }
    }
}