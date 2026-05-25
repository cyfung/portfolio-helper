package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp
import kotlin.math.max

object RebalanceStrategyService {
  private data class CapePoint(val date: LocalDate, val cape: Double)
  private data class CapeHistory(val points: List<CapePoint>) {
    fun valuationFactor(date: LocalDate): Pair<Double, Double>? {
      val usable = points.filter { it.date <= date }
      if (usable.isEmpty()) return null
      val current = usable.last().cape
      if (current <= 0.0) return null
      val earningsYields = usable.mapNotNull { point ->
        if (point.cape > 0.0) 1.0 / point.cape else null
      }.sorted()
      if (earningsYields.isEmpty()) return null

      fun percentile(p: Double): Double {
        if (earningsYields.size == 1) return earningsYields.first()
        val pos = p.coerceIn(0.0, 1.0) * (earningsYields.lastIndex)
        val lowerIndex = pos.toInt()
        val upperIndex = kotlin.math.ceil(pos).toInt()
        val lower = earningsYields[lowerIndex]
        val upper = earningsYields[upperIndex]
        return lower + (upper - lower) * (pos - lowerIndex)
      }

      val p5 = percentile(0.05)
      val median = percentile(0.50)
      val p95 = percentile(0.95)
      val spread = p95 - p5
      if (spread <= 0.0) return null

      val trimmedEp = (1.0 / current).coerceIn(p5, p95)
      val equityWeight = (1.0 + (trimmedEp - median) / spread).coerceIn(0.5, 1.5)
      return (equityWeight - 0.5).coerceIn(0.0, 1.0) to current
    }
  }

  private val capeCache = mutableMapOf<CapeSource, CapeHistory>()

  private fun loadCapeHistory(source: CapeSource): CapeHistory =
      synchronized(capeCache) { capeCache[source] } ?: synchronized(capeCache) {
        capeCache[source] ?: run {
          val fileName =
              when (source) {
                CapeSource.US -> "us-cape-history.csv"
                CapeSource.WORLD -> "world-cape-history.csv"
              }
          val valueColumn =
              when (source) {
                CapeSource.US -> "us_cape"
                CapeSource.WORLD -> "world_cape"
              }
          val candidatePaths =
              listOf(
                  Path.of("frontend", "public", "data", fileName),
                  Path.of("build", "generated", "frontend", "static", "data", fileName),
                  Path.of("static", "data", fileName),
              )
          val text =
              candidatePaths.firstOrNull { Files.exists(it) }?.let { Files.readString(it) }
                  ?: Thread.currentThread().contextClassLoader
                      .getResource("static/data/$fileName")
                      ?.readText()
                  ?: error("CAPE history CSV not found: $fileName")
          val lines = text.trim().lineSequence().filter { it.isNotBlank() }.toList()
          val headers = lines.first().split(",")
          val dateIndex = headers.indexOf("date")
          val capeIndex = headers.indexOf(valueColumn)
          if (dateIndex < 0 || capeIndex < 0) error("Invalid CAPE CSV header: $fileName")
          val points =
              lines.drop(1).mapNotNull { line ->
                val cols = splitCsvLine(line)
                val date = cols.getOrNull(dateIndex)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val cape = cols.getOrNull(capeIndex)?.toDoubleOrNull()
                if (date != null && cape != null && cape > 0.0) CapePoint(date, cape) else null
              }.sortedBy { it.date }
          if (points.isEmpty()) error("No CAPE rows loaded from $fileName")
          CapeHistory(points).also { capeCache[source] = it }
        }
      }

