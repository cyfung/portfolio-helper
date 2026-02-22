package com.portfoliohelper.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun ApplicationCall.renderLoanCalculatorPage() {
    respondHtml(HttpStatusCode.OK) {
        head {
            title { +"Loan Calculator" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

            // Inline script to prevent flash of wrong theme
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

            link(rel = "stylesheet", href = "/static/styles.css")
            script {
                src = "/static/loan-calculator.js"
                defer = true
            }
            script {
                src = "/static/theme-switcher.js"
                defer = true
            }
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        h1 { +"Loan Calculator" }
                        a(href = "/", classes = "loan-back-link") { +"← Portfolio" }
                    }

                    div(classes = "header-buttons") {
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
                }

                div(classes = "loan-card") {
                    div(classes = "loan-inputs") {
                        div(classes = "loan-col-left") {
                            label {
                                attributes["for"] = "loan-amount"
                                +"Loan Amount"
                            }
                            input(type = InputType.number) {
                                id = "loan-amount"
                                attributes["placeholder"] = "100000"
                                attributes["min"] = "0"
                                attributes["step"] = "any"
                            }

                            label {
                                attributes["for"] = "num-periods"
                                +"Number of Periods"
                            }
                            input(type = InputType.number) {
                                id = "num-periods"
                                attributes["placeholder"] = "360"
                                attributes["min"] = "1"
                            }

                            label {
                                attributes["for"] = "period-length"
                                +"Period Length"
                            }
                            select {
                                id = "period-length"
                                option {
                                    value = "365"
                                    +"Daily"
                                }
                                option {
                                    value = "52"
                                    +"Weekly"
                                }
                                option {
                                    value = "26"
                                    +"Bi-weekly"
                                }
                                option {
                                    value = "12"
                                    selected = true
                                    +"Monthly"
                                }
                                option {
                                    value = "4"
                                    +"Quarterly"
                                }
                                option {
                                    value = "2"
                                    +"Semi-annually"
                                }
                                option {
                                    value = "1"
                                    +"Annually"
                                }
                            }
                        }

                        div(classes = "loan-col-right") {
                            fieldSet(classes = "loan-exclusive-group") {
                                legend { +"enter one" }

                                label {
                                    attributes["for"] = "payment"
                                    +"Payment / Period"
                                }
                                input(type = InputType.number) {
                                    id = "payment"
                                    attributes["placeholder"] = "536.82"
                                    attributes["min"] = "0"
                                    attributes["step"] = "any"
                                }

                                hr {}

                                label {
                                    attributes["for"] = "rate-apy"
                                    +"Annual Rate (APY %)"
                                }
                                input(type = InputType.number) {
                                    id = "rate-apy"
                                    attributes["placeholder"] = "6.168"
                                    attributes["min"] = "0"
                                    attributes["step"] = "any"
                                }

                                hr {}

                                label {
                                    attributes["for"] = "rate-flat"
                                    +"Flat Rate (% / period)"
                                }
                                input(type = InputType.number) {
                                    id = "rate-flat"
                                    attributes["placeholder"] = "0.25"
                                    attributes["min"] = "0"
                                    attributes["step"] = "any"
                                }
                            }
                        }
                    }

                    div(classes = "extra-cashflows") {
                        div(classes = "extra-cashflows-header") {
                            span { +"Extra Cash Flows" }
                            button(classes = "add-cashflow-btn") {
                                id = "add-cashflow"
                                attributes["type"] = "button"
                                +"+ Add"
                            }
                        }
                        p(classes = "cashflow-hint") {
                            +"positive = extra received (e.g. rebate), negative = extra payment"
                        }
                        div { id = "cashflow-rows" }
                    }

                    p(classes = "cashflow-hint") {
                        +"Fill in Payment, Annual Rate (APY), or Flat Rate — the others will be cleared automatically."
                    }

                    button(classes = "calculate-btn") {
                        id = "calculate-btn"
                        attributes["type"] = "button"
                        +"Calculate"
                    }

                    div(classes = "loan-results") {
                        id = "loan-results"
                        style = "display:none;"

                        div(classes = "result-row") {
                            span { +"Periodic Rate" }
                            span { id = "result-periodic-rate" }
                        }
                        div(classes = "result-row") {
                            span { +"Nominal APR" }
                            span { id = "result-apr" }
                        }
                        div(classes = "result-row result-highlight") {
                            span { +"Effective APR (APY)" }
                            span { id = "result-apy" }
                        }
                        div(classes = "result-divider") {}
                        div(classes = "result-row") {
                            span { +"Total Payments" }
                            span { id = "result-total-payments" }
                        }
                        div(classes = "result-row") {
                            span { +"Total Interest" }
                            span { id = "result-total-interest" }
                        }
                    }

                    div(classes = "loan-error") {
                        id = "loan-error"
                        style = "display:none;"
                    }
                }
            }
        }
    }
}
