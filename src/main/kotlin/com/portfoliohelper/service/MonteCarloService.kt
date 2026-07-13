package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream
import kotlin.math.pow
import kotlin.random.Random

object MonteCarloService {
    private val logger = LoggerFactory.getLogger(MonteCarloService::class.java)
    private val progressCompleted = AtomicInteger(0)
    private val progressTotal = AtomicInteger(0)

    fun getProgress(): Pair<Int, Int> = progressCompleted.get() to progressTotal.get()

    fun runMonteCarlo(request: MonteCarloRequest): MonteCarloResult {
        progressCompleted.set(0)
        progressTotal.set(request.numSimulations)
        val fromDate = request.fromDate?.let { LocalDate.parse(it) }
        val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        BacktestService.validateDateRange(fromDate, toDate)
        val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)
        val fullHistoryFrom = LocalDate.of(1990, 1, 1)
        val effrxSeries = BacktestService.loadEffrxSeries()
        val portfolios = request.portfolios.map { it.withoutPlaceholderTickers() }

        // ── Step 1: Parse LETF definitions ───────────────────────────────────
        val requestedTickers = portfolios
            .flatMap { it.tickers }
            .map { it.ticker }
            .plus(portfolios.flatMap { RebalanceStrategyService.requiredReferenceTickers(it.rebalanceStrategies) })
            .distinct()
        val hasPrependedChain = requestedTickers.any { BacktestService.parseTickerChain(it) != null }
        val seriesCache = BacktestService.resolveTickerSeries(
            requestedTickers,
            neededFromForTicker = { ticker ->
                if (BacktestService.parseTickerChain(ticker) != null) fullHistoryFrom else neededFrom
            },
            toDate = toDate,
            effrx = effrxSeries,
        )

        // ── Step 2: Load component ticker series ──────────────────────────────
        // ── Step 3: Compute preliminary dates for LETF simulation ─────────────
        // ── Step 4: Compute virtual LETF series ───────────────────────────────
        // ── Step 5: Load real (non-LETF) ticker series ────────────────────────
        // ── Step 6: Build pool date list ──────────────────────────────────────
        val allSeriesMaps = portfolios.map { pConfig ->
            val tickersForPool =
                (pConfig.tickers.map { it.ticker } +
                    RebalanceStrategyService.requiredReferenceTickers(pConfig.rebalanceStrategies))
                    .distinct()
            tickersForPool.associate { ticker ->
                ticker to (seriesCache[ticker]
                    ?: error("Series for '$ticker' not found in cache"))
            }
        }
        val poolDates = BacktestService.intersectDates(
            allSeriesMaps.flatMap { it.values }, if (hasPrependedChain) null else fromDate, toDate
        )
        val poolSize = poolDates.size

        val targetDays = request.simulatedYears * 252
        val minChunkDays = (request.minChunkYears * 252).toInt().coerceAtLeast(1)
        val maxChunkDays = (request.maxChunkYears * 252).toInt().coerceAtLeast(minChunkDays)

        // Validation: pool must be large enough relative to chunk size
        val minForValidation = minOf(minChunkDays, targetDays)
        if (poolSize < 2 * minForValidation) {
            val poolRangeDescription =
                if (hasPrependedChain) "the full prepended ticker history through the selected to date"
                else "the selected date range"
            throw IllegalStateException(
                "Historical pool too small: $poolSize trading days in $poolRangeDescription. " +
                "Need at least ${2 * minForValidation} days (2 × min(minChunk, simulated)). " +
                "Widen the date range or reduce minChunkYears / simulatedYears."
            )
        }

        // ── Pre-compute return multipliers ────────────────────────────────────
        val allTickers =
            (portfolios.flatMap { it.tickers.map { tw -> tw.ticker } } +
                portfolios.flatMap { RebalanceStrategyService.requiredReferenceTickers(it.rebalanceStrategies) })
                .toSet()