  private fun splitCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val ch = line[i]
      when {
        ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
          field.append('"')
          i++
        }
        ch == '"' -> inQuotes = !inQuotes
        ch == ',' && !inQuotes -> {
          fields.add(field.toString())
          field.clear()
        }
        else -> field.append(ch)
      }
      i++
    }
    fields.add(field.toString())
    return fields
  }

  fun run(request: RebalanceStrategyRequest): MultiBacktestResult {
    val fromDate = request.fromDate?.let { LocalDate.parse(it) }
    val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
    val effrx = BacktestService.loadEffrxSeries()
    val historyFrom = LocalDate.of(1990, 1, 1)
    val portfolioNeededFrom = fromDate ?: historyFrom

    val referenceTickers = request.strategies.flatMap { it.referenceTickers() }.distinct()
    val standaloneMarginTickers =
        request.strategies.flatMap { it.standaloneMarginReferenceTickers() }.distinct()
    val referenceTickerSet = referenceTickers.toSet()
    val requestedTickers =
        (request.portfolio.tickers.map { it.ticker } + referenceTickers + standaloneMarginTickers)
            .distinct()
    fun neededFromForTicker(ticker: String) =
        if (ticker in referenceTickerSet) historyFrom else portfolioNeededFrom
    fun earlierDate(a: LocalDate, b: LocalDate) = if (a <= b) a else b

    // Load LETF definitions and component series
    val letfDefs = mutableMapOf<String, LETFDefinition>()
    for (ticker in requestedTickers) {
      BacktestService.parseLETFDefinition(ticker)?.let { letfDefs.putIfAbsent(ticker, it) }
    }
    val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
    fun cachedLoad(ticker: String, neededFrom: LocalDate) =
        seriesCache.getOrPut(ticker) { BacktestService.loadNormalizedSeries(ticker, neededFrom) }

    val componentNeededFrom = mutableMapOf<String, LocalDate>()
    for ((letfTicker, def) in letfDefs) {
      val parentNeededFrom = neededFromForTicker(letfTicker)
      for (comp in def.components) {
        componentNeededFrom.merge(comp.ticker, parentNeededFrom, ::earlierDate)
      }
    }
    for ((ticker, neededFrom) in componentNeededFrom) {
      cachedLoad(ticker, neededFrom)
    }

    val letfComponentSeries =
        letfDefs.values.flatMap { it.components }.mapNotNull { seriesCache[it.ticker] }
    val letfDates =
        if (letfComponentSeries.isNotEmpty())
            BacktestService.intersectDates(letfComponentSeries, null, toDate)
        else emptyList()
    if (letfDates.size >= 2) {
      for ((letfString, def) in letfDefs) {
        if (letfString !in seriesCache) {
          val componentSeries = def.components.associate { it.ticker to seriesCache[it.ticker]!! }
          seriesCache[letfString] =
              BacktestService.computeLetfSeries(
                  def,
                  componentSeries,
                  letfDates,
                  effrx,
                  def.rebalanceStrategy,
              )
        }
      }
    }
    for (ticker in requestedTickers) {
      if (BacktestService.parseLETFDefinition(ticker) == null) {
        // Reference tickers need pre-fromDate history for drawdown and peak/trough triggers.
        // Portfolio-only tickers do not; loading only from fromDate keeps synthetic tests and
        // short local datasets from falling through to Yahoo for irrelevant older data.
        cachedLoad(ticker, neededFromForTicker(ticker))
      }
    }

    val seriesMap: Map<String, Map<LocalDate, Double>> =
        requestedTickers.associateWith { ticker ->
          seriesCache[ticker] ?: error("Series for '$ticker' not found")
        }
    val portfolioSeries = request.portfolio.tickers.map { tw ->
      seriesMap[tw.ticker] ?: error("Series for '${tw.ticker}' not found")
    }
    val dates = BacktestService.intersectDates(portfolioSeries, fromDate, toDate)
    if (dates.size < 2) throw IllegalStateException("Not enough overlapping trading dates")

    val baseResult =
        BacktestService.runMulti(
            MultiBacktestRequest(
                request.fromDate,
                request.toDate,
                listOf(request.portfolio.copy(rebalanceStrategies = emptyList())),
                request.cashflow,
                request.startingBalance,
            )
        )
    val strategyResults =
        request.strategies.map { strategy ->
          val strategyPortfolio = strategy.portfolioWithRebalanceOverride(request.portfolio)
          val curve =
              runStrategy(
                  strategyPortfolio,
                  strategy,
                  request.cashflow,
                  seriesMap,
                  dates,
                  effrx,
                  request.startingBalance,
                  request.includeActionDiagnostics,
              )
          val defaultBaseMargins =
              curve.marginPoints?.map { it.value } ?: List(dates.size) { strategy.marginRatio }
          val standaloneBaseMarginCache = mutableMapOf<String, List<Double>>()
          fun baseMarginsFor(derived: DerivedSubStrategyConfig): List<Double> {
            val ticker =
                if (derived.marginReferenceSource == DerivedMarginReferenceSource.STANDALONE_TICKER)
                    normalizeReferenceTicker(derived.marginReferenceTicker)
                else null
            if (ticker == null) return defaultBaseMargins
            return standaloneBaseMarginCache.getOrPut(ticker) {
              val standalonePortfolio =
                  strategyPortfolio.copy(
                      label = "${strategyPortfolio.label} / $ticker",
                      tickers = listOf(TickerWeight(ticker, 1.0)),
                      marginStrategies = emptyList(),
                      rebalanceStrategies = emptyList(),
                      includeNoMargin = true,
                  )
              val standaloneCurve =
                  runStrategy(
                      standalonePortfolio,
                      strategy,
                      request.cashflow,
                      seriesMap,
                      dates,
                      effrx,
                      request.startingBalance,
                      includeActionDiagnostics = false,
                  )
              standaloneCurve.marginPoints?.map { it.value } ?: List(dates.size) { strategy.marginRatio }
            }
          }
          val derivedCurves =
              strategy.derivedSubStrategies
                  .filter { it.enabled }
                  .map { derived ->
                    val baseMargins = baseMarginsFor(derived)
                    val derivedCurve =
                        runStrategy(
                            strategyPortfolio,
                            strategy.copy(
                                label = "${strategy.label} / ${derived.label}",
                                sellOnHighMargin = null,
                                buyOnLowMargin = null,
                                drawdownSellOnHighMargin = null,
                                drawdownBuyOnLowMargin = null,
                                vmTimingMr = null,
                                buyTheDip = null,
                                sellOnSurge = null,
                                buyTheDipConfigs = emptyList(),
                                sellOnSurgeConfigs = emptyList(),
                                derivedSubStrategies = emptyList(),
                            ),
                            request.cashflow,
                            seriesMap,
                            dates,
                            effrx,
                            request.startingBalance,
                            request.includeActionDiagnostics,
                            derived,
                            baseMargins,
                        )
                    derivedCurve
                  }
          PortfolioResult(request.portfolio.label, listOf(curve) + derivedCurves)
        }
    return MultiBacktestResult(baseResult.portfolios + strategyResults)
  }

  fun scoreBatch(request: RebalanceStrategyScoreBatchRequest): List<Double> {
    val portfolios = request.portfolios.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing portfolios")
    val referenceTickers = request.strategies.flatMap { it.referenceTickers() }.distinct()
    val contexts = portfolios.map { portfolio ->
      portfolio to prepareRunContext(
          request.fromDate,
          request.toDate,
          portfolio,
          referenceTickers,
      )
    }

    return request.strategies.indices
        .toList()
        .parallelStream()
        .map { strategyIndex ->
          val strategy = request.strategies[strategyIndex]
          val candidateRebalance = request.portfolioRebalanceStrategies.getOrNull(strategyIndex)
          val scores = contexts.map { (portfolio, context) ->
            val candidatePortfolio =
                candidateRebalance?.let { portfolio.copy(rebalanceStrategy = it) }
                    ?: strategy.portfolioWithRebalanceOverride(portfolio)
            scoreCandidate(
                candidatePortfolio,
                strategy,
                request.cashflow,
                context,
                request.startingBalance,
                request.metric,
                request.blockedCrossValidation,
            )
          }
          if (scores.isEmpty()) Double.NEGATIVE_INFINITY else scores.average()
        }
        .toList()
  }

  fun runAttachedStrategies(
      fromDate: String?,
      toDate: String?,
      portfolio: PortfolioConfig,
      cashflow: CashflowConfig?,
      strategies: List<RebalStrategyConfig>,
      startingBalance: Double = 10_000.0,
      globalDates: List<LocalDate>? = null,
      includeActionDiagnostics: Boolean = false,
  ): List<CurveResult> {
    if (strategies.isEmpty()) return emptyList()
    val referenceTickers = strategies.flatMap { it.referenceTickers() }.distinct()
    val context = prepareRunContext(fromDate, toDate, portfolio, referenceTickers, globalDates)
    return strategies.map { strategy ->
      runStrategy(
          strategy.portfolioWithRebalanceOverride(portfolio),
          strategy,
          cashflow,
          context.seriesMap,
          context.dates,
          context.effrx,
          startingBalance,
          includeActionDiagnostics,
      )
    }
  }

  private data class RunContext(
      val seriesMap: Map<String, Map<LocalDate, Double>>,
      val dates: List<LocalDate>,
      val effrx: Map<LocalDate, Double>,
  )

  private fun scoreCandidate(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      cashflow: CashflowConfig?,
      context: RunContext,
      startingBalance: Double,
      metric: RebalanceOptimizationMetric,
      blockedCrossValidation: BlockedCrossValidationConfig?,
  ): Double {
    val stats =
        if (blockedCrossValidation == null) {
          runStrategy(
              portfolio,
              strategy,
              cashflow,
              context.seriesMap,
              context.dates,
              context.effrx,
              startingBalance,
          ).stats
        } else {
          scoreBlockedCrossValidationStats(
              portfolio,
              strategy,
              cashflow,
              context,
              startingBalance,
              blockedCrossValidation,
          ) ?: return Double.NEGATIVE_INFINITY
        }
    return metricValue(stats, metric)
  }

  private fun scoreBlockedCrossValidationStats(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      cashflow: CashflowConfig?,
      context: RunContext,
      startingBalance: Double,
      config: BlockedCrossValidationConfig,
  ): BacktestStats? {
    val blocks = splitDateBlocks(context.dates, config.blocks)
    if (blocks.isEmpty()) return null
    val validationBlock = config.validationBlock.coerceIn(0, blocks.lastIndex)
    val segments =
        if (config.mode == BlockedCrossValidationScoreMode.VALIDATION) {
          listOf(blocks[validationBlock])
        } else {
          listOfNotNull(
              blocks.take(validationBlock).flatten().takeIf { it.size >= 2 },
              blocks.drop(validationBlock + 1).flatten().takeIf { it.size >= 2 },
          )
        }
    if (segments.isEmpty()) return null

    val curves =
        segments.map { segmentDates ->
          runStrategy(
              portfolio,
              strategy,
              cashflow,
              context.seriesMap,
              segmentDates,
              context.effrx,
              startingBalance,
          )
        }
    return mergedSegmentStats(curves, context.effrx, startingBalance)
  }

  private fun splitDateBlocks(dates: List<LocalDate>, requestedBlocks: Int): List<List<LocalDate>> {
    if (dates.size < 4) return emptyList()
    val blockCount = requestedBlocks.coerceIn(2, dates.size / 2)
    val baseSize = dates.size / blockCount
    val remainder = dates.size % blockCount
    var start = 0
    return (0 until blockCount).map { blockIndex ->
      val size = baseSize + if (blockIndex < remainder) 1 else 0
      dates.subList(start, start + size).also { start += size }
    }
  }

  private fun mergedSegmentStats(
      curves: List<CurveResult>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double,
  ): BacktestStats? {
    val values = mutableListOf<Double>()
    var activeYears = 0.0
    for (curve in curves) {
      if (curve.points.size < 2) continue
      val segmentValues = curve.points.map { it.value }
      val segmentDates = curve.points.map { LocalDate.parse(it.date) }
      val segmentBase =
          when {
            startingBalance > 0.0 -> startingBalance
            segmentValues.first() > 0.0 -> segmentValues.first()
            else -> 1.0
          }
      val scale =
          if (values.isEmpty()) 1.0
          else values.last() / segmentBase
      val scaled = segmentValues.map { it * scale }
      if (values.isEmpty()) values.addAll(scaled) else values.addAll(scaled.drop(1))
      activeYears += (segmentDates.last().toEpochDay() - segmentDates.first().toEpochDay()) / 365.25
    }
    if (values.size < 2) return null
    val stats = computeStats(values, activeYears, BacktestService.computeRfAnnualized(effrx))
    return BacktestStats(
        stats.cagr,
        stats.maxDrawdown,
        stats.sharpe,
        stats.ulcerIndex,
        stats.upi,
        stats.annualVolatility,
        stats.longestDrawdownDays,
        values.last(),
    )
  }

  private fun metricValue(stats: BacktestStats, metric: RebalanceOptimizationMetric): Double =
      when (metric) {
        RebalanceOptimizationMetric.CAGR -> stats.cagr
        RebalanceOptimizationMetric.SHARPE -> stats.sharpe
        RebalanceOptimizationMetric.UPI -> stats.upi
      }

  private fun normalizeReferenceTicker(ticker: String?): String? =
      ticker?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

  private fun DipSurgeConfig.normalizedReferenceTicker(): String? =
      if (scope == DipSurgeScope.BASE_PORTFOLIO &&
          portfolioSource == PortfolioTriggerSource.REFERENCE_PORTFOLIO
      ) {
        normalizeReferenceTicker(referenceTicker)
      } else {
        null
      }

  private fun DrawdownMarginOverrideConfig.normalizedReferenceTicker(): String? =
      if (portfolioSource == PortfolioTriggerSource.REFERENCE_PORTFOLIO) {
        normalizeReferenceTicker(referenceTicker)
      } else {
        null
      }

  private fun DrawdownMarginTriggerAction.normalizedReferenceTicker(): String? =
      if (portfolioSource == PortfolioTriggerSource.REFERENCE_PORTFOLIO) {
        normalizeReferenceTicker(referenceTicker)
      } else {
        null
      }

  private fun VmTimingMrConfig.normalizedReferenceTicker(): String? =
      if (momentumSource == PortfolioTriggerSource.REFERENCE_PORTFOLIO) {
        normalizeReferenceTicker(momentumReferenceTicker)
      } else {
        null
      }

  private fun RebalStrategyConfig.referenceTickers(): Set<String> =
      ((listOfNotNull(buyTheDip, sellOnSurge) + buyTheDipConfigs + sellOnSurgeConfigs)
          .mapNotNull { it.normalizedReferenceTicker() } +
          listOfNotNull(
              drawdownMarginOverride?.normalizedReferenceTicker(),
              drawdownBuyOnLowMargin?.normalizedReferenceTicker(),
              drawdownSellOnHighMargin?.normalizedReferenceTicker(),
              vmTimingMr?.normalizedReferenceTicker(),
          ))
          .toSet()

  private fun RebalStrategyConfig.standaloneMarginReferenceTickers(): Set<String> =
      derivedSubStrategies
          .mapNotNull {
            if (it.marginReferenceSource == DerivedMarginReferenceSource.STANDALONE_TICKER) {
              normalizeReferenceTicker(it.marginReferenceTicker)
            } else {
              null
            }
          }
          .toSet()

  private fun prepareRunContext(
      fromDateText: String?,
      toDateText: String?,
      portfolio: PortfolioConfig,
      referenceTickers: Collection<String> = emptyList(),
      overrideDates: List<LocalDate>? = null,
  ): RunContext {
    val fromDate = fromDateText?.let { LocalDate.parse(it) }
    val toDate = toDateText?.let { LocalDate.parse(it) } ?: LocalDate.now()
    val effrx = BacktestService.loadEffrxSeries()
    val historyFrom = LocalDate.of(1990, 1, 1)
    val portfolioNeededFrom = fromDate ?: historyFrom

    val requestedTickers = (portfolio.tickers.map { it.ticker } + referenceTickers).distinct()
    val referenceTickerSet = referenceTickers.toSet()
    fun neededFromForTicker(ticker: String) =
        if (ticker in referenceTickerSet) historyFrom else portfolioNeededFrom
    fun earlierDate(a: LocalDate, b: LocalDate) = if (a <= b) a else b
    val letfDefs = mutableMapOf<String, LETFDefinition>()
    for (ticker in requestedTickers) {
      BacktestService.parseLETFDefinition(ticker)?.let { letfDefs.putIfAbsent(ticker, it) }
    }
    val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
    fun cachedLoad(ticker: String, neededFrom: LocalDate) =
        seriesCache.getOrPut(ticker) { BacktestService.loadNormalizedSeries(ticker, neededFrom) }

    val componentNeededFrom = mutableMapOf<String, LocalDate>()
    for ((letfTicker, def) in letfDefs) {
      val parentNeededFrom = neededFromForTicker(letfTicker)
      for (comp in def.components) {
        componentNeededFrom.merge(comp.ticker, parentNeededFrom, ::earlierDate)
      }
    }
    for ((ticker, neededFrom) in componentNeededFrom) {
      cachedLoad(ticker, neededFrom)
    }

    val letfComponentSeries =
        letfDefs.values.flatMap { it.components }.mapNotNull { seriesCache[it.ticker] }
    val letfDates =
        if (letfComponentSeries.isNotEmpty())
            BacktestService.intersectDates(letfComponentSeries, null, toDate)
        else emptyList()
    if (letfDates.size >= 2) {
      for ((letfString, def) in letfDefs) {
        if (letfString !in seriesCache) {
          val componentSeries = def.components.associate { it.ticker to seriesCache[it.ticker]!! }
          seriesCache[letfString] =
              BacktestService.computeLetfSeries(
                  def,
                  componentSeries,
                  letfDates,
                  effrx,
                  def.rebalanceStrategy,
              )
        }
      }
    }
    for (ticker in requestedTickers) {
      if (BacktestService.parseLETFDefinition(ticker) == null) {
        // Reference tickers need pre-fromDate history for drawdown and peak/trough triggers.
        // Portfolio-only tickers do not; loading only from fromDate keeps synthetic tests and
        // short local datasets from falling through to Yahoo for irrelevant older data.
        cachedLoad(ticker, neededFromForTicker(ticker))
      }
    }

    val rawSeriesMap: Map<String, Map<LocalDate, Double>> =
        requestedTickers.associateWith { ticker ->
          seriesCache[ticker] ?: error("Series for '$ticker' not found")
        }

    if (overrideDates != null) return RunContext(rawSeriesMap, overrideDates, effrx)

    val portfolioSeries = portfolio.tickers.map { tw ->
      rawSeriesMap[tw.ticker] ?: error("Series for '${tw.ticker}' not found")
    }
    val dates = BacktestService.intersectDates(portfolioSeries, fromDate, toDate)
    if (dates.size < 2) throw IllegalStateException("Not enough overlapping trading dates")

    return RunContext(rawSeriesMap, dates, effrx)
  }

  internal fun runStrategyForTest(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      cashflow: CashflowConfig?,
      seriesMap: Map<String, Map<LocalDate, Double>>,
      dates: List<LocalDate>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double = 10_000.0,
      includeActionDiagnostics: Boolean = false,
  ): List<Double> =
      runStrategy(portfolio, strategy, cashflow, seriesMap, dates, effrx, startingBalance, includeActionDiagnostics)
          .points
          .map { it.value }

  internal fun runStrategyResultForTest(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      cashflow: CashflowConfig?,
      seriesMap: Map<String, Map<LocalDate, Double>>,
      dates: List<LocalDate>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double = 10_000.0,
      includeActionDiagnostics: Boolean = false,
  ): CurveResult =
      runStrategy(portfolio, strategy, cashflow, seriesMap, dates, effrx, startingBalance, includeActionDiagnostics)

  internal fun runDerivedStrategyResultForTest(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      derivedSubStrategy: DerivedSubStrategyConfig,
      baseMarginSeries: List<Double>,
      cashflow: CashflowConfig?,
      seriesMap: Map<String, Map<LocalDate, Double>>,
      dates: List<LocalDate>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double = 10_000.0,
      includeActionDiagnostics: Boolean = false,
  ): CurveResult =
      runStrategy(
          portfolio,
          strategy,
          cashflow,
          seriesMap,
          dates,
          effrx,
          startingBalance,
          includeActionDiagnostics,
          derivedSubStrategy,
          baseMarginSeries,
      )

  // ── Core simulation ───────────────────────────────────────────────────────

  private fun runStrategy(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      cashflow: CashflowConfig?,
      seriesMap: Map<String, Map<LocalDate, Double>>,
      dates: List<LocalDate>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double = 10_000.0,
      includeActionDiagnostics: Boolean = false,
      derivedSubStrategy: DerivedSubStrategyConfig? = null,
      baseMarginSeries: List<Double>? = null,
  ): CurveResult {
    val (tickers, targetWeights) = portfolio.mergeWeights()
    val normalRebalance = portfolio.rebalanceStrategy
    val marginRebalance =
        if (strategy.marginRebalanceEnabled) strategy.rebalancePeriod.toMarginRebalanceStrategy()
        else RebalanceStrategy.NONE
    val vmTimingMr = strategy.vmTimingMr?.takeIf { it.enabled }
    val vmTimingRebalance =
        vmTimingMr?.rebalancePeriod?.toMarginRebalanceStrategy() ?: RebalanceStrategy.NONE
    val vmTimingCapeHistory = vmTimingMr?.let { loadCapeHistory(it.capeSource) }
    val drawdownMarginOverride =
        strategy.drawdownMarginOverride?.takeIf { strategy.marginRebalanceEnabled && it.enabled }
    val derivedTargetRuntime = derivedSubStrategy?.let { DerivedTargetRuntime.from(it.scale) }
    fun baseMarginAt(recordedIndex: Int): Double? {
      val derived = derivedSubStrategy ?: return null
      val baseMargins = baseMarginSeries ?: return null
      if (baseMargins.isEmpty()) return null
      return baseMargins.getOrNull(recordedIndex.coerceIn(0, baseMargins.lastIndex))
    }
    fun initialDerivedTargetAt(recordedIndex: Int): Double? {
      val baseMargin = baseMarginAt(recordedIndex) ?: return null
      return derivedTargetRuntime?.initialTarget(baseMargin)
    }
    fun dynamicDerivedTargetAt(recordedIndex: Int): DerivedTargetSignal? {
      val baseMargin = baseMarginAt(recordedIndex) ?: return null
      return derivedTargetRuntime?.target(baseMargin)
    }

    val marginTarget = initialDerivedTargetAt(0) ?: strategy.marginRatio
    val comfortLowBound = strategy.comfortZoneLow
    val comfortHighBound = strategy.comfortZoneHigh
    val globalCooldown =
        ReverseDirectionCooldown(
            strategy.buyCooldownAfterSellHighDays,
            strategy.sellCooldownAfterBuyLowDays,
        )

    val account = PaperTradingPortfolio(tickers, targetWeights, startingBalance, marginTarget)

    val allDipSurgeConfigs =
        listOfNotNull(strategy.buyTheDip, strategy.sellOnSurge) +
            strategy.buyTheDipConfigs +
            strategy.sellOnSurgeConfigs
    val singleTickerReferenceTickers =
        (allDipSurgeConfigs.mapNotNull { it.normalizedReferenceTicker() } +
            listOfNotNull(
                drawdownMarginOverride?.normalizedReferenceTicker(),
                strategy.drawdownBuyOnLowMargin?.normalizedReferenceTicker(),
                strategy.drawdownSellOnHighMargin?.normalizedReferenceTicker(),
                vmTimingMr?.normalizedReferenceTicker(),
            )).distinct()
    val returnRatios = BacktestService.buildReturnRatios(tickers, seriesMap, dates)
    val singleTickerReferenceReturnRatios =
        BacktestService.buildReturnRatios(singleTickerReferenceTickers, seriesMap, dates)
    val singleTickerReferenceValues =
        singleTickerReferenceTickers.associateWith { 1.0 }.toMutableMap()
    val dailyLoanRates =
        BacktestService.buildDailyLoanRates(dates, effrx, strategy.marginSpread / 252.0)
    val dailyBaseReferenceHoldings =
        tickers.associateWith { targetWeights[it] ?: 0.0 }.toMutableMap()
    var dailyBaseReferenceValue = dailyBaseReferenceHoldings.values.sum()

    fun portfolioTriggerValue(key: DipSurgeKey.Portfolio): Double =
        when (key.source) {
          PortfolioTriggerSource.STRATEGY_GROSS -> account.grossStockValue()
          PortfolioTriggerSource.STRATEGY_VALUE -> account.equity()
          PortfolioTriggerSource.REFERENCE_PORTFOLIO ->
              key.referenceTicker?.let { singleTickerReferenceValues[it] } ?: dailyBaseReferenceValue
        }

    val firstDate = dates.first()

    fun tickerHistoryValues(ticker: String, normalizeLastToOne: Boolean): List<Double> {
      val series = seriesMap[ticker] ?: return emptyList()
      val historyDates = (series.keys + firstDate)
          .filter { it <= firstDate }
          .distinct()
          .sorted()
      val filled = BacktestService.forwardFillSeries(series, historyDates)
      val values = historyDates.mapNotNull { filled[it] }
      if (!normalizeLastToOne || values.isEmpty()) return values
      val last = values.last().takeIf { it > 0.0 } ?: return values
      return values.map { it / last }
    }

    fun baseReferenceHistoryPoints(): List<Pair<LocalDate, Double>> {
      val historyDates = BacktestService.intersectDates(
          tickers.mapNotNull { seriesMap[it] },
          null,
          firstDate,
      )
      if (historyDates.isEmpty()) return emptyList()
      val holdings = tickers.associateWith { targetWeights[it] ?: 0.0 }.toMutableMap()
      val ratios = BacktestService.buildReturnRatios(tickers, seriesMap, historyDates)
      val values = mutableListOf(holdings.values.sum())
      for (i in 1 until historyDates.size) {
        for (ticker in tickers) {
          holdings[ticker] = (holdings[ticker] ?: 0.0) * (ratios[ticker]?.get(i) ?: 1.0)
        }
        val total = holdings.values.sum()
        for (ticker in tickers) {
          holdings[ticker] = total * (targetWeights[ticker] ?: 0.0)
        }
        values.add(total)
      }
      val last = values.lastOrNull()?.takeIf { it > 0.0 } ?: return emptyList()
      return historyDates.zip(values.map { it / last })
    }

    val baseReferencePreStartHistory = baseReferenceHistoryPoints()

    fun baseReferenceHistoryValues(): List<Double> =
        baseReferencePreStartHistory.map { it.second }

    fun portfolioReferenceHistoryValues(key: DipSurgeKey.Portfolio): List<Double> =
        when (key.source) {
          PortfolioTriggerSource.REFERENCE_PORTFOLIO ->
              key.referenceTicker?.let { tickerHistoryValues(it, normalizeLastToOne = true) }
                  ?: baseReferenceHistoryValues()
          PortfolioTriggerSource.STRATEGY_GROSS,
          PortfolioTriggerSource.STRATEGY_VALUE -> listOf(portfolioTriggerValue(key))
        }

    fun seededDrawdownPeak(key: DipSurgeKey.Portfolio): Double =
        portfolioReferenceHistoryValues(key)
            .maxOrNull()
            ?: portfolioTriggerValue(key)

    val drawdownOverrideKey =
        drawdownMarginOverride?.let {
          DipSurgeKey.Portfolio(it.portfolioSource, it.normalizedReferenceTicker())
        }
    var drawdownOverridePeak = drawdownOverrideKey?.let { seededDrawdownPeak(it) } ?: Double.NaN
    var drawdownOverrideActive = false
    var drawdownOverrideAnchorIndex: Int? = null
    var drawdownOverrideLastRebalanceDate: LocalDate? = null
    var drawdownOverrideLastRebalanceIndex: Int? = null
    var marginRebalanceResumeAnchorDate: LocalDate? = null
    var deferredMarginRebalanceIndex: Int? = null
    var deferredDrawdownOverrideRebalanceIndex: Int? = null

    data class DrawdownMarginTriggerTierRuntime(
        val config: DrawdownMarginTriggerTier,
        var activeReferencePeak: Double = Double.NaN,
        var active: Boolean = false,
    )

    data class DrawdownMarginTriggerRuntime(
        val config: DrawdownMarginTriggerAction,
        val key: DipSurgeKey.Portfolio,
        var peak: Double,
        val tiers: List<DrawdownMarginTriggerTierRuntime>,
    ) {
      fun activeTier(): DrawdownMarginTriggerTier? =
          tiers
              .filter { it.active }
              .maxByOrNull { it.config.enterDrawdownPct }
              ?.config
    }

    fun DrawdownMarginTriggerAction.toRuntime(): DrawdownMarginTriggerRuntime {
      val key = DipSurgeKey.Portfolio(portfolioSource, normalizedReferenceTicker())
      return DrawdownMarginTriggerRuntime(
          this,
          key,
          seededDrawdownPeak(key),
          effectiveTiers()
              .sortedWith(compareBy<DrawdownMarginTriggerTier> { it.enterDrawdownPct }.thenBy { it.exitDrawdownPct })
              .map { DrawdownMarginTriggerTierRuntime(it) },
      )
    }

    val drawdownBuyLowRuntime = strategy.drawdownBuyOnLowMargin?.toRuntime()
    val drawdownSellHighRuntime = strategy.drawdownSellOnHighMargin?.toRuntime()

    val values = mutableListOf(startingBalance)
    val marginUtils = mutableListOf(marginTarget)
    val actionPoints = mutableListOf<ActionPoint>()
    val baseReferenceValues = mutableListOf(dailyBaseReferenceValue)
    val strategyGrossValues = mutableListOf(account.grossStockValue())
    val strategyEquityValues = mutableListOf(account.equity())
    val vmTimingPoints = mutableListOf<VmTimingPoint>()
    var lastDerivedActionIndex: Int? = null

    fun recordVmTimingPoint(date: LocalDate) {
      val valuation = vmTimingCapeHistory?.valuationFactor(date) ?: return
      val (valueFactor, capeValue) = valuation
      vmTimingPoints.add(VmTimingPoint(date.toString(), capeValue, valueFactor))
    }

    if (vmTimingMr != null) recordVmTimingPoint(firstDate)

    // ── Pre-loop: build trigger checkers and executors ────────────────────
    fun DipSurgeConfig.buildResources(): DipSurgeResources {
      val keys =
          if (scope == DipSurgeScope.INDIVIDUAL_STOCK) tickers.map { DipSurgeKey.Stock(it) }
          else listOf(DipSurgeKey.Portfolio(portfolioSource, normalizedReferenceTicker()))
      return DipSurgeResources(
          checkersByKey = keys.associateWith { key -> triggers.map { it.buildChecker(key) } },
          executorsByKey = keys.associateWith { method.newExecutor() },
          allocStrategy = allocStrategy,
          limit = limit,
          cooldown = DipSurgeCooldown(coolingOffDays),
          minAdjustmentPct = minAdjustmentPct.coerceAtLeast(0.0),
      )
    }

    val dipResources = (listOfNotNull(strategy.buyTheDip) + strategy.buyTheDipConfigs)
        .map { it.buildResources() }
    val surgeResources = (listOfNotNull(strategy.sellOnSurge) + strategy.sellOnSurgeConfigs)
        .map { it.buildResources() }

    fun advanceInitialCheckers(res: DipSurgeResources) {
      for ((key, checkers) in res.checkersByKey) {
        val values =
            when (key) {
              is DipSurgeKey.Stock -> tickerHistoryValues(key.ticker, normalizeLastToOne = false)
              is DipSurgeKey.Portfolio -> portfolioReferenceHistoryValues(key)
            }
        val seedValues = values.ifEmpty {
          listOf(
              when (key) {
                is DipSurgeKey.Stock -> seriesMap[key.ticker]!![firstDate]!!
                is DipSurgeKey.Portfolio -> portfolioTriggerValue(key)
              }
          )
        }
        for (value in seedValues) checkers.forEach { it.advance(value) }
      }
    }
    dipResources.forEach { advanceInitialCheckers(it) }
    surgeResources.forEach { advanceInitialCheckers(it) }

    fun dateIndexOnOrBefore(targetDate: LocalDate, maxIndex: Int): Int {
      var lo = 0
      var hi = maxIndex.coerceAtMost(dates.lastIndex)
      var result = -1
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (dates[mid] <= targetDate) {
          result = mid
          lo = mid + 1
        } else {
          hi = mid - 1
        }
      }
      return result
    }

    fun valueOnOrBefore(points: List<Pair<LocalDate, Double>>, targetDate: LocalDate): Double? {
      var result: Double? = null
      for ((date, value) in points) {
        if (date > targetDate) break
        result = value
      }
      return result
    }

    fun seriesValueOnOrBefore(ticker: String, targetDate: LocalDate): Double? =
        seriesMap[ticker]
            ?.entries
            ?.asSequence()
            ?.filter { it.key <= targetDate }
            ?.maxByOrNull { it.key }
            ?.value

    fun portfolioMomentumReturn(
        key: DipSurgeKey.Portfolio,
        lookbackMonths: Int,
        curIndex: Int,
        curDate: LocalDate,
    ): Double? {
      val lookbackDate = curDate.minusMonths(lookbackMonths.coerceAtLeast(1).toLong())
      val currentValue: Double
      val startValue: Double
      when (key.source) {
        PortfolioTriggerSource.STRATEGY_GROSS -> {
          currentValue = account.grossStockValue()
          val lookbackIndex = dateIndexOnOrBefore(lookbackDate, minOf(curIndex - 1, strategyGrossValues.lastIndex))
          if (lookbackIndex < 0) return null
          startValue = strategyGrossValues.getOrNull(lookbackIndex)?.takeIf { it > 0.0 } ?: return null
        }
        PortfolioTriggerSource.STRATEGY_VALUE -> {
          currentValue = account.equity()
          val lookbackIndex = dateIndexOnOrBefore(lookbackDate, minOf(curIndex - 1, strategyEquityValues.lastIndex))
          if (lookbackIndex < 0) return null
          startValue = strategyEquityValues.getOrNull(lookbackIndex)?.takeIf { it > 0.0 } ?: return null
        }
        PortfolioTriggerSource.REFERENCE_PORTFOLIO -> {
          if (key.referenceTicker != null) {
            val referenceTicker = key.referenceTicker
            currentValue = seriesValueOnOrBefore(referenceTicker, curDate)?.takeIf { it > 0.0 } ?: return null
            startValue = seriesValueOnOrBefore(referenceTicker, lookbackDate)?.takeIf { it > 0.0 } ?: return null
          } else {
            currentValue = dailyBaseReferenceValue
            startValue =
                if (lookbackDate < firstDate) {
                  valueOnOrBefore(baseReferencePreStartHistory, lookbackDate)
                } else {
                  val lookbackIndex = dateIndexOnOrBefore(lookbackDate, minOf(curIndex - 1, baseReferenceValues.lastIndex))
                  baseReferenceValues.getOrNull(lookbackIndex)
                }?.takeIf { it > 0.0 } ?: return null
          }
        }
      }
      if (currentValue <= 0.0) return null
      return currentValue / startValue - 1.0
    }

    fun vmMomentumFactor(config: VmTimingMrConfig, curIndex: Int, curDate: LocalDate): Pair<Double, Double>? {
      val key = DipSurgeKey.Portfolio(config.momentumSource, config.normalizedReferenceTicker())
      val momentumReturn =
          portfolioMomentumReturn(key, config.momentumLookbackMonths, curIndex, curDate) ?: return null
      return (if (momentumReturn >= 0.0) 1.0 else 0.0) to momentumReturn
    }

    // ── Per-day helper: check triggers and drive executors for one config ─
    fun processConfig(
        res: DipSurgeResources,
        direction: Direction,
        curIndex: Int,
        curDate: LocalDate,
        actionType: String,
    ) {
      if (globalCooldown.isBlocked(direction, curIndex)) return
      for ((key, checkers) in res.checkersByKey) {
        val rawTriggered = checkers.any { it.check(direction) }
        val triggered = res.cooldown.shouldFire(key, curIndex, rawTriggered)
        val currentValue =
            when (key) {
              is DipSurgeKey.Stock -> seriesMap[key.ticker]!![curDate]!!
              is DipSurgeKey.Portfolio -> portfolioTriggerValue(key)
            }
        var eligibleAmount = 0.0
        res.executorsByKey[key]?.advance(
            triggered,
            currentValue,
            eligible = {
              computeEligible(key, account, targetWeights, direction, res.limit)
                  .also { eligibleAmount = it }
            },
        ) { amount ->
          if (amount > 0.0) {
            val grossBefore = account.grossStockValue()
            val marginBefore =
                if (account.equity() > 0.0) account.currentMarginRatio() else 0.0
            val minAdjustment = grossBefore * res.minAdjustmentPct
            if (amount >= minAdjustment) {
              applyDipSurge(key, tickers, account, targetWeights, amount, direction, res.allocStrategy)
              if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), direction)) {
                val grossAfter = account.grossStockValue()
                val marginAfter =
                    if (account.equity() > 0.0) account.currentMarginRatio() else 0.0
                val detail =
                    if (includeActionDiagnostics) {
                      ActionPointDetail(
                          tradingDayIndex = curIndex,
                          key = key.diagnosticLabel(),
                          direction = direction.name,
                          triggerValue = currentValue,
                          cooldownDays = res.cooldown.coolingOffDays,
                          daysSincePrevious = res.cooldown.daysSinceLastFire(key, curIndex),
                          amount = amount,
                          eligibleAmount = eligibleAmount,
                          minAdjustment = minAdjustment,
                          grossBefore = grossBefore,
                          grossAfter = grossAfter,
                          marginBefore = marginBefore,
                          marginAfter = marginAfter,
                          allocStrategy = res.allocStrategy,
                      )
                    } else {
                      null
                    }
                res.cooldown.recordFire(key, curIndex)
                actionPoints.add(ActionPoint(curDate.toString(), actionType, detail))
              }
            }
          }
        }
      }
    }

    for (i in 1 until dates.size) {
      val prevDate = dates[i - 1]
      val curDate = dates[i]

      val cashBalanceBefore = account.cashBalance()
      val equityBefore = account.equity()

      // Step 1: Apply daily price returns to holdings
      account.applyDayReturns(returnRatios, i)
      for (ticker in tickers) {
        dailyBaseReferenceHoldings[ticker] =
            (dailyBaseReferenceHoldings[ticker] ?: 0.0) * (returnRatios[ticker]?.get(i) ?: 1.0)
      }
      for (ticker in singleTickerReferenceTickers) {
        singleTickerReferenceValues[ticker] =
            (singleTickerReferenceValues[ticker] ?: 1.0) *
                (singleTickerReferenceReturnRatios[ticker]?.get(i) ?: 1.0)
      }
      // Independent reference path: no margin, no cashflow, daily target-weight rebalance.
      val dailyBaseReferenceTotal = dailyBaseReferenceHoldings.values.sum()
      for (ticker in tickers) {
        dailyBaseReferenceHoldings[ticker] = dailyBaseReferenceTotal * (targetWeights[ticker] ?: 0.0)
      }
      dailyBaseReferenceValue = dailyBaseReferenceTotal
      baseReferenceValues.add(dailyBaseReferenceValue)

      // Step 2: Accrue margin interest on debt
      account.accrueMarginInterest(dailyLoanRates[i])

      var currentDrawdown: Double? = null
      drawdownMarginOverride?.let { cfg ->
        drawdownOverrideKey?.let { key ->
          val referenceValue = portfolioTriggerValue(key)
          if (!referenceValue.isNaN()) {
            if (drawdownOverridePeak.isNaN() || referenceValue > drawdownOverridePeak) {
              drawdownOverridePeak = referenceValue
            }
            currentDrawdown =
                if (drawdownOverridePeak > 0.0) (drawdownOverridePeak - referenceValue) / drawdownOverridePeak
                else 0.0
          }
        }
      }
      fun updateDrawdownMarginTrigger(runtime: DrawdownMarginTriggerRuntime?) {
        if (runtime == null) return
        val referenceValue = portfolioTriggerValue(runtime.key)
        if (referenceValue.isNaN()) return
        if (runtime.peak.isNaN() || referenceValue > runtime.peak) {
          runtime.peak = referenceValue
        }
        for (tier in runtime.tiers) {
          if (tier.active) {
            val exitPeak =
                if (!tier.activeReferencePeak.isNaN() && tier.activeReferencePeak > 0.0) {
                  tier.activeReferencePeak
                } else {
                  runtime.peak
                }
            val exitDrawdown =
                if (exitPeak > 0.0) (exitPeak - referenceValue) / exitPeak
                else 0.0
            if (exitDrawdown <= tier.config.exitDrawdownPct) {
              tier.active = false
              tier.activeReferencePeak = Double.NaN
            }
          } else {
            val enterDrawdown =
                if (runtime.peak > 0.0) (runtime.peak - referenceValue) / runtime.peak
                else 0.0
            if (enterDrawdown >= tier.config.enterDrawdownPct.coerceAtLeast(0.0)) {
              tier.active = true
              tier.activeReferencePeak = runtime.peak
            }
          }
        }
      }
      updateDrawdownMarginTrigger(drawdownBuyLowRuntime)
      updateDrawdownMarginTrigger(drawdownSellHighRuntime)
      val enteredDrawdownOverride =
          !drawdownOverrideActive &&
              drawdownMarginOverride?.let { cfg ->
                (currentDrawdown ?: 0.0) >= cfg.enterDrawdownPct.coerceAtLeast(0.0)
              } == true
      if (enteredDrawdownOverride) {
        drawdownOverrideActive = true
        drawdownOverrideAnchorIndex = i
      }

      val derivedReferenceIndex = derivedTargetRuntime?.targetReferenceIndex(i) ?: (i - 1)
      val dynamicTargetSignal = dynamicDerivedTargetAt(derivedReferenceIndex)
      val derivedTargetPaused = dynamicTargetSignal?.adjustmentPaused == true
      val derivedExactTarget = dynamicTargetSignal?.forceExactTarget == true
      val dynamicTargetMargin = dynamicTargetSignal?.targetMargin ?: marginTarget
      val dynamicDeviation = derivedSubStrategy?.absoluteDeviationPct?.coerceAtLeast(0.0)

      fun comfortZoneMargin(useComfortZone: Boolean): Double {
        if (derivedTargetPaused) return account.currentMarginRatio()
        if (derivedExactTarget) return dynamicTargetMargin
        if (dynamicDeviation != null) {
          val currentRatio = account.currentMarginRatio()
          val low = (dynamicTargetMargin - dynamicDeviation).coerceAtLeast(0.0)
          val high = dynamicTargetMargin + dynamicDeviation
          return when {
            currentRatio > high -> high
            currentRatio < low -> low
            else -> currentRatio
          }
        }
        if (!useComfortZone) return marginTarget
        val currentRatio = account.currentMarginRatio()
        return when {
          currentRatio > comfortHighBound -> comfortHighBound
          currentRatio < comfortLowBound -> comfortLowBound
          else -> currentRatio
        }
      }

      val normalRebalanceDay = shouldRebalance(normalRebalance, prevDate, curDate)
      var activeDrawdownOverride = drawdownMarginOverride?.takeIf { drawdownOverrideActive }
      var effectiveMarginRebalance =
          activeDrawdownOverride?.rebalancePeriod?.toMarginRebalanceStrategy() ?: marginRebalance
      val regularDrawdownRebalanceDay =
          activeDrawdownOverride
              ?.takeIf { it.rebalanceOnEnter }
              ?.let { drawdownOverrideAnchorIndex?.let { anchor -> shouldRebalanceFromAnchor(effectiveMarginRebalance, anchor, i) } }
              ?: shouldRebalance(effectiveMarginRebalance, prevDate, curDate)
      val deferredDrawdownRebalanceDue =
          deferredDrawdownOverrideRebalanceIndex?.let { i >= it } == true
      val drawdownOverrideCheckpointDay =
          activeDrawdownOverride != null &&
              if (deferredDrawdownOverrideRebalanceIndex != null) {
                deferredDrawdownRebalanceDue
              } else {
                regularDrawdownRebalanceDay
              }
      val exitedDrawdownOverride =
          activeDrawdownOverride?.let { cfg ->
            !enteredDrawdownOverride &&
                drawdownOverrideCheckpointDay &&
                (currentDrawdown ?: 0.0) <= cfg.exitDrawdownPct.coerceIn(0.0, cfg.enterDrawdownPct.coerceAtLeast(0.0))
          } == true
      val normalRebalanceDueOnDrawdownExit =
          exitedDrawdownOverride &&
              drawdownOverrideLastRebalanceDate?.let { lastDrawdownRebalance ->
                shouldRebalance(marginRebalance, lastDrawdownRebalance, curDate)
              } == true
      if (exitedDrawdownOverride) {
        val exitDaysSincePrevious =
            drawdownOverrideLastRebalanceIndex?.let { i - it }
                ?: drawdownOverrideAnchorIndex?.let { i - it }
        drawdownOverrideActive = false
        drawdownOverrideAnchorIndex = null
        drawdownOverrideLastRebalanceDate = null
        drawdownOverrideLastRebalanceIndex = null
        deferredDrawdownOverrideRebalanceIndex = null
        marginRebalanceResumeAnchorDate = curDate
        if (includeActionDiagnostics) {
          actionPoints.add(
              ActionPoint(
                  curDate.toString(),
                  "DRAWDOWN_MR_EXIT",
                  ActionPointDetail(
                      tradingDayIndex = i,
                      key = drawdownOverrideKey?.diagnosticLabel() ?: "DD-MR",
                      triggerValue = drawdownOverrideKey?.let { portfolioTriggerValue(it) },
                      daysSincePrevious = exitDaysSincePrevious,
                      grossBefore = account.grossStockValue(),
                      grossAfter = account.grossStockValue(),
                      marginBefore = if (account.equity() > 0.0) account.currentMarginRatio() else 0.0,
                      marginAfter = if (account.equity() > 0.0) account.currentMarginRatio() else 0.0,
                  ),
              )
          )
        }
        activeDrawdownOverride = null
        effectiveMarginRebalance = marginRebalance
      }
      val normalMarginRebalanceDue =
          if (normalRebalanceDueOnDrawdownExit) true
          else if (deferredMarginRebalanceIndex != null) {
            i >= deferredMarginRebalanceIndex
          }
          else marginRebalanceResumeAnchorDate?.let { anchor ->
            shouldRebalance(marginRebalance, anchor, curDate)
          } ?: shouldRebalance(marginRebalance, prevDate, curDate)
      val marginRebalanceDay =
          if (activeDrawdownOverride != null) drawdownOverrideCheckpointDay else normalMarginRebalanceDue
      val scheduledMarginRebalancePaused =
          derivedTargetPaused && activeDrawdownOverride == null
      val vmTimingRebalanceDay =
          vmTimingMr != null && shouldRebalance(vmTimingRebalance, prevDate, curDate)

      // Step 3: Normal portfolio rebalance first.
      if (normalRebalanceDay) {
        val eq = account.equity()
        if (eq > 0) {
          val targetTotal = eq * (1.0 + comfortZoneMargin(strategy.portfolioRebalanceUseComfortZone))
          performProportionalRebalance(targetTotal, tickers, targetWeights, account)
          actionPoints.add(ActionPoint(curDate.toString(), "PORTFOLIO_REBALANCE"))
        }
      }
      if (marginRebalanceDay && !scheduledMarginRebalancePaused) {
        // Step 4: Scheduled margin rebalance.
        val drawdownOverrideRebalanceDay = activeDrawdownOverride != null
        var scheduledRebalanceDeferredByCooldown = false
        if (activeDrawdownOverride == null && marginRebalanceResumeAnchorDate != null) {
          marginRebalanceResumeAnchorDate = curDate
        }
        val eq = account.equity()
        if (eq > 0) {
          val tradeDirection =
              activeDrawdownOverride?.tradeDirection ?: strategy.marginRebalanceTradeDirection
          val targetMargin =
              activeDrawdownOverride?.targetMargin ?: if (tradeDirection == MarginRebalanceTradeDirection.BOTH)
                comfortZoneMargin(strategy.useComfortZone)
              else
                strategy.marginRebalanceRestoreMargin ?: dynamicTargetMargin
          val targetTotal = eq * (1.0 + targetMargin)
          val delta = targetTotal - account.grossStockValue()
          val directionAllowed =
              when (tradeDirection) {
                MarginRebalanceTradeDirection.BOTH -> true
                MarginRebalanceTradeDirection.BUY_ONLY -> delta > 0.0
                MarginRebalanceTradeDirection.SELL_ONLY -> delta < 0.0
              }
          if (directionAllowed) {
            val direction =
                if (delta > 0.0) Direction.BUY else if (delta < 0.0) Direction.SELL else null
            val deferredUntilIndex = direction?.let { globalCooldown.nextAllowedIndex(it, i) }
            if (deferredUntilIndex != null) {
              scheduledRebalanceDeferredByCooldown = true
              if (activeDrawdownOverride != null) {
                deferredDrawdownOverrideRebalanceIndex = maxOf(i, deferredUntilIndex)
              } else {
                deferredMarginRebalanceIndex = maxOf(i, deferredUntilIndex)
              }
            }
            if (direction == null || deferredUntilIndex == null) {
              val grossBefore = account.grossStockValue()
              val marginBefore = if (account.equity() > 0.0) account.currentMarginRatio() else 0.0
              val effectiveAllocStrategy =
                  when {
                    activeDrawdownOverride == null -> strategy.rebalanceAllocStrategy
                    delta >= 0.0 -> activeDrawdownOverride.buyAllocStrategy
                    else -> activeDrawdownOverride.sellAllocStrategy
                  }
              applyAllocDelta(
                  tickers,
                  account,
                  targetWeights,
                  delta,
                  effectiveAllocStrategy,
              )
              if (direction != null && tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), direction)) {
                val grossAfter = account.grossStockValue()
                val detail =
                    if (includeActionDiagnostics) {
                      ActionPointDetail(
                          tradingDayIndex = i,
                          key = if (activeDrawdownOverride != null) {
                            drawdownOverrideKey?.diagnosticLabel() ?: "DD-MR"
                          } else {
                            "MR"
                          },
                          direction = direction.name,
                          triggerValue = drawdownOverrideKey?.let { portfolioTriggerValue(it) },
                          daysSincePrevious = if (activeDrawdownOverride != null) {
                            drawdownOverrideLastRebalanceIndex?.let { i - it }
                                ?: drawdownOverrideAnchorIndex?.let { anchor -> i - anchor }
                          } else {
                            null
                          },
                          amount = kotlin.math.abs(delta),
                          grossBefore = grossBefore,
                          grossAfter = grossAfter,
                          marginBefore = marginBefore,
                          marginAfter = if (account.equity() > 0.0) account.currentMarginRatio() else 0.0,
                          allocStrategy = effectiveAllocStrategy,
                      )
                    } else {
                      null
                    }
                actionPoints.add(
                    ActionPoint(
                        curDate.toString(),
                        if (activeDrawdownOverride != null) "DRAWDOWN_MR" else "MARGIN_REBALANCE",
                        detail,
                    )
                )
                if (activeDrawdownOverride == null) marginRebalanceResumeAnchorDate = curDate
                if (activeDrawdownOverride != null) {
                  deferredDrawdownOverrideRebalanceIndex = null
                } else {
                  deferredMarginRebalanceIndex = null
                }
              }
            }
          }
        }
        if (!scheduledRebalanceDeferredByCooldown) {
          if (activeDrawdownOverride != null) {
            deferredDrawdownOverrideRebalanceIndex = null
          } else {
            deferredMarginRebalanceIndex = null
          }
        }
        if (drawdownOverrideRebalanceDay && !scheduledRebalanceDeferredByCooldown) {
          drawdownOverrideLastRebalanceDate = curDate
          drawdownOverrideLastRebalanceIndex = i
        }
      }

      if (vmTimingRebalanceDay) {
        val activeVmTimingMr = requireNotNull(vmTimingMr)
        val activeVmTimingCapeHistory = requireNotNull(vmTimingCapeHistory)
        val eq = account.equity()
        val valuation = activeVmTimingCapeHistory.valuationFactor(curDate)
        val momentum = vmMomentumFactor(activeVmTimingMr, i, curDate)
        if (eq > 0 && valuation != null && momentum != null) {
          val (valuationFactor, capeValue) = valuation
          val (momentumFactor, _) = momentum
          val vmFactor = ((valuationFactor + momentumFactor) / 2.0).coerceIn(0.0, 1.0)
          val lower = minOf(activeVmTimingMr.lowerMargin, activeVmTimingMr.upperMargin)
          val upper = maxOf(activeVmTimingMr.lowerMargin, activeVmTimingMr.upperMargin)
          val targetMargin = lower + vmFactor * (upper - lower)
          val targetTotal = eq * (1.0 + targetMargin)
          val delta = targetTotal - account.grossStockValue()
          val direction =
              if (delta > 0.0) Direction.BUY else if (delta < 0.0) Direction.SELL else null
          val grossBefore = account.grossStockValue()
          val marginBefore = if (account.equity() > 0.0) account.netMarginRatio() else 0.0
          applyAllocDelta(tickers, account, targetWeights, delta, activeVmTimingMr.allocStrategy)
          if (direction != null && tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), direction)) {
            val detail =
                if (includeActionDiagnostics) {
                  ActionPointDetail(
                      tradingDayIndex = i,
                      key = "VM:${activeVmTimingMr.capeSource.name}:${activeVmTimingMr.momentumLookbackMonths}M",
                      direction = direction.name,
                      triggerValue = capeValue,
                      amount = kotlin.math.abs(delta),
                      grossBefore = grossBefore,
                      grossAfter = account.grossStockValue(),
                      marginBefore = marginBefore,
                      marginAfter = if (account.equity() > 0.0) account.netMarginRatio() else 0.0,
                      allocStrategy = activeVmTimingMr.allocStrategy,
                  )
                } else {
                  null
                }
            actionPoints.add(ActionPoint(curDate.toString(), "VM_TIMING_MR", detail))
          }
        }
      }

      // Step 5: Margin deviation triggers (sell on high / buy on low margin).
      // These explicit trigger sections are independent of the scheduled margin
      // rebalance trade-direction filter above.
      if (equityBefore > 0) {
        val currentRatio = (-cashBalanceBefore).coerceAtLeast(0.0) / equityBefore
        val derivedTimeoutBlocked =
            derivedSubStrategy
                ?.let { derived ->
                  lastDerivedActionIndex?.let { last -> i - last <= derived.timeoutDays.coerceAtLeast(0) }
                }
                ?: false
        val derivedUsesPostPriceMargin = derivedTargetRuntime?.usesPostPriceMarginForTriggers == true
        val triggerCurrentRatio =
            if (derivedUsesPostPriceMargin) account.currentMarginRatio() else currentRatio
        val derivedMaxSell =
            derivedSubStrategy?.takeIf {
              !derivedTargetPaused && triggerCurrentRatio > it.maxMargin.coerceAtLeast(0.0)
            }

        val effectiveSellHigh =
            drawdownSellHighRuntime
                ?.activeTier()
                ?.let { MarginTriggerAction(it.triggerMargin, it.allocStrategy, it.targetMargin) }
                ?: if (derivedSubStrategy != null) {
                  if (derivedTargetPaused) {
                    null
                  } else {
                    derivedMaxSell?.let {
                      MarginTriggerAction(
                          it.maxMargin.coerceAtLeast(0.0),
                          it.sellAllocStrategy,
                          dynamicTargetMargin,
                      )
                    }
                        ?: MarginTriggerAction(
                            if (derivedExactTarget) dynamicTargetMargin
                            else dynamicTargetMargin + derivedSubStrategy.sellDeviationPct.coerceAtLeast(0.0),
                            derivedSubStrategy.sellAllocStrategy,
                            dynamicTargetMargin,
                        )
                  }
                } else {
                  strategy.sellOnHighMargin
                }
        effectiveSellHigh
            ?.takeIf { it.deviationPct > 0 }
            ?.let { cfg ->
              val forcedDerivedSell = derivedMaxSell != null
              if ((forcedDerivedSell || derivedExactTarget || (!derivedTimeoutBlocked && !globalCooldown.isBlocked(Direction.SELL, i))) &&
                  triggerCurrentRatio > cfg.deviationPct
              ) {
                val targetCashBalance = -account.equity() * cfg.targetMargin
                if (account.cashBalance() < targetCashBalance) {
                  val excess = targetCashBalance - account.cashBalance()
                  val grossBefore = account.grossStockValue()
                  applyAllocDelta(tickers, account, targetWeights, -excess, cfg.allocStrategy)
                  if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), Direction.SELL)) {
                    globalCooldown.recordSellHigh(i)
                    if (derivedSubStrategy != null) lastDerivedActionIndex = i
                    actionPoints.add(ActionPoint(curDate.toString(), "SELL_HIGH"))
                  }
                }
              }
            }

        val activeDrawdownBuyLowTier = drawdownBuyLowRuntime?.activeTier()
        val activeDrawdownBuyLowRuntime = drawdownBuyLowRuntime?.takeIf { activeDrawdownBuyLowTier != null }
        val drawdownBuyLowMomentumAllowed =
            if (activeDrawdownBuyLowRuntime == null) {
              true
            } else {
              activeDrawdownBuyLowRuntime.config.momentumLookbackMonths
                  ?.let { months ->
                    portfolioMomentumReturn(activeDrawdownBuyLowRuntime.key, months, i, curDate)
                        ?.let { it > 0.0 } == true
                  }
                  ?: true
            }
        val effectiveBuyLow =
            activeDrawdownBuyLowTier
                ?.let { MarginTriggerAction(it.triggerMargin, it.allocStrategy, it.targetMargin) }
                ?: if (derivedSubStrategy != null) {
                  if (derivedTargetPaused) {
                    null
                  } else {
                    MarginTriggerAction(
                        if (derivedExactTarget) dynamicTargetMargin
                        else (dynamicTargetMargin - derivedSubStrategy.buyDeviationPct.coerceAtLeast(0.0)).coerceAtLeast(0.0),
                        derivedSubStrategy.buyAllocStrategy,
                        dynamicTargetMargin,
                    )
                  }
                } else {
                  strategy.buyOnLowMargin
                }
        effectiveBuyLow
            ?.takeIf { it.deviationPct > 0 }
            ?.let { cfg ->
              if (drawdownBuyLowMomentumAllowed &&
                  (derivedExactTarget || (!derivedTimeoutBlocked && !globalCooldown.isBlocked(Direction.BUY, i))) &&
                  triggerCurrentRatio < cfg.deviationPct
              ) {
                val targetCashBalance = -account.equity() * cfg.targetMargin
                if (account.cashBalance() > targetCashBalance) {
                  val deficit = account.cashBalance() - targetCashBalance
                  val grossBefore = account.grossStockValue()
                  applyAllocDelta(tickers, account, targetWeights, deficit, cfg.allocStrategy)
                  if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), Direction.BUY)) {
                    globalCooldown.recordBuyLow(i)
                    if (derivedSubStrategy != null) lastDerivedActionIndex = i
                    actionPoints.add(ActionPoint(curDate.toString(), "BUY_LOW"))
                  }
                }
              }
            }
      }

      // Step 6: Cashflow injection (if due)
      if (cashflow != null && isCashflowDate(cashflow.frequency, curDate)) {
        val raw = cashflow.amount
        val currentMarginRatio =
            if (account.equity() > 0) account.currentMarginRatio() else marginTarget
        val scaleFactor =
            strategy.cashflowScalingMargin?.let { 1.0 + it }
                ?: when (strategy.cashflowScaling) {
                  CashflowScaling.SCALED_BY_TARGET_MARGIN ->
                      1.0 + if (derivedTargetPaused) currentMarginRatio else dynamicTargetMargin
                  CashflowScaling.SCALED_BY_CURRENT_MARGIN -> 1.0 + currentMarginRatio
                  CashflowScaling.NO_SCALING -> 1.0
                }
        val totalInvest = raw * strategy.cashflowImmediateInvestPct * scaleFactor
        account.deposit(raw)
        for (ticker in tickers) account.buy(ticker, totalInvest * (targetWeights[ticker] ?: 0.0))
      }

      // Step 7: Advance all trigger checkers with today's values
      fun advanceCheckers(res: DipSurgeResources) {
        for ((key, checkers) in res.checkersByKey) {
          val v =
              when (key) {
                is DipSurgeKey.Stock -> seriesMap[key.ticker]!![curDate]!!
                is DipSurgeKey.Portfolio -> portfolioTriggerValue(key)
              }
          checkers.forEach { it.advance(v) }
        }
      }
      dipResources.forEach { advanceCheckers(it) }
      surgeResources.forEach { advanceCheckers(it) }

      // Step 8: Buy-the-dip — check triggers + executor.advance
      dipResources.forEach { processConfig(it, Direction.BUY, i, curDate, "BUY_DIP") }

      // Step 9: Sell-on-surge — check triggers + executor.advance
      surgeResources.forEach { processConfig(it, Direction.SELL, i, curDate, "SELL_SURGE") }

      // Step 10: Record equity
      val equity = max(0.0, account.equity())
      marginUtils.add(
          if (equity > 0.0) account.netMarginRatio() else 0.0
      )
      values.add(equity)
      strategyGrossValues.add(account.grossStockValue())
      strategyEquityValues.add(account.equity())
      if (vmTimingMr != null) recordVmTimingPoint(curDate)
    }

    val points = dates.mapIndexed { i, d -> DataPoint(d.toString(), values[i]) }
    val marginPoints =
        if (marginTarget > 0.0) dates.mapIndexed { i, d -> DataPoint(d.toString(), marginUtils[i]) }
        else null
    val stats = BacktestService.computeBacktestStats(values, dates, effrx)
    return CurveResult(
        strategy.label,
        points,
        stats,
        marginPoints,
        actionPoints.takeIf { it.isNotEmpty() },
        vmTimingPoints.takeIf { it.isNotEmpty() },
    )
  }

  // ── Eligible amount calculation ───────────────────────────────────────────

  private fun computeEligible(
      key: DipSurgeKey,
      account: PaperTradingPortfolio,
      targetWeights: Map<String, Double>,
      direction: Direction,
      limit: Double,
  ): Double {
    val grossValue = account.grossStockValue()
    val equity = account.equity()
    if (equity <= 0) return 0.0

    return when (key) {
      is DipSurgeKey.Portfolio -> {
        val currentRatio = (-account.cashBalance()).coerceAtLeast(0.0) / equity
        if (direction == Direction.BUY) {
          max(0.0, equity * (limit - currentRatio))
        } else {
          max(0.0, equity * (currentRatio - limit))
        }
      }

      is DipSurgeKey.Stock -> {
        val cur = account.holding(key.ticker)
        val targetWeight = targetWeights[key.ticker] ?: 0.0
        if (direction == Direction.BUY) {
          val target = targetWeight * equity * (1 + limit) - cur
          val capPortfolioValueAdjust = equity * (1 + limit) - grossValue
          maxOf(0.0, target.coerceAtMost(capPortfolioValueAdjust))
        } else {
          val target = cur - targetWeight * equity * (1 + limit)
          val capPortfolioValueAdjust = grossValue - equity * (1 + limit)
          maxOf(0.0, target.coerceAtMost(capPortfolioValueAdjust))
        }
      }
    }
  }

  // ── Allocation helpers ────────────────────────────────────────────────────

  private fun applyAllocDelta(
      tickers: List<String>,
      account: PaperTradingPortfolio,
      targetWeights: Map<String, Double>,
      delta: Double,
      allocStrategy: String?,
  ) {
    val holdings = tickers.associateWith { account.holding(it) }
    val deltas = BacktestService.computeAllocationDeltas(
        tickers,
        holdings,
        targetWeights,
        delta,
        allocStrategy ?: MarginRebalanceMode.PROPORTIONAL.name
    )
    for (ticker in tickers) {
      account.applyTradeDelta(ticker, deltas[ticker] ?: 0.0)
    }
  }

  private fun applyDipSurge(
      key: DipSurgeKey,
      tickers: List<String>,
      account: PaperTradingPortfolio,
      targetWeights: Map<String, Double>,
      amount: Double,
      direction: Direction,
      allocStrategy: String?,
  ) {
    val delta = if (direction == Direction.BUY) amount else -amount
    when (key) {
      is DipSurgeKey.Portfolio ->
          applyAllocDelta(tickers, account, targetWeights, delta, allocStrategy)
      is DipSurgeKey.Stock ->
          account.applyTradeDelta(key.ticker, delta)
    }
  }

  // ── Utility ───────────────────────────────────────────────────────────────

  private fun performProportionalRebalance(
      targetTotal: Double,
      tickers: List<String>,
      targetWeights: Map<String, Double>,
      account: PaperTradingPortfolio,
  ) {
    for (ticker in tickers) {
      val target = targetTotal * (targetWeights[ticker] ?: 0.0)
      val diff = target - account.holding(ticker)
      account.applyTradeDelta(ticker, diff)
    }
  }

  private fun isCashflowDate(frequency: CashflowFrequency, date: LocalDate): Boolean =
      when (frequency) {
        CashflowFrequency.NONE -> false
        CashflowFrequency.MONTHLY -> date.dayOfMonth == 1
        CashflowFrequency.QUARTERLY ->
            date.dayOfMonth == 1 && date.monthValue in listOf(1, 4, 7, 10)

        CashflowFrequency.YEARLY -> date.dayOfMonth == 1 && date.monthValue == 1
      }

  private fun shouldRebalance(
      strategy: RebalanceStrategy,
      prev: LocalDate,
      cur: LocalDate,
  ): Boolean =
      when (strategy) {
        RebalanceStrategy.NONE -> false
        RebalanceStrategy.DAILY -> true
        RebalanceStrategy.WEEKLY ->
            cur.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) !=
                prev.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) || cur.year != prev.year

        RebalanceStrategy.BI_WEEKLY -> biWeeklyBucket(cur) != biWeeklyBucket(prev)
        RebalanceStrategy.MONTHLY -> cur.month != prev.month
        RebalanceStrategy.BI_MONTHLY -> monthBucket(cur, 2) != monthBucket(prev, 2)
        RebalanceStrategy.QUARTERLY -> (cur.monthValue - 1) / 3 != (prev.monthValue - 1) / 3
        RebalanceStrategy.EVERY_4_MONTHS -> monthBucket(cur, 4) != monthBucket(prev, 4)
        RebalanceStrategy.HALF_YEARLY -> monthBucket(cur, 6) != monthBucket(prev, 6)

        RebalanceStrategy.YEARLY -> cur.year != prev.year
      }

  private fun shouldRebalanceFromAnchor(
      strategy: RebalanceStrategy,
      anchorIndex: Int,
      curIndex: Int,
  ): Boolean {
    val elapsed = curIndex - anchorIndex
    if (elapsed < 0) return false
    return when (strategy) {
      RebalanceStrategy.NONE -> false
      RebalanceStrategy.DAILY -> true
      RebalanceStrategy.WEEKLY -> elapsed % 5 == 0
      RebalanceStrategy.BI_WEEKLY -> elapsed % 10 == 0
      RebalanceStrategy.MONTHLY -> elapsed % 21 == 0
      RebalanceStrategy.BI_MONTHLY -> elapsed % 42 == 0
      RebalanceStrategy.QUARTERLY -> elapsed % 63 == 0
      RebalanceStrategy.EVERY_4_MONTHS -> elapsed % 84 == 0
      RebalanceStrategy.HALF_YEARLY -> elapsed % 126 == 0
      RebalanceStrategy.YEARLY -> elapsed % 252 == 0
    }
  }
}

