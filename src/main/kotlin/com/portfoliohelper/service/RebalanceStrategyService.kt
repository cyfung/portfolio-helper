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
    val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)

    val referenceTickers = request.strategies.flatMap { it.referenceTickers() }.distinct()
    val requestedTickers = (request.portfolio.tickers.map { it.ticker } + referenceTickers).distinct()

    // Load LETF definitions and component series
    val letfDefs = mutableMapOf<String, LETFDefinition>()
    for (ticker in requestedTickers) {
      BacktestService.parseLETFDefinition(ticker)?.let { letfDefs.putIfAbsent(ticker, it) }
    }
    val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
    fun cachedLoad(ticker: String) =
        seriesCache.getOrPut(ticker) { BacktestService.loadNormalizedSeries(ticker, neededFrom) }

    for (comp in letfDefs.values.flatMap { it.components }) cachedLoad(comp.ticker)

    val letfComponentSeries =
        letfDefs.values.flatMap { it.components }.mapNotNull { seriesCache[it.ticker] }
    val letfDates =
        if (letfComponentSeries.isNotEmpty())
            BacktestService.intersectDates(letfComponentSeries, fromDate, toDate)
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
      if (BacktestService.parseLETFDefinition(ticker) == null) cachedLoad(ticker)
    }

    val seriesMap: Map<String, Map<LocalDate, Double>> =
        requestedTickers.associateWith { ticker ->
          seriesCache[ticker] ?: error("Series for '$ticker' not found")
        }
    val dates = BacktestService.intersectDates(seriesMap.values.toList(), fromDate, toDate)
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

  private fun RebalStrategyConfig.referenceTickers(): Set<String> =
      (listOfNotNull(buyTheDip, sellOnSurge) + buyTheDipConfigs + sellOnSurgeConfigs)
          .mapNotNull { it.normalizedReferenceTicker() }
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
    val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)

    val requestedTickers = (portfolio.tickers.map { it.ticker } + referenceTickers).distinct()
    val letfDefs = mutableMapOf<String, LETFDefinition>()
    for (ticker in requestedTickers) {
      BacktestService.parseLETFDefinition(ticker)?.let { letfDefs.putIfAbsent(ticker, it) }
    }
    val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
    fun cachedLoad(ticker: String) =
        seriesCache.getOrPut(ticker) { BacktestService.loadNormalizedSeries(ticker, neededFrom) }

    for (comp in letfDefs.values.flatMap { it.components }) cachedLoad(comp.ticker)

    val letfComponentSeries =
        letfDefs.values.flatMap { it.components }.mapNotNull { seriesCache[it.ticker] }
    val letfDates =
        if (letfComponentSeries.isNotEmpty())
            BacktestService.intersectDates(letfComponentSeries, fromDate, toDate)
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
      if (BacktestService.parseLETFDefinition(ticker) == null) cachedLoad(ticker)
    }

    val rawSeriesMap: Map<String, Map<LocalDate, Double>> =
        requestedTickers.associateWith { ticker ->
          seriesCache[ticker] ?: error("Series for '$ticker' not found")
        }

    if (overrideDates != null) {
      // Use the provided dates (already intersected from portfolio tickers) and forward-fill all
      // series so that direct date lookups never miss, even for reference tickers with gaps.
      val filledSeriesMap = rawSeriesMap.mapValues { (_, series) ->
        BacktestService.forwardFillSeries(series, overrideDates)
      }
      return RunContext(filledSeriesMap, overrideDates, effrx)
    }

    val dates = BacktestService.intersectDates(rawSeriesMap.values.toList(), fromDate, toDate)
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
  ): List<Double> =
      runStrategy(portfolio, strategy, cashflow, seriesMap, dates, effrx, startingBalance)
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
  ): CurveResult =
      runStrategy(portfolio, strategy, cashflow, seriesMap, dates, effrx, startingBalance)

  // ── Core simulation ───────────────────────────────────────────────────────

  private fun runStrategy(
      portfolio: PortfolioConfig,
      strategy: RebalStrategyConfig,
      cashflow: CashflowConfig?,
      seriesMap: Map<String, Map<LocalDate, Double>>,
      dates: List<LocalDate>,
      effrx: Map<LocalDate, Double>,
      startingBalance: Double = 10_000.0,
  ): CurveResult {
    val (tickers, targetWeights) = portfolio.mergeWeights()
    val normalRebalance = portfolio.rebalanceStrategy
    val marginRebalance =
        if (strategy.marginRebalanceEnabled) strategy.rebalancePeriod.toMarginRebalanceStrategy()
        else RebalanceStrategy.NONE
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
        allDipSurgeConfigs.mapNotNull { it.normalizedReferenceTicker() }.distinct()
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
          PortfolioTriggerSource.REFERENCE_PORTFOLIO ->
              key.referenceTicker?.let { singleTickerReferenceValues[it] } ?: dailyBaseReferenceValue
        }

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
      val firstDate = dates.firstOrNull() ?: return
      for ((key, checkers) in res.checkersByKey) {
        val v =
            when (key) {
              is DipSurgeKey.Stock -> seriesMap[key.ticker]!![firstDate]!!
              is DipSurgeKey.Portfolio -> portfolioTriggerValue(key)
            }
        checkers.forEach { it.advance(v) }
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
        res.executorsByKey[key]?.advance(
            triggered,
            currentValue,
            eligible = { computeEligible(key, account, targetWeights, direction, res.limit) },
        ) { amount ->
          if (amount > 0.0) {
            val grossBefore = account.grossStockValue()
            val minAdjustment = grossBefore * res.minAdjustmentPct
            if (amount >= minAdjustment) {
              applyDipSurge(key, tickers, account, targetWeights, amount, direction, res.allocStrategy)
              if (tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), direction)) {
                res.cooldown.recordFire(key, curIndex)
                actionPoints.add(ActionPoint(curDate.toString(), actionType))
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
      val marginRebalanceDay =
          !normalRebalanceDay && shouldRebalance(marginRebalance, prevDate, curDate)

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
          val eq = account.equity()
          if (eq > 0) {
            val targetMargin =
                if (strategy.marginRebalanceTradeDirection == MarginRebalanceTradeDirection.BOTH)
                  comfortZoneMargin(strategy.useComfortZone)
                else
                  strategy.marginRebalanceRestoreMargin ?: marginTarget
            val targetTotal = eq * (1.0 + targetMargin)
            val delta = targetTotal - account.grossStockValue()
            val directionAllowed =
                when (strategy.marginRebalanceTradeDirection) {
                  MarginRebalanceTradeDirection.BOTH -> true
                  MarginRebalanceTradeDirection.BUY_ONLY -> delta > 0.0
                  MarginRebalanceTradeDirection.SELL_ONLY -> delta < 0.0
                }
            if (directionAllowed) {
              val direction =
                  if (delta > 0.0) Direction.BUY else if (delta < 0.0) Direction.SELL else null
              if (direction == null || !globalCooldown.isBlocked(direction, i)) {
                val grossBefore = account.grossStockValue()
                applyAllocDelta(tickers, account, targetWeights, delta, strategy.rebalanceAllocStrategy)
                if (direction != null && tradeMovedGrossInDirection(grossBefore, account.grossStockValue(), direction)) {
                  actionPoints.add(ActionPoint(curDate.toString(), "MARGIN_REBALANCE"))
                }
              }
            }
          }
        }

        // Step 5: Margin deviation triggers (sell on high / buy on low margin).
        // These explicit trigger sections are independent of the scheduled margin
        // rebalance trade-direction filter above.
        if (equityBefore > 0) {
          val currentRatio = (-cashBalanceBefore).coerceAtLeast(0.0) / equityBefore

          strategy.sellOnHighMargin
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

          strategy.buyOnLowMargin
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
      allocStrategy: MarginRebalanceMode?,
  ) {
    when (allocStrategy ?: MarginRebalanceMode.PROPORTIONAL) {
      MarginRebalanceMode.PROPORTIONAL,
      MarginRebalanceMode.DAILY -> {
        for (ticker in tickers) {
          val amount = delta * (targetWeights[ticker] ?: 0.0)
          account.applyTradeDelta(ticker, amount)
        }
      }

      MarginRebalanceMode.CURRENT_WEIGHT -> {
        val total = tickers.sumOf { account.holding(it) }
        if (total == 0.0) return
        for (ticker in tickers) {
          val amount = delta * (account.holding(ticker) / total)
          account.applyTradeDelta(ticker, amount)
        }
      }

      MarginRebalanceMode.FULL_REBALANCE -> {
        val newTotal = tickers.sumOf { account.holding(it) } + delta
        performProportionalRebalance(newTotal, tickers, targetWeights, account)
      }

      MarginRebalanceMode.UNDERVALUED_PRIORITY -> {
        val temp = tickers.associateWith { account.holding(it) }.toMutableMap()
        BacktestService.computeUndervalueFirst(tickers, temp, targetWeights, delta)
        for (ticker in tickers) {
          val diff = (temp[ticker] ?: 0.0) - account.holding(ticker)
          account.applyTradeDelta(ticker, diff)
        }
      }

      MarginRebalanceMode.WATERFALL -> {
        val temp = tickers.associateWith { account.holding(it) }.toMutableMap()
        BacktestService.computeWaterfall(tickers, temp, targetWeights, delta)
        for (ticker in tickers) {
          val diff = (temp[ticker] ?: 0.0) - account.holding(ticker)
          account.applyTradeDelta(ticker, diff)
        }
      }
    }
  }

  private fun applyDipSurge(
      key: DipSurgeKey,
      tickers: List<String>,
      account: PaperTradingPortfolio,
      targetWeights: Map<String, Double>,
      amount: Double,
      direction: Direction,
      allocStrategy: MarginRebalanceMode?,
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
}

// ── Extension / helpers ───────────────────────────────────────────────────────

private fun tradeMovedGrossInDirection(before: Double, after: Double, direction: Direction): Boolean {
  val epsilon = 1e-9
  return if (direction == Direction.BUY) after > before + epsilon else after < before - epsilon
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
    val allocStrategy: MarginRebalanceMode?,
    val limit: Double,
    val cooldown: DipSurgeCooldown,
    val minAdjustmentPct: Double,
)

internal class DipSurgeCooldown(coolingOffDays: Int = 10) {
  private val days = coolingOffDays.coerceAtLeast(0)
  private val lastTriggerIndexByKey = mutableMapOf<DipSurgeKey, Int>()

  fun shouldFire(key: DipSurgeKey, curIndex: Int, rawTriggered: Boolean): Boolean {
    if (!rawTriggered) return false
    val lastTriggerIndex = lastTriggerIndexByKey[key]
    if (lastTriggerIndex != null && curIndex - lastTriggerIndex <= days) return false
    return true
  }

  fun recordFire(key: DipSurgeKey, curIndex: Int) {
    lastTriggerIndexByKey[key] = curIndex
  }
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
}

private fun PaperTradingPortfolio.applyTradeDelta(ticker: String, amount: Double) {
  if (amount > 0.0) buy(ticker, amount)
  else if (amount < 0.0) sell(ticker, -amount)
}
