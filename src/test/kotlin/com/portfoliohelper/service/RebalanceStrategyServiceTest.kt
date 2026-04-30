package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RebalanceStrategyServiceTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertApprox(expected: Double, actual: Double, eps: Double = 1e-9, label: String = "") =
        assertTrue(
            abs(expected - actual) <= eps,
            "Expected $expected but was $actual (eps=$eps)${if (label.isNotEmpty()) " [$label]" else ""}"
        )

    /** N consecutive calendar days starting from [start]. */
    private fun days(start: LocalDate, n: Int) = (0 until n).map { start.plusDays(it.toLong()) }

    private fun flatCurve(dates: List<LocalDate>) = dates.associateWith { 1.0 }

    /** V-shape: 1.0 → 0.5 at mid → 1.0. For 21 dates: mid=10, price[10]=0.5. */
    private fun vShapeCurve(dates: List<LocalDate>): Map<LocalDate, Double> {
        val n = dates.size; val mid = (n - 1) / 2
        return dates.mapIndexed { i, d ->
            val p = if (i <= mid) 1.0 - 0.5 * (i.toDouble() / mid)
                    else 0.5 + 0.5 * ((i - mid).toDouble() / (n - 1 - mid))
            d to p
        }.toMap()
    }

    /** Reverse-V: 1.0 → 1.5 at mid → 1.0. For 21 dates: mid=10, price[10]=1.5. */
    private fun reverseVCurve(dates: List<LocalDate>): Map<LocalDate, Double> {
        val n = dates.size; val mid = (n - 1) / 2
        return dates.mapIndexed { i, d ->
            val p = if (i <= mid) 1.0 + 0.5 * (i.toDouble() / mid)
                    else 1.5 - 0.5 * ((i - mid).toDouble() / (n - 1 - mid))
            d to p
        }.toMap()
    }

    private fun singleStockPortfolio(rebalance: RebalanceStrategy = RebalanceStrategy.NONE) =
        PortfolioConfig("test", listOf(TickerWeight("SPY", 1.0)), rebalance, emptyList())

    private fun strategy(
        marginRatio: Double = 0.0,
        marginSpread: Double = 0.0,
        rebalancePeriod: RebalancePeriodOverride = RebalancePeriodOverride.NONE,
        comfortLow: Double = 0.0,
        comfortHigh: Double = 0.0,
        sellOnHighMargin: MarginTriggerAction? = null,
        buyOnLowMargin: MarginTriggerAction? = null,
        buyTheDip: DipSurgeConfig? = null,
        sellOnSurge: DipSurgeConfig? = null,
        cashflowImmediateInvestPct: Double = 1.0,
        cashflowScaling: CashflowScaling = CashflowScaling.NO_SCALING
    ) = RebalStrategyConfig(
        label = "test",
        marginRatio = marginRatio,
        marginSpread = marginSpread,
        rebalancePeriod = rebalancePeriod,
        cashflowImmediateInvestPct = cashflowImmediateInvestPct,
        cashflowScaling = cashflowScaling,
        deviationMode = DeviationMode.ABSOLUTE,
        sellOnHighMargin = sellOnHighMargin,
        buyOnLowMargin = buyOnLowMargin,
        buyTheDip = buyTheDip,
        sellOnSurge = sellOnSurge,
        comfortZoneLow = comfortLow,
        comfortZoneHigh = comfortHigh
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun flatCurve_noMargin_noRebalance() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val series = mapOf("SPY" to flatCurve(dates))

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(), strategy(), null, series, dates, emptyMap()
        )

        // price = 1.0 always, no margin → equity constant
        result.points.forEachIndexed { i, p -> assertApprox(10_000.0, p.value, label = "equity[$i]") }
        assertNull(result.marginPoints, "marginPoints must be null when marginRatio=0")
    }

    @Test
    fun flatCurve_monthlyRebalance() {
        val dates = days(LocalDate.of(2024, 1, 2), 45)
        val series = mapOf("SPY" to flatCurve(dates))
        val portfolio = singleStockPortfolio(rebalance = RebalanceStrategy.MONTHLY)
        // single stock 100% — rebalancing to itself is identity, equity unchanged
        val equity = RebalanceStrategyService.runStrategyForTest(
            portfolio, strategy(rebalancePeriod = RebalancePeriodOverride.INHERIT),
            null, series, dates, emptyMap()
        )

        equity.forEachIndexed { i, v -> assertApprox(10_000.0, v, label = "equity[$i]") }
    }

    @Test
    fun flatCurve_withMargin_zeroSpread() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val series = mapOf("SPY" to flatCurve(dates))
        // holdings=15_000, cashBalance=-5_000; no price change, no interest → equity=10_000 always
        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.5, marginSpread = 0.0, comfortLow = 0.5, comfortHigh = 0.5),
            null, series, dates, emptyMap()
        )

        result.points.forEachIndexed { i, p -> assertApprox(10_000.0, p.value, label = "equity[$i]") }
        assertTrue(result.marginPoints != null, "marginPoints must be non-null when marginRatio=0.5")
        // marginUtil = (-CB)/equity = 5_000/10_000 = 0.5 throughout (no price movement, no interest)
        result.marginPoints!!.forEachIndexed { i, p -> assertApprox(0.5, p.value, label = "margin[$i]") }
    }

    @Test
    fun vShape_noMargin_noRebalance() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = vShapeCurve(dates)
        val series = mapOf("SPY" to prices)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(), strategy(), null, series, dates, emptyMap()
        )

        // equity[i] = 10_000 * price[i] (cumulative returnRatios = price[i]/price[0] = price[i])
        // i=0:10_000, i=1:9_500, i=2:9_000 ... i=10:5_000 ... i=20:10_000
        dates.forEachIndexed { i, d ->
            assertApprox(10_000.0 * prices[d]!!, result.points[i].value, label = "equity[$i]")
        }
        assertNull(result.marginPoints)
    }

    @Test
    fun cashflow_monthly_flatCurve() {
        val start = LocalDate.of(2024, 1, 2)
        val dates = days(start, 45)
        val series = mapOf("SPY" to flatCurve(dates))
        // dates[30] = 2024-02-01 (30 days after Jan 2)
        val cashflow = CashflowConfig(amount = 1_000.0, frequency = CashflowFrequency.MONTHLY)

        val equity = RebalanceStrategyService.runStrategyForTest(
            singleStockPortfolio(),
            strategy(cashflowImmediateInvestPct = 1.0, cashflowScaling = CashflowScaling.NO_SCALING),
            cashflow, series, dates, emptyMap()
        )

        val feb1Index = dates.indexOfFirst { it.monthValue == 2 && it.dayOfMonth == 1 }
        assertTrue(feb1Index == 30, "Expected Feb 1 at index 30, got $feb1Index")

        // Before Feb 1: flat 10_000; on Feb 1: +1_000 (cashflowImmediateInvestPct=1.0, scaleFactor=1.0
        // → cashBalance += raw - totalInvest = 1000 - 1000 = 0; holdings += 1000); after: 11_000
        equity.forEachIndexed { i, v ->
            val expected = if (i < feb1Index) 10_000.0 else 11_000.0
            assertApprox(expected, v, label = "equity[$i]")
        }
    }

    @Test
    fun vShape_sellOnHighMargin() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = vShapeCurve(dates)
        val series = mapOf("SPY" to prices)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                comfortHigh = 0.5,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.7,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.5,
                )
            ),
            null, series, dates, emptyMap()
        )

        // Reference simulation: mirrors service loop exactly.
        // Trigger uses cashBalanceBefore/equityBefore (previous-day state); sells to -equity_current*0.5.
        // Single stock weight=1.0 → PROPORTIONAL delta = delta*1.0 = delta, applied entirely to SPY.
        var H = 15_000.0; var CB = -5_000.0
        val expEqs = mutableListOf(10_000.0)
        val expMargins = mutableListOf(0.5)
        for (i in 1 until dates.size) {
            val CBB = CB; val eqB = H + CBB
            H *= prices[dates[i]]!! / prices[dates[i - 1]]!!
            // no interest (marginSpread=0, effrx=empty)
            val equity = H + CB
            if (eqB > 0) {
                val ratio = (-CBB).coerceAtLeast(0.0) / eqB
                if (ratio > 0.7) {
                    val targetCB = -equity * 0.5
                    if (CB < targetCB) { H -= (targetCB - CB); CB = targetCB }
                }
            }
            val eq = maxOf(0.0, H + CB)
            expEqs += eq
            expMargins += if (eq > 0.0) (-CB).coerceAtLeast(0.0) / eq else 0.0
        }

        result.points.forEachIndexed { i, p -> assertApprox(expEqs[i], p.value, label = "equity[$i]") }
        assertTrue(result.marginPoints != null)
        result.marginPoints!!.forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "margin[$i]") }
    }

    @Test
    fun reverseV_buyOnLowMargin() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = reverseVCurve(dates)
        val series = mapOf("SPY" to prices)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                comfortLow = 0.5,
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.3,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.5,
                )
            ),
            null, series, dates, emptyMap()
        )

        // Reference: trigger fires when previous-day ratio < 0.3; buys to -equity_current*0.5.
        var H = 15_000.0; var CB = -5_000.0
        val expEqs = mutableListOf(10_000.0)
        val expMargins = mutableListOf(0.5)
        for (i in 1 until dates.size) {
            val CBB = CB; val eqB = H + CBB
            H *= prices[dates[i]]!! / prices[dates[i - 1]]!!
            val equity = H + CB
            if (eqB > 0) {
                val ratio = (-CBB).coerceAtLeast(0.0) / eqB
                if (ratio < 0.3) {
                    val targetCB = -equity * 0.5
                    if (CB > targetCB) { H += (CB - targetCB); CB = targetCB }
                }
            }
            val eq = maxOf(0.0, H + CB)
            expEqs += eq
            expMargins += if (eq > 0.0) (-CB).coerceAtLeast(0.0) / eq else 0.0
        }

        result.points.forEachIndexed { i, p -> assertApprox(expEqs[i], p.value, label = "equity[$i]") }
        assertTrue(result.marginPoints != null)
        result.marginPoints!!.forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "margin[$i]") }
    }

    @Test
    fun vShape_buyTheDip_wholePortfolio() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = vShapeCurve(dates)
        val series = mapOf("SPY" to prices)

        // Reference: mirrors PeakDeviationChecker advance/check ordering and OnceExecutor.
        // advance(i) runs before check(i) — price[0] never seen so runningPeak starts from price[1].
        // OnceExecutor fires execute(eligible()) every day triggered == true (stateless).
        // eligible = max(0, equity*(limit - currentRatio)); when currentRatio >= limit, eligible=0.
        var H = 15_000.0; var CB = -5_000.0
        var runningPeak = Double.MIN_VALUE
        val expEqs = mutableListOf(10_000.0)
        val expMargins = mutableListOf(0.5)
        for (i in 1 until dates.size) {
            H *= prices[dates[i]]!! / prices[dates[i - 1]]!!
            val curPrice = prices[dates[i]]!!
            runningPeak = maxOf(runningPeak, curPrice)
            val triggered = runningPeak > 0 && (runningPeak - curPrice) / runningPeak > 0.15
            if (triggered) {
                val eq = H + CB
                val currentRatio = (-CB).coerceAtLeast(0.0) / eq
                val eligible = maxOf(0.0, eq * (1.5 - currentRatio))
                H += eligible; CB -= eligible
            }
            val eq = maxOf(0.0, H + CB)
            expEqs += eq
            expMargins += if (eq > 0.0) (-CB).coerceAtLeast(0.0) / eq else 0.0
        }

        for (scope in listOf(DipSurgeScope.WHOLE_PORTFOLIO, DipSurgeScope.INDIVIDUAL_STOCK)) {
            val r = RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5, marginSpread = 0.0,
                    rebalancePeriod = RebalancePeriodOverride.NONE, comfortHigh = 1.5,
                    buyTheDip = DipSurgeConfig(
                        scope = scope, allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                        triggers = listOf(PriceMoveTrigger.PeakDeviation(0.15)),
                        method = ExecutionMethod.Once, limit = 1.5
                    )
                ),
                null, series, dates, emptyMap()
            )
            r.points.forEachIndexed { i, p -> assertApprox(expEqs[i], p.value, label = "$scope equity[$i]") }
            assertTrue(r.marginPoints != null)
            r.marginPoints!!.forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "$scope margin[$i]") }
        }
    }

    @Test
    fun reverseV_sellOnSurge_wholePortfolio() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = reverseVCurve(dates)
        val series = mapOf("SPY" to prices)

        // Reference: mirrors PeakDeviationChecker for SELL direction (runningTrough).
        // marginRatio=0.5 needed so currentRatio > limit=0.2 → eligible > 0 when sell fires.
        // advance(i) runs before check(i) — price[0] never seen, trough starts from price[1].
        // check(i, SELL): (cur - trough)/trough > 0.15
        // eligible = max(0, equity*(currentRatio - limit)); fires only when currentRatio > 0.2.
        var H = 15_000.0; var CB = -5_000.0
        var runningTrough = Double.MAX_VALUE
        val expEqs = mutableListOf(10_000.0)
        val expMargins = mutableListOf(0.5)
        for (i in 1 until dates.size) {
            H *= prices[dates[i]]!! / prices[dates[i - 1]]!!
            val curPrice = prices[dates[i]]!!
            runningTrough = minOf(runningTrough, curPrice)
            val triggered = runningTrough > 0 && (curPrice - runningTrough) / runningTrough > 0.15
            if (triggered) {
                val eq = H + CB
                val currentRatio = (-CB).coerceAtLeast(0.0) / eq
                val eligible = maxOf(0.0, eq * (currentRatio - 0.2))
                H -= eligible; CB += eligible
            }
            val eq = maxOf(0.0, H + CB)
            expEqs += eq
            expMargins += if (eq > 0.0) (-CB).coerceAtLeast(0.0) / eq else 0.0
        }

        for (scope in listOf(DipSurgeScope.WHOLE_PORTFOLIO, DipSurgeScope.INDIVIDUAL_STOCK)) {
            val r = RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5, marginSpread = 0.0,
                    rebalancePeriod = RebalancePeriodOverride.NONE, comfortHigh = 1.5,
                    sellOnSurge = DipSurgeConfig(
                        scope = scope, allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                        triggers = listOf(PriceMoveTrigger.PeakDeviation(0.15)),
                        method = ExecutionMethod.Once, limit = 0.2
                    )
                ),
                null, series, dates, emptyMap()
            )
            r.points.forEachIndexed { i, p -> assertApprox(expEqs[i], p.value, label = "$scope equity[$i]") }
            assertTrue(r.marginPoints != null)
            r.marginPoints!!.forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "$scope margin[$i]") }
        }
    }
}