// ── Extension / helpers ───────────────────────────────────────────────────────

private fun tradeMovedGrossInDirection(before: Double, after: Double, direction: Direction): Boolean {
  val epsilon = 1e-9
  return if (direction == Direction.BUY) after > before + epsilon else after < before - epsilon
}

private fun DipSurgeKey.diagnosticLabel(): String =
    when (this) {
      is DipSurgeKey.Stock -> "STOCK:$ticker"
      is DipSurgeKey.Portfolio -> "PORTFOLIO:${source.name}${referenceTicker?.let { ":$it" } ?: ""}"
    }

private fun RebalancePeriodOverride.toMarginRebalanceStrategy(): RebalanceStrategy =
    when (this) {
      RebalancePeriodOverride.INHERIT -> RebalanceStrategy.NONE
      RebalancePeriodOverride.NONE -> RebalanceStrategy.NONE
      RebalancePeriodOverride.DAILY -> RebalanceStrategy.DAILY
      RebalancePeriodOverride.WEEKLY -> RebalanceStrategy.WEEKLY
      RebalancePeriodOverride.BI_WEEKLY -> RebalanceStrategy.BI_WEEKLY
      RebalancePeriodOverride.MONTHLY -> RebalanceStrategy.MONTHLY
      RebalancePeriodOverride.BI_MONTHLY -> RebalanceStrategy.BI_MONTHLY
      RebalancePeriodOverride.QUARTERLY -> RebalanceStrategy.QUARTERLY
      RebalancePeriodOverride.EVERY_4_MONTHS -> RebalanceStrategy.EVERY_4_MONTHS
      RebalancePeriodOverride.HALF_YEARLY -> RebalanceStrategy.HALF_YEARLY
      RebalancePeriodOverride.YEARLY -> RebalanceStrategy.YEARLY
    }

