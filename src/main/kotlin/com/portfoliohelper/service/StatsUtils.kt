package com.portfoliohelper.service

import kotlin.math.*

/** Standard portfolio statistics computed from a daily value series. */
data class PortfolioStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double,
    val annualVolatility: Double,
    val longestDrawdownDays: Int,   // trading days below previous peak (peak-to-recovery)
    val sortino: Double = 0.0,
    val averageDrawdown: Double = 0.0,
    val calmar: Double = 0.0,
    val beta: Double = 0.0
)

/**
 * Compute standard portfolio statistics from a daily value series.
 *
 * @param values       daily portfolio values (any starting level, length ≥ 2)
 * @param years        length of the series in calendar years
 * @param rfAnnualized annualised risk-free rate as a decimal (e.g. 0.05 = 5%)
 * @param cashflows    external cashflows aligned to [values]; positive means client contribution,
 *                     negative means withdrawal, and the flow is already included in that day's value
 */
fun computeStats(
    values: List<Double>,
    years: Double,
    rfAnnualized: Double,
    cashflows: List<Double> = emptyList(),
    benchmarkValues: List<Double> = emptyList()
): PortfolioStats {
    if (values.size < 2) return PortfolioStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

    val cagr = cashflowAdjustedCagr(values, years, cashflows)

    // Max drawdown
    var peak = values[0]; var maxDD = 0.0; var drawdownSum = 0.0; var drawdownCount = 0
    for (v in values) {
        if (v > peak) peak = v
        if (peak > 0) {
            val dd = 1.0 - v / peak
            maxDD = max(maxDD, dd)
            drawdownSum += dd.coerceAtLeast(0.0)
            drawdownCount++
        }
    }
    val averageDrawdown = if (drawdownCount > 0) drawdownSum / drawdownCount else 0.0
    val calmar = if (maxDD > 0.0) cagr / maxDD else 0.0

    // Sharpe: daily simple returns, Welford's online variance → annualised
    var n = 0; var mean = 0.0; var m2 = 0.0
    val dailyReturns = mutableListOf<Double>()
    for (i in 1 until values.size) {
        val prev = values[i - 1]; if (prev <= 0.0) continue
        val r = (values[i] - cashflows.getOrElse(i) { 0.0 }) / prev - 1.0
        dailyReturns += r
        n++; val delta = r - mean; mean += delta / n; m2 += delta * (r - mean)
    }
    val stdDev = if (n > 1) sqrt(m2 / (n - 1)) else 0.0
    val annualVolatility = stdDev * sqrt(252.0)
    val sharpe = if (stdDev > 0) (cagr - rfAnnualized) / annualVolatility else 0.0
    val rfDaily = rfAnnualized / 252.0
    val downsideMeanSquare =
        if (dailyReturns.isNotEmpty()) {
            dailyReturns.sumOf { min(0.0, it - rfDaily).pow(2) } / dailyReturns.size
        } else {
            0.0
        }
    val downsideDeviation = sqrt(downsideMeanSquare) * sqrt(252.0)
    val sortino = if (downsideDeviation > 0.0) (cagr - rfAnnualized) / downsideDeviation else 0.0
    val beta = computeBeta(values, cashflows, benchmarkValues)

    // Ulcer Index: RMS of drawdowns from running peak
    var peakUI = values[0]; var sumSq = 0.0; var count = 0
    for (v in values) {
        if (v > peakUI) peakUI = v
        if (peakUI > 0) { val dd = 1.0 - v / peakUI; sumSq += dd * dd; count++ }
    }
    val ulcerIndex = if (count > 0) sqrt(sumSq / count) else 0.0
    val upi = if (ulcerIndex > 0) (cagr - rfAnnualized) / ulcerIndex else 0.0

    // Longest drawdown: max consecutive trading days below the running peak
    var peakLD = values[0]; var ddLen = 0; var longestDD = 0
    for (v in values) {
        if (v >= peakLD) { peakLD = v; longestDD = max(longestDD, ddLen); ddLen = 0 }
        else ddLen++
    }
    longestDD = max(longestDD, ddLen)  // still in drawdown at end of series

    return PortfolioStats(cagr, maxDD, sharpe, ulcerIndex, upi, annualVolatility, longestDD, sortino, averageDrawdown, calmar, beta)
}

