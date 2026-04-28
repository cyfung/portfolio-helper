package com.portfoliohelper.service

import kotlinx.serialization.Serializable

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
    val marginStrategies: List<MarginConfig>,  // empty = base curve only
    val includeNoMargin: Boolean = true
)

enum class CashflowFrequency { NONE, MONTHLY, QUARTERLY, YEARLY }

data class CashflowConfig(
    val amount: Double,
    val frequency: CashflowFrequency
)

data class MultiBacktestRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolios: List<PortfolioConfig>,  // 1–3
    val cashflow: CashflowConfig? = null
)

@Serializable
data class DataPoint(val date: String, val value: Double)

@Serializable
data class BacktestStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double,
    val annualVolatility: Double,
    val longestDrawdownDays: Int,
    val endingValue: Double,
    val marginUpperTriggers: Int? = null,   // deviation breach above target (market fell, leverage too high)
    val marginLowerTriggers: Int? = null    // deviation breach below target (market rose, leverage too low)
)

@Serializable
data class CurveResult(
    val label: String,
    val points: List<DataPoint>,
    val stats: BacktestStats,
    val marginPoints: List<DataPoint>? = null
)

@Serializable
data class PortfolioResult(
    val label: String,
    val curves: List<CurveResult>  // index 0 = no-margin; rest = margin variants
)

@Serializable
data class MultiBacktestResult(
    val portfolios: List<PortfolioResult>
)

/** Merges duplicate tickers by summing weights, then normalises to sum-to-1. */
fun PortfolioConfig.mergeWeights(): Pair<List<String>, Map<String, Double>> {
    val totalWeight = tickers.sumOf { it.weight }
    val merged = mutableMapOf<String, Double>()
    for (tw in tickers) merged[tw.ticker] = (merged[tw.ticker] ?: 0.0) + tw.weight
    val tickerList = merged.keys.toList()
    val targetWeights = merged.mapValues { (_, w) -> w / totalWeight }
    return tickerList to targetWeights
}
