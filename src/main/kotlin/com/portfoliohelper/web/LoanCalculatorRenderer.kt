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

            renderCommonHeadElements()
            
            script {
                src = "/static/loan-calculator.js"
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
                        renderThemeToggle()
                    }
                }

                div { id = "loan-history" }

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
                        div(classes = "result-row") {
                            span { +"Flat Rate" }
                            span { id = "result-flat-rate" }
                        }
                        div(classes = "result-divider") {}
                        div(classes = "result-row") {
                            span { +"Payment / Period" }
                            span { id = "result-payment-per-period" }
                        }
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