private fun RebalancePeriodOverride.toPortfolioRebalanceStrategy(base: RebalanceStrategy): RebalanceStrategy =
    when (this) {
      RebalancePeriodOverride.INHERIT -> base
      RebalancePeriodOverride.NONE -> RebalanceStrategy.NONE
      RebalancePeriodOverride.DAILY -> RebalanceStrategy.DAILY
      RebalancePeriodOverride.WEEKLY -> RebalanceStrategy.WEEKLY
      RebalancePeriodOverride.BI_WEEKLY -> RebalanceStrategy.BI_WEEKLY
      RebalancePeriodOverride.MONTHLY -> RebalanceStrategy.MONTHLY
      RebalancePeriodOverride.BI_MONTHLY -> RebalanceStrategy.BI_MONTHLY
      RebalancePeriodOverride.QUARTERLY -> RebalanceStrategy.QUARTERLY
      RebalancePeriodOverride.EVERY_4_MONTHS -> RebalanceStrategy.EVERY_4_MONTHS
      RebalancePeriodOverride.HALF_YEARLY -> RebalanceStrategy.HALF_YEARLY
      RebalancePeriodOverride.YEARLY -> RebalanceStrategy.YEARLY
    }

private fun RebalStrategyConfig.portfolioWithRebalanceOverride(portfolio: PortfolioConfig): PortfolioConfig {
  val effective = portfolioRebalancePeriod.toPortfolioRebalanceStrategy(portfolio.rebalanceStrategy)
  return if (effective == portfolio.rebalanceStrategy) portfolio else portfolio.copy(rebalanceStrategy = effective)
}

