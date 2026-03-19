package com.portfoliohelper.model

data class Stock(
    val label: String,
    val amount: Double,
    val markPrice: Double? = null,
    val lastClosePrice: Double? = null,
    val targetWeight: Double? = null,
    val isMarketClosed: Boolean = false,
    val lastNav: Double? = null,
    val letfComponents: List<Pair<Double, String>>? = null,
    val groups: List<Pair<Double, String>> = emptyList()
) {
    val value: Double?
        get() = when {
            markPrice != null -> markPrice * amount
            lastClosePrice != null -> lastClosePrice * amount
            else -> null
        }
}
