package com.portfoliohelper.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

internal data class MonteCarloSimplePortfolioRuntime(
    val tickers: List<String>,
    val targetWeightMap: Map<String, Double>,
    val returnIndexes: IntArray,
    val weights: DoubleArray,
    val rebalanceStrategy: RebalanceStrategy,
)

internal data class MonteCarloIndexedPath(val returnIndexes: IntArray)

internal object MonteCarloIndexedSimulation {
    fun simpleRuntimeForPortfolio(
        pConfig: PortfolioConfig,
        tickerIndex: Map<String, Int>
    ): MonteCarloSimplePortfolioRuntime {
        val (tickers, targetWeights) = pConfig.mergeWeights()
        return MonteCarloSimplePortfolioRuntime(
            tickers = tickers,
            targetWeightMap = targetWeights,
            returnIndexes = tickers.map { ticker ->
                tickerIndex[ticker] ?: error("Ticker '$ticker' missing from Monte Carlo return pool")
            }.toIntArray(),
            weights = tickers.map { targetWeights[it] ?: 0.0 }.toDoubleArray(),
            rebalanceStrategy = pConfig.rebalanceStrategy,
        )
    }

    fun assemblePath(
        rng: Random,
        targetDays: Int,
        minChunkDays: Int,
        maxChunkDays: Int,
        poolSize: Int
    ): MonteCarloIndexedPath {
        val path = IntArray(targetDays)
        var offset = 0
        var remaining = targetDays
        var firstChunk = true

        while (remaining > 0) {
            val chunkMax = minOf(maxChunkDays, remaining, poolSize - 1)
            val chunkMin = minChunkDays.coerceAtMost(chunkMax)
            val chunkDays = if (chunkMin >= chunkMax) chunkMax else rng.nextInt(chunkMin, chunkMax + 1)
            val startIdx = if (poolSize - chunkDays > 1) rng.nextInt(0, poolSize - chunkDays) else 0

            for (k in 0 until chunkDays) {
                path[offset++] = if (k == 0 && !firstChunk) -1 else startIdx + k
            }

            remaining -= chunkDays
            firstChunk = false
        }
        return MonteCarloIndexedPath(path)
    }

    fun toAssembledPath(
        path: MonteCarloIndexedPath,
        allTickers: List<String>,
        tickerReturnsByDay: Array<DoubleArray>,
        effrxDailyRates: DoubleArray
    ): List<AssembledDay> =
        List(path.returnIndexes.size) { dayIndex ->
            val returnIndex = path.returnIndexes[dayIndex]
            if (returnIndex < 0) {
                AssembledDay(emptyMap(), 0.0, true)
            } else {
                val returns = linkedMapOf<String, Double>()
                val dayReturns = tickerReturnsByDay[returnIndex]
                for (tickerIndex in allTickers.indices) {
                    returns[allTickers[tickerIndex]] = dayReturns[tickerIndex]
                }
                AssembledDay(returns, effrxDailyRates[returnIndex], false)
            }
        }

    fun simulate(
        runtime: MonteCarloSimplePortfolioRuntime,
        mc: MarginConfig?,
        path: MonteCarloIndexedPath,
        tickerReturnsByDay: Array<DoubleArray>,
        effrxDailyRates: DoubleArray,
        startingBalance: Double,
        cashflows: DoubleArray = DoubleArray(0),
        rebalanceFlags: BooleanArray = BooleanArray(0)
    ): DoubleArray =
        if (mc == null) {
            simulateNoMargin(runtime, path, tickerReturnsByDay, startingBalance, cashflows, rebalanceFlags)
        } else {
            simulateWithMargin(runtime, mc, path, tickerReturnsByDay, effrxDailyRates, startingBalance, cashflows, rebalanceFlags)
        }

