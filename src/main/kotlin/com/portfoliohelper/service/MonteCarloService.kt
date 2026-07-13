package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.random.Random

object MonteCarloService {
    private val logger = LoggerFactory.getLogger(MonteCarloService::class.java)
    private const val progressStepCount = 7
    private val progressState = AtomicReference(MonteCarloProgress.idle())
    private val lastResultState = AtomicReference<MonteCarloResult?>(null)
    private val lastErrorState = AtomicReference<String?>(null)

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
        MonteCarloParallel.parallelForRange(returnDayCount) { i ->
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
        MonteCarloParallel.parallelForRange(returnDayCount) { i ->
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
        val portfolioRuntimes = portfolios.map {
            MonteCarloIndexedSimulation.simpleRuntimeForPortfolio(it, allTickerIndex)
        }

        val syntheticDates = MonteCarloSyntheticSeries.tradingDates(targetDays + 1)

        fun simulateAttachedStrategies(
            pConfig: PortfolioConfig,
            path: List<AssembledDay>,
            startingBalance: Double,
            cashflow: CashflowConfig?
        ): List<CurveResult> {
            val seriesMap = MonteCarloSyntheticSeries.seriesMap(allTickers, path, syntheticDates)
            val syntheticEffrx = MonteCarloSyntheticSeries.effrxSeries(path, syntheticDates)
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

        MonteCarloParallel.parallelForRange(numSims) { simIdx ->
            val rng = Random(masterSeed + simIdx)
            val indexedPath = MonteCarloIndexedSimulation.assemblePath(
                rng,
                targetDays,
                minChunkDays,
                maxChunkDays,
                poolSize,
            )

            portfolioCurveConfigs.forEachIndexed { pi, config ->
                var ci = 0
                config.simpleCurves.forEach { curveConfig ->
                    val values = MonteCarloIndexedSimulation.simulate(
                        portfolioRuntimes[pi],
                        curveConfig.mc,
                        indexedPath,
                        tickerReturnsByDay,
                        effrxDailyRates,
                        request.startingBalance,
                        request.cashflow,
                    )
                    val stats = MonteCarloIndexedSimulation.computeStats(values, years, rfAnnualized)
                    allMetrics[pi][ci][simIdx] = SimPassMetrics(stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi, stats.annualVolatility, stats.longestDrawdownDays)
                    ci++
                }
                if (config.strategyLabels.isNotEmpty()) {
                    val path = MonteCarloIndexedSimulation.toAssembledPath(
                        indexedPath,
                        allTickerList,
                        tickerReturnsByDay,
                        effrxDailyRates,
                    )
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
        val pctSimIndices = MonteCarloParallel.parallelMap(allMetrics.toList()) { portfolioMetrics ->
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

        val fullPaths: Map<Int, MonteCarloIndexedPath> = MonteCarloParallel.parallelMap(neededSimIndices.toList()) { simIdx ->
            val rng = Random(masterSeed + simIdx)
            val path = MonteCarloIndexedSimulation.assemblePath(
                rng,
                targetDays,
                minChunkDays,
                maxChunkDays,
                poolSize,
            )
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

        val portfolioResults = MonteCarloParallel.parallelMapIndexed(portfolioCurveConfigs) { pi, config ->
            val curveResults = MonteCarloParallel.parallelMapIndexed(config.allLabels) { ci, label ->
                val percentilePaths = percentiles.mapIndexed { pctIdx, pct ->
                    val simIdx = pctSimIndices[pi][ci][pctIdx]
                    val path = fullPaths[simIdx]!!
                    val values: List<Double>
                    val stats: PortfolioStats
                    if (ci < config.simpleCurves.size) {
                        val valueArray = MonteCarloIndexedSimulation.simulate(
                            portfolioRuntimes[pi],
                            config.simpleCurves[ci].mc,
                            path,
                            tickerReturnsByDay,
                            effrxDailyRates,
                            request.startingBalance,
                            request.cashflow,
                        )
                        values = valueArray.toList()
                        stats = MonteCarloIndexedSimulation.computeStats(valueArray, years, rfAnnualized)
                    } else {
                        val strategyIndex = ci - config.simpleCurves.size
                        values = simulateAttachedStrategies(
                            config.portfolio,
                            MonteCarloIndexedSimulation.toAssembledPath(
                                path,
                                allTickerList,
                                tickerReturnsByDay,
                                effrxDailyRates,
                            ),
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
    ): List<List<List<Double>>> = MonteCarloParallel.parallelMap(allMetrics.toList()) { portMetrics ->
        portMetrics.map { simMetrics ->
            val sorted = (0 until numSims).sortedBy { if (descending) -selector(simMetrics[it]) else selector(simMetrics[it]) }
            pctIdxList.map { selector(simMetrics[sorted[it]]) }
        }
    }


}
