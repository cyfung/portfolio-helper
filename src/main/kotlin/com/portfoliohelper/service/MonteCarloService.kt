package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

object MonteCarloService {
    private val logger = LoggerFactory.getLogger(MonteCarloService::class.java)
    private const val progressStepCount = 7
    private val progressState = AtomicReference(MonteCarloProgress.idle())
    private val lastResultState = AtomicReference<MonteCarloResult?>(null)
    private val lastErrorState = AtomicReference<String?>(null)
    private val monteCarloParallelism = configuredParallelism()
    private val monteCarloDispatcher = Executors
        .newFixedThreadPool(monteCarloParallelism, MonteCarloThreadFactory())
        .asCoroutineDispatcher()

    fun getProgress(): MonteCarloProgress = progressState.get()
    fun getRunState(): MonteCarloRunState =
        MonteCarloRunState(progressState.get(), lastResultState.get(), lastErrorState.get())

    fun markFailed(message: String?) {
        val errorMessage = message?.takeIf { it.isNotBlank() } ?: "Monte Carlo run failed"
        lastResultState.set(null)
        lastErrorState.set(errorMessage)
        updateProgress(
            phase = "error",
            phaseLabel = "Run failed",
            action = errorMessage,
            currentStep = progressState.get().currentStep.coerceAtLeast(1),
            done = true
        )
    }

    private fun detail(label: String, value: Any?) =
        MonteCarloProgressDetail(label, value?.toString() ?: "")

    private fun updateProgress(
        phase: String,
        phaseLabel: String,
        action: String,
        currentStep: Int,
        progressLabel: String = "Progress",
        completed: Int = 0,
        total: Int = 0,
        details: List<MonteCarloProgressDetail> = emptyList(),
        done: Boolean = false
    ) {
        progressState.set(
            MonteCarloProgress(
                phase = phase,
                phaseLabel = phaseLabel,
                action = action,
                progressLabel = progressLabel,
                completed = completed,
                total = total,
                currentStep = currentStep,
                totalSteps = progressStepCount,
                details = details,
                done = done
            )
        )
    }

    suspend fun runMonteCarlo(request: MonteCarloRequest): MonteCarloResult = withContext(Dispatchers.Default) {
        lastResultState.set(null)
        lastErrorState.set(null)
        updateProgress(
            phase = "validate",
            phaseLabel = "Validating request",
            action = "Checking date range and simulation settings",
            currentStep = 1,
            details = listOf(
                detail("Portfolio blocks", request.portfolios.size),
                detail("Simulations", request.numSimulations),
                detail("Simulated years", request.simulatedYears),
            )
        )
        val fromDate = request.fromDate?.let { LocalDate.parse(it) }
        val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        BacktestService.validateDateRange(fromDate, toDate)
        val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)
        val fullHistoryFrom = LocalDate.of(1990, 1, 1)
        updateProgress(
            phase = "prepare",
            phaseLabel = "Preparing data",
            action = "Loading rates and resolving requested tickers",
            currentStep = 2,
            details = listOf(
                detail("Portfolio blocks", request.portfolios.size),
                detail("From", fromDate ?: "1990-01-01"),
                detail("To", toDate),
            )
        )
        val effrxSeries = withContext(Dispatchers.IO) { BacktestService.loadEffrxSeries() }
        val portfolios = request.portfolios.map { it.withoutPlaceholderTickers() }
        val referenceTickersByPortfolio =
            portfolios.map { RebalanceStrategyService.requiredReferenceTickers(it.rebalanceStrategies) }