private fun biWeeklyBucket(date: LocalDate): Long =
    ChronoUnit.WEEKS.between(LocalDate.of(1970, 1, 5), date) / 2

private fun monthBucket(date: LocalDate, monthsPerBucket: Int): Int =
    date.year * 12 + (date.monthValue - 1) / monthsPerBucket

// ── Pre-loop resources ────────────────────────────────────────────────────────

private data class DerivedTargetSignal(
    val targetMargin: Double?,
    val adjustmentPaused: Boolean = false,
    val forceExactTarget: Boolean = false,
)

private sealed interface DerivedTargetRuntime {
  val usesPostPriceMarginForTriggers: Boolean
    get() = false

  fun initialTarget(baseMargin: Double): Double

  fun target(baseMargin: Double): DerivedTargetSignal

  fun targetReferenceIndex(currentIndex: Int): Int = currentIndex - 1

  companion object {
    fun from(scale: DerivedTargetScaleConfig): DerivedTargetRuntime =
        when (scale.function) {
          DerivedTargetScaleFunction.SIGMOID -> SigmoidDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.ADAPTIVE_LOW_SIGMOID -> AdaptiveLowSigmoidDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.LINEAR -> LinearDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.STEP -> StepDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.HYSTERESIS_STEP -> HysteresisStepDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.HYSTERESIS_STAIRS -> HysteresisStairsDerivedTargetRuntime(scale)
        }
  }
}

