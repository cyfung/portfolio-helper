package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.max

object RebalanceStrategyService {

  fun run(request: RebalanceStrategyRequest): MultiBacktestResult {
    val fromDate = request.fromDate?.let { LocalDate.parse(it) }
    val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
    val effrx = BacktestService.loadEffrxSeries()
    val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)

    // Load LETF definitions and component series
    val letfDefs = mutableMapOf<String, LETFDefinition>()
    for (tw in request.portfolio.tickers) {
      BacktestService.parseLETFDefinition(tw.ticker)?.let { letfDefs.putIfAbsent(tw.ticker, it) }
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
    for (tw in request.portfolio.tickers) {
      if (BacktestService.parseLETFDefinition(tw.ticker) == null) cachedLoad(tw.ticker)
    }

    val seriesMap: Map<String, Map<LocalDate, Double>> =
        request.portfolio.tickers.associate { tw ->
          tw.ticker to (seriesCache[tw.ticker] ?: error("Series for '${tw.ticker}' not found"))
        }
    val dates = BacktestService.intersectDates(seriesMap.values.toList(), fromDate, toDate)
    if (dates.size < 2) throw IllegalStateException("Not enough overlapping trading dates")

    val baseResult =
        BacktestService.runMulti(
            MultiBacktestRequest(
                request.fromDate,
                request.toDate,
                listOf(request.portfolio),
                request.cashflow,
                request.startingBalance,
            )
        )
    val strategyResults =
        request.strategies.map { strategy ->
          val curve =
              runStrategy(
                  request.portfolio,
                  strategy,
                  request.cashflow,
                  seriesMap,
                  dates,
                  effrx,
                  request.startingBalance,
              )
          PortfolioResult(strategy.label, listOf(curve))
        }
    return MultiBacktestResult(baseResult.portfolios + strategyResults)
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
    val effectiveRebalance =
        strategy.rebalancePeriod.toRebalanceStrategy(portfolio.rebalanceStrategy)
    val marginTarget = strategy.marginRatio
    val comfortLowBound = strategy.comfortZoneLow
    val comfortHighBound = strategy.comfortZoneHigh

    val account = PaperTradingPortfolio(tickers, targetWeights, startingBalance, marginTarget)

    val returnRatios = BacktestService.buildReturnRatios(tickers, seriesMap, dates)
    val dailyLoanRates =
        BacktestService.buildDailyLoanRates(dates, effrx, strategy.marginSpread / 252.0)

    val values = mutableListOf(startingBalance)
    val marginUtils = mutableListOf(marginTarget)

    // ── Pre-loop: build trigger checkers and executors ────────────────────
    fun DipSurgeConfig.buildResources(): DipSurgeResources {
      val keys =
          if (scope == DipSurgeScope.INDIVIDUAL_STOCK) tickers.map { DipSurgeKey.Stock(it) }
          else listOf(DipSurgeKey.WholePortfolio)
      return DipSurgeResources(
          checkersByKey = keys.associateWith { key -> triggers.map { it.buildChecker(key) } },
          executorsByKey = keys.associateWith { method.newExecutor() },
          allocStrategy = allocStrategy,
          limit = limit,
      )
    }

    val dipResources = strategy.buyTheDip?.buildResources()
    val surgeResources = strategy.sellOnSurge?.buildResources()

    // ── Per-day helper: check triggers and drive executors for one config ─
    fun processConfig(res: DipSurgeResources, direction: Direction, curDate: LocalDate) {
      for ((key, checkers) in res.checkersByKey) {
        val triggered = checkers.any { it.check(direction) }
        val currentValue =
            when (key) {
              is DipSurgeKey.Stock -> seriesMap[key.ticker]!![curDate]!!
              is DipSurgeKey.WholePortfolio -> account.grossStockValue()
            }
        res.executorsByKey[key]?.advance(
            triggered,
            currentValue,
            eligible = { computeEligible(key, account, targetWeights, direction, res.limit) },
        ) { amount ->
          applyDipSurge(key, tickers, account, targetWeights, amount, direction, res.allocStrategy)
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

      // Step 2: Accrue margin interest on debt
      account.accrueMarginInterest(dailyLoanRates[i])

      // Step 3: Periodic rebalance — if triggered, skip Step 4
      if (shouldRebalance(effectiveRebalance, prevDate, curDate)) {
        val eq = account.equity()
        if (eq > 0) {
          val currentRatio = account.currentMarginRatio()
          val effectiveMargin =
              when {
                currentRatio > comfortHighBound -> comfortHighBound
                currentRatio < comfortLowBound -> comfortLowBound
                else -> currentRatio
              }
          val targetTotal = eq * (1.0 + effectiveMargin)
          performProportionalRebalance(targetTotal, tickers, targetWeights, account)
        }
      } else {
        // Step 4: Margin deviation triggers (sell on high / buy on low margin)
        if (equityBefore > 0) {
          val currentRatio = (-cashBalanceBefore).coerceAtLeast(0.0) / equityBefore

          strategy.sellOnHighMargin
              ?.takeIf { it.deviationPct > 0 }
              ?.let { cfg ->
                if (currentRatio > cfg.deviationPct) {
                  val targetCashBalance = -account.equity() * cfg.targetMargin
                  if (account.cashBalance() < targetCashBalance) {
                    val excess = targetCashBalance - account.cashBalance()
                    applyAllocDelta(tickers, account, targetWeights, -excess, cfg.allocStrategy)
                  }
                }
              }

          strategy.buyOnLowMargin
              ?.takeIf { it.deviationPct > 0 }
              ?.let { cfg ->
                if (currentRatio < cfg.deviationPct) {
                  val targetCashBalance = -account.equity() * cfg.targetMargin
                  if (account.cashBalance() > targetCashBalance) {
                    val deficit = account.cashBalance() - targetCashBalance
                    applyAllocDelta(tickers, account, targetWeights, deficit, cfg.allocStrategy)
                  }
                }
              }
        }
      }

      // Step 5: Cashflow injection (if due)
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

      // Step 6: Advance all trigger checkers with today's values
      val portfolioGrossValue = account.grossStockValue()
      fun advanceCheckers(res: DipSurgeResources) {
        for ((key, checkers) in res.checkersByKey) {
          val v =
              when (key) {
                is DipSurgeKey.Stock -> seriesMap[key.ticker]!![curDate]!!
                is DipSurgeKey.WholePortfolio -> portfolioGrossValue
              }
          checkers.forEach { it.advance(v) }
        }
      }
      dipResources?.let { advanceCheckers(it) }
      surgeResources?.let { advanceCheckers(it) }

      // Step 7: Buy-the-dip — check triggers + executor.advance
      dipResources?.let { processConfig(it, Direction.BUY, curDate) }

      // Step 8: Sell-on-surge — check triggers + executor.advance
      surgeResources?.let { processConfig(it, Direction.SELL, curDate) }

      // Step 9: Record equity
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
    return CurveResult(strategy.label, points, stats, marginPoints)
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
      is DipSurgeKey.WholePortfolio -> {
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
          if (amount > 0) account.buy(ticker, amount)
          else if (amount < 0) account.sell(ticker, -amount)
        }
      }

      MarginRebalanceMode.CURRENT_WEIGHT -> {
        val total = tickers.sumOf { account.holding(it) }
        if (total == 0.0) return
        for (ticker in tickers) {
          val amount = delta * (account.holding(ticker) / total)
          if (amount > 0) account.buy(ticker, amount)
          else if (amount < 0) account.sell(ticker, -amount)
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
          if (diff > 0) account.buy(ticker, diff) else if (diff < 0) account.sell(ticker, -diff)
        }
      }

      MarginRebalanceMode.WATERFALL -> {
        val temp = tickers.associateWith { account.holding(it) }.toMutableMap()
        BacktestService.computeWaterfall(tickers, temp, targetWeights, delta)
        for (ticker in tickers) {
          val diff = (temp[ticker] ?: 0.0) - account.holding(ticker)
          if (diff > 0) account.buy(ticker, diff) else if (diff < 0) account.sell(ticker, -diff)
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
      is DipSurgeKey.WholePortfolio ->
          applyAllocDelta(tickers, account, targetWeights, delta, allocStrategy)
      is DipSurgeKey.Stock ->
          if (direction == Direction.BUY) account.buy(key.ticker, amount)
          else account.sell(key.ticker, amount)
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
      if (diff > 0) {
        account.buy(ticker, diff)
      } else if (diff < 0) {
        account.sell(ticker, -diff)
      }
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

        RebalanceStrategy.MONTHLY -> cur.month != prev.month
        RebalanceStrategy.QUARTERLY -> (cur.monthValue - 1) / 3 != (prev.monthValue - 1) / 3

        RebalanceStrategy.YEARLY -> cur.year != prev.year
      }
}

// ── Extension / helpers ───────────────────────────────────────────────────────

private fun RebalancePeriodOverride.toRebalanceStrategy(
    inherit: RebalanceStrategy
): RebalanceStrategy =
    when (this) {
      RebalancePeriodOverride.INHERIT -> inherit
      RebalancePeriodOverride.NONE -> RebalanceStrategy.NONE
      RebalancePeriodOverride.MONTHLY -> RebalanceStrategy.MONTHLY
      RebalancePeriodOverride.QUARTERLY -> RebalanceStrategy.QUARTERLY
      RebalancePeriodOverride.YEARLY -> RebalanceStrategy.YEARLY
    }

// ── Pre-loop resources ────────────────────────────────────────────────────────

private data class DipSurgeResources(
    val checkersByKey: Map<DipSurgeKey, List<TriggerChecker>>,
    val executorsByKey: Map<DipSurgeKey, DipSurgeExecutor>,
    val allocStrategy: MarginRebalanceMode?,
    val limit: Double,
)
