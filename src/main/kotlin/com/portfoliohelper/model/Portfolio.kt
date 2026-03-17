package com.portfoliohelper.model

data class Portfolio(
    val stocks: List<Stock>
) {
    val stockGrossValue: Double get() = stocks.mapNotNull { it.value }.sum()
}
