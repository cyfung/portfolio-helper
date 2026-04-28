package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object RebalanceStrategyService {

    fun run(request: RebalanceStrategyRequest): MultiBacktestResult {
        val fromDate = request.fromDate?.let { LocalDate.parse(it) }
        val toDate   = request.toDate?.let  { LocalDate.parse(it) } ?: LocalDate.now()
        val effrx    = BacktestService.loadEffrxSeries()
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

        val letfComponentSeries = letfDefs.values.flatMap { it.components }.mapNotNull { seriesCache[it.ticker] }
        val letfDates = if (letfComponentSeries.isNotEmpty())
            BacktestService.intersectDates(letfComponentSeries, fromDate, toDate) else emptyList()
        if (letfDates.size >= 2) {
            for ((letfString, def) in letfDefs) {
                if (letfString !in seriesCache) {
                    val componentSeries = def.components.associate { it.ticker to seriesCache[it.ticker]!! }
                    seriesCache[letfString] = BacktestService.computeLetfSeries(
                        def, componentSeries, letfDates, effrx, def.rebalanceStrategy
                    )
                }
            }
        }
        for (tw in request.portfolio.tickers) {
            if (BacktestService.parseLETFDefinition(tw.ticker) == null) cachedLoad(tw.ticker)
        }

        val seriesMap: Map<String, Map<LocalDate, Double>> = request.portfolio.tickers.associate { tw ->
            tw.ticker to (seriesCache[tw.ticker] ?: error("Series for '${tw.ticker}' not found"))
        }
        val dates = BacktestService.intersectDates(seriesMap.values.toList(), fromDate, toDate)
        if (dates.size < 2) throw IllegalStateException("Not enough overlapping trading dates")

        val portfolioResults = request.strategies.map { strategy ->
            val curve = runStrategy(request.portfolio, strategy, request.cashflow, seriesMap, dates, effrx)
            PortfolioResult(strategy.label, listOf(curve))
        }
        return MultiBacktestResult(portfolioResults)
    }

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
        val effectiveRebalance = strategy.rebalancePeriod.toRebalanceStrategy(portfolio.rebalanceStrategy)
        val marginTarget = strategy.marginRatio

        val startEquity = 10_000.0
        val holdings = tickers.associateWith { startEquity * (targetWeights[it] ?: 0.0) }.toMutableMap()
        var borrowed = startEquity * marginTarget

        val returnRatios = BacktestService.buildReturnRatios(tickers, seriesMap, dates)
        val dailyLoanRates = BacktestService.buildDailyLoanRates(dates, effrx, strategy.marginSpread / 252.0)

        // Price history for dip/surge trigger evaluation (normalised to 1.0 at start)
        val rawPrices: Map<String, Map<LocalDate, Double>> = seriesMap

        val values = mutableListOf<Double>(startEquity)

        // Pending orders: executed next day
        var pendingMarginHigh  = false
        var pendingMarginLow   = false
        // Consecutive / stepped state per target key ("portfolio" or ticker)
        val consecutiveState = mutableMapOf<String, ConsecutiveState>()
        val steppedState     = mutableMapOf<String, SteppedState>()

        // Running averages for VS_RUNNING_AVG trigger (price sum over N days)
        val runningAvgAccum = mutableMapOf<String, ArrayDeque<Double>>()

        for (i in 1 until dates.size) {
            val prevDate = dates[i - 1]
            val curDate  = dates[i]

            val equityBefore = holdings.values.sum() - borrowed

            // ── Execute pending 1-day-delayed orders ──────────────────────────
            if (pendingMarginHigh && equityBefore > 0) {
                val target = equityBefore * marginTarget
                if (borrowed > target) {
                    val excess = borrowed - target
                    applyAllocDelta(tickers, holdings, targetWeights, -excess, strategy.sellOnHighMargin.allocStrategy)
                    borrowed = target
                }
                pendingMarginHigh = false
            }
            if (pendingMarginLow && equityBefore > 0) {
                val target = equityBefore * marginTarget
                if (borrowed < target) {
                    val deficit = target - borrowed
                    applyAllocDelta(tickers, holdings, targetWeights, deficit, strategy.buyOnLowMargin.allocStrategy)
                    borrowed = target
                }
                pendingMarginLow = false
            }

            // Execute consecutive dip/surge
            for ((key, cs) in consecutiveState.toMap()) {
                if (cs.daysRemaining <= 0) { consecutiveState.remove(key); continue }
                val eligible = computeEligible(key, holdings, targetWeights, strategy, borrowed, cs.direction)
                if (eligible > 0) {
                    val amount = eligible / cs.daysRemaining
                    applyDipSurge(key, tickers, holdings, targetWeights, amount, cs.direction, cs.allocStrategy)
                    if (cs.direction == "buy") borrowed += amount else borrowed -= amount.coerceAtMost(borrowed)
                }
                consecutiveState[key] = cs.copy(daysRemaining = cs.daysRemaining - 1)
                if (cs.daysRemaining - 1 <= 0) consecutiveState.remove(key)
            }

            // ── Apply daily returns ───────────────────────────────────────────
            for (ticker in tickers)
                holdings[ticker] = (holdings[ticker] ?: 0.0) * (returnRatios[ticker]?.get(i) ?: 1.0)

            // Borrowed amount stays fixed in nominal terms; only grows with interest
            borrowed *= (1.0 + dailyLoanRates[i])

            var equity = holdings.values.sum() - borrowed

            // ── Cashflow injection ────────────────────────────────────────────
            if (cashflow != null && isCashflowDate(cashflow.frequency, curDate)) {
                val raw = cashflow.amount
                val scaleFactor = when (strategy.cashflowScaling) {
                    CashflowScaling.SCALED_BY_TARGET_MARGIN  -> 1.0 + marginTarget
                    CashflowScaling.SCALED_BY_CURRENT_MARGIN ->
                        if (equity > 0) 1.0 + borrowed / equity else 1.0 + marginTarget
                    CashflowScaling.NO_SCALING               -> 1.0
                }
                val totalInvest = raw * strategy.cashflowImmediateInvestPct
                val equityPart  = raw                           // equity contribution
                val marginPart  = totalInvest * scaleFactor - totalInvest  // additional via margin
                equity += equityPart
                borrowed += marginPart
                for (ticker in tickers)
                    holdings[ticker] = (holdings[ticker] ?: 0.0) + totalInvest * scaleFactor * (targetWeights[ticker] ?: 0.0)
            }

            // ── Periodic rebalance ────────────────────────────────────────────
            if (shouldRebalance(effectiveRebalance, prevDate, curDate) && equity > 0) {
                val total = holdings.values.sum()
                for (ticker in tickers) holdings[ticker] = total * (targetWeights[ticker] ?: 0.0)
                borrowed = equity * marginTarget
            }

            equity = holdings.values.sum() - borrowed

            // ── Check margin deviation triggers ───────────────────────────────
            if (equity > 0) {
                val currentRatio = borrowed / equity
                val dev = computeDeviation(currentRatio, marginTarget, strategy.deviationMode)

                val highThreshold = strategy.sellOnHighMargin.deviationPct
                val lowThreshold  = strategy.buyOnLowMargin.deviationPct

                if (highThreshold != null && dev > highThreshold && !pendingMarginHigh)
                    pendingMarginHigh = true

                if (lowThreshold != null && -dev > lowThreshold && !pendingMarginLow)
                    pendingMarginLow = true
            }

            // ── Update running averages for price triggers ────────────────────
            for (ticker in tickers) {
                val price = rawPrices[ticker]?.get(curDate) ?: continue
                runningAvgAccum.getOrPut(ticker) { ArrayDeque() }.addLast(price)
            }

            // ── Buy the Dip triggers ──────────────────────────────────────────
            strategy.buyTheDip?.let { cfg ->
                checkDipSurgeTriggers(
                    cfg, "buy", i, dates, tickers, holdings, targetWeights,
                    rawPrices, runningAvgAccum, strategy, borrowed,
                    consecutiveState, steppedState
                ) { key, amount ->
                    applyDipSurge(key, tickers, holdings, targetWeights, amount, "buy", cfg.allocStrategy)
                    borrowed += amount
                }
            }

            // ── Sell on Surge triggers ────────────────────────────────────────
            strategy.sellOnSurge?.let { cfg ->
                checkDipSurgeTriggers(
                    cfg, "sell", i, dates, tickers, holdings, targetWeights,
                    rawPrices, runningAvgAccum, strategy, borrowed,
                    consecutiveState, steppedState
                ) { key, amount ->
                    applyDipSurge(key, tickers, holdings, targetWeights, amount, "sell", cfg.allocStrategy)
                    borrowed -= amount.coerceAtMost(borrowed)
                }
            }

            equity = max(0.0, holdings.values.sum() - borrowed)
            values.add(equity)
        }

        val points = dates.mapIndexed { i, d -> DataPoint(d.toString(), values[i]) }
        val stats  = BacktestService.computeBacktestStats(values, dates, effrx)
        return CurveResult(strategy.label, points, stats)
    }

    // ── Trigger evaluation ────────────────────────────────────────────────────

    private fun checkDipSurgeTriggers(
        cfg: DipSurgeConfig,
        direction: String,
        i: Int,
        dates: List<LocalDate>,
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        rawPrices: Map<String, Map<LocalDate, Double>>,
        runningAvgAccum: MutableMap<String, ArrayDeque<Double>>,
        strategy: RebalStrategyConfig,
        borrowed: Double,
        consecutiveState: MutableMap<String, ConsecutiveState>,
        steppedState: MutableMap<String, SteppedState>,
        executeNow: (key: String, amount: Double) -> Unit
    ) {
        val keys = if (cfg.scope == DipSurgeScope.INDIVIDUAL_STOCK) tickers else listOf("portfolio")

        for (key in keys) {
            if (consecutiveState.containsKey(key)) continue  // already in a consecutive run

            val priceHistory = if (key == "portfolio") null else rawPrices[key]
            val triggered = cfg.triggers.any { trigger ->
                isTriggerFired(trigger, direction, i, dates, key, priceHistory, holdings, rawPrices)
            }
            if (!triggered) continue

            val eligible = computeEligible(key, holdings, targetWeights, strategy, borrowed, direction)
            if (eligible <= 0) continue

            when (val method = cfg.method) {
                is ExecutionMethod.Once -> executeNow(key, eligible)

                is ExecutionMethod.Consecutive -> {
                    consecutiveState[key] = ConsecutiveState(
                        daysRemaining = method.days,
                        direction = direction,
                        allocStrategy = cfg.allocStrategy
                    )
                    val firstAmount = eligible / method.days
                    executeNow(key, firstAmount)
                    consecutiveState[key] = consecutiveState[key]!!.copy(daysRemaining = method.days - 1)
                }

                is ExecutionMethod.Stepped -> {
                    val ss = steppedState[key]
                    val basePrice = priceHistory?.get(dates[i]) ?: holdings.values.sum()
                    val additionalDrop = method.additionalPct

                    val portionIdx = if (ss == null) {
                        steppedState[key] = SteppedState(
                            basePrice = basePrice,
                            portionsFired = 1,
                            totalPortions = method.portions
                        )
                        0
                    } else {
                        val curPrice = priceHistory?.get(dates[i]) ?: holdings.values.sum()
                        val expectedDrop = additionalDrop * ss.portionsFired
                        val actualDrop   = (ss.basePrice - curPrice) / ss.basePrice
                        if (actualDrop < expectedDrop || ss.portionsFired >= ss.totalPortions) continue
                        steppedState[key] = ss.copy(portionsFired = ss.portionsFired + 1)
                        ss.portionsFired
                    }

                    val portionsLeft = method.portions - portionIdx
                    val amount = eligible / portionsLeft
                    executeNow(key, amount)
                    if (steppedState[key]?.portionsFired == method.portions) steppedState.remove(key)
                }
            }
        }
    }

    private fun isTriggerFired(
        trigger: PriceMoveTrigger,
        direction: String,
        i: Int,
        dates: List<LocalDate>,
        key: String,
        priceHistory: Map<LocalDate, Double>?,
        holdings: MutableMap<String, Double>,
        rawPrices: Map<String, Map<LocalDate, Double>>
    ): Boolean {
        val curDate = dates[i]

        return when (trigger) {
            is PriceMoveTrigger.VsNDaysAgo -> {
                if (i < trigger.nDays) return false
                val nDaysAgoDate = dates[i - trigger.nDays]
                val cur  = priceHistory?.get(curDate) ?: return false
                val past = priceHistory[nDaysAgoDate] ?: return false
                if (past <= 0) return false
                val move = (cur - past) / past
                if (direction == "buy") move < -trigger.pct else move > trigger.pct
            }
            is PriceMoveTrigger.VsRunningAvg -> {
                val window = trigger.nDays
                val priceHistory2 = priceHistory ?: return false
                val prices = (max(0, i - window) until i).mapNotNull { priceHistory2[dates[it]] }
                if (prices.isEmpty()) return false
                val avg = prices.average()
                val cur = priceHistory2[curDate] ?: return false
                if (avg <= 0) return false
                val move = (cur - avg) / avg
                if (direction == "buy") move < -trigger.pct else move > trigger.pct
            }
            is PriceMoveTrigger.PeakDeviation -> {
                val priceHistory2 = priceHistory ?: return false
                val prices = (0..i).mapNotNull { priceHistory2[dates[it]] }
                if (prices.isEmpty()) return false
                if (direction == "buy") {
                    val peak = prices.max()
                    val cur  = priceHistory2[curDate] ?: return false
                    peak > 0 && (peak - cur) / peak > trigger.pct
                } else {
                    val trough = prices.min()
                    val cur    = priceHistory2[curDate] ?: return false
                    trough > 0 && (cur - trough) / trough > trigger.pct
                }
            }
        }
    }

    // ── Eligible amount calculation ───────────────────────────────────────────

    private fun computeEligible(
        key: String,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        strategy: RebalStrategyConfig,
        borrowed: Double,
        direction: String
    ): Double {
        val equity = holdings.values.sum() - borrowed
        if (equity <= 0) return 0.0

        val upperLimit = strategy.upperLimit ?: Double.MAX_VALUE
        val lowerLimit = strategy.lowerLimit ?: Double.MAX_VALUE

        if (key == "portfolio") {
            val currentRatio = borrowed / equity
            val deviation = computeDeviation(currentRatio, strategy.marginRatio, strategy.deviationMode)
            val limit = if (direction == "buy") lowerLimit else upperLimit
            val eligible = equity * min(abs(deviation), limit)
            return max(0.0, eligible)
        }

        // Individual stock
        val total       = holdings.values.sum()
        val cur         = holdings[key] ?: 0.0
        val target      = (targetWeights[key] ?: 0.0) * total
        val adjustment  = if (direction == "buy") target - cur else cur - target
        val cap         = (if (direction == "buy") lowerLimit else upperLimit) * equity
        return max(0.0, min(adjustment, cap))
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
                val total = holdings.values.sum()
                for (ticker in tickers)
                    holdings[ticker] = (holdings[ticker] ?: 0.0) + delta * (targetWeights[ticker] ?: 0.0)
            }
            MarginRebalanceMode.CURRENT_WEIGHT -> {
                val total = holdings.values.sum()
                if (total == 0.0) return
                for (ticker in tickers)
                    holdings[ticker] = (holdings[ticker] ?: 0.0) + delta * ((holdings[ticker] ?: 0.0) / total)
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
        key: String,
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        amount: Double,
        direction: String,
        allocStrategy: MarginRebalanceMode?
    ) {
        val delta = if (direction == "buy") amount else -amount
        if (key == "portfolio") {
            applyAllocDelta(tickers, holdings, targetWeights, delta, allocStrategy)
        } else {
            holdings[key] = (holdings[key] ?: 0.0) + delta
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun computeDeviation(current: Double, target: Double, mode: DeviationMode): Double =
        if (mode == DeviationMode.ABSOLUTE) current - target
        else if (target != 0.0) (current - target) / target else 0.0

    private fun isCashflowDate(frequency: CashflowFrequency, date: LocalDate): Boolean = when (frequency) {
        CashflowFrequency.NONE      -> false
        CashflowFrequency.MONTHLY   -> date.dayOfMonth == 1
        CashflowFrequency.QUARTERLY -> date.dayOfMonth == 1 && date.monthValue in listOf(1, 4, 7, 10)
        CashflowFrequency.YEARLY    -> date.dayOfMonth == 1 && date.monthValue == 1
    }

    private fun shouldRebalance(strategy: RebalanceStrategy, prev: LocalDate, cur: LocalDate): Boolean =
        when (strategy) {
            RebalanceStrategy.NONE    -> false
            RebalanceStrategy.DAILY   -> true
            RebalanceStrategy.WEEKLY  ->
                cur.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) != prev.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                || cur.year != prev.year
            RebalanceStrategy.MONTHLY   -> cur.month != prev.month
            RebalanceStrategy.QUARTERLY ->
                (cur.monthValue - 1) / 3 != (prev.monthValue - 1) / 3
            RebalanceStrategy.YEARLY -> cur.year != prev.year
        }
}

// ── Extension / helpers ───────────────────────────────────────────────────────

private fun RebalancePeriodOverride.toRebalanceStrategy(inherit: RebalanceStrategy): RebalanceStrategy =
    when (this) {
        RebalancePeriodOverride.INHERIT    -> inherit
        RebalancePeriodOverride.NONE       -> RebalanceStrategy.NONE
        RebalancePeriodOverride.MONTHLY    -> RebalanceStrategy.MONTHLY
        RebalancePeriodOverride.QUARTERLY  -> RebalanceStrategy.QUARTERLY
        RebalancePeriodOverride.YEARLY     -> RebalanceStrategy.YEARLY
    }

// ── State data classes ────────────────────────────────────────────────────────

private data class ConsecutiveState(
    val daysRemaining: Int,
    val direction: String,
    val allocStrategy: MarginRebalanceMode?
)

private data class SteppedState(
    val basePrice: Double,
    val portionsFired: Int,
    val totalPortions: Int
)