        // ── Step 1: Parse LETF definitions ───────────────────────────────────
        val requestedTickers = portfolios.indices
            .flatMap { index ->
                portfolios[index].tickers.map { it.ticker } + referenceTickersByPortfolio[index]
            }
            .distinct()
        val hasPrependedChain = requestedTickers.any { BacktestService.parseTickerChain(it) != null }
        updateProgress(
            phase = "prepare",
            phaseLabel = "Preparing data",
            action = "Loading component ticker history",
            currentStep = 2,
            details = listOf(
                detail("Requested tickers", requestedTickers.size),
                detail("EFFRX rows", effrxSeries.size),
                detail("Prepended chains", if (hasPrependedChain) "yes" else "no"),
            )
        )
        val seriesCache = withContext(Dispatchers.IO) {
            BacktestService.resolveTickerSeries(
                requestedTickers,
                neededFromForTicker = { ticker ->
                    if (BacktestService.parseTickerChain(ticker) != null) fullHistoryFrom else neededFrom
                },
                toDate = toDate,
                effrx = effrxSeries,
            )
        }

        // ── Step 2: Load component ticker series ──────────────────────────────
        // ── Step 3: Compute preliminary dates for LETF simulation ─────────────
        // ── Step 4: Compute virtual LETF series ───────────────────────────────
        // ── Step 5: Load real (non-LETF) ticker series ────────────────────────
        // ── Step 6: Build pool date list ──────────────────────────────────────
        val allSeriesMaps = portfolios.mapIndexed { index, pConfig ->
            val tickersForPool =
                (pConfig.tickers.map { it.ticker } + referenceTickersByPortfolio[index])
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
        updateProgress(
            phase = "pool",
            phaseLabel = "Building simulation pool",
            action = "Aligning history and precomputing daily returns",
            currentStep = 3,
            details = listOf(
                detail("Pool trading days", poolSize),
                detail("Target trading days", targetDays),
                detail("Min chunk days", minChunkDays),
                detail("Max chunk days", maxChunkDays),
                detail("Tickers", requestedTickers.size),
            )
        )

        val allTickerList =
            (portfolios.flatMap { it.tickers.map { tw -> tw.ticker } } +
                referenceTickersByPortfolio.flatten())
                .distinct()
        val allTickerIndex = allTickerList.withIndex().associate { it.value to it.index }
        val allTickers = allTickerList.toSet()

        // tickerReturnsByDay[i] = returns for day i (transition poolDates[i] → poolDates[i+1])
        // indexed 0..poolSize-2
        val returnDayCount = poolSize - 1
        val tickerReturnsByDay = Array(returnDayCount) { DoubleArray(allTickerList.size) }
        parallelForRange(returnDayCount) { i ->
            val dayReturns = tickerReturnsByDay[i]
            for (tickerIndex in allTickerList.indices) {
                val ticker = allTickerList[tickerIndex]
                val s = seriesCache[ticker]
                val prev = s?.get(poolDates[i])
                val cur = s?.get(poolDates[i + 1])
                dayReturns[tickerIndex] =
                    if (prev != null && cur != null && prev != 0.0) cur / prev else 1.0
            }
        }

        // effrxDailyRates[i] = effrx daily return for transition i → i+1
        val effrxDailyRates = DoubleArray(returnDayCount)
        parallelForRange(returnDayCount) { i ->
            val prev = effrxSeries[poolDates[i]]
            val cur = effrxSeries[poolDates[i + 1]]
            effrxDailyRates[i] = if (prev != null && cur != null && prev != 0.0) cur / prev - 1.0 else 0.0
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
        val portfolioRuntimes = portfolios.map { simpleRuntimeForPortfolio(it, allTickerIndex) }

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

        val percentiles = listOf(5, 10, 25, 50, 75, 90, 95)
        val numSims = request.numSimulations
        val curveCount = portfolioCurveConfigs.sumOf { it.allLabels.size }

        // ── Pass 1: run all simulations, record metrics ───────────────────────
        logger.info("MC Pass 1: $numSims simulations × $curveCount curves")
        updateProgress(
            phase = "simulate",
            phaseLabel = "Running simulations",
            action = "Pass 1: computing metrics for every simulation",
            currentStep = 4,
            progressLabel = "Simulations",
            completed = 0,
            total = numSims,
            details = listOf(
                detail("Curves per simulation", curveCount),
                detail("Target trading days", targetDays),
                detail("Simulated years", request.simulatedYears),
            )
        )
        val simulationCompleted = AtomicInteger(0)

        // allMetrics[pi][ci][simIdx]
        val zero = SimPassMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val allMetrics = Array(portfolioCurveConfigs.size) { pi ->
            Array(portfolioCurveConfigs[pi].allLabels.size) { Array(numSims) { zero } }
        }

        parallelForRange(numSims) { simIdx ->
            val rng = Random(masterSeed + simIdx)
            val indexedPath = assembleIndexedPath(rng, targetDays, minChunkDays, maxChunkDays, poolSize)

            portfolioCurveConfigs.forEachIndexed { pi, config ->
                var ci = 0
                config.simpleCurves.forEach { curveConfig ->
                    val values = simulateIndexed(
                        portfolioRuntimes[pi],
                        curveConfig.mc,
                        indexedPath,
                        tickerReturnsByDay,
                        effrxDailyRates,
                        request.startingBalance,
                        request.cashflow,
                    )
                    val stats = computeStats(values, years, rfAnnualized)
                    allMetrics[pi][ci][simIdx] = SimPassMetrics(stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                    ci++
                }
                if (config.strategyLabels.isNotEmpty()) {
                    val path = assembledPathFromIndexed(indexedPath, allTickerList, tickerReturnsByDay, effrxDailyRates)
                    val strategyCurves = simulateAttachedStrategies(config.portfolio, path, request.startingBalance, request.cashflow)
                    strategyCurves.forEach { curve ->
                        val values = curve.points.map { it.value }
                        val stats = computeStats(values, years, rfAnnualized)
                        allMetrics[pi][ci][simIdx] = SimPassMetrics(stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                        ci++
                    }
                }
            }
            val done = simulationCompleted.incrementAndGet()
            if (shouldPublishProgress(done, numSims)) {
                updateProgress(
                    phase = "simulate",
                    phaseLabel = "Running simulations",
                    action = "Pass 1: computing metrics for every simulation",
                    currentStep = 4,
                    progressLabel = "Simulations",
                    completed = done,
                    total = numSims,
                    details = listOf(
                        detail("Curves per simulation", curveCount),
                        detail("Target trading days", targetDays),
                        detail("Simulated years", request.simulatedYears),
                    )
                )
            }
        }

        // ── Identify percentile sim indices (CAGR-sorted, for pass 2) ─────────
        // pctSimIndices[pi][ci][pctIdx] = simIdx
        updateProgress(
            phase = "rank",
            phaseLabel = "Ranking outcomes",
            action = "Selecting percentile simulations and independent metric percentiles",
            currentStep = 5,
            details = listOf(
                detail("Simulations ranked", numSims),
                detail("Curves", curveCount),
                detail("Percentiles", percentiles.size),
            )
        )

        val pctIdxList = percentiles.map { pct ->
            (pct.toDouble() / 100.0 * (numSims - 1)).toInt().coerceIn(0, numSims - 1)
        }
        val pctSimIndices = parallelMap(allMetrics.toList()) { portfolioMetrics ->
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
        updateProgress(
            phase = "paths",
            phaseLabel = "Rebuilding percentile paths",
            action = "Pass 2: assembling full paths for selected percentile simulations",
            currentStep = 6,
            progressLabel = "Unique paths",
            completed = 0,
            total = neededSimIndices.size,
            details = listOf(
                detail("Percentile path slots", curveCount * percentiles.size),
                detail("Target trading days", targetDays),
                detail("Percentiles", percentiles.size),
            )
        )
        val pathsCompleted = AtomicInteger(0)

        val fullPaths: Map<Int, IndexedPath> = parallelMap(neededSimIndices.toList()) { simIdx ->
            val rng = Random(masterSeed + simIdx)
            val path = assembleIndexedPath(rng, targetDays, minChunkDays, maxChunkDays, poolSize)
            val done = pathsCompleted.incrementAndGet()
            if (shouldPublishProgress(done, neededSimIndices.size)) {
                updateProgress(
                    phase = "paths",
                    phaseLabel = "Rebuilding percentile paths",
                    action = "Pass 2: assembling full paths for selected percentile simulations",
                    currentStep = 6,
                    progressLabel = "Unique paths",
                    completed = done,
                    total = neededSimIndices.size,
                    details = listOf(
                        detail("Percentile path slots", curveCount * percentiles.size),
                        detail("Target trading days", targetDays),
                        detail("Percentiles", percentiles.size),
                    )
                )
            }
            simIdx to path
        }.toMap()

        // ── Build final result ────────────────────────────────────────────────
        val finalPathSlots = curveCount * percentiles.size
        updateProgress(
            phase = "finalize",
            phaseLabel = "Finalizing results",
            action = "Building percentile curves and summary statistics",
            currentStep = 7,
            progressLabel = "Percentile curves",
            completed = 0,
            total = finalPathSlots,
            details = listOf(
                detail("Portfolios", portfolioCurveConfigs.size),
                detail("Curves", curveCount),
                detail("Percentiles", percentiles.size),
            )
        )
        val resultPathsCompleted = AtomicInteger(0)

        val portfolioResults = parallelMapIndexed(portfolioCurveConfigs) { pi, config ->
            val curveResults = parallelMapIndexed(config.allLabels) { ci, label ->
                val percentilePaths = percentiles.mapIndexed { pctIdx, pct ->
                    val simIdx = pctSimIndices[pi][ci][pctIdx]
                    val path = fullPaths[simIdx]!!
                    val values: List<Double>
                    val stats: PortfolioStats
                    if (ci < config.simpleCurves.size) {
                        val valueArray = simulateIndexed(
                            portfolioRuntimes[pi],
                            config.simpleCurves[ci].mc,
                            path,
                            tickerReturnsByDay,
                            effrxDailyRates,
                            request.startingBalance,
                            request.cashflow,
                        )
                        values = valueArray.toList()
                        stats = computeStats(valueArray, years, rfAnnualized)
                    } else {
                        val strategyIndex = ci - config.simpleCurves.size
                        values = simulateAttachedStrategies(
                            config.portfolio,
                            assembledPathFromIndexed(path, allTickerList, tickerReturnsByDay, effrxDailyRates),
                            request.startingBalance,
                            request.cashflow,
                        )[strategyIndex].points.map { it.value }
                        stats = computeStats(values, years, rfAnnualized)
                    }
                    val endValue = values.last()
                    val done = resultPathsCompleted.incrementAndGet()
                    if (shouldPublishProgress(done, finalPathSlots)) {
                        updateProgress(
                            phase = "finalize",
                            phaseLabel = "Finalizing results",
                            action = "Building percentile curves and summary statistics",
                            currentStep = 7,
                            progressLabel = "Percentile curves",
                            completed = done,
                            total = finalPathSlots,
                            details = listOf(
                                detail("Portfolios", portfolioCurveConfigs.size),
                                detail("Curves", curveCount),
                                detail("Percentiles", percentiles.size),
                            )
                        )
                    }
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

        updateProgress(
            phase = "complete",
            phaseLabel = "Complete",
            action = "Simulation results are ready",
            currentStep = progressStepCount,
            details = listOf(
                detail("Portfolios", portfolioCurveConfigs.size),
                detail("Curves", curveCount),
                detail("Simulations", numSims),
                detail("Seed", masterSeed),
            ),
            done = true
        )
        val result = MonteCarloResult(request.simulatedYears, numSims, portfolioResults, masterSeed)
        lastResultState.set(result)
        result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class SimplePortfolioRuntime(
        val tickers: List<String>,
        val targetWeightMap: Map<String, Double>,
        val returnIndexes: IntArray,
        val weights: DoubleArray,
        val rebalanceStrategy: RebalanceStrategy,
    )

    private data class IndexedPath(val returnIndexes: IntArray)

    private class MonteCarloThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "monte-carlo-${counter.getAndIncrement()}").apply {
                isDaemon = true
            }
    }

    private fun configuredParallelism(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val configured = System.getProperty("portfoliohelper.monteCarlo.parallelism")
            ?.toIntOrNull()
            ?: System.getenv("PORTFOLIOHELPER_MONTE_CARLO_PARALLELISM")?.toIntOrNull()
        return configured?.coerceAtLeast(1) ?: available
    }

    private fun simpleRuntimeForPortfolio(
        pConfig: PortfolioConfig,
        tickerIndex: Map<String, Int>
    ): SimplePortfolioRuntime {
        val (tickers, targetWeights) = pConfig.mergeWeights()
        return SimplePortfolioRuntime(
            tickers = tickers,
            targetWeightMap = targetWeights,
            returnIndexes = tickers.map { ticker ->
                tickerIndex[ticker] ?: error("Ticker '$ticker' missing from Monte Carlo return pool")
            }.toIntArray(),
            weights = tickers.map { targetWeights[it] ?: 0.0 }.toDoubleArray(),
            rebalanceStrategy = pConfig.rebalanceStrategy,
        )
    }

    private fun assembleIndexedPath(
        rng: Random,
        targetDays: Int,
        minChunkDays: Int,
        maxChunkDays: Int,
        poolSize: Int
    ): IndexedPath {
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
        return IndexedPath(path)
    }

    private fun assembledPathFromIndexed(
        path: IndexedPath,
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

    private fun simulateIndexed(
        runtime: SimplePortfolioRuntime,
        mc: MarginConfig?,
        path: IndexedPath,
        tickerReturnsByDay: Array<DoubleArray>,
        effrxDailyRates: DoubleArray,
        startingBalance: Double,
        cashflow: CashflowConfig?
    ): DoubleArray =
        if (mc == null) {
            simulateNoMarginIndexed(runtime, path, tickerReturnsByDay, startingBalance, cashflow)
        } else {
            simulateWithMarginIndexed(runtime, mc, path, tickerReturnsByDay, effrxDailyRates, startingBalance, cashflow)
        }

    private fun simulateNoMarginIndexed(
        runtime: SimplePortfolioRuntime,
        path: IndexedPath,
        tickerReturnsByDay: Array<DoubleArray>,
        startingBalance: Double,
        cashflow: CashflowConfig?
    ): DoubleArray {
        val holdings = DoubleArray(runtime.tickers.size) { startingBalance * runtime.weights[it] }
        val values = DoubleArray(path.returnIndexes.size + 1)
        values[0] = startingBalance
        var totalHoldings = startingBalance
        var tradingDayCount = 0

        for (dayIndex in path.returnIndexes.indices) {
            val returnIndex = path.returnIndexes[dayIndex]
            if (returnIndex >= 0) {
                tradingDayCount++
                if (shouldRebalanceByCount(runtime.rebalanceStrategy, tradingDayCount)) {
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

            if (returnIndex >= 0 && cashflow != null && isCashflowDay(cashflow.frequency, tradingDayCount)) {
                for (i in holdings.indices) {
                    val addition = cashflow.amount * runtime.weights[i]
                    holdings[i] += addition
                    nextTotal += addition
                }
            }
            totalHoldings = nextTotal
            values[dayIndex + 1] = totalHoldings
        }
        return values
    }

    private fun simulateWithMarginIndexed(
        runtime: SimplePortfolioRuntime,
        mc: MarginConfig,
        path: IndexedPath,
        tickerReturnsByDay: Array<DoubleArray>,
        effrxDailyRates: DoubleArray,
        startingBalance: Double,
        cashflow: CashflowConfig?
    ): DoubleArray {
        var borrowed = startingBalance * mc.marginRatio
        val holdings = DoubleArray(runtime.tickers.size) { (startingBalance + borrowed) * runtime.weights[it] }
        var totalHoldings = startingBalance + borrowed
        val values = DoubleArray(path.returnIndexes.size + 1)
        values[0] = startingBalance
        var tradingDayCount = 0
        val dailySpread = mc.marginSpread / 252.0
        val isDailyMode = mc.upperRebalanceMode == MarginRebalanceMode.DAILY.name

        for (dayIndex in path.returnIndexes.indices) {
            val returnIndex = path.returnIndexes[dayIndex]
            if (returnIndex >= 0) {
                tradingDayCount++
                if (shouldRebalanceByCount(runtime.rebalanceStrategy, tradingDayCount)) {
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

            if (returnIndex >= 0 && cashflow != null && isCashflowDay(cashflow.frequency, tradingDayCount)) {
                val contributionExposure = cashflow.amount * (1.0 + mc.marginRatio)
                borrowed += cashflow.amount * mc.marginRatio
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
                    applyAllocationModeIndexed(runtime, holdings, newBorrowed - borrowed, mode)
                    totalHoldings = holdings.sum()
                    borrowed = newBorrowed
                }
            }

            values[dayIndex + 1] = totalHoldings - borrowed
        }
        return values
    }

    private fun applyAllocationModeIndexed(
        runtime: SimplePortfolioRuntime,
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
        runtime: SimplePortfolioRuntime,
        holdings: DoubleArray,
        delta: Double,
        mode: String
    ) {
        val holdingMap = linkedMapOf<String, Double>()
        for (i in runtime.tickers.indices) holdingMap[runtime.tickers[i]] = holdings[i]
        BacktestService.applyAllocationMode(runtime.tickers, holdingMap, runtime.targetWeightMap, delta, mode)
        for (i in runtime.tickers.indices) holdings[i] = holdingMap[runtime.tickers[i]] ?: 0.0
    }

    private fun computeStats(values: DoubleArray, years: Double, rfAnnualized: Double): PortfolioStats {
        if (values.size < 2) return PortfolioStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

        val cagr = if (years > 0 && values.last() > 0)
            (values.last() / values.first()).pow(1.0 / years) - 1.0 else 0.0

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
            val r = values[i] / prev - 1.0
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

    private suspend fun parallelForRange(size: Int, action: (Int) -> Unit) {
        if (size <= 0) return
        val workers = minOf(size, monteCarloParallelism)
        coroutineScope {
            (0 until workers).map { worker ->
                val start = worker * size / workers
                val end = (worker + 1) * size / workers
                async(monteCarloDispatcher) {
                    for (index in start until end) {
                        action(index)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun <T> parallelMapRange(size: Int, transform: suspend (Int) -> T): List<T> {
        if (size <= 0) return emptyList()
        val workers = minOf(size, monteCarloParallelism)
        val results = arrayOfNulls<Any>(size)
        coroutineScope {
            (0 until workers).map { worker ->
                val start = worker * size / workers
                val end = (worker + 1) * size / workers
                async(monteCarloDispatcher) {
                    for (index in start until end) {
                        results[index] = transform(index)
                    }
                }
            }.awaitAll()
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as T }
    }

    private suspend fun <T, R> parallelMap(items: List<T>, transform: suspend (T) -> R): List<R> =
        parallelMapRange(items.size) { index -> transform(items[index]) }

    private suspend fun <T, R> parallelMapIndexed(items: List<T>, transform: suspend (Int, T) -> R): List<R> =
        parallelMapRange(items.size) { index -> transform(index, items[index]) }

    private fun shouldPublishProgress(completed: Int, total: Int): Boolean {
        if (total <= 0) return false
        if (completed == 1 || completed == total) return true
        val step = (total / 100).coerceAtLeast(1)
        return completed % step == 0
    }

    private suspend fun metricPercentiles(
        allMetrics: Array<Array<Array<SimPassMetrics>>>,
        numSims: Int,
        pctIdxList: List<Int>,
        descending: Boolean = false,
        selector: (SimPassMetrics) -> Double
    ): List<List<List<Double>>> = parallelMap(allMetrics.toList()) { portMetrics ->
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
