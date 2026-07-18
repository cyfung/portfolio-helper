package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.math.max

object RebalanceStrategyService {
  fun run(request: RebalanceStrategyRequest): MultiBacktestResult {
    val enabledStrategies = request.strategies.filter { it.enabled }
    val referenceTickers = enabledStrategies.flatMap { it.referenceTickers() }.distinct()
    val standaloneMarginTickers =
        enabledStrategies.flatMap { it.standaloneMarginReferenceTickers() }.distinct()
    val warnings = linkedSetOf<String>()
    val warningCollector = { _: String, tickerWarnings: List<String> ->
      warnings.addAll(tickerWarnings)
      Unit
    }
    val context =
        prepareRunContext(
            request.fromDate,
            request.toDate,
            request.portfolio,
            referenceTickers,
            extraTickers = standaloneMarginTickers,
            warningCollector = warningCollector,
        )
    val baseResult = runBasePortfolio(request)
    val standaloneBaseCache = ConcurrentHashMap<StandaloneDerivedReferenceCacheKey, DerivedReferenceSeries>()
    val strategyResults =
        if (enabledStrategies.size > 1) {
          enabledStrategies
              .parallelStream()
              .map { runConfiguredStrategy(request, it, context, standaloneBaseCache) }
              .toList()
        } else {
          enabledStrategies.map { runConfiguredStrategy(request, it, context, standaloneBaseCache) }
        }
    warnings.addAll(baseResult.warnings)
    return MultiBacktestResult(baseResult.portfolios + strategyResults, warnings.toList())
  }