private abstract class DerivedTargetRuntimeBase(
    protected val scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntime {
  override fun initialTarget(baseMargin: Double): Double =
      requireNotNull(target(baseMargin).targetMargin)
}

private abstract class InterpolatedDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  private val configuredRefLow = minOf(scale.referenceLower, scale.referenceUpper)
  private val refHigh = maxOf(scale.referenceLower, scale.referenceUpper)
  private val configuredTargetLow = minOf(scale.targetLower, scale.targetUpper)
  private val targetHigh = maxOf(scale.targetLower, scale.targetUpper)

  protected open fun refLow(baseMargin: Double): Double = configuredRefLow

  protected open fun targetLow(baseMargin: Double): Double = configuredTargetLow

  protected abstract fun shape(normalized: Double): Double

  override fun target(baseMargin: Double): DerivedTargetSignal {
    val lowRef = refLow(baseMargin)
    val lowTarget = targetLow(baseMargin)
    val refSpan = refHigh - lowRef
    val normalized =
        if (refSpan > 0.0) ((baseMargin - lowRef) / refSpan).coerceIn(0.0, 1.0)
        else 0.5
    return DerivedTargetSignal(lowTarget + shape(normalized) * (targetHigh - lowTarget))
  }
}

private open class SigmoidDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : InterpolatedDerivedTargetRuntime(scale) {
  override fun shape(normalized: Double): Double {
    val k = scale.sigmoidSteepness.takeIf { it.isFinite() && it > 0.0 } ?: 8.0
    return 1.0 / (1.0 + exp(-k * (normalized - 0.5)))
  }
}

private class AdaptiveLowSigmoidDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : SigmoidDerivedTargetRuntime(scale) {
  private val configuredRefLow = minOf(scale.referenceLower, scale.referenceUpper)
  private val configuredTargetLow = minOf(scale.targetLower, scale.targetUpper)

  override fun refLow(baseMargin: Double): Double =
      if (baseMargin < configuredRefLow) baseMargin else configuredRefLow

  override fun targetLow(baseMargin: Double): Double =
      if (baseMargin < configuredTargetLow) baseMargin else configuredTargetLow
}

private class LinearDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : InterpolatedDerivedTargetRuntime(scale) {
  override fun shape(normalized: Double): Double = normalized
}

private class StepDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  override fun target(baseMargin: Double): DerivedTargetSignal {
    var target = scale.stepBaseTarget
    for (step in scale.steps.sortedBy { it.referenceMargin }) {
      if (baseMargin >= step.referenceMargin) target = step.targetMargin
    }
    return DerivedTargetSignal(target.coerceAtLeast(0.0))
  }
}

private class HysteresisStepDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  private enum class Stage { TARGET_HIGH, NO_TARGET, TARGET_LOW }

  override val usesPostPriceMarginForTriggers: Boolean = true

  private var stage = Stage.TARGET_HIGH

  override fun initialTarget(baseMargin: Double): Double = highTarget

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(baseMargin: Double): DerivedTargetSignal {
    if (stage != Stage.TARGET_HIGH && baseMargin > resetThreshold) {
      stage = Stage.TARGET_HIGH
      return DerivedTargetSignal(highTarget)
    }

    return when (stage) {
      Stage.TARGET_HIGH -> targetFromHighStage(baseMargin)
      Stage.NO_TARGET -> targetFromNoTargetStage(baseMargin)
      Stage.TARGET_LOW -> DerivedTargetSignal(fixedTarget)
    }
  }

  private fun targetFromHighStage(baseMargin: Double): DerivedTargetSignal =
      when {
        baseMargin < exitThreshold -> {
          stage = Stage.TARGET_LOW
          DerivedTargetSignal(fixedTarget)
        }
        baseMargin < enterThreshold -> {
          stage = Stage.NO_TARGET
          noTargetSignal()
        }
        else -> DerivedTargetSignal(highTarget)
      }

  private fun targetFromNoTargetStage(baseMargin: Double): DerivedTargetSignal =
      if (baseMargin < exitThreshold) {
        stage = Stage.TARGET_LOW
        DerivedTargetSignal(fixedTarget)
      } else {
        noTargetSignal()
      }

  private fun noTargetSignal(): DerivedTargetSignal =
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)

  private val enterThreshold = maxOf(scale.referenceLower, scale.referenceUpper)
  private val exitThreshold = minOf(scale.referenceLower, scale.referenceUpper)
  private val resetThreshold =
      if (scale.stepBaseTarget.isFinite() && scale.stepBaseTarget > enterThreshold) {
        scale.stepBaseTarget
      } else {
        Double.POSITIVE_INFINITY
      }
  private val highTarget = scale.targetUpper.coerceAtLeast(0.0)
  private val fixedTarget = scale.targetLower.coerceAtLeast(0.0)
}

