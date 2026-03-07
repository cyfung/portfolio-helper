package com.portfoliohelper.web

import com.portfoliohelper.model.CashEntry
import kotlinx.html.*

internal fun FlowContent.buildCashEditTable(sortedEntries: List<CashEntry>) {
    div(classes = "cash-edit-table-wrapper") {
        table(classes = "cash-edit-table") {
            tbody {
                for (entry in sortedEntries) {
                    val valueStr = if (entry.portfolioRef != null) {
                        if (entry.amount < 0) "-${entry.portfolioRef}" else entry.portfolioRef
                    } else {
                        entry.amount.toString()
                    }
                    val entryType = when {
                        entry.marginFlag -> "margin"
                        entry.portfolioRef != null -> "ref"
                        else -> "normal"
                    }
                    val badgeText = when (entryType) {
                        "margin" -> "M"
                        "ref" -> "\u2197"
                        else -> ""
                    }
                    tr {
                        attributes["data-cash-edit-row"] = "true"
                        attributes["data-entry-type"] = entryType
                        td {
                            input(type = InputType.text, classes = "edit-input cash-edit-key") {
                                attributes["data-original-key"] = entry.key
                                attributes["data-column"] = "cash-key"
                                value = entry.key
                            }
                        }
                        td {
                            input(type = InputType.text, classes = "edit-input cash-edit-value") {
                                attributes["data-original-value"] = valueStr
                                attributes["data-column"] = "cash-value"
                                value = valueStr
                            }
                        }
                        td {
                            button(classes = "delete-cash-btn") {
                                attributes["type"] = "button"
                                +"×"
                            }
                        }
                        td(classes = "cash-type-badge-cell") {
                            span(classes = "cash-type-badge") { +badgeText }
                        }
                    }
                }
            }
        }
        p(classes = "edit-hint") { +"Format: Label.CCY[.M] or Label.P=id · Paste rows (Ctrl+V) supported" }
        button(classes = "add-cash-btn") {
            attributes["type"] = "button"
            id = "add-cash-btn"
            +"+ Add Entry"
        }
    }
}
