package com.portfoliohelper.service

// ── Domain types ──────────────────────────────────────────────────────────────

enum class RebalanceStrategy { NONE, DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY }

enum class MarginRebalanceMode { CURRENT_WEIGHT, PROPORTIONAL, FULL_REBALANCE, UNDERVALUED_PRIORITY, WATERFALL, DAILY }

data class TickerWeight(val ticker: String, val weight: Double)

data class LETFComponent(val ticker: String, val multiplier: Double)

data class LETFDefinition(
    val components: List<LETFComponent>,
    val spread: Double,                                                       // annual fraction, default 0.015
    val rebalanceStrategy: RebalanceStrategy = RebalanceStrategy.QUARTERLY,  // default Q
    val expenseRatio: Double = 0.0                                           // annual fraction, e.g. 0.02 = 2%
) {
    val totalMultiplier: Double get() = components.sumOf { it.multiplier }
    val borrowedRatio: Double get() = totalMultiplier - 1.0
}

data class MarginConfig(
    val marginRatio: Double,        // e.g. 0.5 = 50% borrow-to-equity
    val marginSpread: Double,       // annualised fraction e.g. 0.015
    val marginDeviationUpper: Double, // upper breach threshold e.g. 0.05
    val marginDeviationLower: Double, // lower breach threshold e.g. 0.05
    val upperRebalanceMode: MarginRebalanceMode = MarginRebalanceMode.PROPORTIONAL,
    val lowerRebalanceMode: MarginRebalanceMode = MarginRebalanceMode.PROPORTIONAL
)

data class PortfolioConfig(
    val label: String,
    val tickers: List<TickerWeight>,
    val rebalanceStrategy: RebalanceStrategy,
    val marginStrategies: List<MarginConfig>  // empty = base curve only
)

data class MultiBacktestRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolios: List<PortfolioConfig>  // 1–3
)

data class DataPoint(val date: String, val value: Double)

data class BacktestStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double,
    val endingValue: Double,
    val marginUpperTriggers: Int? = null,   // deviation breach above target (market fell, leverage too high)
    val marginLowerTriggers: Int? = null    // deviation breach below target (market rose, leverage too low)
)

data class CurveResult(
    val label: String,
    val points: List<DataPoint>,
    val stats: BacktestStats
)

data class PortfolioResult(
    val label: String,
    val curves: List<CurveResult>  // index 0 = no-margin; rest = margin variants
)

data class MultiBacktestResult(
    val portfolios: List<PortfolioResult>
)
