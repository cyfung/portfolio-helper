package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.max

object RebalanceStrategyService {

  fun run(request: RebalanceStrategyRequest): MultiBacktestResult {
    val fromDate = request.fromDate?.let { LocalDate.parse(it) }
    val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
    val effrx = BacktestService.loadEffrxSeries()
    val historyFrom = LocalDate.of(1990, 1, 1)
    val portfolioNeededFrom = fromDate ?: historyFrom

    val referenceTickers = request.strategies.flatMap { it.referenceTickers() }.distinct()
    val referenceTickerSet = referenceTickers.toSet()
    val requestedTickers = (request.portfolio.tickers.map { it.ticker } + referenceTickers).distinct()
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
          PortfolioResult(request.portfolio.label, listOf(curve))
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

  private fun RebalStrategyConfig.referenceTickers(): Set<String> =
      ((listOfNotNull(buyTheDip, sellOnSurge) + buyTheDipConfigs + sellOnSurgeConfigs)
          .mapNotNull { it.normalizedReferenceTicker() } +
          listOfNotNull(
              drawdownMarginOverride?.normalizedReferenceTicker(),
              drawdownBuyOnLowMargin?.normalizedReferenceTicker(),
              drawdownSellOnHighMargin?.normalizedReferenceTicker(),
          ))
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
  ): CurveResult {
    val (tickers, targetWeights) = portfolio.mergeWeights()
    val normalRebalance = portfolio.rebalanceStrategy
    val marginRebalance =
        if (strategy.marginRebalanceEnabled) strategy.rebalancePeriod.toMarginRebalanceStrategy()
        else RebalanceStrategy.NONE
    val drawdownMarginOverride =
        strategy.drawdownMarginOverride?.takeIf { strategy.marginRebalanceEnabled && it.enabled }
    val marginTarget = strategy.marginRatio
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

    fun baseReferenceHistoryValues(): List<Double> {
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
      val last = values.lastOrNull()?.takeIf { it > 0.0 } ?: return values
      return values.map { it / last }
    }

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

    data class DrawdownMarginTriggerRuntime(
        val config: DrawdownMarginTriggerAction,
        val key: DipSurgeKey.Portfolio,
        var peak: Double,
        var activeReferencePeak: Double = Double.NaN,
        var active: Boolean = false,
    )

    fun DrawdownMarginTriggerAction.toRuntime(): DrawdownMarginTriggerRuntime {
      val key = DipSurgeKey.Portfolio(portfolioSource, normalizedReferenceTicker())
      return DrawdownMarginTriggerRuntime(this, key, seededDrawdownPeak(key))
    }

    val drawdownBuyLowRuntime = strategy.drawdownBuyOnLowMargin?.toRuntime()
    val drawdownSellHighRuntime = strategy.drawdownSellOnHighMargin?.toRuntime()

    val values = mutableListOf(startingBalance)
    val marginUtils = mutableListOf(marginTarget)
    val actionPoints = mutableListOf<ActionPoint>()

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
        if (runtime.active) {
          val exitPeak =
              if (!runtime.activeReferencePeak.isNaN() && runtime.activeReferencePeak > 0.0) {
                runtime.activeReferencePeak
              } else {
                runtime.peak
              }
          val exitDrawdown =
              if (exitPeak > 0.0) (exitPeak - referenceValue) / exitPeak
              else 0.0
          if (exitDrawdown <= runtime.config.exitDrawdownPct) {
            runtime.active = false
            runtime.activeReferencePeak = Double.NaN
          }
        } else {
          val enterDrawdown =
              if (runtime.peak > 0.0) (runtime.peak - referenceValue) / runtime.peak
              else 0.0
          if (enterDrawdown >= runtime.config.enterDrawdownPct.coerceAtLeast(0.0)) {
            runtime.active = true
            runtime.activeReferencePeak = runtime.peak
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

      fun comfortZoneMargin(useComfortZone: Boolean): Double {
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
          !normalRebalanceDay &&
              if (activeDrawdownOverride != null) drawdownOverrideCheckpointDay else normalMarginRebalanceDue

      // Step 3: Normal portfolio rebalance first.
      if (normalRebalanceDay) {
        val eq = account.equity()
        if (eq > 0) {
          val targetTotal = eq * (1.0 + comfortZoneMargin(strategy.portfolioRebalanceUseComfortZone))
          performProportionalRebalance(targetTotal, tickers, targetWeights, account)
          actionPoints.add(ActionPoint(curDate.toString(), "PORTFOLIO_REBALANCE"))
        }
      } else {
        if (marginRebalanceDay) {
          // Step 4: Scheduled margin rebalance only runs on days without normal rebalance.
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
                  strategy.marginRebalanceRestoreMargin ?: marginTarget
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

        // Step 5: Margin deviation triggers (sell on high / buy on low margin).
        // These explicit trigger sections are independent of the scheduled margin
        // rebalance trade-direction filter above.
        if (equityBefore > 0) {
          val currentRatio = (-cashBalanceBefore).coerceAtLeast(0.0) / equityBefore

          val effectiveSellHigh =
              drawdownSellHighRuntime
                  ?.takeIf { it.active }
                  ?.let { MarginTriggerAction(it.config.triggerMargin, it.config.allocStrategy, it.config.targetMargin) }
                  ?: strategy.sellOnHighMargin
          effectiveSellHigh
              ?.takeIf { it.deviationPct > 0 }
              ?.let { cfg ->
                if (!globalCooldown.isBlocked(Direction.SELL, i) && currentRatio > cfg.deviationPct) {
                  val targetCashBalance = -account.equity() * cfg.targetMargin
                  if (account.cashBalance() < targetCashBalance) {
                    val excess = targetCashBalance - account.cashBalance()
                    val grossBefore = account.grossStockValue()
                    applyAllocDelta(tickers, account, targetWeights, -excess, cfg.allocStrategy)
                    if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), Direction.SELL)) {
                      globalCooldown.recordSellHigh(i)
                      actionPoints.add(ActionPoint(curDate.toString(), "SELL_HIGH"))
                    }
                  }
                }
              }

          val effectiveBuyLow =
              drawdownBuyLowRuntime
                  ?.takeIf { it.active }
                  ?.let { MarginTriggerAction(it.config.triggerMargin, it.config.allocStrategy, it.config.targetMargin) }
                  ?: strategy.buyOnLowMargin
          effectiveBuyLow
              ?.takeIf { it.deviationPct > 0 }
              ?.let { cfg ->
                if (!globalCooldown.isBlocked(Direction.BUY, i) && currentRatio < cfg.deviationPct) {
                  val targetCashBalance = -account.equity() * cfg.targetMargin
                  if (account.cashBalance() > targetCashBalance) {
                    val deficit = account.cashBalance() - targetCashBalance
                    val grossBefore = account.grossStockValue()
                    applyAllocDelta(tickers, account, targetWeights, deficit, cfg.allocStrategy)
                    if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), Direction.BUY)) {
                      globalCooldown.recordBuyLow(i)
                      actionPoints.add(ActionPoint(curDate.toString(), "BUY_LOW"))
                    }
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
                  CashflowScaling.SCALED_BY_TARGET_MARGIN -> 1.0 + marginTarget
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
          if (equity > 0.0) (-account.cashBalance()).coerceAtLeast(0.0) / equity else 0.0
      )
      values.add(equity)
    }

    val points = dates.mapIndexed { i, d -> DataPoint(d.toString(), values[i]) }
    val marginPoints =
        if (marginTarget > 0.0) dates.mapIndexed { i, d -> DataPoint(d.toString(), marginUtils[i]) }
        else null
    val stats = BacktestService.computeBacktestStats(values, dates, effrx)
    return CurveResult(strategy.label, points, stats, marginPoints, actionPoints.takeIf { it.isNotEmpty() })
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
