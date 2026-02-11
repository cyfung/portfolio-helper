package com.portfoliohelper.model

/**
 * Represents a portfolio of stock holdings.
 *
 * @property stocks List of stock holdings in the portfolio
 */
data class Portfolio(
    val stocks: List<Stock>
) {
    /**
     * Total value of all stock positions.
     * Sums only stocks with available market data (non-null values).
     */
    val totalValue: Double get() = stocks.mapNotNull { it.value }.sum()

    /**
     * Check if all stocks in the portfolio have market data.
     */
    val hasCompleteMarketData: Boolean get() = stocks.all { it.hasMarketData }

    /**
     * Count of stocks with at least some market data.
     */
    val stocksWithMarketData: Int get() = stocks.count { it.hasMarketData }

    /**
     * Total number of stock positions in the portfolio.
     */
    val totalStocks: Int get() = stocks.size

    /**
     * Get loading progress as a percentage (0.0 to 100.0).
     */
    val loadingProgress: Double get() = if (totalStocks > 0) {
        (stocksWithMarketData.toDouble() / totalStocks) * 100.0
    } else {
        100.0
    }

    /**
     * Portfolio daily change in dollars (sum of all position changes).
     */
    val dailyChangeDollars: Double get() = stocks.mapNotNull { it.positionChangeDollars }.sum()

    /**
     * Previous day's total portfolio value (based on last close prices).
     */
    val previousTotalValue: Double get() = stocks.mapNotNull { stock ->
        stock.lastClosePrice?.let { it * stock.amount }
    }.sum()

    /**
     * Portfolio daily change as a percentage.
     * Returns 0.0 if previousTotalValue is 0 or negative.
     */
    val dailyChangePercent: Double get() = when {
        previousTotalValue > 0 -> (dailyChangeDollars / previousTotalValue) * 100.0
        else -> 0.0
    }

    /**
     * Direction of portfolio daily change for CSS styling.
     * Returns "positive", "negative", or "neutral".
     */
    val dailyChangeDirection: String get() = when {
        dailyChangeDollars > 0 -> "positive"
        dailyChangeDollars < 0 -> "negative"
        else -> "neutral"
    }
}
