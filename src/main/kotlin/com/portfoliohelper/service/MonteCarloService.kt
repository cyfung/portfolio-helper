package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
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
        val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)
        val effrxSeries = BacktestService.loadEffrxSeries()

        // ── Step 1: Parse LETF definitions ───────────────────────────────────
        val letfDefs = mutableMapOf<String, LETFDefinition>()
        for (pConfig in request.portfolios) {
            for (tw in pConfig.tickers) {
                val def = BacktestService.parseLETFDefinition(tw.ticker) ?: continue
                letfDefs.putIfAbsent(tw.ticker, def)
            }
        }

        // ── Step 2: Load component ticker series ──────────────────────────────
        val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
        fun cachedLoad(ticker: String) =
            seriesCache.getOrPut(ticker) { BacktestService.loadNormalizedSeries(ticker, neededFrom) }

        val letfComponentTickers = letfDefs.values
            .flatMap { def -> def.components.map { it.ticker } }
            .toSet()
        for (ticker in letfComponentTickers) cachedLoad(ticker)

        // ── Step 3: Compute preliminary dates for LETF simulation ─────────────
        val componentSeriesForDates = letfComponentTickers.mapNotNull { seriesCache[it] }
        val letfDates = if (componentSeriesForDates.isNotEmpty())
            BacktestService.intersectDates(componentSeriesForDates, fromDate, toDate)
        else emptyList()

        // ── Step 4: Compute virtual LETF series ───────────────────────────────
        if (letfDates.size >= 2) {
            for ((letfString, def) in letfDefs) {
                if (letfString !in seriesCache) {
                    val componentSeriesMap = def.components.associate { comp ->
                        comp.ticker to (seriesCache[comp.ticker]
                            ?: error("Component ticker ${comp.ticker} was not loaded"))
                    }
                    seriesCache[letfString] = BacktestService.computeLetfSeries(
                        def, componentSeriesMap, letfDates, effrxSeries, def.rebalanceStrategy
                    )
                }
            }
        }

        // ── Step 5: Load real (non-LETF) ticker series ────────────────────────
        val realTickers = request.portfolios
            .flatMap { it.tickers }
            .map { it.ticker }
            .filter { BacktestService.parseLETFDefinition(it) == null }
            .toSet()
        for (ticker in realTickers) cachedLoad(ticker)

        // ── Step 6: Build pool date list ──────────────────────────────────────
        val allSeriesMaps = request.portfolios.map { pConfig ->
            pConfig.tickers.associate { tw ->
                tw.ticker to (seriesCache[tw.ticker]
                    ?: error("Series for '${tw.ticker}' not found in cache"))
            }
        }
        val poolDates = BacktestService.intersectDates(
            allSeriesMaps.flatMap { it.values }, fromDate, toDate
        )
        val poolSize = poolDates.size

        val targetDays = request.simulatedYears * 252
        val minChunkDays = (request.minChunkYears * 252).toInt().coerceAtLeast(1)
        val maxChunkDays = (request.maxChunkYears * 252).toInt().coerceAtLeast(minChunkDays)

        // Validation: pool must be large enough relative to chunk size
        val minForValidation = minOf(minChunkDays, targetDays)
        if (poolSize < 2 * minForValidation) {
            throw IllegalStateException(
                "Historical pool too small: $poolSize trading days in the selected date range. " +
                "Need at least ${2 * minForValidation} days (2 × min(minChunk, simulated)). " +
                "Widen the date range or reduce minChunkYears / simulatedYears."
            )
        }

        // ── Pre-compute return multipliers ────────────────────────────────────
        val allTickers = request.portfolios.flatMap { it.tickers.map { tw -> tw.ticker } }.toSet()

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

        fun modeAbbr(m: MarginRebalanceMode) = when (m) {
            MarginRebalanceMode.CURRENT_WEIGHT -> "Cur Wt"
            MarginRebalanceMode.PROPORTIONAL -> "Tgt Wt"
            MarginRebalanceMode.FULL_REBALANCE -> "Full"
            MarginRebalanceMode.UNDERVALUED_PRIORITY -> "UVal"
            MarginRebalanceMode.WATERFALL -> "WaterFall"
            MarginRebalanceMode.DAILY -> "Daily"
        }

        val portfolioCurveConfigs: List<Pair<PortfolioConfig, List<CurveConfig>>> =
            request.portfolios.map { pConfig ->
                val curves = mutableListOf<CurveConfig>()
                if (pConfig.includeNoMargin) curves.add(CurveConfig("No Margin", null))
                pConfig.marginStrategies.forEachIndexed { mIdx, mc ->
                    val uAbbr = modeAbbr(mc.upperRebalanceMode)
                    val lAbbr = modeAbbr(mc.lowerRebalanceMode)
                    val label = if (uAbbr == lAbbr) "Margin ${mIdx + 1} ($uAbbr)"
                    else "Margin ${mIdx + 1} ($uAbbr↑/$lAbbr↓)"
                    curves.add(CurveConfig(label, mc))
                }
                pConfig to curves
            }

        val percentiles = listOf(5, 10, 25, 50, 75, 90, 95)
        val numSims = request.numSimulations

        // ── Pass 1: run all simulations, record metrics ───────────────────────
        logger.info("MC Pass 1: $numSims simulations × ${portfolioCurveConfigs.sumOf { it.second.size }} curves")

        // allMetrics[pi][ci][simIdx]
        val zero = SimPassMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val allMetrics = Array(portfolioCurveConfigs.size) { pi ->
            Array(portfolioCurveConfigs[pi].second.size) { Array(numSims) { zero } }
        }

        for (simIdx in 0 until numSims) {
            val rng = Random(masterSeed + simIdx)
            val path = assemblePath(rng, targetDays, minChunkDays, maxChunkDays, poolSize,
                tickerReturnsByDay, effrxDailyRates)

            portfolioCurveConfigs.forEachIndexed { pi, (pConfig, curves) ->
                curves.forEachIndexed { ci, curveConfig ->
                    val values = simulate(pConfig, curveConfig.mc, path)
                    val stats = computeStats(values, years, rfAnnualized)
                    allMetrics[pi][ci][simIdx] = SimPassMetrics(stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
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
        val portfolioResults = portfolioCurveConfigs.mapIndexed { pi, (pConfig, curves) ->
            val curveResults = curves.mapIndexed { ci, curveConfig ->
                val percentilePaths = percentiles.mapIndexed { pctIdx, pct ->
                    val simIdx = pctSimIndices[pi][ci][pctIdx]
                    val path = fullPaths[simIdx]!!
                    val values = simulate(pConfig, curveConfig.mc, path)
                    val endValue = values.last()
                    val stats = computeStats(values, years, rfAnnualized)
                    MonteCarloPercentilePath(pct, values, endValue, stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                }
                MonteCarloCurveResult(
                    curveConfig.label,
                    percentilePaths,
                    maxDdPctValues[pi][ci],
                    sharpePctValues[pi][ci],
                    ulcerPctValues[pi][ci],
                    upiPctValues[pi][ci],
                    volPctValues[pi][ci],
                    longestDdPctValues[pi][ci]
                )
            }
            MonteCarloPortfolioResult(pConfig.label, curveResults)
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
        path: List<AssembledDay>
    ): List<Double> = if (mc == null) simulateNoMargin(pConfig, path)
                      else simulateWithMargin(pConfig, mc, path)

    private fun simulateNoMargin(pConfig: PortfolioConfig, path: List<AssembledDay>): List<Double> {
        val (tickers, targetWeights) = pConfig.mergeWeights()

        val startValue = 10_000.0
        val holdings = tickers.associateWith { startValue * (targetWeights[it] ?: 0.0) }
            .toMutableMap()

        val values = ArrayList<Double>(path.size + 1)
        values.add(startValue)
        var tradingDayCount = 0

        for (day in path) {
            if (!day.isChunkBoundary) {
                tradingDayCount++
                if (shouldRebalanceByCount(pConfig.rebalanceStrategy, tradingDayCount)) {
                    val total = holdings.values.sum()
                    for (ticker in tickers) holdings[ticker] = total * (targetWeights[ticker] ?: 0.0)
                }
            }
            for (ticker in tickers) {
                val ret = if (day.isChunkBoundary) 1.0 else (day.tickerReturns[ticker] ?: 1.0)
                holdings[ticker] = (holdings[ticker] ?: 0.0) * ret
            }
            values.add(holdings.values.sum())
        }
        return values
    }

    private fun simulateWithMargin(
        pConfig: PortfolioConfig,
        mc: MarginConfig,
        path: List<AssembledDay>
    ): List<Double> {
        val (tickers, targetWeights) = pConfig.mergeWeights()

        val startEquity = 10_000.0
        var borrowed = startEquity * mc.marginRatio
        val holdings = tickers.associateWith {
            (startEquity + borrowed) * (targetWeights[it] ?: 0.0)
        }.toMutableMap()

        val result = ArrayList<Double>(path.size + 1)
        result.add(startEquity)
        var tradingDayCount = 0

        for (day in path) {
            val rebalanceDay: Boolean
            if (!day.isChunkBoundary) {
                tradingDayCount++
                rebalanceDay = shouldRebalanceByCount(pConfig.rebalanceStrategy, tradingDayCount)
                if (rebalanceDay) {
                    val currentEquity = holdings.values.sum() - borrowed
                    borrowed = currentEquity * mc.marginRatio
                    val newTotal = currentEquity + borrowed
                    for (ticker in tickers) holdings[ticker] = newTotal * (targetWeights[ticker] ?: 0.0)
                }
            }

            for (ticker in tickers) {
                val ret = if (day.isChunkBoundary) 1.0 else (day.tickerReturns[ticker] ?: 1.0)
                holdings[ticker] = (holdings[ticker] ?: 0.0) * ret
            }

            val dailyLoanRate = day.effrxRate + mc.marginSpread / 252.0
            borrowed *= (1.0 + dailyLoanRate)

            val equity = holdings.values.sum() - borrowed
            val isDailyMode = mc.upperRebalanceMode == MarginRebalanceMode.DAILY

            if (isDailyMode) {
                val newBorrowed = equity * mc.marginRatio
                val delta = newBorrowed - borrowed
                val totalHoldings = holdings.values.sum()
                if (totalHoldings != 0.0)
                    for (ticker in tickers)
                        holdings[ticker] = (holdings[ticker] ?: 0.0) * (1.0 + delta / totalHoldings)
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
                    when (mode) {
                        MarginRebalanceMode.FULL_REBALANCE -> {
                            val newTotal = equity + newBorrowed
                            for (ticker in tickers)
                                holdings[ticker] = newTotal * (targetWeights[ticker] ?: 0.0)
                        }
                        MarginRebalanceMode.WATERFALL ->
                            BacktestService.computeWaterfall(tickers, holdings, targetWeights, delta)
                        MarginRebalanceMode.UNDERVALUED_PRIORITY ->
                            BacktestService.computeUndervalueFirst(tickers, holdings, targetWeights, delta)
                        MarginRebalanceMode.PROPORTIONAL ->
                            for (ticker in tickers)
                                holdings[ticker] =
                                    (holdings[ticker] ?: 0.0) + delta * (targetWeights[ticker] ?: 0.0)
                        MarginRebalanceMode.CURRENT_WEIGHT -> {
                            val totalHoldings = holdings.values.sum()
                            if (totalHoldings != 0.0)
                                for (ticker in tickers)
                                    holdings[ticker] =
                                        (holdings[ticker] ?: 0.0) * (1.0 + delta / totalHoldings)
                        }
                        MarginRebalanceMode.DAILY -> { /* handled above */ }
                    }
                    borrowed = newBorrowed
                }
            }

            result.add(holdings.values.sum() - borrowed)
        }
        return result
    }

    // ── Trading-day-count-based rebalance trigger ─────────────────────────────

    private fun shouldRebalanceByCount(strategy: RebalanceStrategy, count: Int): Boolean =
        when (strategy) {
            RebalanceStrategy.NONE -> false
            RebalanceStrategy.DAILY -> true
            RebalanceStrategy.WEEKLY -> count % 5 == 0
            RebalanceStrategy.MONTHLY -> count % 21 == 0
            RebalanceStrategy.QUARTERLY -> count % 63 == 0
            RebalanceStrategy.YEARLY -> count % 252 == 0
        }
}