private class HysteresisStairsDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  private data class Stair(val referenceMargin: Double, val targetMargin: Double)

  override val usesPostPriceMarginForTriggers: Boolean = true

  private val stairs =
      scale.steps
          .filter { it.referenceMargin.isFinite() && it.targetMargin.isFinite() }
          .sortedByDescending { it.referenceMargin }
          .map { Stair(it.referenceMargin, it.targetMargin.coerceAtLeast(0.0)) }
  private val highestReference = stairs.firstOrNull()?.referenceMargin ?: Double.NEGATIVE_INFINITY
  private val resetThreshold =
      if (scale.stepBaseTarget.isFinite() && scale.stepBaseTarget > highestReference) {
        scale.stepBaseTarget
      } else {
        Double.POSITIVE_INFINITY
      }
  private val highTarget = scale.targetUpper.coerceAtLeast(0.0)
  private var nextStairIndex = 0

  override fun initialTarget(baseMargin: Double): Double = highTarget

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(baseMargin: Double): DerivedTargetSignal {
    if (nextStairIndex > 0 && baseMargin > resetThreshold) {
      nextStairIndex = 0
      return DerivedTargetSignal(highTarget, forceExactTarget = true)
    }

    val crossedIndex =
        stairs
            .withIndex()
            .drop(nextStairIndex)
            .lastOrNull { (_, stair) -> baseMargin < stair.referenceMargin }
            ?.index

    if (crossedIndex != null) {
      nextStairIndex = crossedIndex + 1
      return DerivedTargetSignal(stairs[crossedIndex].targetMargin, forceExactTarget = true)
    }

    return if (nextStairIndex == 0) {
      DerivedTargetSignal(highTarget)
    } else {
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
    }
  }
}

private data class DipSurgeResources(
    val checkersByKey: Map<DipSurgeKey, List<TriggerChecker>>,
    val executorsByKey: Map<DipSurgeKey, DipSurgeExecutor>,
    val allocStrategy: String?,
    val limit: Double,
    val cooldown: DipSurgeCooldown,
    val minAdjustmentPct: Double,
)

internal class DipSurgeCooldown(coolingOffDays: Int = 10) {
  val coolingOffDays = coolingOffDays.coerceAtLeast(0)
  private val lastTriggerIndexByKey = mutableMapOf<DipSurgeKey, Int>()

  fun shouldFire(key: DipSurgeKey, curIndex: Int, rawTriggered: Boolean): Boolean {
    if (!rawTriggered) return false
    val lastTriggerIndex = lastTriggerIndexByKey[key]
    if (lastTriggerIndex != null && curIndex - lastTriggerIndex <= coolingOffDays) return false
    return true
  }

  fun recordFire(key: DipSurgeKey, curIndex: Int) {
    lastTriggerIndexByKey[key] = curIndex
  }

  fun daysSinceLastFire(key: DipSurgeKey, curIndex: Int): Int? =
      lastTriggerIndexByKey[key]?.let { curIndex - it }
}

private class ReverseDirectionCooldown(
    buyCooldownAfterSellHighDays: Int,
    sellCooldownAfterBuyLowDays: Int,
) {
  private val buyDays = buyCooldownAfterSellHighDays.coerceAtLeast(0)
  private val sellDays = sellCooldownAfterBuyLowDays.coerceAtLeast(0)
  private var lastSellHighIndex: Int? = null
  private var lastBuyLowIndex: Int? = null

  fun recordSellHigh(curIndex: Int) {
    lastSellHighIndex = curIndex
  }

  fun recordBuyLow(curIndex: Int) {
    lastBuyLowIndex = curIndex
  }

  fun isBlocked(direction: Direction, curIndex: Int): Boolean =
      when (direction) {
        Direction.BUY -> buyDays > 0 && lastSellHighIndex?.let { curIndex - it <= buyDays } == true
        Direction.SELL -> sellDays > 0 && lastBuyLowIndex?.let { curIndex - it <= sellDays } == true
      }

  fun nextAllowedIndex(direction: Direction, curIndex: Int): Int? =
      when (direction) {
        Direction.BUY ->
            lastSellHighIndex
                ?.takeIf { buyDays > 0 && curIndex - it <= buyDays }
                ?.let { it + buyDays + 1 }
        Direction.SELL ->
            lastBuyLowIndex
                ?.takeIf { sellDays > 0 && curIndex - it <= sellDays }
                ?.let { it + sellDays + 1 }
      }
}

private fun PaperTradingPortfolio.applyTradeDelta(ticker: String, amount: Double) {
  if (amount > 0.0) buy(ticker, amount)
  else if (amount < 0.0) sell(ticker, -amount)
}

private fun PaperTradingPortfolio.netMarginRatio(): Double {
  val eq = equity()
  return if (eq > 0.0) -cashBalance() / eq else 0.0
}
