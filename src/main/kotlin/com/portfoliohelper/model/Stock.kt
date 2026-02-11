package com.portfoliohelper.model

/**
 * Represents a stock holding in the portfolio.
 *
 * @property label Stock ticker symbol (e.g., "AAPL")
 * @property amount Number of shares owned
 * @property markPrice Current market price from IB API (null until fetched)
 * @property lastClosePrice Previous day's closing price from IB API (null until fetched)
 * @property targetWeight Target allocation percentage for this stock (e.g., 9.5 for 9.5%)
 */
data class Stock(
    val label: String,
    val amount: Int,
    val markPrice: Double? = null,
    val lastClosePrice: Double? = null,
    val targetWeight: Double? = null
) {
    /**
     * Total value of this stock position.
     * Uses markPrice if available, falls back to lastClosePrice, null if neither available.
     */
    val value: Double? get() = when {
        markPrice != null -> markPrice * amount
        lastClosePrice != null -> lastClosePrice * amount
        else -> null
    }

    /**
     * Check if this stock has any market data.
     */
    val hasMarketData: Boolean get() = markPrice != null || lastClosePrice != null

    /**
     * Get the primary price for display (prefers mark price).
     */
    val displayPrice: Double? get() = markPrice ?: lastClosePrice

    /**
     * Daily price change in dollars (markPrice - lastClosePrice).
     * Null if either price is unavailable.
     */
    val priceChangeDollars: Double? get() = when {
        markPrice != null && lastClosePrice != null -> markPrice - lastClosePrice
        else -> null
    }

    /**
     * Daily price change as a percentage.
     * Null if either price is unavailable or lastClosePrice is zero.
     */
    val priceChangePercent: Double? get() = when {
        markPrice != null && lastClosePrice != null && lastClosePrice != 0.0 ->
            ((markPrice - lastClosePrice) / lastClosePrice) * 100.0
        else -> null
    }

    /**
     * Direction of price change for CSS styling.
     * Returns "positive", "negative", or "neutral".
     */
    val priceChangeDirection: String get() = when {
        priceChangeDollars == null -> "neutral"
        priceChangeDollars!! > 0 -> "positive"
        priceChangeDollars!! < 0 -> "negative"
        else -> "neutral"
    }

    /**
     * Total position daily change in dollars (priceChangeDollars * amount).
     * Null if priceChangeDollars is unavailable.
     */
    val positionChangeDollars: Double? get() = priceChangeDollars?.let { it * amount }

    /**
     * Calculate target position value based on target weight and portfolio total.
     * Returns null if targetWeight is not set or value is unavailable.
     */
    fun targetValue(portfolioTotal: Double): Double? {
        return if (targetWeight != null && portfolioTotal > 0) {
            (targetWeight!! / 100.0) * portfolioTotal
        } else null
    }

    /**
     * Calculate dollar amount to add/reduce to reach target weight.
     * Positive = need to buy more, Negative = need to sell/reduce.
     * Returns null if target weight not set or market data unavailable.
     */
    fun rebalanceDollars(portfolioTotal: Double): Double? {
        val target = targetValue(portfolioTotal) ?: return null
        val current = value ?: return null
        return target - current
    }

    /**
     * Calculate number of shares to buy/sell to reach target weight.
     * Positive = buy, Negative = sell.
     * Returns null if rebalancing amount unavailable or no mark price.
     */
    fun rebalanceShares(portfolioTotal: Double): Double? {
        val dollars = rebalanceDollars(portfolioTotal) ?: return null
        val price = markPrice ?: return null
        return if (price > 0) dollars / price else null
    }

    /**
     * Get CSS direction class for rebalancing action.
     * "positive" = need to buy, "negative" = need to sell, "neutral" = balanced.
     */
    fun rebalanceDirection(portfolioTotal: Double): String {
        val dollars = rebalanceDollars(portfolioTotal) ?: return "neutral"
        return when {
            dollars > 0.50 -> "positive"  // Need to buy (threshold: $0.50)
            dollars < -0.50 -> "negative" // Need to sell
            else -> "neutral"             // Effectively balanced
        }
    }
}