        // tickerReturnsByDay[i] = returns for day i (transition poolDates[i] → poolDates[i+1])
        // indexed 0..poolSize-2
        val tickerReturnsByDay: List<Map<String, Double>> = (0 until poolSize - 1).map { i ->
            allTickers.associateWith { ticker ->
                val s = seriesCache[ticker] ?: return@associateWith 1.0
                val prev = s[poolDates[i]] ?: return@associateWith 1.0
                val cur = s[poolDates[i + 1]] ?: return@associateWith 1.0
                if (prev == 0.0) 1.0 else cur / prev
            }
        }

        // effrxDailyRates[i] = effrx daily return for transition i → i+1
        val effrxDailyRates: List<Double> = (0 until poolSize - 1).map { i ->
            val prev = effrxSeries[poolDates[i]]
            val cur = effrxSeries[poolDates[i + 1]]
            if (prev != null && cur != null && prev != 0.0) cur / prev - 1.0 else 0.0
        }

        val masterSeed = request.seed ?: Random.nextLong()
        logger.info("MC seed: $masterSeed")

        val years = request.simulatedYears.toDouble()
        val avgRfDaily = if (effrxDailyRates.isNotEmpty()) effrxDailyRates.average() else 0.0
        val rfAnnualized = (1.0 + avgRfDaily).pow(252.0) - 1.0

        // ── Build curve configs for each portfolio ────────────────────────────
        data class CurveConfig(val label: String, val mc: MarginConfig?)
        data class PortfolioCurveConfig(
            val portfolio: PortfolioConfig,
            val simpleCurves: List<CurveConfig>,
            val strategyLabels: List<String>,
        ) {
            val allLabels: List<String> = simpleCurves.map { it.label } + strategyLabels
        }

        fun modeAbbr(m: String) = HybridAllocStrategyRegistry.modeLabel(m)
        fun strategyLabels(pConfig: PortfolioConfig): List<String> =
            pConfig.rebalanceStrategies
                .filter { it.enabled }
                .flatMap { strategy ->
                    listOf(strategy.label) +
                        strategy.derivedSubStrategies
                            .filter { it.enabled }
                            .map { derived -> "${strategy.label} / ${derived.label}" }
                }

        val portfolioCurveConfigs: List<PortfolioCurveConfig> =
            portfolios.map { pConfig ->
                val curves = mutableListOf<CurveConfig>()
                if (pConfig.includeNoMargin) curves.add(CurveConfig("No Margin", null))
                pConfig.marginStrategies.forEachIndexed { mIdx, mc ->
                    val uAbbr = modeAbbr(mc.upperRebalanceMode)
                    val lAbbr = modeAbbr(mc.lowerRebalanceMode)
                    val label = if (uAbbr == lAbbr) "Margin ${mIdx + 1} ($uAbbr)"
                    else "Margin ${mIdx + 1} ($uAbbr↑/$lAbbr↓)"
                    curves.add(CurveConfig(label, mc))
                }
                PortfolioCurveConfig(pConfig, curves, strategyLabels(pConfig))
            }

        val syntheticDates = syntheticTradingDates(targetDays + 1)

        fun simulateAttachedStrategies(
            pConfig: PortfolioConfig,
            path: List<AssembledDay>,
            startingBalance: Double,
            cashflow: CashflowConfig?
        ): List<CurveResult> {
            val seriesMap = syntheticSeriesMap(allTickers, path, syntheticDates)
            val syntheticEffrx = syntheticEffrxSeries(path, syntheticDates)
            val curves =
                RebalanceStrategyService.runAttachedStrategiesOnSeries(
                    pConfig,
                    cashflow,
                    pConfig.rebalanceStrategies,
                    seriesMap,
                    syntheticDates,
                    syntheticEffrx,
                    startingBalance,
                )
            val expected = strategyLabels(pConfig).size
            require(curves.size == expected) {
                "Expected $expected rebalance strategy curves for ${pConfig.label}, got ${curves.size}"
            }
            return curves
        }

        fun valuesForCurve(
            config: PortfolioCurveConfig,
            curveIndex: Int,
            path: List<AssembledDay>,
            startingBalance: Double,
            cashflow: CashflowConfig?
        ): List<Double> =
            if (curveIndex < config.simpleCurves.size) {
                simulate(config.portfolio, config.simpleCurves[curveIndex].mc, path, startingBalance, cashflow)
            } else {
                val strategyIndex = curveIndex - config.simpleCurves.size
                simulateAttachedStrategies(config.portfolio, path, startingBalance, cashflow)[strategyIndex]
                    .points
                    .map { it.value }
            }

