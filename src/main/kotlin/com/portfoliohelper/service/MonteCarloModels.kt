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
    val cashflow: CashflowConfig? = null,
    val startingBalance: Double = 10_000.0,
    val seed: Long? = null,
    val betaReferenceTicker: String? = "SPY",
)

@Serializable
data class MonteCarloProgressDetail(
    val label: String,
    val value: String
)

@Serializable
data class MonteCarloProgress(
    val phase: String,
    val phaseLabel: String,
    val action: String,
    val progressLabel: String = "Progress",
    val completed: Int = 0,
    val total: Int = 0,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val details: List<MonteCarloProgressDetail> = emptyList(),
    val done: Boolean = false
) {
    companion object {
        fun idle() = MonteCarloProgress(
            phase = "idle",
            phaseLabel = "Idle",
            action = "Waiting to run",
        )
    }
}

@Serializable
data class MonteCarloRunState(
    val progress: MonteCarloProgress,
    val result: MonteCarloResult? = null,
    val error: String? = null
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
    val longestDrawdownDays: Int,
    val sortino: Double,
    val averageDrawdown: Double,
    val calmar: Double,
    val beta: Double
)

@Serializable
data class MonteCarloCurveResult(
    val label: String,
    val percentilePaths: List<MonteCarloPercentilePath>,  // CAGR-sorted, full paths
    val maxDdPercentiles: List<Double>,           // MaxDD-sorted, raw drawdown values
    val sharpePercentiles: List<Double>,          // Sharpe-sorted
    val sortinoPercentiles: List<Double>,
    val ulcerPercentiles: List<Double>,           // UlcerIndex-sorted (lower=better → inverted sort)
    val upiPercentiles: List<Double>,             // UPI-sorted
    val averageDrawdownPercentiles: List<Double>,
    val calmarPercentiles: List<Double>,
    val betaPercentiles: List<Double>,
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
    val longestDrawdownDays: Int,
    val sortino: Double,
    val averageDrawdown: Double,
    val calmar: Double,
    val beta: Double
)

internal data class AssembledDay(
    val tickerReturns: Map<String, Double>,  // empty on boundary days
    val effrxRate: Double,                   // daily EFFRX return (cur/prev - 1)
    val isChunkBoundary: Boolean             // first day of chunk 2+ → no market move
)