    fun computeStats(
        values: DoubleArray,
        years: Double,
        rfAnnualized: Double,
        cashflows: DoubleArray = DoubleArray(0)
    ): PortfolioStats {
        if (values.size < 2) return PortfolioStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

        val cagr = cashflowAdjustedCagr(values, years, cashflows)

        var peak = values[0]
        var maxDD = 0.0
        for (v in values) {
            if (v > peak) peak = v
            if (peak > 0) maxDD = max(maxDD, 1.0 - v / peak)
        }

        var n = 0
        var mean = 0.0
        var m2 = 0.0
        for (i in 1 until values.size) {
            val prev = values[i - 1]
            if (prev <= 0.0) continue
            val r = (values[i] - cashflows.getOrElse(i) { 0.0 }) / prev - 1.0
            n++
            val delta = r - mean
            mean += delta / n
            m2 += delta * (r - mean)
        }
        val stdDev = if (n > 1) sqrt(m2 / (n - 1)) else 0.0
        val annualVolatility = stdDev * sqrt(252.0)
        val sharpe = if (stdDev > 0) (cagr - rfAnnualized) / annualVolatility else 0.0

        var peakUI = values[0]
        var sumSq = 0.0
        var count = 0
        for (v in values) {
            if (v > peakUI) peakUI = v
            if (peakUI > 0) {
                val dd = 1.0 - v / peakUI
                sumSq += dd * dd
                count++
            }
        }
        val ulcerIndex = if (count > 0) sqrt(sumSq / count) else 0.0
        val upi = if (ulcerIndex > 0) (cagr - rfAnnualized) / ulcerIndex else 0.0

        var peakLD = values[0]
        var ddLen = 0
        var longestDD = 0
        for (v in values) {
            if (v >= peakLD) {
                peakLD = v
                longestDD = max(longestDD, ddLen)
                ddLen = 0
            } else {
                ddLen++
            }
        }
        longestDD = max(longestDD, ddLen)

        return PortfolioStats(cagr, maxDD, sharpe, ulcerIndex, upi, annualVolatility, longestDD)
    }

