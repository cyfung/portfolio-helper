package com.portfoliohelper.web

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.service.ManagedPortfolio
import kotlinx.html.*

internal fun FlowContent.buildCashEditTable(
    sortedEntries: List<CashEntry>,
    allPortfolios: List<ManagedPortfolio>
) {
    div(classes = "cash-edit-table-wrapper") {
        table(classes = "cash-edit-table") {
            tbody {
                for (entry in sortedEntries) {
                    val isRef = entry.currency == "P"
                    val entryType = when {
                        isRef && entry.marginFlag -> "ref-margin"
                        isRef -> "ref"
                        entry.marginFlag -> "margin"
                        else -> "normal"
                    }
                    tr {
                        attributes["data-cash-edit-row"] = "true"
                        attributes["data-entry-type"] = entryType
                        attributes["data-original-label"] = entry.label
                        attributes["data-original-currency"] = entry.currency
                        attributes["data-original-amount"] = entry.amount.toString()
                        attributes["data-original-margin"] = entry.marginFlag.toString()
                        attributes["data-original-is-ref"] = isRef.toString()
                        if (isRef) {
                            attributes["data-original-portfolio-ref"] = entry.portfolioRef ?: ""
                            attributes["data-original-multiplier"] = entry.amount.toString()
                        }

                        // Label
                        td {
                            input(type = InputType.text, classes = "edit-input cash-edit-label") {
                                value = entry.label
                                placeholder = "Label"
                            }
                        }

                        // Ref toggle
                        td {
                            label(classes = "cash-ref-toggle-label") {
                                input(type = InputType.checkBox, classes = "cash-edit-is-ref") {
                                    checked = isRef
                                }
                                +"Ref"
                            }
                        }

                        // Normal fields (currency + amount) — hidden when isRef
                        td(classes = "cash-normal-fields") {
                            if (isRef) style = "display:none"
                            input(type = InputType.text, classes = "edit-input cash-edit-currency") {
                                value = if (isRef) "" else entry.currency
                                placeholder = "USD"
                                attributes["autocomplete"] = "off"
                            }
                            input(type = InputType.number, classes = "edit-input cash-edit-amount") {
                                value = if (isRef) "" else entry.amount.toString()
                                placeholder = "0"
                                attributes["step"] = "any"
                            }
                        }

                        // Ref fields (portfolio select + sign) — hidden when not isRef
                        td(classes = "cash-ref-fields") {
                            if (!isRef) style = "display:none"
                            select(classes = "cash-edit-portfolio-ref") {
                                if (isRef && entry.portfolioRef == null) {
                                    option {
                                        attributes["value"] = ""
                                        attributes["disabled"] = "disabled"
                                        attributes["selected"] = "selected"
                                        +"— (portfolio deleted) —"
                                    }
                                }
                                for (p in allPortfolios) {
                                    option {
                                        attributes["value"] = p.slug
                                        if (p.slug == entry.portfolioRef) selected = true
                                        +p.name
                                    }
                                }
                            }
                            input(type = InputType.number, classes = "edit-input cash-edit-multiplier") {
                                value = if (isRef) entry.amount.toString() else "1"
                                placeholder = "1"
                                attributes["step"] = "any"
                            }
                        }

                        // Margin toggle
                        td {
                            label(classes = "cash-margin-toggle") {
                                input(type = InputType.checkBox, classes = "cash-edit-margin") {
                                    checked = entry.marginFlag
                                }
                                +"M"
                            }
                        }

                        // Delete button
                        td {
                            button(classes = "delete-cash-btn") {
                                attributes["type"] = "button"
                                +"×"
                            }
                        }
                    }
                }
            }
        }
        p(classes = "edit-hint") { +"Label · Ref · Currency · Amount · Margin" }
        button(classes = "add-cash-btn") {
            attributes["type"] = "button"
            id = "add-cash-btn"
            +"+ Add Entry"
        }
    }
}