  fun scoreBatch(request: RebalanceStrategyScoreBatchRequest): List<Double> {
    val portfolios = request.portfolios.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing portfolios")
    val enabledStrategies = request.strategies.withIndex().filter { it.value.enabled }
    val referenceTickers = enabledStrategies.flatMap { it.value.referenceTickers() }.distinct()
    val standaloneMarginTickers =
        enabledStrategies.flatMap { it.value.standaloneMarginReferenceTickers() }.distinct()
    val contexts = portfolios.map { portfolio ->
      portfolio to prepareRunContext(
          request.fromDate,
          request.toDate,
          portfolio,
          referenceTickers,
          extraTickers = standaloneMarginTickers,
      )
    }

    return enabledStrategies
        .toList()
        .parallelStream()
        .map { indexedStrategy ->
          val strategyIndex = indexedStrategy.index
          val strategy = indexedStrategy.value
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
      zeroMarginInterest: Boolean = false,
      warningCollector: ((String, List<String>) -> Unit)? = null,
  ): List<CurveResult> {
    val enabledStrategies = strategies.filter { it.enabled }
    if (enabledStrategies.isEmpty()) return emptyList()
    val referenceTickers = enabledStrategies.flatMap { it.referenceTickers() }.distinct()
    val standaloneMarginTickers =
        enabledStrategies.flatMap { it.standaloneMarginReferenceTickers() }.distinct()
    val context =
        prepareRunContext(
            fromDate,
            toDate,
            portfolio,
            referenceTickers,
            extraTickers = standaloneMarginTickers,
            overrideDates = globalDates,
            warningCollector = warningCollector,
        )
    return enabledStrategies.map { strategy ->
      runStrategy(
          strategy.portfolioWithRebalanceOverride(portfolio),
          strategy,
          cashflow,
          context.seriesMap,
          context.dates,
          context.effrx,
          startingBalance,
          includeActionDiagnostics,
          zeroMarginInterest = zeroMarginInterest,
      )
    }
  }

  internal fun requiredReferenceTickers(strategies: List<RebalStrategyConfig>): Set<String> {
    val enabledStrategies = strategies.filter { it.enabled }
    return (enabledStrategies.flatMap { it.referenceTickers() } +
        enabledStrategies.flatMap { it.standaloneMarginReferenceTickers() })
        .toSet()
  }

  internal fun runAttachedStrategiesOnSeries(
      portfolio: PortfolioConfig,
      cashflow: CashflowConfig?,
      strategies: List<RebalStrategyConfig>,
      seriesMap: Map<String, Map<LocalDate, Double>>,
      dates: List<LocalDate>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double = 10_000.0,
      includeActionDiagnostics: Boolean = false,
      zeroMarginInterest: Boolean = false,
  ): List<CurveResult> {
    val context = RunContext(seriesMap, dates, effrx)
    val standaloneBaseCache = ConcurrentHashMap<StandaloneDerivedReferenceCacheKey, DerivedReferenceSeries>()
    return strategies
        .filter { it.enabled }
        .flatMap { strategy ->
          val strategyPortfolio = strategy.portfolioWithRebalanceOverride(portfolio)
          val baseRun =
              runStrategyWithIntentions(
                  strategyPortfolio,
                  strategy,
                  cashflow,
                  context.seriesMap,
                  context.dates,
                  context.effrx,
                  startingBalance,
                  includeActionDiagnostics,
                  zeroMarginInterest = zeroMarginInterest,
              )
          val request =
              RebalanceStrategyRequest(
                  fromDate = null,
                  toDate = null,
                  portfolio = portfolio,
                  cashflow = cashflow,
                  strategies = listOf(strategy),
                  startingBalance = startingBalance,
                  includeActionDiagnostics = includeActionDiagnostics,
                  zeroMarginInterest = zeroMarginInterest,
              )
          val derivedCurves =
              runDerivedSubStrategies(
                  request,
                  portfolio,
                  strategy,
                  context,
                  DerivedReferenceSeries(
                      baseRun.marginHistory,
                      baseRun.marginIntentions,
                  ),
                  standaloneBaseCache,
              )
          if (strategy.baseEnabled) listOf(baseRun.curve) + derivedCurves else derivedCurves
        }
  }

  private data class RunContext(
      val seriesMap: Map<String, Map<LocalDate, Double>>,
      val dates: List<LocalDate>,
      val effrx: Map<LocalDate, Double>,
  )

  private data class DerivedReferenceSeries(
      val margins: List<Double>,
      val marginIntentions: List<List<MarginIntention>>,
  )

  private data class StrategyRunResult(
      val curve: CurveResult,
      val marginIntentions: List<List<MarginIntention>>,
      val marginHistory: List<Double>,
  )

  private data class StandaloneDerivedReferenceCacheKey(
      val ticker: String,
      val strategy: RebalStrategyConfig,
  )

  private fun runBasePortfolio(request: RebalanceStrategyRequest): MultiBacktestResult =
      BacktestService.runMulti(
          MultiBacktestRequest(
              request.fromDate,
              request.toDate,
              listOf(request.portfolio.copy(rebalanceStrategies = emptyList())),
              request.cashflow,
              request.startingBalance,
              request.zeroMarginInterest,
          )
      )

  private fun runConfiguredStrategy(
      request: RebalanceStrategyRequest,
      strategy: RebalStrategyConfig,
      context: RunContext,
      standaloneBaseCache: ConcurrentMap<StandaloneDerivedReferenceCacheKey, DerivedReferenceSeries>,
  ): PortfolioResult {
    val strategyPortfolio = strategy.portfolioWithRebalanceOverride(request.portfolio)
    val baseRun =
        runStrategyWithIntentions(
            strategyPortfolio,
            strategy,
            request.cashflow,
            context.seriesMap,
            context.dates,
            context.effrx,
            request.startingBalance,
            request.includeActionDiagnostics,
            zeroMarginInterest = request.zeroMarginInterest,
        )
    val curve = baseRun.curve
    val derivedCurves =
        runDerivedSubStrategies(
            request,
            request.portfolio,
            strategy,
            context,
            DerivedReferenceSeries(
                baseRun.marginHistory,
                baseRun.marginIntentions,
            ),
            standaloneBaseCache,
        )
    return PortfolioResult(request.portfolio.label, if (strategy.baseEnabled) listOf(curve) + derivedCurves else derivedCurves)
  }

  private fun runDerivedSubStrategies(
      request: RebalanceStrategyRequest,
      strategyPortfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      context: RunContext,
      defaultReferenceSeries: DerivedReferenceSeries,
      standaloneBaseCache: ConcurrentMap<StandaloneDerivedReferenceCacheKey, DerivedReferenceSeries>,
  ): List<CurveResult> {
    return strategy.derivedSubStrategies
        .filter { it.enabled }
        .map { derived ->
          val referenceSeries =
              referenceSeriesForDerived(
                  derived,
                  defaultReferenceSeries,
                  standaloneBaseCache,
                  strategyPortfolio,
                  strategy,
                  request,
                  context,
              )
          runStrategy(
              strategyPortfolio,
              strategy.derivedOnlyConfig(derived),
              request.cashflow,
              context.seriesMap,
              context.dates,
              context.effrx,
              request.startingBalance,
              request.includeActionDiagnostics,
              derived,
              referenceSeries.margins,
              baseMarginIntentionSeries = referenceSeries.marginIntentions,
              zeroMarginInterest = request.zeroMarginInterest,
          )
        }
  }

  private fun referenceSeriesForDerived(
      derived: DerivedSubStrategyConfig,
      defaultReferenceSeries: DerivedReferenceSeries,
      standaloneBaseCache: ConcurrentMap<StandaloneDerivedReferenceCacheKey, DerivedReferenceSeries>,
      strategyPortfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      request: RebalanceStrategyRequest,
      context: RunContext,
  ): DerivedReferenceSeries {
    val ticker =
        if (derived.marginReferenceSource == DerivedMarginReferenceSource.STANDALONE_TICKER) {
          normalizeReferenceTicker(derived.marginReferenceTicker)
        } else {
          null
        }
    if (ticker == null) return defaultReferenceSeries

    val cacheKey = StandaloneDerivedReferenceCacheKey(ticker, strategy.standaloneReferenceStrategyKey())
    return standaloneBaseCache.computeIfAbsent(cacheKey) {
      val standalonePortfolio =
          strategyPortfolio.copy(
              label = "${strategyPortfolio.label} / $ticker",
              tickers = listOf(TickerWeight(ticker, 1.0)),
              marginStrategies = emptyList(),
              rebalanceStrategies = emptyList(),
              includeNoMargin = true,
          )
      val standaloneRun =
          runStrategyWithIntentions(
              standalonePortfolio,
              strategy,
              request.cashflow,
              context.seriesMap,
              context.dates,
              context.effrx,
              request.startingBalance,
              includeActionDiagnostics = false,
              zeroMarginInterest = request.zeroMarginInterest,
          )
      DerivedReferenceSeries(
          standaloneRun.marginHistory,
          standaloneRun.marginIntentions,
      )
    }
  }

  private fun RebalStrategyConfig.standaloneReferenceStrategyKey(): RebalStrategyConfig =
      copy(
          label = "",
          derivedSubStrategies = emptyList(),
      )

  private fun RebalStrategyConfig.derivedOnlyConfig(derived: DerivedSubStrategyConfig): RebalStrategyConfig =
      copy(
          label = "$label / ${derived.label}",
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
          .filter { it.enabled }
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
      extraTickers: Collection<String> = emptyList(),
      overrideDates: List<LocalDate>? = null,
      warningCollector: ((String, List<String>) -> Unit)? = null,
  ): RunContext {
    val fromDate = fromDateText?.let { LocalDate.parse(it) }
    val toDate = toDateText?.let { LocalDate.parse(it) } ?: LocalDate.now()
    BacktestService.validateDateRange(fromDate, toDate)
    val effrx = BacktestService.loadEffrxSeries()
    val historyFrom = LocalDate.of(1990, 1, 1)
    val sanitizedPortfolio = portfolio.withoutPlaceholderTickers()
    val portfolioNeededFrom = fromDate ?: historyFrom

    val requestedTickers = (sanitizedPortfolio.tickers.map { it.ticker } + referenceTickers + extraTickers).distinct()
    val referenceTickerSet = referenceTickers.toSet()
    fun neededFromForTicker(ticker: String) =
        if (ticker in referenceTickerSet) historyFrom else portfolioNeededFrom
    val seriesCache = BacktestService.resolveTickerSeries(
        requestedTickers,
        neededFromForTicker = { ticker -> neededFromForTicker(ticker) },
        toDate = toDate,
        effrx = effrx,
        warningCollector = warningCollector,
    )

    val rawSeriesMap: Map<String, Map<LocalDate, Double>> =
        requestedTickers.associateWith { ticker ->
          seriesCache[ticker] ?: error("Series for '$ticker' not found")
        }

    if (overrideDates != null) return RunContext(rawSeriesMap, overrideDates, effrx)

    val portfolioSeries = sanitizedPortfolio.tickers.map { tw ->
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
      baseBuyLowEventSeries: List<Boolean> = emptyList(),
      baseBuyLowTargetMarginSeries: List<Double?> = emptyList(),
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
          baseBuyLowEventSeries,
          baseMarginIntentionSeries = baseBuyLowTargetMarginSeries.map { targetMargin ->
            if (targetMargin == null) emptyList()
            else listOf(MarginIntention(MarginIntentionType.BUY_LOW, targetMargin, triggerMargin = null))
          },
      )

  // Core simulation

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
      baseBuyLowEventSeries: List<Boolean>? = null,
      baseMarginIntentionSeries: List<List<MarginIntention>>? = null,
      zeroMarginInterest: Boolean = false,
  ): CurveResult =
      runStrategyWithIntentions(
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
          baseBuyLowEventSeries,
          baseMarginIntentionSeries,
          zeroMarginInterest,
      ).curve

  private fun runStrategyWithIntentions(
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
      baseBuyLowEventSeries: List<Boolean>? = null,
      baseMarginIntentionSeries: List<List<MarginIntention>>? = null,
      zeroMarginInterest: Boolean = false,
  ): StrategyRunResult {
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
    val derivedTargetRuntime =
        derivedSubStrategy?.let { DerivedTargetRuntime.from(it.scale.withReferenceMetric(it.marginReferenceMetric)) }
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
    fun baseMarginAt(recordedIndex: Int): Double? {
      if (derivedSubStrategy == null) return null
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
      val safeIndex = recordedIndex.coerceAtLeast(0)
      val referenceMarginIntentions =
          baseMarginIntentionSeries?.getOrNull(safeIndex)
              ?: if (baseBuyLowEventSeries?.getOrNull(safeIndex) == true) {
                listOf(MarginIntention(MarginIntentionType.BUY_LOW, targetMargin = null, triggerMargin = null))
              } else {
                emptyList()
              }
      val lookbackMargin =
          derivedTargetRuntime
              ?.momentumLookbackMonths
              ?.let { months ->
                val safeIndex = recordedIndex.coerceIn(0, dates.lastIndex)
                val lookbackDate = dates[safeIndex].minusMonths(months.toLong())
                val lookbackIndex =
                    dateIndexOnOrBefore(
                        lookbackDate,
                        minOf(safeIndex - 1, (baseMarginSeries?.lastIndex ?: -1)),
                    )
                if (lookbackIndex >= 0) baseMarginAt(lookbackIndex) else null
              }
      return derivedTargetRuntime?.target(baseMargin, referenceMarginIntentions, lookbackMargin)
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
        if (zeroMarginInterest) DoubleArray(dates.size)
        else BacktestService.buildDailyLoanRates(dates, effrx, strategy.marginSpread / 252.0)
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
    val tickerHistoryValueCache = mutableMapOf<Pair<String, Boolean>, List<Double>>()
    val sortedSeriesEntriesCache = mutableMapOf<String, List<Map.Entry<LocalDate, Double>>>()

    fun tickerHistoryValues(ticker: String, normalizeLastToOne: Boolean): List<Double> {
      val cacheKey = ticker to normalizeLastToOne
      tickerHistoryValueCache[cacheKey]?.let { return it }
      val series = seriesMap[ticker] ?: return emptyList()
      val historyDates = (series.keys + firstDate)
          .filter { it <= firstDate }
          .distinct()
          .sorted()
      val filled = BacktestService.forwardFillSeries(series, historyDates)
      val values = historyDates.mapNotNull { filled[it] }
      val normalized =
          if (!normalizeLastToOne || values.isEmpty()) {
            values
          } else {
            val last = values.last().takeIf { it > 0.0 }
            if (last == null) values else values.map { it / last }
          }
      tickerHistoryValueCache[cacheKey] = normalized
      return normalized
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

    val baseReferencePreStartHistory by lazy(LazyThreadSafetyMode.NONE) { baseReferenceHistoryPoints() }
    val baseReferencePreStartValues by lazy(LazyThreadSafetyMode.NONE) {
      baseReferencePreStartHistory.map { it.second }
    }

    fun baseReferenceHistoryValues(): List<Double> =
        baseReferencePreStartValues

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
        var exitExtensionUntil: LocalDate? = null,
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
    val marginIntentions = dates.map { mutableListOf<MarginIntention>() }
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

    fun recordMarginIntention(
        curIndex: Int,
        type: MarginIntentionType,
        cfg: MarginTriggerAction,
    ) {
      marginIntentions
          .getOrNull(curIndex)
          ?.add(MarginIntention(type, cfg.targetMargin, cfg.deviationPct))
    }

    // Pre-loop: build trigger checkers and executors
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

    fun valueOnOrBefore(points: List<Pair<LocalDate, Double>>, targetDate: LocalDate): Double? {
      var result: Double? = null
      for ((date, value) in points) {
        if (date > targetDate) break
        result = value
      }
      return result
    }

    fun seriesValueOnOrBefore(ticker: String, targetDate: LocalDate): Double? {
      val entries =
          sortedSeriesEntriesCache.getOrPut(ticker) {
            seriesMap[ticker]?.entries?.sortedBy { it.key }.orEmpty()
          }
      var lo = 0
      var hi = entries.lastIndex
      var result: Double? = null
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val entry = entries[mid]
        if (entry.key <= targetDate) {
          result = entry.value
          lo = mid + 1
        } else {
          hi = mid - 1
        }
      }
      return result
    }

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

    // Per-day helper: check triggers and drive executors for one config
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
      var dailyBaseReferenceTotal = 0.0
      for (ticker in tickers) {
        val nextValue =
            (dailyBaseReferenceHoldings[ticker] ?: 0.0) * (returnRatios[ticker]?.get(i) ?: 1.0)
        dailyBaseReferenceHoldings[ticker] = nextValue
        dailyBaseReferenceTotal += nextValue
      }
      for (ticker in singleTickerReferenceTickers) {
        singleTickerReferenceValues[ticker] =
            (singleTickerReferenceValues[ticker] ?: 1.0) *
                (singleTickerReferenceReturnRatios[ticker]?.get(i) ?: 1.0)
      }
      // Independent reference path: no margin, no cashflow, daily target-weight rebalance.
      for (ticker in tickers) {
        dailyBaseReferenceHoldings[ticker] = dailyBaseReferenceTotal * (targetWeights[ticker] ?: 0.0)
      }
      dailyBaseReferenceValue = dailyBaseReferenceTotal
      baseReferenceValues.add(dailyBaseReferenceValue)

      // Step 2: Accrue margin interest on debt
      account.accrueMarginInterest(dailyLoanRates[i])

      var currentDrawdown: Double? = null
      if (drawdownMarginOverride != null) {
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
      fun updateDrawdownMarginTrigger(runtime: DrawdownMarginTriggerRuntime?): Boolean {
        if (runtime == null) return false
        val referenceValue = portfolioTriggerValue(runtime.key)
        if (referenceValue.isNaN()) return false
        val exitExtensionMonths = runtime.config.exitExtensionMonths.coerceAtLeast(0)
        val wasActive = runtime.tiers.any { it.active }
        var deactivatedAny = false
        if (runtime.peak.isNaN() || referenceValue > runtime.peak) {
          runtime.peak = referenceValue
        }
        for (tier in runtime.tiers) {
          val enterDrawdown =
              if (runtime.peak > 0.0) (runtime.peak - referenceValue) / runtime.peak
              else 0.0
          if (
              tier.active &&
              tier.exitExtensionUntil != null &&
              enterDrawdown >= tier.config.enterDrawdownPct.coerceAtLeast(0.0)
          ) {
            tier.activeReferencePeak = runtime.peak
            tier.exitExtensionUntil = null
          }
          if (tier.active && tier.exitExtensionUntil?.let { curDate > it } == true) {
            tier.active = false
            tier.activeReferencePeak = Double.NaN
            tier.exitExtensionUntil = null
            deactivatedAny = true
          }
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
              if (exitExtensionMonths > 0) {
                tier.exitExtensionUntil = tier.exitExtensionUntil ?: curDate.plusMonths(exitExtensionMonths.toLong())
              } else {
                tier.active = false
                tier.activeReferencePeak = Double.NaN
                tier.exitExtensionUntil = null
                deactivatedAny = true
              }
            }
          } else {
            if (enterDrawdown >= tier.config.enterDrawdownPct.coerceAtLeast(0.0)) {
              tier.active = true
              tier.activeReferencePeak = runtime.peak
              tier.exitExtensionUntil = null
            }
          }
        }
        return wasActive && deactivatedAny && runtime.tiers.none { it.active }
      }
      val exitedAllDrawdownBuyLow = updateDrawdownMarginTrigger(drawdownBuyLowRuntime)
      updateDrawdownMarginTrigger(drawdownSellHighRuntime)
      var drawdownBuyLowExitSellFired = false
      if (exitedAllDrawdownBuyLow) {
        val exitTargetMargin = drawdownBuyLowRuntime?.config?.exitTargetMargin?.coerceAtLeast(0.0)
        if (exitTargetMargin != null && exitTargetMargin.isFinite() && account.equity() > 0.0) {
          val targetCashBalance = -account.equity() * exitTargetMargin
          if (account.cashBalance() < targetCashBalance) {
            val excess = targetCashBalance - account.cashBalance()
            val grossBefore = account.grossStockValue()
            applyAllocDelta(tickers, account, targetWeights, -excess, MarginRebalanceMode.PROPORTIONAL.name)
            if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), Direction.SELL)) {
              globalCooldown.recordSellHigh(i)
              actionPoints.add(ActionPoint(curDate.toString(), "SELL_HIGH"))
              drawdownBuyLowExitSellFired = true
            }
          }
        }
      }
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
          val targetTotal =
              if (derivedSubStrategy != null) account.grossStockValue()
              else eq * (1.0 + comfortZoneMargin(strategy.portfolioRebalanceUseComfortZone))
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
      if (equityBefore > 0 && !drawdownBuyLowExitSellFired) {
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
            ?.takeIf { it.deviationPct > 0 || derivedSubStrategy != null }
            ?.let { cfg ->
              val forcedDerivedSell = derivedMaxSell != null
              val sellIntended = triggerCurrentRatio > cfg.deviationPct
              if (sellIntended) recordMarginIntention(i, MarginIntentionType.SELL_HIGH, cfg)
              if (sellIntended &&
                  (forcedDerivedSell || derivedExactTarget || (!derivedTimeoutBlocked && !globalCooldown.isBlocked(Direction.SELL, i)))
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
        val buyCooldownBlocked =
            activeDrawdownBuyLowRuntime == null && globalCooldown.isBlocked(Direction.BUY, i)
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
            ?.takeIf { it.deviationPct > 0 || derivedSubStrategy != null }
            ?.let { cfg ->
              val drawdownBuyLowIntended =
                  activeDrawdownBuyLowTier != null && drawdownBuyLowMomentumAllowed
              val buyTriggered = triggerCurrentRatio < cfg.deviationPct
              val buyIntended = drawdownBuyLowIntended || (drawdownBuyLowMomentumAllowed && buyTriggered)
              if (buyIntended) recordMarginIntention(i, MarginIntentionType.BUY_LOW, cfg)
              if (drawdownBuyLowMomentumAllowed &&
                  buyTriggered &&
                  (derivedExactTarget || (!derivedTimeoutBlocked && !buyCooldownBlocked))
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

      // Step 8: Buy-the-dip: check triggers + executor.advance
      dipResources.forEach { processConfig(it, Direction.BUY, i, curDate, "BUY_DIP") }

      // Step 9: Sell-on-surge: check triggers + executor.advance
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
    return StrategyRunResult(
        CurveResult(
            strategy.label,
            points,
            stats,
            marginPoints,
            actionPoints.takeIf { it.isNotEmpty() },
            vmTimingPoints.takeIf { it.isNotEmpty() },
        ),
        marginIntentions.map { it.toList() },
        marginUtils.toList(),
    )
  }

  // Eligible amount calculation

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

  // Allocation helpers

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

  // Utility

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