    private fun cashflowAdjustedCagr(values: DoubleArray, years: Double, cashflows: DoubleArray): Double {
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

    private fun simulateNoMargin(
        runtime: MonteCarloSimplePortfolioRuntime,
        path: MonteCarloIndexedPath,
        tickerReturnsByDay: Array<DoubleArray>,
        startingBalance: Double,
        cashflows: DoubleArray,
        rebalanceFlags: BooleanArray
    ): DoubleArray {
        val holdings = DoubleArray(runtime.tickers.size) { startingBalance * runtime.weights[it] }
        val values = DoubleArray(path.returnIndexes.size + 1)
        values[0] = startingBalance
        var totalHoldings = startingBalance

        for (dayIndex in path.returnIndexes.indices) {
            val returnIndex = path.returnIndexes[dayIndex]
            if (returnIndex >= 0) {
                if (rebalanceFlags.getOrElse(dayIndex + 1) { false }) {
                    for (i in holdings.indices) holdings[i] = totalHoldings * runtime.weights[i]
                }
            }

            var nextTotal = 0.0
            if (returnIndex >= 0) {
                val dayReturns = tickerReturnsByDay[returnIndex]
                for (i in holdings.indices) {
                    val nextHolding = holdings[i] * dayReturns[runtime.returnIndexes[i]]
                    holdings[i] = nextHolding
                    nextTotal += nextHolding
                }
            } else {
                for (holding in holdings) nextTotal += holding
            }

            val cashflowAmount = cashflows.getOrElse(dayIndex + 1) { 0.0 }
            if (cashflowAmount != 0.0) {
                for (i in holdings.indices) {
                    val addition = cashflowAmount * runtime.weights[i]
                    holdings[i] += addition
                    nextTotal += addition
                }
            }
            totalHoldings = nextTotal
            values[dayIndex + 1] = totalHoldings
        }
        return values
    }

    private fun simulateWithMargin(
        runtime: MonteCarloSimplePortfolioRuntime,
        mc: MarginConfig,
        path: MonteCarloIndexedPath,
        tickerReturnsByDay: Array<DoubleArray>,
        effrxDailyRates: DoubleArray,
        startingBalance: Double,
        cashflows: DoubleArray,
        rebalanceFlags: BooleanArray
    ): DoubleArray {
        var borrowed = startingBalance * mc.marginRatio
        val holdings = DoubleArray(runtime.tickers.size) { (startingBalance + borrowed) * runtime.weights[it] }
        var totalHoldings = startingBalance + borrowed
        val values = DoubleArray(path.returnIndexes.size + 1)
        values[0] = startingBalance
        val dailySpread = mc.marginSpread / 252.0
        val isDailyMode = mc.upperRebalanceMode == MarginRebalanceMode.DAILY.name

        for (dayIndex in path.returnIndexes.indices) {
            val returnIndex = path.returnIndexes[dayIndex]
            if (returnIndex >= 0) {
                if (rebalanceFlags.getOrElse(dayIndex + 1) { false }) {
                    val currentEquity = totalHoldings - borrowed
                    borrowed = currentEquity * mc.marginRatio
                    totalHoldings = currentEquity + borrowed
                    for (i in holdings.indices) holdings[i] = totalHoldings * runtime.weights[i]
                }
            }

            var nextTotal = 0.0
            if (returnIndex >= 0) {
                val dayReturns = tickerReturnsByDay[returnIndex]
                for (i in holdings.indices) {
                    val nextHolding = holdings[i] * dayReturns[runtime.returnIndexes[i]]
                    holdings[i] = nextHolding
                    nextTotal += nextHolding
                }
            } else {
                for (holding in holdings) nextTotal += holding
            }
            totalHoldings = nextTotal

            val cashflowAmount = cashflows.getOrElse(dayIndex + 1) { 0.0 }
            if (cashflowAmount != 0.0) {
                val contributionExposure = cashflowAmount * (1.0 + mc.marginRatio)
                borrowed += cashflowAmount * mc.marginRatio
                for (i in holdings.indices) {
                    val addition = contributionExposure * runtime.weights[i]
                    holdings[i] += addition
                    totalHoldings += addition
                }
            }

            val dailyLoanRate = (if (returnIndex >= 0) effrxDailyRates[returnIndex] else 0.0) + dailySpread
            borrowed *= (1.0 + dailyLoanRate)

            val equity = totalHoldings - borrowed
            if (isDailyMode) {
                val newBorrowed = equity * mc.marginRatio
                val delta = newBorrowed - borrowed
                if (totalHoldings != 0.0) {
                    val scale = 1.0 + delta / totalHoldings
                    for (i in holdings.indices) holdings[i] *= scale
                }
                totalHoldings += delta
                borrowed = newBorrowed
            } else {
                val currentMarginRatio = if (equity != 0.0) borrowed / equity else mc.marginRatio
                val upperBreach = currentMarginRatio > mc.marginRatio + mc.marginDeviationUpper
                val lowerBreach = currentMarginRatio < mc.marginRatio - mc.marginDeviationLower
                if (upperBreach || lowerBreach) {
                    val newBorrowed = equity * mc.marginRatio
                    val mode = if (upperBreach) mc.upperRebalanceMode else mc.lowerRebalanceMode
                    applyAllocationMode(runtime, holdings, newBorrowed - borrowed, mode)
                    totalHoldings = holdings.sum()
                    borrowed = newBorrowed
                }
            }

            values[dayIndex + 1] = totalHoldings - borrowed
        }
        return values
    }

    private fun applyAllocationMode(
        runtime: MonteCarloSimplePortfolioRuntime,
        holdings: DoubleArray,
        delta: Double,
        mode: String
    ) {
        if (HybridAllocStrategyRegistry.find(mode) != null) {
            applyAllocationModeFallback(runtime, holdings, delta, mode)
            return
        }

        when (HybridAllocStrategyRegistry.baseMode(mode) ?: MarginRebalanceMode.PROPORTIONAL) {
            MarginRebalanceMode.PROPORTIONAL,
            MarginRebalanceMode.DAILY -> {
                for (i in holdings.indices) holdings[i] += delta * runtime.weights[i]
            }

            MarginRebalanceMode.CURRENT_WEIGHT -> {
                val total = holdings.sum()
                if (total != 0.0) {
                    for (i in holdings.indices) holdings[i] += delta * (holdings[i] / total)
                }
            }

            MarginRebalanceMode.FULL_REBALANCE -> {
                val finalTotal = holdings.sum() + delta
                for (i in holdings.indices) holdings[i] = finalTotal * runtime.weights[i]
            }

            MarginRebalanceMode.UNDERVALUED_PRIORITY,
            MarginRebalanceMode.WATERFALL,
            MarginRebalanceMode.HYBRID_WATERFALL_FULL_REBALANCE ->
                applyAllocationModeFallback(runtime, holdings, delta, mode)
        }
    }

    private fun applyAllocationModeFallback(
        runtime: MonteCarloSimplePortfolioRuntime,
        holdings: DoubleArray,
        delta: Double,
        mode: String
    ) {
        val holdingMap = linkedMapOf<String, Double>()
        for (i in runtime.tickers.indices) holdingMap[runtime.tickers[i]] = holdings[i]
        BacktestService.applyAllocationMode(runtime.tickers, holdingMap, runtime.targetWeightMap, delta, mode)
        for (i in runtime.tickers.indices) holdings[i] = holdingMap[runtime.tickers[i]] ?: 0.0
    }

}
