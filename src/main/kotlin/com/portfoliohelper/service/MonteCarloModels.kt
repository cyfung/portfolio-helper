package com.portfoliohelper.service

import kotlinx.serialization.Serializable

// ── Request / Response types ──────────────────────────────────────────────────

data class MonteCarloRequest(
    val fromDate: String?,
    val toDate: String?,
    val minChunkYears: Double,
    val maxChunkYears: Double,
    val simulatedYears: Int,
    val numSimulations: Int,
    val portfolios: List<PortfolioConfig>,
    val seed: Long? = null            // null = generate fresh random seed
)

@Serializable
data class MonteCarloPercentilePath(
    val percentile: Int,       // 5, 10, 25, 50, 75, 90, 95
    val points: List<Double>,  // full-resolution values, starts at 10 000
    val endValue: Double,
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double,
    val annualVolatility: Double,
    val longestDrawdownDays: Int
)

@Serializable
data class MonteCarloCurveResult(
    val label: String,
    val percentilePaths: List<MonteCarloPercentilePath>,  // CAGR-sorted, full paths
    val maxDdPercentiles: List<Double>,           // MaxDD-sorted, raw drawdown values
    val sharpePercentiles: List<Double>,          // Sharpe-sorted
    val ulcerPercentiles: List<Double>,           // UlcerIndex-sorted (lower=better → inverted sort)
    val upiPercentiles: List<Double>,             // UPI-sorted
    val volatilityPercentiles: List<Double>,      // Volatility-sorted (lower=better → inverted sort)
    val longestDrawdownPercentiles: List<Double>  // Longest drawdown (trading days), lower=better → inverted sort
)

@Serializable
data class MonteCarloPortfolioResult(
    val label: String,
    val curves: List<MonteCarloCurveResult>  // [0] = no-margin, rest = margin variants
)

@Serializable
data class MonteCarloResult(
    val simulatedYears: Int,
    val numSimulations: Int,
    val portfolios: List<MonteCarloPortfolioResult>,
    val seed: Long
)

// ── Internal only ─────────────────────────────────────────────────────────────

internal data class SimPassMetrics(
    val cagr: Double,
    val maxDD: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double,
    val volatility: Double,
    val longestDrawdownDays: Int
)

internal data class AssembledDay(
    val tickerReturns: Map<String, Double>,  // empty on boundary days
    val effrxRate: Double,                   // daily EFFRX return (cur/prev - 1)
    val isChunkBoundary: Boolean             // first day of chunk 2+ → no market move
)
