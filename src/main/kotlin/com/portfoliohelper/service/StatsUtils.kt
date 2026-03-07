package com.portfoliohelper.service

import kotlin.math.*

/** Standard portfolio statistics computed from a daily value series. */
data class PortfolioStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double
)

/**
 * Compute standard portfolio statistics from a daily value series.
 *
 * @param values       daily portfolio values (any starting level, length ≥ 2)
 * @param years        length of the series in calendar years
 * @param rfAnnualized annualised risk-free rate as a decimal (e.g. 0.05 = 5%)
 */
fun computeStats(values: List<Double>, years: Double, rfAnnualized: Double): PortfolioStats {
    if (values.size < 2) return PortfolioStats(0.0, 0.0, 0.0, 0.0, 0.0)

    val cagr = if (years > 0 && values.last() > 0)
        (values.last() / values.first()).pow(1.0 / years) - 1.0 else 0.0

    // Max drawdown
    var peak = values[0]; var maxDD = 0.0
    for (v in values) {
        if (v > peak) peak = v
        if (peak > 0) maxDD = max(maxDD, 1.0 - v / peak)
    }

    // Sharpe: daily simple returns, Welford's online variance → annualised
    var n = 0; var mean = 0.0; var m2 = 0.0
    for (i in 1 until values.size) {
        val prev = values[i - 1]; if (prev <= 0.0) continue
        val r = values[i] / prev - 1.0
        n++; val delta = r - mean; mean += delta / n; m2 += delta * (r - mean)
    }
    val stdDev = if (n > 1) sqrt(m2 / (n - 1)) else 0.0
    val sharpe = if (stdDev > 0) (cagr - rfAnnualized) / (stdDev * sqrt(252.0)) else 0.0

    // Ulcer Index: RMS of drawdowns from running peak
    var peakUI = values[0]; var sumSq = 0.0; var count = 0
    for (v in values) {
        if (v > peakUI) peakUI = v
        if (peakUI > 0) { val dd = 1.0 - v / peakUI; sumSq += dd * dd; count++ }
    }
    val ulcerIndex = if (count > 0) sqrt(sumSq / count) else 0.0
    val upi = if (ulcerIndex > 0) (cagr - rfAnnualized) / ulcerIndex else 0.0

    return PortfolioStats(cagr, maxDD, sharpe, ulcerIndex, upi)
}
