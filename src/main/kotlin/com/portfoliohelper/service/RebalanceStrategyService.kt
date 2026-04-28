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
            BacktestService.parseLETFDefinition(tw.ticker)
                ?.let { letfDefs.putIfAbsent(tw.ticker, it) }
        }
        val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
        fun cachedLoad(ticker: String) =
            seriesCache.getOrPut(ticker) {
                BacktestService.loadNormalizedSeries(
                    ticker,
                    neededFrom
                )
            }

        for (comp in letfDefs.values.flatMap { it.components }) cachedLoad(comp.ticker)

        val letfComponentSeries =
            letfDefs.values.flatMap { it.components }.mapNotNull { seriesCache[it.ticker] }
        val letfDates = if (letfComponentSeries.isNotEmpty())
            BacktestService.intersectDates(letfComponentSeries, fromDate, toDate) else emptyList()
        if (letfDates.size >= 2) {
            for ((letfString, def) in letfDefs) {
                if (letfString !in seriesCache) {
                    val componentSeries =
                        def.components.associate { it.ticker to seriesCache[it.ticker]!! }
                    seriesCache[letfString] = BacktestService.computeLetfSeries(
                        def, componentSeries, letfDates, effrx, def.rebalanceStrategy
                    )
                }
            }
        }
        for (tw in request.portfolio.tickers) {
            if (BacktestService.parseLETFDefinition(tw.ticker) == null) cachedLoad(tw.ticker)
        }

        val seriesMap: Map<String, Map<LocalDate, Double>> =
            request.portfolio.tickers.associate { tw ->
                tw.ticker to (seriesCache[tw.ticker]
                    ?: error("Series for '${tw.ticker}' not found"))
            }
        val dates = BacktestService.intersectDates(seriesMap.values.toList(), fromDate, toDate)
        if (dates.size < 2) throw IllegalStateException("Not enough overlapping trading dates")

        val portfolioResults = request.strategies.map { strategy ->
            val curve =
                runStrategy(request.portfolio, strategy, request.cashflow, seriesMap, dates, effrx)
            PortfolioResult(strategy.label, listOf(curve))
        }
        return MultiBacktestResult(portfolioResults)
    }

    internal fun runStrategyForTest(
        portfolio: PortfolioConfig,
        strategy: RebalStrategyConfig,
        cashflow: CashflowConfig?,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>
    ): List<Double> =
        runStrategy(portfolio, strategy, cashflow, seriesMap, dates, effrx).points.map { it.value }

    // ── Core simulation ───────────────────────────────────────────────────────

    private fun runStrategy(
        portfolio: PortfolioConfig,
        strategy: RebalStrategyConfig,
        cashflow: CashflowConfig?,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>
    ): CurveResult {
        val (tickers, targetWeights) = portfolio.mergeWeights()
        val effectiveRebalance =
            strategy.rebalancePeriod.toRebalanceStrategy(portfolio.rebalanceStrategy)
        val marginTarget = strategy.marginRatio

        val startEquity = 10_000.0
        val grossStockValue = startEquity * (1 + marginTarget)
        val holdings =
            tickers.associateWith { grossStockValue * (targetWeights[it] ?: 0.0) }.toMutableMap()
        // cashBalance > 0 = uninvested cash; cashBalance < 0 = margin debt (borrowed)
        var cashBalance = -startEquity * marginTarget

        val returnRatios = BacktestService.buildReturnRatios(tickers, seriesMap, dates)
        val dailyLoanRates =
            BacktestService.buildDailyLoanRates(dates, effrx, strategy.marginSpread / 252.0)
        val rawPrices: Map<String, Map<LocalDate, Double>> = seriesMap

        val values = mutableListOf(startEquity)

        // ── Pre-loop: build trigger checkers and executors ────────────────────
        fun DipSurgeConfig.buildResources(): DipSurgeResources {
            val keys = if (scope == DipSurgeScope.INDIVIDUAL_STOCK)
                tickers.map { DipSurgeKey.Stock(it) }
            else
                listOf(DipSurgeKey.WholePortfolio)
            return DipSurgeResources(
                checkersByKey = keys.associateWith { key ->
                    triggers.map {
                        it.buildChecker(
                            key,
                            dates,
                            rawPrices
                        )
                    }
                },
                executorsByKey = keys.associateWith { method.newExecutor() },
                allocStrategy = allocStrategy,
                limit = limit
            )
        }

        val dipResources = strategy.buyTheDip?.buildResources()
        val surgeResources = strategy.sellOnSurge?.buildResources()

        // ── Per-day helper: check triggers and drive executors for one config ─
        fun processConfig(
            res: DipSurgeResources,
            direction: Direction,
            i: Int,
            curDate: LocalDate
        ) {
            for ((key, checkers) in res.checkersByKey) {
                val triggered = checkers.any { it.check(i, direction) }
                val currentValue = when (key) {
                    is DipSurgeKey.Stock -> rawPrices[key.ticker]?.get(curDate) ?: 0.0
                    is DipSurgeKey.WholePortfolio -> holdings.values.sum()
                }
                res.executorsByKey[key]?.advance(
                    i, triggered, currentValue,
                    eligible = {
                        computeEligible(
                            key,
                            holdings,
                            targetWeights,
                            cashBalance,
                            direction,
                            marginTarget,
                            res.limit,
                            strategy.deviationMode
                        )
                    }
                ) { amount ->
                    applyDipSurge(
                        key,
                        tickers,
                        holdings,
                        targetWeights,
                        amount,
                        direction,
                        res.allocStrategy
                    )
                    cashBalance += if (direction == Direction.BUY) -amount else amount
                }
            }
        }

        for (i in 1 until dates.size) {
            val prevDate = dates[i - 1]
            val curDate = dates[i]

            val cashBalanceBefore = cashBalance
            val equityBefore = holdings.values.sum() + cashBalanceBefore

            // Step 1: Apply daily price returns to holdings
            for (ticker in tickers)
                holdings[ticker] = (holdings[ticker] ?: 0.0) * (returnRatios[ticker]?.get(i) ?: 1.0)

            // Step 2: Accrue margin interest on debt
            if (cashBalance < 0) cashBalance *= (1.0 + dailyLoanRates[i])

            var equity = holdings.values.sum() + cashBalance

            // Step 3: Periodic rebalance — if triggered, skip Step 4
            if (shouldRebalance(effectiveRebalance, prevDate, curDate)) {
                val actualEquity = holdings.values.sum() + cashBalance
                if (actualEquity > 0) {
                    val targetTotal = actualEquity * (1.0 + marginTarget)
                    for (ticker in tickers) holdings[ticker] =
                        targetTotal * (targetWeights[ticker] ?: 0.0)
                    cashBalance = -actualEquity * marginTarget
                }
            } else {
                // Step 4: Margin deviation triggers (sell on high / buy on low margin)
                if (equityBefore > 0) {
                    val currentRatio = (-cashBalanceBefore).coerceAtLeast(0.0) / equityBefore

                    strategy.sellOnHighMargin?.let { cfg ->
                        val threshold = computeThreshold(marginTarget, cfg.deviationPct, strategy.deviationMode, high = true)
                        if (currentRatio > threshold) {
                            val targetCashBalance = -equity * marginTarget
                            if (cashBalance < targetCashBalance) {
                                val excess = targetCashBalance - cashBalance
                                applyAllocDelta(tickers, holdings, targetWeights, -excess, cfg.allocStrategy)
                                cashBalance = targetCashBalance
                            }
                        }
                    }

                    strategy.buyOnLowMargin?.let { cfg ->
                        val threshold = computeThreshold(marginTarget, cfg.deviationPct, strategy.deviationMode, high = false)
                        if (currentRatio < threshold) {
                            val targetCashBalance = -equity * marginTarget
                            if (cashBalance > targetCashBalance) {
                                val deficit = cashBalance - targetCashBalance
                                applyAllocDelta(tickers, holdings, targetWeights, deficit, cfg.allocStrategy)
                                cashBalance = targetCashBalance
                            }
                        }
                    }
                }
            }

            // Step 5: Cashflow injection (if due)
            if (cashflow != null && isCashflowDate(cashflow.frequency, curDate)) {
                val raw = cashflow.amount
                val currentMarginRatio =
                    if (equity > 0) (-cashBalance).coerceAtLeast(0.0) / equity else marginTarget
                val scaleFactor = when (strategy.cashflowScaling) {
                    CashflowScaling.SCALED_BY_TARGET_MARGIN -> 1.0 + marginTarget
                    CashflowScaling.SCALED_BY_CURRENT_MARGIN -> 1.0 + currentMarginRatio
                    CashflowScaling.NO_SCALING -> 1.0
                }
                val totalInvest = raw * strategy.cashflowImmediateInvestPct * scaleFactor
                // cashBalance gains the full raw equity contribution, then loses what's deployed to holdings
                cashBalance += raw - totalInvest
                for (ticker in tickers)
                    holdings[ticker] =
                        (holdings[ticker] ?: 0.0) + totalInvest * (targetWeights[ticker] ?: 0.0)
            }

            // Step 6: Advance all trigger checkers (running averages, peak/trough)
            dipResources?.checkersByKey?.values?.flatten()?.forEach { it.advance(i) }
            surgeResources?.checkersByKey?.values?.flatten()?.forEach { it.advance(i) }

            // Step 7: Buy-the-dip — check triggers + executor.advance (handles installments & new fires)
            dipResources?.let { processConfig(it, Direction.BUY, i, curDate) }

            // Step 8: Sell-on-surge — check triggers + executor.advance (handles installments & new fires)
            surgeResources?.let { processConfig(it, Direction.SELL, i, curDate) }

            // Step 9: Record equity
            equity = max(0.0, holdings.values.sum() + cashBalance)
            values.add(equity)
        }

        val points = dates.mapIndexed { i, d -> DataPoint(d.toString(), values[i]) }
        val stats = BacktestService.computeBacktestStats(values, dates, effrx)
        return CurveResult(strategy.label, points, stats)
    }

    // ── Eligible amount calculation ───────────────────────────────────────────

    private fun computeEligible(
        key: DipSurgeKey,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        cashBalance: Double,
        direction: Direction,
        marginTarget: Double,
        limit: Double,
        deviationMode: DeviationMode
    ): Double {
        val equity = holdings.values.sum() + cashBalance
        if (equity <= 0) return 0.0

        return when (key) {
            is DipSurgeKey.WholePortfolio -> {
                val currentRatio = (-cashBalance).coerceAtLeast(0.0) / equity
                if (direction == Direction.BUY) {
                    val capMargin = computeThreshold(marginTarget, limit, deviationMode, high = true)
                    max(0.0, equity * (capMargin - currentRatio))
                } else {
                    val floorMargin = computeThreshold(marginTarget, limit, deviationMode, high = false)
                    max(0.0, equity * (currentRatio - floorMargin))
                }
            }

            is DipSurgeKey.Stock -> {
                val cur = holdings[key.ticker] ?: 0.0
                val targetWeight = targetWeights[key.ticker] ?: 0.0
                if (direction == Direction.BUY) {
                    val capMargin = computeThreshold(marginTarget, limit, deviationMode, high = true)
                    max(0.0, targetWeight * equity * (1 + capMargin) - cur)
                } else {
                    val floorMargin = computeThreshold(marginTarget, limit, deviationMode, high = false)
                    max(0.0, cur - targetWeight * equity * (1 + floorMargin))
                }
            }
        }
    }

    // ── Allocation helpers ────────────────────────────────────────────────────

    private fun applyAllocDelta(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double,
        allocStrategy: MarginRebalanceMode?
    ) {
        when (allocStrategy ?: MarginRebalanceMode.PROPORTIONAL) {
            MarginRebalanceMode.PROPORTIONAL, MarginRebalanceMode.DAILY -> {
                for (ticker in tickers)
                    holdings[ticker] =
                        (holdings[ticker] ?: 0.0) + delta * (targetWeights[ticker] ?: 0.0)
            }

            MarginRebalanceMode.CURRENT_WEIGHT -> {
                val total = holdings.values.sum()
                if (total == 0.0) return
                for (ticker in tickers)
                    holdings[ticker] =
                        (holdings[ticker] ?: 0.0) + delta * ((holdings[ticker] ?: 0.0) / total)
            }

            MarginRebalanceMode.FULL_REBALANCE -> {
                val total = holdings.values.sum() + delta
                for (ticker in tickers)
                    holdings[ticker] = total * (targetWeights[ticker] ?: 0.0)
            }

            MarginRebalanceMode.UNDERVALUED_PRIORITY ->
                BacktestService.computeUndervalueFirst(tickers, holdings, targetWeights, delta)

            MarginRebalanceMode.WATERFALL ->
                BacktestService.computeWaterfall(tickers, holdings, targetWeights, delta)
        }
    }

    private fun applyDipSurge(
        key: DipSurgeKey,
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        amount: Double,
        direction: Direction,
        allocStrategy: MarginRebalanceMode?
    ) {
        val delta = if (direction == Direction.BUY) amount else -amount
        when (key) {
            is DipSurgeKey.WholePortfolio -> applyAllocDelta(
                tickers,
                holdings,
                targetWeights,
                delta,
                allocStrategy
            )

            is DipSurgeKey.Stock -> holdings[key.ticker] = (holdings[key.ticker] ?: 0.0) + delta
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun computeThreshold(target: Double, pct: Double, mode: DeviationMode, high: Boolean): Double =
        if (mode == DeviationMode.ABSOLUTE) if (high) target + pct else target - pct
        else if (high) target * (1 + pct) else target * (1 - pct)

    private fun isCashflowDate(frequency: CashflowFrequency, date: LocalDate): Boolean =
        when (frequency) {
            CashflowFrequency.NONE -> false
            CashflowFrequency.MONTHLY -> date.dayOfMonth == 1
            CashflowFrequency.QUARTERLY -> date.dayOfMonth == 1 && date.monthValue in listOf(
                1,
                4,
                7,
                10
            )

            CashflowFrequency.YEARLY -> date.dayOfMonth == 1 && date.monthValue == 1
        }

    private fun shouldRebalance(
        strategy: RebalanceStrategy,
        prev: LocalDate,
        cur: LocalDate
    ): Boolean =
        when (strategy) {
            RebalanceStrategy.NONE -> false
            RebalanceStrategy.DAILY -> true
            RebalanceStrategy.WEEKLY ->
                cur.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) != prev.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                        || cur.year != prev.year

            RebalanceStrategy.MONTHLY -> cur.month != prev.month
            RebalanceStrategy.QUARTERLY ->
                (cur.monthValue - 1) / 3 != (prev.monthValue - 1) / 3

            RebalanceStrategy.YEARLY -> cur.year != prev.year
        }
}

// ── Extension / helpers ───────────────────────────────────────────────────────

private fun RebalancePeriodOverride.toRebalanceStrategy(inherit: RebalanceStrategy): RebalanceStrategy =
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
    val limit: Double
)
