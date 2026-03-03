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
                    tr {
                        attributes["data-cash-edit-row"] = "true"
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
                    }
                }
            }
        }
        button(classes = "add-cash-btn") {
            attributes["type"] = "button"
            id = "add-cash-btn"
            +"+ Add Entry"
        }
    }
}
