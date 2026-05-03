package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import java.time.LocalDate
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        cashflowScaling: CashflowScaling = CashflowScaling.NO_SCALING,
        useComfortZone: Boolean = true,
        marginRebalanceTradeDirection: MarginRebalanceTradeDirection = MarginRebalanceTradeDirection.BOTH,
    ) = RebalStrategyConfig(
        label = "test",
        marginRatio = marginRatio,
        marginSpread = marginSpread,
        rebalancePeriod = rebalancePeriod,
        marginRebalanceTradeDirection = marginRebalanceTradeDirection,
        cashflowImmediateInvestPct = cashflowImmediateInvestPct,
        cashflowScaling = cashflowScaling,
        deviationMode = DeviationMode.ABSOLUTE,
        sellOnHighMargin = sellOnHighMargin,
        buyOnLowMargin = buyOnLowMargin,
        buyTheDip = buyTheDip,
        sellOnSurge = sellOnSurge,
        useComfortZone = useComfortZone,
        comfortZoneLow = comfortLow,
        comfortZoneHigh = comfortHigh
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun dipSurgeConfig_defaultsCoolingOffToTenDays() {
        val cfg = DipSurgeConfig(
            scope = DipSurgeScope.INDIVIDUAL_STOCK,
            allocStrategy = null,
            triggers = listOf(PriceMoveTrigger.PeakDeviation(0.1)),
            method = ExecutionMethod.Once,
            limit = 0.5,
        )

        assertEquals(10, cfg.coolingOffDays)
    }

    @Test
    fun dipSurgeCooldown_isPerKeyAndBlocksNextTenTradingDays() {
        val cooldown = DipSurgeCooldown()
        val aaa = DipSurgeKey.Stock("AAA")
        val bbb = DipSurgeKey.Stock("BBB")

        assertTrue(cooldown.shouldFire(aaa, 1, rawTriggered = true), "AAA first trigger should fire")
        assertFalse(cooldown.shouldFire(aaa, 2, rawTriggered = true), "AAA next day should be blocked")
        assertFalse(cooldown.shouldFire(aaa, 11, rawTriggered = true), "AAA tenth day after trigger should be blocked")
        assertTrue(cooldown.shouldFire(aaa, 12, rawTriggered = true), "AAA should fire after ten full cooldown days")

        assertTrue(cooldown.shouldFire(bbb, 2, rawTriggered = true), "BBB should have independent cooldown")
        assertFalse(cooldown.shouldFire(bbb, 12, rawTriggered = true), "BBB should still block its own tenth day")
        assertTrue(cooldown.shouldFire(bbb, 13, rawTriggered = true), "BBB should fire after its own cooldown")
    }

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
        val marginPoints = requireNotNull(result.marginPoints) {
            "marginPoints must be non-null when marginRatio=0.5"
        }
        // marginUtil = (-CB)/equity = 5_000/10_000 = 0.5 throughout (no price movement, no interest)
        marginPoints.forEachIndexed { i, p -> assertApprox(0.5, p.value, label = "margin[$i]") }
    }

    @Test
    fun monthlyRebalance_canIgnoreComfortZoneAndRestoreTargetMargin() {
        val dates = days(LocalDate.of(2024, 1, 30), 4)
        val prices = mapOf(
            dates[0] to 1.0,
            dates[1] to 2.0,
            dates[2] to 2.0,
            dates[3] to 2.0,
        )
        val series = mapOf("SPY" to prices)
        val portfolio = singleStockPortfolio(rebalance = RebalanceStrategy.MONTHLY)

        val withComfort = RebalanceStrategyService.runStrategyResultForTest(
            portfolio,
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.INHERIT,
                comfortLow = 0.4,
                comfortHigh = 0.6,
                useComfortZone = true,
            ),
            null,
            series,
            dates,
            emptyMap(),
        )
        val withoutComfort = RebalanceStrategyService.runStrategyResultForTest(
            portfolio,
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.INHERIT,
                comfortLow = 0.4,
                comfortHigh = 0.6,
                useComfortZone = false,
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val withComfortMargins = requireNotNull(withComfort.marginPoints)
        val withoutComfortMargins = requireNotNull(withoutComfort.marginPoints)
        assertApprox(0.4, withComfortMargins[2].value, label = "with comfort Feb 1 margin")
        assertApprox(0.5, withoutComfortMargins[2].value, label = "without comfort Feb 1 margin")
        withoutComfortMargins.forEachIndexed { i, p ->
            if (i >= 2) assertApprox(0.5, p.value, label = "without comfort margin[$i]")
        }
    }

    @Test
    fun marginRebalance_doesNotRunWhenNormalRebalanceTriggersSameDay() {
        val dates = listOf(
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
            LocalDate.of(2024, 2, 2),
        )
        val series = mapOf(
            "AAA" to mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 4.0),
            "BBB" to mapOf(dates[0] to 1.0, dates[1] to 1.0, dates[2] to 1.0),
        )
        val portfolio = PortfolioConfig(
            "test",
            listOf(TickerWeight("AAA", 0.5), TickerWeight("BBB", 0.5)),
            RebalanceStrategy.MONTHLY,
            emptyList(),
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            portfolio,
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
            ).copy(rebalanceAllocStrategy = MarginRebalanceMode.CURRENT_WEIGHT),
            null,
            series,
            dates,
            emptyMap(),
        )

        // Feb 1 is both a normal rebalance day and a margin rebalance day. Only the
        // normal rebalance should run, restoring both tickers to target weights
        // before AAA doubles again on Feb 2.
        assertApprox(30_625.0, result.points[2].value, label = "equity after normal-only rebalance")
    }

    @Test
    fun marginRebalance_tradeDirectionCanLimitBuysOrSells() {
        val dates = listOf(
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
            LocalDate.of(2024, 2, 2),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 2.0)
        val falling = mapOf(dates[0] to 1.0, dates[1] to 0.5, dates[2] to 0.5)

        val buyBlocked = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.SELL_ONLY,
            ),
            null,
            mapOf("SPY" to rising),
            dates,
            emptyMap(),
        )
        val sellBlocked = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
            ),
            null,
            mapOf("SPY" to falling),
            dates,
            emptyMap(),
        )
        val buyAllowed = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
            ),
            null,
            mapOf("SPY" to rising),
            dates,
            emptyMap(),
        )
        val sellAllowed = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.SELL_ONLY,
            ),
            null,
            mapOf("SPY" to falling),
            dates,
            emptyMap(),
        )

        assertApprox(0.2, requireNotNull(buyBlocked.marginPoints)[1].value, label = "sell-only blocks scheduled buy")
        assertApprox(2.0, requireNotNull(sellBlocked.marginPoints)[1].value, label = "buy-only blocks scheduled sell")
        assertApprox(0.5, requireNotNull(buyAllowed.marginPoints)[1].value, label = "buy-only allows scheduled buy")
        assertApprox(0.5, requireNotNull(sellAllowed.marginPoints)[1].value, label = "sell-only allows scheduled sell")
    }

    @Test
    fun marginRebalance_supportsAdditionalPeriodBoundaries() {
        fun assertRebalancesAt(period: RebalancePeriodOverride, prev: LocalDate, cur: LocalDate) {
            val dates = listOf(prev, cur)
            val series = mapOf("SPY" to mapOf(prev to 1.0, cur to 2.0))

            val result = RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5,
                    rebalancePeriod = period,
                    useComfortZone = false,
                ),
                null,
                series,
                dates,
                emptyMap(),
            )

            assertApprox(0.5, requireNotNull(result.marginPoints)[1].value, label = "$period margin")
        }

        assertRebalancesAt(RebalancePeriodOverride.BI_WEEKLY, LocalDate.of(2024, 1, 7), LocalDate.of(2024, 1, 8))
        assertRebalancesAt(RebalancePeriodOverride.BI_MONTHLY, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1))
        assertRebalancesAt(RebalancePeriodOverride.EVERY_4_MONTHS, LocalDate.of(2024, 4, 30), LocalDate.of(2024, 5, 1))
        assertRebalancesAt(RebalancePeriodOverride.HALF_YEARLY, LocalDate.of(2024, 6, 30), LocalDate.of(2024, 7, 1))
    }

    @Test
    fun actualBacktestEqualsRebalanceStrategyWithInheritedRebalanceAndMarginRestores() {
        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-backtest-rebalance-test")
        AppDirs.dataDir = tempDataDir
        try {
            val dates = days(LocalDate.of(2024, 1, 29), 12)
            val tickerDir = tempDataDir.resolve(".ticker").toFile().also { it.mkdirs() }
            val today = LocalDate.now()
            val aValues = listOf(10_000.0, 8_000.0, 7_000.0, 7_000.0, 10_000.0, 12_000.0, 11_000.0, 9_000.0, 9_500.0, 11_000.0, 10_500.0, 10_800.0)
            val bValues = listOf(10_000.0, 10_500.0, 10_200.0, 10_200.0, 10_000.0, 9_700.0, 10_300.0, 10_700.0, 10_200.0, 9_900.0, 10_100.0, 10_000.0)
            val seriesA = dates.zip(aValues).toMap() + (today to aValues.last())
            val seriesB = dates.zip(bValues).toMap() + (today to bValues.last())
            BacktestService.writeSimCsv(tickerDir.resolve("BTSTA-$today.csv"), seriesA)
            BacktestService.writeSimCsv(tickerDir.resolve("BTSTB-$today.csv"), seriesB)

            val marginConfig = MarginConfig(
                marginRatio = 0.5,
                marginSpread = 0.0,
                marginDeviationUpper = 0.1,
                marginDeviationLower = 0.1,
                upperRebalanceMode = MarginRebalanceMode.PROPORTIONAL,
                lowerRebalanceMode = MarginRebalanceMode.PROPORTIONAL,
            )
            val portfolio = PortfolioConfig(
                label = "actual",
                tickers = listOf(TickerWeight("BTSTA", 0.6), TickerWeight("BTSTB", 0.4)),
                rebalanceStrategy = RebalanceStrategy.MONTHLY,
                marginStrategies = listOf(marginConfig),
                includeNoMargin = false,
            )
            val strategy = RebalStrategyConfig(
                label = "strategy",
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.INHERIT,
                cashflowImmediateInvestPct = 1.0,
                cashflowScaling = CashflowScaling.NO_SCALING,
                deviationMode = DeviationMode.ABSOLUTE,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.6,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.5,
                ),
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.4,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.5,
                ),
                buyTheDip = null,
                sellOnSurge = null,
                useComfortZone = false,
                comfortZoneLow = 0.4,
                comfortZoneHigh = 0.6,
            )

            val backtestCurve = BacktestService.runMulti(
                MultiBacktestRequest(
                    fromDate = dates.first().toString(),
                    toDate = dates.last().toString(),
                    portfolios = listOf(portfolio),
                    cashflow = null,
                    startingBalance = 10_000.0,
                )
            ).portfolios.single().curves.single()

            val strategyCurve = RebalanceStrategyService.run(
                RebalanceStrategyRequest(
                    fromDate = dates.first().toString(),
                    toDate = dates.last().toString(),
                    portfolio = portfolio,
                    cashflow = null,
                    strategies = listOf(strategy),
                    startingBalance = 10_000.0,
                )
            ).portfolios.last().curves.single()

            assertTrue(backtestCurve.points.size == strategyCurve.points.size, "Equity point counts differ")
            val backtestMargins = requireNotNull(backtestCurve.marginPoints)
            val strategyMargins = requireNotNull(strategyCurve.marginPoints)
            assertTrue(backtestMargins.size == strategyMargins.size, "Margin point counts differ")
            backtestCurve.points.zip(strategyCurve.points).forEachIndexed { i, (backtest, rebalStrategy) ->
                assertTrue(backtest.date == rebalStrategy.date, "Equity dates differ at index $i")
                assertApprox(backtest.value, rebalStrategy.value, eps = 1e-6, label = "equity[$i] ${backtest.date}")
            }
            backtestMargins.zip(strategyMargins).forEachIndexed { i, (backtest, rebalStrategy) ->
                assertTrue(backtest.date == rebalStrategy.date, "Margin dates differ at index $i")
                assertApprox(backtest.value, rebalStrategy.value, eps = 1e-6, label = "margin[$i] ${backtest.date}")
            }
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun actualBacktestEqualsRebalanceStrategyForKmlmVtLongPeriodProfile() {
        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-kmlm-vt-long-period-test")
        AppDirs.dataDir = tempDataDir
        try {
            val fromDate = "1992-01-02"
            val toDate = "2026-03-02"
            val marginConfig = MarginConfig(
                marginRatio = 0.5,
                marginSpread = 0.0,
                marginDeviationUpper = 0.1,
                marginDeviationLower = 0.1,
                upperRebalanceMode = MarginRebalanceMode.PROPORTIONAL,
                lowerRebalanceMode = MarginRebalanceMode.PROPORTIONAL,
            )
            val portfolio = PortfolioConfig(
                label = "40% KMLM / 60% VT",
                tickers = listOf(TickerWeight("KMLM", 0.4), TickerWeight("VT", 0.6)),
                rebalanceStrategy = RebalanceStrategy.MONTHLY,
                marginStrategies = listOf(marginConfig),
                includeNoMargin = false,
            )
            val strategy = RebalStrategyConfig(
                label = "strategy",
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.INHERIT,
                cashflowImmediateInvestPct = 1.0,
                cashflowScaling = CashflowScaling.NO_SCALING,
                deviationMode = DeviationMode.ABSOLUTE,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.6,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.5,
                ),
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.4,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.5,
                ),
                buyTheDip = null,
                sellOnSurge = null,
                useComfortZone = false,
                comfortZoneLow = 0.4,
                comfortZoneHigh = 0.6,
            )

            val backtestCurve = BacktestService.runMulti(
                MultiBacktestRequest(
                    fromDate = fromDate,
                    toDate = toDate,
                    portfolios = listOf(portfolio),
                    cashflow = null,
                    startingBalance = 10_000.0,
                )
            ).portfolios.single().curves.single()

            val strategyCurve = RebalanceStrategyService.run(
                RebalanceStrategyRequest(
                    fromDate = fromDate,
                    toDate = toDate,
                    portfolio = portfolio,
                    cashflow = null,
                    strategies = listOf(strategy),
                    startingBalance = 10_000.0,
                )
            ).portfolios.last().curves.single()

            assertTrue(backtestCurve.points.size == strategyCurve.points.size, "Equity point counts differ")
            val backtestMargins = requireNotNull(backtestCurve.marginPoints)
            val strategyMargins = requireNotNull(strategyCurve.marginPoints)
            assertTrue(backtestMargins.size == strategyMargins.size, "Margin point counts differ")
            assertTrue(backtestCurve.points.size > 8_000, "Expected a long overlapping history")
            backtestCurve.points.zip(strategyCurve.points).forEachIndexed { i, (backtest, rebalStrategy) ->
                assertTrue(backtest.date == rebalStrategy.date, "Equity dates differ at index $i")
                assertApprox(backtest.value, rebalStrategy.value, eps = 1e-5, label = "equity[$i] ${backtest.date}")
            }
            backtestMargins.zip(strategyMargins).forEachIndexed { i, (backtest, rebalStrategy) ->
                assertTrue(backtest.date == rebalStrategy.date, "Margin dates differ at index $i")
                assertApprox(backtest.value, rebalStrategy.value, eps = 1e-8, label = "margin[$i] ${backtest.date}")
            }
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
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
        requireNotNull(result.marginPoints)
            .forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "margin[$i]") }
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
        requireNotNull(result.marginPoints)
            .forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "margin[$i]") }
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
            requireNotNull(r.marginPoints)
                .forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "$scope margin[$i]") }
        }
    }

    @Test
    fun buyTheDipActionPointRequiresActualPurchase() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = vShapeCurve(dates)
        val series = mapOf("SPY" to prices)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.WHOLE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.15)),
                    method = ExecutionMethod.Once,
                    limit = 0.5,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        assertNull(result.actionPoints, "BD markers should not be emitted when the trigger fires but no purchase is made")
    }

    @Test
    fun buyTheDipCooldownCanBeConsumedBeforeSellHighMakesBuyingEligible() {
        val dates = days(LocalDate.of(2024, 1, 2), 16)
        val values = listOf(
            1.00, 0.95, 0.90, 0.85, 0.84, 0.83, 0.82, 0.81,
            0.80, 0.79, 0.78, 0.77, 0.76, 0.75, 0.74, 0.73,
        )
        val series = mapOf("SPY" to dates.zip(values).toMap())

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.85,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 1.0,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    targetMargin = 0.75,
                ),
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.WHOLE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL,
                    triggers = listOf(PriceMoveTrigger.VsRunningAvg(nDays = 1, pct = 0.0)),
                    method = ExecutionMethod.Once,
                    limit = 0.85,
                    coolingOffDays = 10,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        assertTrue(actions.any { it.date == dates[3].toString() && it.type == "SELL_HIGH" })
        assertFalse(
            actions.any { it.date == dates[3].toString() && it.type == "BUY_DIP" },
            "BD is blocked on the SH day because the previous zero-eligible dip trigger consumed cooldown",
        )
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
            requireNotNull(r.marginPoints)
                .forEachIndexed { i, p -> assertApprox(expMargins[i], p.value, label = "$scope margin[$i]") }
        }
    }
}