private fun computeBeta(
    values: List<Double>,
    cashflows: List<Double>,
    benchmarkValues: List<Double>
): Double {
    if (benchmarkValues.size < 2) return 0.0
    val portfolioReturns = mutableListOf<Double>()
    val benchmarkReturns = mutableListOf<Double>()
    val lastIndex = minOf(values.lastIndex, benchmarkValues.lastIndex)
    for (i in 1..lastIndex) {
        val prev = values[i - 1]
        val bPrev = benchmarkValues[i - 1]
        val bCur = benchmarkValues[i]
        if (prev <= 0.0 || bPrev <= 0.0 || !prev.isFinite() || !values[i].isFinite() || !bPrev.isFinite() || !bCur.isFinite()) continue
        portfolioReturns += (values[i] - cashflows.getOrElse(i) { 0.0 }) / prev - 1.0
        benchmarkReturns += bCur / bPrev - 1.0
    }
    if (portfolioReturns.size < 2) return 0.0
    val portfolioMean = portfolioReturns.average()
    val benchmarkMean = benchmarkReturns.average()
    var covariance = 0.0
    var variance = 0.0
    for (i in portfolioReturns.indices) {
        val benchmarkDelta = benchmarkReturns[i] - benchmarkMean
        covariance += (portfolioReturns[i] - portfolioMean) * benchmarkDelta
        variance += benchmarkDelta * benchmarkDelta
    }
    return if (variance > 0.0) covariance / variance else 0.0
}

private fun cashflowAdjustedCagr(values: List<Double>, years: Double, cashflows: List<Double>): Double {
    if (years <= 0.0 || values.first() <= 0.0) return 0.0
    val hasCashflows = cashflows.any { it.isFinite() && abs(it) > 1e-9 }
    if (!hasCashflows) {
        return if (values.last() > 0.0) (values.last() / values.first()).pow(1.0 / years) - 1.0 else 0.0
    }

    val signedFlows = mutableListOf<Pair<Double, Double>>()
    signedFlows += 0.0 to -values.first()
    val lastIndex = values.lastIndex.coerceAtLeast(1)
    for (i in 1 until values.size) {
        val amount = cashflows.getOrElse(i) { 0.0 }
        if (amount.isFinite() && abs(amount) > 1e-9) {
            signedFlows += (years * i / lastIndex) to -amount
        }
    }
    signedFlows += years to values.last()

    fun npv(rate: Double): Double {
        val base = 1.0 + rate
        if (base <= 0.0) return Double.NaN
        return signedFlows.sumOf { (t, amount) -> amount / base.pow(t) }
    }

    var low = -0.999999999
    var high = 1.0
    var lowValue = npv(low)
    var highValue = npv(high)
    var bracketed = lowValue.isFinite() && highValue.isFinite() && lowValue * highValue <= 0.0
    repeat(80) {
        if (bracketed) return@repeat
        high = (high + 1.0) * 2.0 - 1.0
        highValue = npv(high)
        bracketed = lowValue.isFinite() && highValue.isFinite() && lowValue * highValue <= 0.0
    }
    if (!bracketed) return 0.0

    repeat(120) {
        val mid = (low + high) / 2.0
        val midValue = npv(mid)
        if (!midValue.isFinite()) {
            low = mid
            lowValue = midValue
        } else if (lowValue * midValue <= 0.0) {
            high = mid
            highValue = midValue
        } else {
            low = mid
            lowValue = midValue
        }
    }
    return (low + high) / 2.0
}
