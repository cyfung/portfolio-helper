package com.portfoliohelper.service

// ── Request / Response types ──────────────────────────────────────────────────

data class MonteCarloRequest(
    val fromDate: String?,
    val toDate: String?,
    val minChunkYears: Double,
    val maxChunkYears: Double,
    val simulatedYears: Int,
    val numSimulations: Int,
    val portfolios: List<PortfolioConfig>,
    val sortMetric: String = "END_VALUE"  // END_VALUE, CAGR, MAX_DD, SHARPE, ULCER_INDEX, UPI
)

data class MonteCarloPercentilePath(
    val percentile: Int,       // 5, 10, 25, 50, 75, 90, 95
    val points: List<Double>,  // full-resolution values, starts at 10 000
    val endValue: Double,
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double
)

data class MonteCarloCurveResult(
    val label: String,
    val percentilePaths: List<MonteCarloPercentilePath>
)

data class MonteCarloPortfolioResult(
    val label: String,
    val curves: List<MonteCarloCurveResult>  // [0] = no-margin, rest = margin variants
)

data class MonteCarloResult(
    val simulatedYears: Int,
    val numSimulations: Int,
    val portfolios: List<MonteCarloPortfolioResult>
)

// ── Internal only ─────────────────────────────────────────────────────────────

internal data class AssembledDay(
    val tickerReturns: Map<String, Double>,  // empty on boundary days
    val effrxRate: Double,                   // daily EFFRX return (cur/prev - 1)
    val isChunkBoundary: Boolean             // first day of chunk 2+ → no market move
)