        val percentiles = listOf(5, 10, 25, 50, 75, 90, 95)
        val numSims = request.numSimulations

        // ── Pass 1: run all simulations, record metrics ───────────────────────
        logger.info("MC Pass 1: $numSims simulations × ${portfolioCurveConfigs.sumOf { it.allLabels.size }} curves")

        // allMetrics[pi][ci][simIdx]
        val zero = SimPassMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val allMetrics = Array(portfolioCurveConfigs.size) { pi ->
            Array(portfolioCurveConfigs[pi].allLabels.size) { Array(numSims) { zero } }
        }

        IntStream.range(0, numSims).parallel().forEach { simIdx ->
            val rng = Random(masterSeed + simIdx)
            val path = assemblePath(rng, targetDays, minChunkDays, maxChunkDays, poolSize,
                tickerReturnsByDay, effrxDailyRates)

            portfolioCurveConfigs.forEachIndexed { pi, config ->
                var ci = 0
                config.simpleCurves.forEach { curveConfig ->
                    val values = simulate(config.portfolio, curveConfig.mc, path, request.startingBalance, request.cashflow)
                    val stats = computeStats(values, years, rfAnnualized)
                    allMetrics[pi][ci][simIdx] = SimPassMetrics(stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                    ci++
                }
                if (config.strategyLabels.isNotEmpty()) {
                    val strategyCurves = simulateAttachedStrategies(config.portfolio, path, request.startingBalance, request.cashflow)
                    strategyCurves.forEach { curve ->
                        val values = curve.points.map { it.value }
                        val stats = computeStats(values, years, rfAnnualized)
                        allMetrics[pi][ci][simIdx] = SimPassMetrics(stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                        ci++
                    }
                }
            }
            progressCompleted.incrementAndGet()
        }

        // ── Identify percentile sim indices (CAGR-sorted, for pass 2) ─────────
        // pctSimIndices[pi][ci][pctIdx] = simIdx
        val pctIdxList = percentiles.map { pct ->
            (pct.toDouble() / 100.0 * (numSims - 1)).toInt().coerceIn(0, numSims - 1)
        }
        val pctSimIndices = allMetrics.map { portfolioMetrics ->
            portfolioMetrics.map { simMetrics ->
                val sortedByCagr = (0 until numSims).sortedBy { simMetrics[it].cagr }
                pctIdxList.map { sortedByCagr[it] }
            }
        }

        // ── Per-metric independent percentile values ───────────────────────────
        val maxDdPctValues   = metricPercentiles(allMetrics, numSims, pctIdxList, descending = true)  { it.maxDD }
        val sharpePctValues  = metricPercentiles(allMetrics, numSims, pctIdxList) { it.sharpe }
        val ulcerPctValues   = metricPercentiles(allMetrics, numSims, pctIdxList, descending = true)  { it.ulcerIndex }
        val upiPctValues     = metricPercentiles(allMetrics, numSims, pctIdxList) { it.upi }
        val volPctValues     = metricPercentiles(allMetrics, numSims, pctIdxList, descending = true)  { it.volatility }
        val longestDdPctValues = metricPercentiles(allMetrics, numSims, pctIdxList, descending = true) { it.longestDrawdownDays.toDouble() }

        // ── Pass 2: re-run needed sims with full paths ────────────────────────
        val neededSimIndices = pctSimIndices.flatten().flatten().toSet()
        logger.info("MC Pass 2: ${neededSimIndices.size} unique sims for full paths")

        val fullPaths: Map<Int, List<AssembledDay>> = neededSimIndices.associateWith { simIdx ->
            val rng = Random(masterSeed + simIdx)
            assemblePath(rng, targetDays, minChunkDays, maxChunkDays, poolSize,
                tickerReturnsByDay, effrxDailyRates)
        }

        // ── Build final result ────────────────────────────────────────────────
        val portfolioResults = portfolioCurveConfigs.mapIndexed { pi, config ->
            val curveResults = config.allLabels.mapIndexed { ci, label ->
                val percentilePaths = percentiles.mapIndexed { pctIdx, pct ->
                    val simIdx = pctSimIndices[pi][ci][pctIdx]
                    val path = fullPaths[simIdx]!!
                    val values = valuesForCurve(config, ci, path, request.startingBalance, request.cashflow)
                    val endValue = values.last()
                    val stats = computeStats(values, years, rfAnnualized)
                    MonteCarloPercentilePath(pct, values, endValue, stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                }
                MonteCarloCurveResult(
                    label,
                    percentilePaths,
                    maxDdPctValues[pi][ci],
                    sharpePctValues[pi][ci],
                    ulcerPctValues[pi][ci],
                    upiPctValues[pi][ci],
                    volPctValues[pi][ci],
                    longestDdPctValues[pi][ci]
                )
            }
            MonteCarloPortfolioResult(config.portfolio.label, curveResults)
        }

        return MonteCarloResult(request.simulatedYears, numSims, portfolioResults, masterSeed)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun metricPercentiles(
        allMetrics: Array<Array<Array<SimPassMetrics>>>,
        numSims: Int,
        pctIdxList: List<Int>,
        descending: Boolean = false,
        selector: (SimPassMetrics) -> Double
    ): List<List<List<Double>>> = allMetrics.map { portMetrics ->
        portMetrics.map { simMetrics ->
            val sorted = (0 until numSims).sortedBy { if (descending) -selector(simMetrics[it]) else selector(simMetrics[it]) }
            pctIdxList.map { selector(simMetrics[sorted[it]]) }
        }
    }

    // ── Path assembly ─────────────────────────────────────────────────────────

    private fun syntheticTradingDates(count: Int): List<LocalDate> {
        val dates = ArrayList<LocalDate>(count)
        var date = LocalDate.of(2000, 1, 3)
        while (dates.size < count) {
            if (date.dayOfWeek.value <= 5) dates.add(date)
            date = date.plusDays(1)
        }
        return dates
    }

    private fun syntheticSeriesMap(
        tickers: Set<String>,
        path: List<AssembledDay>,
        dates: List<LocalDate>
    ): Map<String, Map<LocalDate, Double>> {
        require(dates.size == path.size + 1) { "Synthetic date count must equal path size + 1" }
        val values = tickers.associateWith { 100.0 }.toMutableMap()
        val series = tickers.associateWith { linkedMapOf(dates.first() to 100.0) }
        path.forEachIndexed { index, day ->
            val date = dates[index + 1]
            for (ticker in tickers) {
                val ret = if (day.isChunkBoundary) 1.0 else (day.tickerReturns[ticker] ?: 1.0)
                val next = (values[ticker] ?: 100.0) * ret
                values[ticker] = next
                series[ticker]?.put(date, next)
            }
        }
        return series
    }

    private fun syntheticEffrxSeries(path: List<AssembledDay>, dates: List<LocalDate>): Map<LocalDate, Double> {
        require(dates.size == path.size + 1) { "Synthetic date count must equal path size + 1" }
        val series = linkedMapOf(dates.first() to 100.0)
        var value = 100.0
        path.forEachIndexed { index, day ->
            value *= 1.0 + if (day.isChunkBoundary) 0.0 else day.effrxRate
            series[dates[index + 1]] = value
        }
        return series
    }

    private fun assemblePath(
        rng: Random,
        targetDays: Int,
        minChunkDays: Int,
        maxChunkDays: Int,
        poolSize: Int,
        tickerReturnsByDay: List<Map<String, Double>>,
        effrxDailyRates: List<Double>
    ): List<AssembledDay> {
        val path = ArrayList<AssembledDay>(targetDays)
        var remaining = targetDays
        var firstChunk = true

        while (remaining > 0) {
            // Cap chunk size so chunk fits within pool (need poolSize - chunkDays >= 1 for startIdx)
            val chunkMax = minOf(maxChunkDays, remaining, poolSize - 1)
            val chunkMin = minChunkDays.coerceAtMost(chunkMax)
            val chunkDays = if (chunkMin >= chunkMax) chunkMax
                            else rng.nextInt(chunkMin, chunkMax + 1)

            // startIdx in [0, poolSize - chunkDays) → chunk return indices [startIdx, startIdx+chunkDays-1]
            val startIdx = if (poolSize - chunkDays > 1) rng.nextInt(0, poolSize - chunkDays)
                           else 0

            for (k in 0 until chunkDays) {
                val isBoundary = k == 0 && !firstChunk
                if (isBoundary) {
                    path.add(AssembledDay(emptyMap(), 0.0, true))
                } else {
                    val poolRetIdx = startIdx + k
                    path.add(AssembledDay(
                        tickerReturnsByDay[poolRetIdx],
                        effrxDailyRates[poolRetIdx],
                        false
                    ))
                }
            }

            remaining -= chunkDays
            firstChunk = false
        }
        return path
    }

    // ── Portfolio simulation on pre-computed path ─────────────────────────────

    private fun simulate(
        pConfig: PortfolioConfig,
        mc: MarginConfig?,
        path: List<AssembledDay>,
        startingBalance: Double,
        cashflow: CashflowConfig?
    ): List<Double> = if (mc == null) simulateNoMargin(pConfig, path, startingBalance, cashflow)
                      else simulateWithMargin(pConfig, mc, path, startingBalance, cashflow)

    private fun simulateNoMargin(
        pConfig: PortfolioConfig,
        path: List<AssembledDay>,
        startingBalance: Double,
        cashflow: CashflowConfig?
    ): List<Double> {
        val (tickers, targetWeights) = pConfig.mergeWeights()

        val startValue = startingBalance
        val holdings = tickers.associateWith { startValue * (targetWeights[it] ?: 0.0) }
            .toMutableMap()
        var totalHoldings = startValue

        val values = ArrayList<Double>(path.size + 1)
        values.add(startValue)
        var tradingDayCount = 0

        for (day in path) {
            if (!day.isChunkBoundary) {
                tradingDayCount++
                if (shouldRebalanceByCount(pConfig.rebalanceStrategy, tradingDayCount)) {
                    for (ticker in tickers) holdings[ticker] = totalHoldings * (targetWeights[ticker] ?: 0.0)
                }
            }
            var nextTotal = 0.0
            for (ticker in tickers) {
                val ret = if (day.isChunkBoundary) 1.0 else (day.tickerReturns[ticker] ?: 1.0)
                val nextHolding = (holdings[ticker] ?: 0.0) * ret
                holdings[ticker] = nextHolding
                nextTotal += nextHolding
            }
            if (!day.isChunkBoundary && cashflow != null && isCashflowDay(cashflow.frequency, tradingDayCount)) {
                for (ticker in tickers) {
                    val addition = cashflow.amount * (targetWeights[ticker] ?: 0.0)
                    holdings[ticker] = (holdings[ticker] ?: 0.0) + addition
                    nextTotal += addition
                }
            }
            totalHoldings = nextTotal
            values.add(totalHoldings)
        }
        return values
    }

    private fun simulateWithMargin(
        pConfig: PortfolioConfig,
        mc: MarginConfig,
        path: List<AssembledDay>,
        startingBalance: Double,
        cashflow: CashflowConfig?
    ): List<Double> {
        val (tickers, targetWeights) = pConfig.mergeWeights()

        val startEquity = startingBalance
        var borrowed = startEquity * mc.marginRatio
        val holdings = tickers.associateWith {
            (startEquity + borrowed) * (targetWeights[it] ?: 0.0)
        }.toMutableMap()
        var totalHoldings = startEquity + borrowed

        val result = ArrayList<Double>(path.size + 1)
        result.add(startEquity)
        var tradingDayCount = 0

        for (day in path) {
            val rebalanceDay: Boolean
            if (!day.isChunkBoundary) {
                tradingDayCount++
                rebalanceDay = shouldRebalanceByCount(pConfig.rebalanceStrategy, tradingDayCount)
                if (rebalanceDay) {
                    val currentEquity = totalHoldings - borrowed
                    borrowed = currentEquity * mc.marginRatio
                    totalHoldings = currentEquity + borrowed
                    for (ticker in tickers) holdings[ticker] = totalHoldings * (targetWeights[ticker] ?: 0.0)
                }
            }

            var nextTotal = 0.0
            for (ticker in tickers) {
                val ret = if (day.isChunkBoundary) 1.0 else (day.tickerReturns[ticker] ?: 1.0)
                val nextHolding = (holdings[ticker] ?: 0.0) * ret
                holdings[ticker] = nextHolding
                nextTotal += nextHolding
            }
            totalHoldings = nextTotal

            if (!day.isChunkBoundary && cashflow != null && isCashflowDay(cashflow.frequency, tradingDayCount)) {
                val contributionExposure = cashflow.amount * (1.0 + mc.marginRatio)
                borrowed += cashflow.amount * mc.marginRatio
                for (ticker in tickers) {
                    val addition = contributionExposure * (targetWeights[ticker] ?: 0.0)
                    holdings[ticker] = (holdings[ticker] ?: 0.0) + addition
                    totalHoldings += addition
                }
            }

            val dailyLoanRate = day.effrxRate + mc.marginSpread / 252.0
            borrowed *= (1.0 + dailyLoanRate)

            val equity = totalHoldings - borrowed
            val isDailyMode = mc.upperRebalanceMode == MarginRebalanceMode.DAILY.name

            if (isDailyMode) {
                val newBorrowed = equity * mc.marginRatio
                val delta = newBorrowed - borrowed
                if (totalHoldings != 0.0)
                    for (ticker in tickers)
                        holdings[ticker] = (holdings[ticker] ?: 0.0) * (1.0 + delta / totalHoldings)
                totalHoldings += delta
                borrowed = newBorrowed
            } else {
                val currentMarginRatio = if (equity != 0.0) borrowed / equity else mc.marginRatio
                val upperBreach = currentMarginRatio > mc.marginRatio + mc.marginDeviationUpper
                val lowerBreach = currentMarginRatio < mc.marginRatio - mc.marginDeviationLower
                val deviationBreach = upperBreach || lowerBreach

                if (deviationBreach) {
                    val newBorrowed = equity * mc.marginRatio
                    val mode = if (upperBreach) mc.upperRebalanceMode else mc.lowerRebalanceMode
                    val delta = newBorrowed - borrowed
                    BacktestService.applyAllocationMode(tickers, holdings, targetWeights, delta, mode)
                    totalHoldings = holdings.values.sum()
                    borrowed = newBorrowed
                }
            }

            result.add(totalHoldings - borrowed)
        }
        return result
    }

    // ── Trading-day-count-based rebalance trigger ─────────────────────────────

    private fun shouldRebalanceByCount(strategy: RebalanceStrategy, count: Int): Boolean =
        when (strategy) {
            RebalanceStrategy.NONE -> false
            RebalanceStrategy.DAILY -> true
            RebalanceStrategy.WEEKLY -> count % 5 == 0
            RebalanceStrategy.BI_WEEKLY -> count % 10 == 0
            RebalanceStrategy.MONTHLY -> count % 21 == 0
            RebalanceStrategy.BI_MONTHLY -> count % 42 == 0
            RebalanceStrategy.QUARTERLY -> count % 63 == 0
            RebalanceStrategy.EVERY_4_MONTHS -> count % 84 == 0
            RebalanceStrategy.HALF_YEARLY -> count % 126 == 0
            RebalanceStrategy.YEARLY -> count % 252 == 0
        }

    private fun isCashflowDay(frequency: CashflowFrequency, tradingDayCount: Int): Boolean =
        tradingDayCount > 0 && when (frequency) {
            CashflowFrequency.NONE -> false
            CashflowFrequency.MONTHLY -> tradingDayCount % 21 == 0
            CashflowFrequency.QUARTERLY -> tradingDayCount % 63 == 0
            CashflowFrequency.YEARLY -> tradingDayCount % 252 == 0
        }
}
