package com.portfoliohelper.model

data class CashEntry(
    val label: String,
    val currency: String,       // "P" for portfolio-reference entries
    val marginFlag: Boolean,
    val equityFlag: Boolean,    // .E flag — adds USD value to margin denominator
    val amount: Double,         // For P entries: the sign multiplier (+1.0 or -1.0)
    val portfolioRef: String? = null,  // lowercase portfolio id for P entries
) {
    val key: String get() = buildString {
        append(label).append(".").append(currency)
        if (marginFlag) append(".M")
        if (equityFlag) append(".E")
    }
}
