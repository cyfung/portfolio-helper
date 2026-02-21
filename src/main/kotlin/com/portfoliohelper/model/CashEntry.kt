package com.portfoliohelper.model

data class CashEntry(val label: String, val currency: String, val marginFlag: Boolean, val amount: Double) {
    val key: String get() = if (marginFlag) "$label.$currency.M" else "$label.$currency"
}
