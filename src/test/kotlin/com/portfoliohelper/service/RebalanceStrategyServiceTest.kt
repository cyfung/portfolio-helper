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

    @Test
    fun `waterfall mode id is normalized and does not fall back to target weight`() {
        val tickers = listOf("A", "B")
        val holdings = mapOf("A" to 80.0, "B" to 20.0)
        val targetWeights = mapOf("A" to 0.5, "B" to 0.5)

        val deltas = BacktestService.computeAllocationDeltas(
            tickers,
            holdings,
            targetWeights,
            20.0,
            "waterfall"
        )

        assertApprox(0.0, deltas["A"] ?: 0.0)
        assertApprox(20.0, deltas["B"] ?: 0.0)
        assertApprox(20.0, deltas.values.sum())
    }

    @Test
    fun `waterfall sell prioritizes overweight holdings`() {
        val tickers = listOf("A", "B")
        val holdings = mapOf("A" to 80.0, "B" to 20.0)
        val targetWeights = mapOf("A" to 0.5, "B" to 0.5)

        val deltas = BacktestService.computeAllocationDeltas(
            tickers,
            holdings,
            targetWeights,
            -20.0,
            MarginRebalanceMode.WATERFALL.name
        )

        assertApprox(-20.0, deltas["A"] ?: 0.0)
        assertApprox(0.0, deltas["B"] ?: 0.0)
        assertApprox(-20.0, deltas.values.sum())
    }

    @Test
    fun `waterfall large buy does not collapse to target weight allocation`() {
        val tickers = listOf("A", "B")
        val holdings = mapOf("A" to 80.0, "B" to 20.0)
        val targetWeights = mapOf("A" to 0.5, "B" to 0.5)

        val deltas = BacktestService.computeAllocationDeltas(
            tickers,
            holdings,
            targetWeights,
            100.0,
            MarginRebalanceMode.WATERFALL.name
        )

        assertApprox(20.0, deltas["A"] ?: 0.0)
        assertApprox(80.0, deltas["B"] ?: 0.0)
        assertApprox(100.0, deltas.values.sum())
    }

    @Test
    fun `waterfall levels underweight tiers instead of target-weight splitting`() {
        val tickers = listOf("A", "B", "C")
        val holdings = mapOf("A" to 70.0, "B" to 20.0, "C" to 10.0)
        val targetWeights = mapOf("A" to 1.0 / 3.0, "B" to 1.0 / 3.0, "C" to 1.0 / 3.0)

        val deltas = BacktestService.computeAllocationDeltas(
            tickers,
            holdings,
            targetWeights,
            30.0,
            MarginRebalanceMode.WATERFALL.name
        )

        assertApprox(0.0, deltas["A"] ?: 0.0)
        assertApprox(10.0, deltas["B"] ?: 0.0)
        assertApprox(20.0, deltas["C"] ?: 0.0)
        assertApprox(30.0, deltas.values.sum())
    }

    @Test
    fun `hybrid target waterfall averages target-weight and waterfall deltas`() {
        val tickers = listOf("A", "B")
        val holdings = mapOf("A" to 80.0, "B" to 20.0)
        val targetWeights = mapOf("A" to 0.5, "B" to 0.5)

        val deltas = BacktestService.computeAllocationDeltas(
            tickers,
            holdings,
            targetWeights,
            20.0,
            MarginRebalanceMode.HYBRID_TARGET_WATERFALL.name
        )

        assertApprox(5.0, deltas["A"] ?: 0.0)
        assertApprox(15.0, deltas["B"] ?: 0.0)
        assertApprox(20.0, deltas.values.sum())
    }

    @Test
    fun `hybrid waterfall full rebalance averages waterfall and full rebalance deltas`() {
        val tickers = listOf("A", "B")
        val holdings = mapOf("A" to 80.0, "B" to 20.0)
        val targetWeights = mapOf("A" to 0.5, "B" to 0.5)

        val deltas = BacktestService.computeAllocationDeltas(
            tickers,
            holdings,
            targetWeights,
            20.0,
            MarginRebalanceMode.HYBRID_WATERFALL_FULL_REBALANCE.name
        )

        assertApprox(-10.0, deltas["A"] ?: 0.0)
        assertApprox(30.0, deltas["B"] ?: 0.0)
        assertApprox(20.0, deltas.values.sum())
    }

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

    private fun twoStockPortfolio(rebalance: RebalanceStrategy = RebalanceStrategy.NONE) =
        PortfolioConfig(
            "test",
            listOf(TickerWeight("AAA", 0.5), TickerWeight("BBB", 0.5)),
            rebalance,
            emptyList(),
        )

    private fun strategy(
        marginRatio: Double = 0.0,
        marginSpread: Double = 0.0,
        rebalancePeriod: RebalancePeriodOverride = RebalancePeriodOverride.NONE,
        comfortLow: Double = 0.0,
        comfortHigh: Double = 0.0,
        sellOnHighMargin: MarginTriggerAction? = null,
        buyOnLowMargin: MarginTriggerAction? = null,
        drawdownSellOnHighMargin: DrawdownMarginTriggerAction? = null,
        drawdownBuyOnLowMargin: DrawdownMarginTriggerAction? = null,
        vmTimingMr: VmTimingMrConfig? = null,
        buyTheDip: DipSurgeConfig? = null,
        sellOnSurge: DipSurgeConfig? = null,
        drawdownMarginOverride: DrawdownMarginOverrideConfig? = null,
        cashflowImmediateInvestPct: Double = 1.0,
        cashflowScaling: CashflowScaling = CashflowScaling.NO_SCALING,
        useComfortZone: Boolean = true,
        portfolioRebalanceUseComfortZone: Boolean = useComfortZone,
        marginRebalanceTradeDirection: MarginRebalanceTradeDirection = MarginRebalanceTradeDirection.BOTH,
        buyCooldownAfterSellHighDays: Int = 0,
        sellCooldownAfterBuyLowDays: Int = 0,
    ) = RebalStrategyConfig(
        label = "test",
        marginRatio = marginRatio,
        marginSpread = marginSpread,
        portfolioRebalanceUseComfortZone = portfolioRebalanceUseComfortZone,
        rebalancePeriod = rebalancePeriod,
        marginRebalanceTradeDirection = marginRebalanceTradeDirection,
        drawdownMarginOverride = drawdownMarginOverride,
        cashflowImmediateInvestPct = cashflowImmediateInvestPct,
        cashflowScaling = cashflowScaling,
        deviationMode = DeviationMode.ABSOLUTE,
        sellOnHighMargin = sellOnHighMargin,
        buyOnLowMargin = buyOnLowMargin,
        drawdownSellOnHighMargin = drawdownSellOnHighMargin,
        drawdownBuyOnLowMargin = drawdownBuyOnLowMargin,
        vmTimingMr = vmTimingMr,
        buyTheDip = buyTheDip,
        sellOnSurge = sellOnSurge,
        useComfortZone = useComfortZone,
        comfortZoneLow = comfortLow,
        comfortZoneHigh = comfortHigh,
        buyCooldownAfterSellHighDays = buyCooldownAfterSellHighDays,
        sellCooldownAfterBuyLowDays = sellCooldownAfterBuyLowDays,
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
        cooldown.recordFire(aaa, 1)
        assertFalse(cooldown.shouldFire(aaa, 2, rawTriggered = true), "AAA next day should be blocked")
        assertFalse(cooldown.shouldFire(aaa, 11, rawTriggered = true), "AAA tenth day after trigger should be blocked")
        assertTrue(cooldown.shouldFire(aaa, 12, rawTriggered = true), "AAA should fire after ten full cooldown days")
        cooldown.recordFire(aaa, 12)

        assertTrue(cooldown.shouldFire(bbb, 2, rawTriggered = true), "BBB should have independent cooldown")
        cooldown.recordFire(bbb, 2)
        assertFalse(cooldown.shouldFire(bbb, 12, rawTriggered = true), "BBB should still block its own tenth day")
        assertTrue(cooldown.shouldFire(bbb, 13, rawTriggered = true), "BBB should fire after its own cooldown")
    }

    @Test
    fun vmTimingMr_usesReferenceTickerHistoryBeforeBacktestStart() {
        val dates = days(LocalDate.of(2024, 1, 15), 20)
        val spy = flatCurve(dates)
        val refDates = listOf(LocalDate.of(2023, 10, 31)) + dates
        val ref = refDates.associateWith { date -> if (date < dates.first()) 1.0 else 1.1 }

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.1,
                vmTimingMr = VmTimingMrConfig(
                    enabled = true,
                    capeSource = CapeSource.US,
                    lowerMargin = 0.5,
                    upperMargin = 0.5,
                    momentumSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    momentumReferenceTicker = "REF",
                    momentumLookbackMonths = 3,
                    rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                ),
            ),
            null,
            mapOf("SPY" to spy, "REF" to ref),
            dates,
            emptyMap(),
        )

        assertTrue(result.actionPoints.orEmpty().any { it.date == "2024-02-01" && it.type == "VM_TIMING_MR" })
        assertApprox(0.5, requireNotNull(result.marginPoints)[17].value, label = "VM timing MR applies first monthly target")
    }

    @Test
    fun buyTheDipDiagnostics_showTenCooldownDaysMeansNextActionAfterElevenTradingDates() {
        val dates = days(LocalDate.of(2024, 1, 1), 20)
        val spy = dates.mapIndexed { i, d -> d to Math.pow(1.01, i.toDouble()) }.toMap()
        val ref = dates.mapIndexed { i, d -> d to if (i == 0) 1.0 else 0.8 }.toMap()

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.0,
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.1)),
                    method = ExecutionMethod.Once,
                    limit = 1.0,
                    coolingOffDays = 10,
                    minAdjustmentPct = 0.0,
                ),
            ),
            null,
            mapOf("SPY" to spy, "REF" to ref),
            dates,
            emptyMap(),
            includeActionDiagnostics = true,
        )

        val buyDipActions = result.actionPoints.orEmpty().filter { it.type == "BUY_DIP" }
        assertEquals(
            listOf(dates[1].toString(), dates[12].toString()),
            buyDipActions.take(2).map { it.date },
            "10 cooling-off days block the next ten trading dates after a BD action",
        )
        assertEquals(1, buyDipActions[0].detail?.tradingDayIndex)
        assertEquals(12, buyDipActions[1].detail?.tradingDayIndex)
        assertEquals(10, buyDipActions[1].detail?.cooldownDays)
        assertEquals(11, buyDipActions[1].detail?.daysSincePrevious)
    }

    @Test
    fun screenshotParameters_compareBuyTheDipAndDrawdownMarginOverrideDiagnostics() {
        val portfolio = PortfolioConfig(
            label = "VT:CTA 7:3",
            tickers = listOf(
                TickerWeight("1 KMLM 1 VT", 0.276),
                TickerWeight("DBMF", 0.108),
                TickerWeight("VT", 0.616),
            ),
            rebalanceStrategy = RebalanceStrategy.YEARLY,
            marginStrategies = emptyList(),
            includeNoMargin = false,
        )

        fun baseStrategy(
            label: String,
            buyTheDip: DipSurgeConfig?,
            drawdownMarginOverride: DrawdownMarginOverrideConfig?,
        ) = RebalStrategyConfig(
            label = label,
            marginRatio = 0.80,
            marginSpread = 0.015,
            portfolioRebalancePeriod = RebalancePeriodOverride.INHERIT,
            portfolioRebalanceUseComfortZone = true,
            marginRebalanceEnabled = true,
            rebalancePeriod = RebalancePeriodOverride.MONTHLY,
            rebalanceAllocStrategy = MarginRebalanceMode.WATERFALL.name,
            marginRebalanceTradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
            marginRebalanceRestoreMargin = 0.80,
            drawdownMarginOverride = drawdownMarginOverride,
            cashflowImmediateInvestPct = 1.0,
            cashflowScaling = CashflowScaling.SCALED_BY_TARGET_MARGIN,
            cashflowScalingMargin = 0.80,
            deviationMode = DeviationMode.ABSOLUTE,
            sellOnHighMargin = MarginTriggerAction(
                deviationPct = 1.10,
                allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                targetMargin = 0.90,
            ),
            buyOnLowMargin = null,
            buyTheDip = buyTheDip,
            sellOnSurge = null,
            useComfortZone = true,
            comfortZoneLow = 0.62,
            comfortZoneHigh = 1.10,
            buyCooldownAfterSellHighDays = 10,
            sellCooldownAfterBuyLowDays = 10,
        )

        val buyDipStrategy = baseStrategy(
            label = "80-100-110",
            buyTheDip = DipSurgeConfig(
                scope = DipSurgeScope.BASE_PORTFOLIO,
                allocStrategy = MarginRebalanceMode.WATERFALL.name,
                portfolioSource = PortfolioTriggerSource.STRATEGY_GROSS,
                triggers = listOf(PriceMoveTrigger.PeakDeviation(0.10)),
                method = ExecutionMethod.Once,
                limit = 1.00,
                coolingOffDays = 10,
                minAdjustmentPct = 0.000,
            ),
            drawdownMarginOverride = null,
        )
        val drawdownMrStrategy = baseStrategy(
            label = "80-100-110 (2)",
            buyTheDip = null,
            drawdownMarginOverride = DrawdownMarginOverrideConfig(
                enabled = true,
                portfolioSource = PortfolioTriggerSource.STRATEGY_GROSS,
                enterDrawdownPct = 0.10,
                exitDrawdownPct = 0.10,
                targetMargin = 1.00,
                rebalancePeriod = RebalancePeriodOverride.BI_WEEKLY,
                rebalanceOnEnter = true,
                allocStrategy = MarginRebalanceMode.WATERFALL.name,
                buyAllocStrategy = MarginRebalanceMode.WATERFALL.name,
                sellAllocStrategy = MarginRebalanceMode.WATERFALL.name,
                tradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
            ),
        )

        val result = RebalanceStrategyService.run(
            RebalanceStrategyRequest(
                fromDate = null,
                toDate = null,
                portfolio = portfolio,
                cashflow = null,
                strategies = listOf(buyDipStrategy, drawdownMrStrategy),
                startingBalance = 10_000.0,
                includeActionDiagnostics = true,
            )
        )
        val curves = result.portfolios.flatMap { it.curves }.associateBy { it.label }
        val buyDipCurve = requireNotNull(curves["80-100-110"])
        val drawdownMrCurve = requireNotNull(curves["80-100-110 (2)"])

        fun avgMargin(curve: CurveResult): Double =
            requireNotNull(curve.marginPoints).map { it.value }.average()

        fun count(curve: CurveResult, type: String): Int =
            curve.actionPoints.orEmpty().count { it.type == type }

        fun timing(curve: CurveResult, type: String, limit: Int = 12): String =
            curve.actionPoints.orEmpty()
                .filter { it.type == type }
                .take(limit)
                .joinToString(" | ") {
                    val d = requireNotNull(it.detail)
                    "${it.date}#${d.tradingDayIndex} since=${d.daysSincePrevious ?: "-"} " +
                        "amt=${"%.0f".format(d.amount ?: 0.0)} " +
                        "m=${"%.3f".format(d.marginBefore ?: 0.0)}->${"%.3f".format(d.marginAfter ?: 0.0)}"
                }

        fun gaps(curve: CurveResult, type: String): List<Int> =
            curve.actionPoints.orEmpty()
                .filter { it.type == type }
                .mapNotNull { it.detail?.tradingDayIndex }
                .zipWithNext { a, b -> b - a }

        println(
            "EXACT_COMPARE_SUMMARY BD end=${"%.2f".format(buyDipCurve.stats.endingValue)} " +
                "cagr=${"%.6f".format(buyDipCurve.stats.cagr)} maxDD=${"%.6f".format(buyDipCurve.stats.maxDrawdown)} " +
                "avgMargin=${"%.6f".format(avgMargin(buyDipCurve))} SH=${count(buyDipCurve, "SELL_HIGH")} " +
                "BD=${count(buyDipCurve, "BUY_DIP")} DDMR=${count(buyDipCurve, "DRAWDOWN_MR")} " +
                "DDMR_EXIT=${count(buyDipCurve, "DRAWDOWN_MR_EXIT")} MR=${count(buyDipCurve, "MARGIN_REBALANCE")}"
        )
        println(
            "EXACT_COMPARE_SUMMARY DDMR end=${"%.2f".format(drawdownMrCurve.stats.endingValue)} " +
                "cagr=${"%.6f".format(drawdownMrCurve.stats.cagr)} maxDD=${"%.6f".format(drawdownMrCurve.stats.maxDrawdown)} " +
                "avgMargin=${"%.6f".format(avgMargin(drawdownMrCurve))} SH=${count(drawdownMrCurve, "SELL_HIGH")} " +
                "BD=${count(drawdownMrCurve, "BUY_DIP")} DDMR=${count(drawdownMrCurve, "DRAWDOWN_MR")} " +
                "DDMR_EXIT=${count(drawdownMrCurve, "DRAWDOWN_MR_EXIT")} MR=${count(drawdownMrCurve, "MARGIN_REBALANCE")}"
        )
        println("EXACT_COMPARE_TIMING BD ${timing(buyDipCurve, "BUY_DIP")}")
        println("EXACT_COMPARE_TIMING DDMR ${timing(drawdownMrCurve, "DRAWDOWN_MR")}")
        println("EXACT_COMPARE_TIMING DDMR_EXIT ${timing(drawdownMrCurve, "DRAWDOWN_MR_EXIT")}")
        println("EXACT_COMPARE_TIMING DDMR_BASE_MR ${timing(drawdownMrCurve, "MARGIN_REBALANCE")}")
        println("EXACT_COMPARE_GAPS BD ${gaps(buyDipCurve, "BUY_DIP").take(20)}")
        println("EXACT_COMPARE_GAPS DDMR ${gaps(drawdownMrCurve, "DRAWDOWN_MR").take(20)}")

        assertTrue(count(buyDipCurve, "BUY_DIP") > 0, "BD strategy should produce buy-dip actions")
        assertTrue(count(drawdownMrCurve, "DRAWDOWN_MR") > 0, "DD-MR strategy should produce drawdown-MR actions")
    }

    @Test
    fun screenshotParameters_compareStockGrossAndPortfolioValueBuyTheDipDiagnostics() {
        val portfolio = PortfolioConfig(
            label = "VT:CTA 65:35",
            tickers = listOf(
                TickerWeight("VT", 0.5619),
                TickerWeight("1 KMLM 1 VT", 0.2524),
                TickerWeight("KMLM", 0.0657),
                TickerWeight("DBMF", 0.1200),
            ),
            rebalanceStrategy = RebalanceStrategy.YEARLY,
            marginStrategies = emptyList(),
            includeNoMargin = false,
        )

        fun strategyFor(
            label: String,
            source: PortfolioTriggerSource,
            dropPct: Double,
            minAdjustmentPct: Double = 0.005,
        ) =
            RebalStrategyConfig(
                label = label,
                marginRatio = 0.80,
                marginSpread = 0.015,
                portfolioRebalancePeriod = RebalancePeriodOverride.INHERIT,
                portfolioRebalanceUseComfortZone = true,
                marginRebalanceEnabled = true,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                rebalanceAllocStrategy = MarginRebalanceMode.WATERFALL.name,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
                marginRebalanceRestoreMargin = 0.80,
                cashflowImmediateInvestPct = 1.0,
                cashflowScaling = CashflowScaling.SCALED_BY_TARGET_MARGIN,
                cashflowScalingMargin = 0.80,
                deviationMode = DeviationMode.ABSOLUTE,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 1.10,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.95,
                ),
                buyOnLowMargin = null,
                useComfortZone = true,
                comfortZoneLow = 0.62,
                comfortZoneHigh = 1.10,
                buyCooldownAfterSellHighDays = 10,
                sellCooldownAfterBuyLowDays = 10,
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.WATERFALL.name,
                    portfolioSource = source,
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(dropPct)),
                    method = ExecutionMethod.Once,
                    limit = 1.00,
                    coolingOffDays = 9,
                    minAdjustmentPct = minAdjustmentPct,
                ),
                sellOnSurge = null,
            )

        val stockGross = strategyFor(
            "80-100-110",
            PortfolioTriggerSource.STRATEGY_GROSS,
            0.10,
            minAdjustmentPct = 0.0,
        )
        val portfolioValue = strategyFor("80-100-110 (2)", PortfolioTriggerSource.STRATEGY_VALUE, 0.18)
        val portfolioValueBaseline = strategyFor(
            "80-100-110 (2) baseline",
            PortfolioTriggerSource.STRATEGY_VALUE,
            0.18,
        )
        val result = RebalanceStrategyService.run(
            RebalanceStrategyRequest(
                fromDate = null,
                toDate = null,
                portfolio = portfolio,
                cashflow = null,
                strategies = listOf(stockGross, portfolioValue, portfolioValueBaseline),
                startingBalance = 10_000.0,
                includeActionDiagnostics = true,
            )
        )
        val curves = result.portfolios.flatMap { it.curves }.associateBy { it.label }
        val stockGrossCurve = requireNotNull(curves["80-100-110"])
        val portfolioValueCurve = requireNotNull(curves["80-100-110 (2)"])
        val portfolioValueBaselineCurve = requireNotNull(curves["80-100-110 (2) baseline"])

        fun avgMargin(curve: CurveResult): Double =
            requireNotNull(curve.marginPoints).map { it.value }.average()

        fun count(curve: CurveResult, type: String): Int =
            curve.actionPoints.orEmpty().count { it.type == type }

        fun actionMap(curve: CurveResult, type: String): Map<String, ActionPoint> =
            curve.actionPoints.orEmpty().filter { it.type == type }.associateBy { it.date }

        fun timing(curve: CurveResult, type: String, limit: Int = 14): String =
            curve.actionPoints.orEmpty()
                .filter { it.type == type }
                .take(limit)
                .joinToString(" | ") {
                    val d = requireNotNull(it.detail)
                    "${it.date}#${d.tradingDayIndex} since=${d.daysSincePrevious ?: "-"} " +
                        "amt=${"%.0f".format(d.amount ?: 0.0)} " +
                        "m=${"%.3f".format(d.marginBefore ?: 0.0)}->${"%.3f".format(d.marginAfter ?: 0.0)}"
                }

        fun pointMap(curve: CurveResult): Map<String, Double> = curve.points.associate { it.date to it.value }

        val stockValues = pointMap(stockGrossCurve)
        val valueValues = pointMap(portfolioValueCurve)
        val sharedDates = stockValues.keys.intersect(valueValues.keys).sorted()
        val largestGapGrowth =
            sharedDates.zipWithNext()
                .map { (prev, cur) ->
                    val prevGap = stockValues.getValue(prev) - valueValues.getValue(prev)
                    val curGap = stockValues.getValue(cur) - valueValues.getValue(cur)
                    Triple(prev, cur, curGap - prevGap)
                }
                .sortedByDescending { abs(it.third) }
                .take(12)
                .joinToString(" | ") { (prev, cur, delta) ->
                    "$prev->$cur delta=${"%.0f".format(delta)} gap=${"%.0f".format(stockValues.getValue(cur) - valueValues.getValue(cur))}"
                }

        val stockBdByDate = actionMap(stockGrossCurve, "BUY_DIP")
        val valueBdByDate = actionMap(portfolioValueCurve, "BUY_DIP")
        fun mismatches(primary: Map<String, ActionPoint>, other: Map<String, ActionPoint>, limit: Int): String =
            primary.values
                .filter { other[it.date] == null }
                .take(limit)
                .joinToString(" | ") {
                    val d = requireNotNull(it.detail)
                    "${it.date}#${d.tradingDayIndex} amt=${"%.0f".format(d.amount ?: 0.0)} m=${"%.3f".format(d.marginBefore ?: 0.0)}"
                }
        val dateIndex = sharedDates.withIndex().associate { it.value to it.index }
        fun gapAt(date: String): Double = stockValues.getValue(date) - valueValues.getValue(date)
        fun unmatchedImpacts(label: String, primary: Map<String, ActionPoint>, other: Map<String, ActionPoint>): List<String> =
            primary.values
                .filter { other[it.date] == null }
                .mapNotNull { action ->
                    val idx = dateIndex[action.date] ?: return@mapNotNull null
                    val d = requireNotNull(action.detail)
                    val qDate = sharedDates[(idx + 63).coerceAtMost(sharedDates.lastIndex)]
                    val yDate = sharedDates[(idx + 252).coerceAtMost(sharedDates.lastIndex)]
                    val qChange = gapAt(qDate) - gapAt(action.date)
                    val yChange = gapAt(yDate) - gapAt(action.date)
                    "$label ${action.date} amt=${"%.0f".format(d.amount ?: 0.0)} " +
                        "gap+63=${"%.0f".format(qChange)} gap+252=${"%.0f".format(yChange)}"
                }

        val largestUnmatchedImpacts =
            (unmatchedImpacts("STOCK_ONLY", stockBdByDate, valueBdByDate) +
                unmatchedImpacts("VALUE_ONLY", valueBdByDate, stockBdByDate))
                .sortedByDescending {
                    Regex("gap\\+252=([-0-9]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull()?.let { value ->
                        abs(value)
                    } ?: 0.0
                }
                .take(16)
                .joinToString(" | ")

        println(
            "GROSS_VALUE_COMPARE_SUMMARY STOCK_GROSS end=${"%.2f".format(stockGrossCurve.stats.endingValue)} " +
                "cagr=${"%.6f".format(stockGrossCurve.stats.cagr)} maxDD=${"%.6f".format(stockGrossCurve.stats.maxDrawdown)} " +
                "avgMargin=${"%.6f".format(avgMargin(stockGrossCurve))} SH=${count(stockGrossCurve, "SELL_HIGH")} " +
                "BD=${count(stockGrossCurve, "BUY_DIP")} MR=${count(stockGrossCurve, "MARGIN_REBALANCE")}"
        )
        println(
            "GROSS_VALUE_COMPARE_SUMMARY PORTFOLIO_VALUE end=${"%.2f".format(portfolioValueCurve.stats.endingValue)} " +
                "cagr=${"%.6f".format(portfolioValueCurve.stats.cagr)} maxDD=${"%.6f".format(portfolioValueCurve.stats.maxDrawdown)} " +
                "avgMargin=${"%.6f".format(avgMargin(portfolioValueCurve))} SH=${count(portfolioValueCurve, "SELL_HIGH")} " +
                "BD=${count(portfolioValueCurve, "BUY_DIP")} MR=${count(portfolioValueCurve, "MARGIN_REBALANCE")}"
        )
        println("GROSS_VALUE_COMPARE_TIMING STOCK_GROSS ${timing(stockGrossCurve, "BUY_DIP")}")
        println("GROSS_VALUE_COMPARE_TIMING PORTFOLIO_VALUE ${timing(portfolioValueCurve, "BUY_DIP")}")
        println("GROSS_VALUE_COMPARE_ONLY_STOCK_GROSS ${mismatches(stockBdByDate, valueBdByDate, 18)}")
        println("GROSS_VALUE_COMPARE_ONLY_PORTFOLIO_VALUE ${mismatches(valueBdByDate, stockBdByDate, 18)}")
        println("GROSS_VALUE_COMPARE_LARGEST_GAP_GROWTH $largestGapGrowth")
        println("GROSS_VALUE_COMPARE_UNMATCHED_IMPACTS $largestUnmatchedImpacts")

        assertTrue(count(stockGrossCurve, "BUY_DIP") > 0, "Stock-gross trigger should produce buy-dip actions")
        assertTrue(count(portfolioValueCurve, "BUY_DIP") > 0, "Portfolio-value trigger should produce buy-dip actions")
        portfolioValueCurve.points.zip(portfolioValueBaselineCurve.points).forEachIndexed { i, (actual, baseline) ->
            assertEquals(baseline.date, actual.date)
            assertApprox(baseline.value, actual.value, eps = 1e-9, label = "portfolio-value equity[$i]")
        }
        requireNotNull(portfolioValueCurve.marginPoints)
            .zip(requireNotNull(portfolioValueBaselineCurve.marginPoints))
            .forEachIndexed { i, (actual, baseline) ->
                assertEquals(baseline.date, actual.date)
                assertApprox(baseline.value, actual.value, eps = 1e-9, label = "portfolio-value margin[$i]")
            }
    }

    @Test
    fun vsNDaysAgoTriggerUsesRollingExtreme() {
        val checker = PriceMoveTrigger.VsNDaysAgo(nDays = 3, pct = 0.1).buildChecker(DipSurgeKey.Stock("SPY"))

        checker.advance(100.0)
        assertFalse(checker.check(Direction.BUY))
        checker.advance(90.0)
        assertFalse(checker.check(Direction.BUY))
        checker.advance(89.0)
        assertTrue(checker.check(Direction.BUY), "BD should compare against the highest price in the N-day window")

        val sellChecker = PriceMoveTrigger.VsNDaysAgo(nDays = 3, pct = 0.1).buildChecker(DipSurgeKey.Stock("SPY"))
        sellChecker.advance(100.0)
        sellChecker.advance(110.0)
        sellChecker.advance(112.0)
        assertTrue(sellChecker.check(Direction.SELL), "SS should compare against the lowest price in the N-day window")
    }

    @Test
    fun vsRunningAvgTriggerRequiresFullWindow() {
        val checker = PriceMoveTrigger.VsRunningAvg(nDays = 3, pct = 0.1).buildChecker(DipSurgeKey.Stock("SPY"))

        checker.advance(100.0)
        checker.advance(80.0)
        assertFalse(checker.check(Direction.BUY), "BD should wait for a full running-average window")
        checker.advance(80.0)
        assertFalse(checker.check(Direction.BUY), "BD should still wait for three prior values")
        checker.advance(70.0)
        assertTrue(checker.check(Direction.BUY), "BD should compare against the full prior 3-day average")
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
            ).copy(rebalanceAllocStrategy = MarginRebalanceMode.CURRENT_WEIGHT.name),
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
    fun drawdownMarginOverride_replacesScheduledMrUntilReferenceRecovers() {
        val dates = days(LocalDate.of(2024, 1, 1), 5)
        val series = mapOf(
            "SPY" to flatCurve(dates),
            "REF" to mapOf(
                dates[0] to 1.0,
                dates[1] to 0.89,
                dates[2] to 0.88,
                dates[3] to 0.96,
                dates[4] to 0.97,
            ),
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                drawdownMarginOverride = DrawdownMarginOverrideConfig(
                    enabled = true,
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.10,
                    exitDrawdownPct = 0.05,
                    targetMargin = 0.95,
                    rebalancePeriod = RebalancePeriodOverride.DAILY,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    tradeDirection = MarginRebalanceTradeDirection.BOTH,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        assertTrue(
            actions.any { it.date == dates[1].toString() && it.type == "DRAWDOWN_MR" },
            "override should run when the reference reaches the entry drawdown",
        )
        assertFalse(actions.any { it.type == "MARGIN_REBALANCE" }, "base MR should be replaced while override is active")
        assertFalse(
            actions.any { it.date == dates[3].toString() && it.type == "DRAWDOWN_MR" },
            "override should stop once drawdown recovers to the exit threshold",
        )
        assertApprox(0.95, requireNotNull(result.marginPoints)[1].value, label = "override margin target")
    }

    @Test
    fun drawdownMarginOverride_rebalanceOnEnterAnchorsWeeklyScheduleToEntryDay() {
        val dates = days(LocalDate.of(2024, 1, 1), 8)
        val series = mapOf(
            "SPY" to mapOf(
                dates[0] to 1.0,
                dates[1] to 1.0,
                dates[2] to 1.05,
                dates[3] to 1.10,
                dates[4] to 1.15,
                dates[5] to 1.20,
                dates[6] to 1.20,
                dates[7] to 1.20,
            ),
            "REF" to dates.associateWith { if (it == dates[0]) 1.0 else 0.89 },
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                useComfortZone = false,
                drawdownMarginOverride = DrawdownMarginOverrideConfig(
                    enabled = true,
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.10,
                    exitDrawdownPct = 0.05,
                    targetMargin = 0.95,
                    rebalancePeriod = RebalancePeriodOverride.WEEKLY,
                    rebalanceOnEnter = true,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    tradeDirection = MarginRebalanceTradeDirection.BOTH,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val drawdownMrDates = result.actionPoints.orEmpty()
            .filter { it.type == "DRAWDOWN_MR" }
            .map { it.date }
        assertEquals(
            listOf(dates[1].toString(), dates[6].toString()),
            drawdownMrDates,
            "weekly override MR should run on entry day and five trading days after entry",
        )
    }

    @Test
    fun drawdownMarginOverride_exitsOnlyOnIntervalAndAnchorsBaseMrResume() {
        val dates =
            listOf(LocalDate.of(2023, 12, 31), LocalDate.of(2024, 1, 1)) +
                (2..21).map { LocalDate.of(2024, 1, it) } +
                listOf(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1))
        val series = mapOf(
            "SPY" to flatCurve(dates),
            "REF" to dates.associateWith { date ->
                when (date) {
                    dates.first() -> 1.0
                    LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1) -> 0.96
                    else -> 0.89
                }
            },
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.BI_MONTHLY,
                useComfortZone = false,
                drawdownMarginOverride = DrawdownMarginOverrideConfig(
                    enabled = true,
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.10,
                    exitDrawdownPct = 0.05,
                    targetMargin = 1.0,
                    rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                    rebalanceOnEnter = true,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    buyAllocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    sellAllocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    tradeDirection = MarginRebalanceTradeDirection.BOTH,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
            includeActionDiagnostics = true,
        )

        val actions = result.actionPoints.orEmpty().map { it.date to it.type }
        assertTrue(
            actions.contains(LocalDate.of(2024, 1, 1).toString() to "DRAWDOWN_MR"),
            "DD-MR should rebalance on the entry day",
        )
        assertTrue(
            actions.contains(LocalDate.of(2024, 2, 1).toString() to "DRAWDOWN_MR_EXIT"),
            "DD-MR should exit on its monthly checkpoint after recovery",
        )
        assertFalse(
            actions.contains(LocalDate.of(2024, 2, 1).toString() to "MARGIN_REBALANCE"),
            "Base MR should not run on the same day DD-MR exits",
        )
        assertTrue(
            actions.contains(LocalDate.of(2024, 3, 1).toString() to "MARGIN_REBALANCE"),
            "Bi-monthly base MR should resume from the DD-MR exit date, producing the next checkpoint on Mar 1",
        )
    }

    @Test
    fun drawdownMarginOverride_runsBaseMrOnExitWhenBaseIntervalIsAlreadyDue() {
        val entryDate = LocalDate.of(2024, 1, 1)
        val exitDate = entryDate.plusDays(42)
        val dates = listOf(LocalDate.of(2023, 12, 31)) + (0..42).map { entryDate.plusDays(it.toLong()) }
        val series = mapOf(
            "SPY" to flatCurve(dates),
            "REF" to dates.associateWith { date ->
                when (date) {
                    dates.first() -> 1.0
                    exitDate -> 0.96
                    else -> 0.89
                }
            },
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                drawdownMarginOverride = DrawdownMarginOverrideConfig(
                    enabled = true,
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.10,
                    exitDrawdownPct = 0.05,
                    targetMargin = 1.0,
                    rebalancePeriod = RebalancePeriodOverride.BI_MONTHLY,
                    rebalanceOnEnter = true,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    buyAllocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    sellAllocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    tradeDirection = MarginRebalanceTradeDirection.BOTH,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
            includeActionDiagnostics = true,
        )

        val actions = result.actionPoints.orEmpty().map { it.date to it.type }
        assertTrue(
            actions.contains(entryDate.toString() to "DRAWDOWN_MR"),
            "DD-MR should rebalance on the entry day",
        )
        assertTrue(
            actions.contains(exitDate.toString() to "DRAWDOWN_MR_EXIT"),
            "DD-MR should exit on the bi-monthly checkpoint after recovery",
        )
        assertTrue(
            actions.contains(exitDate.toString() to "MARGIN_REBALANCE"),
            "Monthly base MR should run on the DD-MR exit day because it is already due",
        )
    }

    @Test
    fun marginRebalance_buyOnlyStillAllowsSellOnHighMarginTrigger() {
        val dates = listOf(
            LocalDate.of(2024, 1, 30),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
        )
        val falling = mapOf(dates[0] to 1.0, dates[1] to 0.5, dates[2] to 0.5)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.7,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
            ),
            null,
            mapOf("SPY" to falling),
            dates,
            emptyMap(),
        )

        assertApprox(0.5, requireNotNull(result.marginPoints)[2].value, label = "sell high restores margin")
        assertTrue(result.actionPoints?.any { it.date == "2024-02-01" && it.type == "SELL_HIGH" } == true)
    }

    @Test
    fun normalPortfolioRebalanceDoesNotBlockMarginTriggers() {
        val dates = listOf(
            LocalDate.of(2024, 1, 30),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
        )
        val falling = mapOf(dates[0] to 1.0, dates[1] to 0.5, dates[2] to 0.5)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(rebalance = RebalanceStrategy.MONTHLY),
            strategy(
                marginRatio = 0.5,
                useComfortZone = false,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.7,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.3,
                ),
            ),
            null,
            mapOf("SPY" to falling),
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty().filter { it.date == "2024-02-01" }.map { it.type }
        assertTrue("PORTFOLIO_REBALANCE" in actions)
        assertTrue("SELL_HIGH" in actions)
        assertApprox(0.3, requireNotNull(result.marginPoints)[2].value, label = "sell high can run after normal rebalance")
    }

    @Test
    fun marginRebalance_sellOnlyStillAllowsBuyOnLowMarginTrigger() {
        val dates = listOf(
            LocalDate.of(2024, 1, 30),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 2.0)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                marginRebalanceTradeDirection = MarginRebalanceTradeDirection.SELL_ONLY,
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.3,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
            ),
            null,
            mapOf("SPY" to rising),
            dates,
            emptyMap(),
        )

        assertApprox(0.5, requireNotNull(result.marginPoints)[2].value, label = "buy low restores margin")
        assertTrue(result.actionPoints?.any { it.date == "2024-02-01" && it.type == "BUY_LOW" } == true)
    }

    @Test
    fun drawdownBuyLowRunsWithoutBaseBuyLow() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 3),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 2.0)
        val reference = mapOf(dates[0] to 1.0, dates[1] to 0.8, dates[2] to 0.8)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                drawdownBuyOnLowMargin = DrawdownMarginTriggerAction(
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.1,
                    triggerMargin = 0.6,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
            ),
            null,
            mapOf("SPY" to rising, "REF" to reference),
            dates,
            emptyMap(),
        )

        assertApprox(0.5, requireNotNull(result.marginPoints)[1].value, label = "drawdown BL restores margin")
        assertTrue(result.actionPoints?.any { it.date == "2024-01-02" && it.type == "BUY_LOW" } == true)
    }

    @Test
    fun drawdownBuyLowMomentumLookbackRequiresPositiveMomentum() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 3),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 2.0)
        val lookbackDate = LocalDate.of(2023, 12, 2)

        fun runWithLookbackReference(lookbackValue: Double): CurveResult {
            val reference = mapOf(
                lookbackDate to lookbackValue,
                dates[0] to 1.0,
                dates[1] to 0.8,
                dates[2] to 0.8,
            )
            return RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5,
                    drawdownBuyOnLowMargin = DrawdownMarginTriggerAction(
                        portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                        referenceTicker = "REF",
                        momentumLookbackMonths = 1,
                        enterDrawdownPct = 0.1,
                        triggerMargin = 0.6,
                        allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                        targetMargin = 0.5,
                    ),
                ),
                null,
                mapOf("SPY" to rising, "REF" to reference),
                dates,
                emptyMap(),
            )
        }

        assertFalse(
            runWithLookbackReference(0.9).actionPoints.orEmpty().any { it.type == "BUY_LOW" },
            "DD BL should not buy when configured momentum is negative",
        )
        assertTrue(
            runWithLookbackReference(0.7).actionPoints.orEmpty().any { it.date == "2024-01-02" && it.type == "BUY_LOW" },
            "DD BL should buy when configured momentum is positive",
        )
    }

    @Test
    fun drawdownBuyLowExitUsesPeakFromEntrySoNegativeExitCanRequireNewHigh() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 3),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 4.0)
        val reference = mapOf(dates[0] to 1.0, dates[1] to 0.8, dates[2] to 1.3)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                drawdownBuyOnLowMargin = DrawdownMarginTriggerAction(
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.1,
                    exitDrawdownPct = -0.25,
                    triggerMargin = 0.6,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
            ),
            null,
            mapOf("SPY" to rising, "REF" to reference),
            dates,
            emptyMap(),
        )

        assertEquals(
            listOf("2024-01-02"),
            result.actionPoints.orEmpty().filter { it.type == "BUY_LOW" }.map { it.date },
            "DD BL should exit when the reference is 25% above the peak captured on entry",
        )
    }

    @Test
    fun activeDrawdownBuyLowOverridesBaseBuyLow() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 3),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 2.0)
        val reference = mapOf(dates[0] to 1.0, dates[1] to 0.8, dates[2] to 0.8)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.9,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.3,
                ),
                drawdownBuyOnLowMargin = DrawdownMarginTriggerAction(
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.1,
                    triggerMargin = 0.6,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
            ),
            null,
            mapOf("SPY" to rising, "REF" to reference),
            dates,
            emptyMap(),
        )

        assertApprox(0.5, requireNotNull(result.marginPoints)[1].value, label = "drawdown BL target overrides base")
    }

    @Test
    fun tieredDrawdownBuyLowUsesDeepestActiveTier() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 3),
        )
        val rising = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 2.0)
        val reference = mapOf(dates[0] to 1.0, dates[1] to 0.70, dates[2] to 0.70)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                drawdownBuyOnLowMargin = DrawdownMarginTriggerAction(
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    tiers = listOf(
                        DrawdownMarginTriggerTier(
                            enterDrawdownPct = 0.20,
                            exitDrawdownPct = 0.15,
                            triggerMargin = 0.6,
                            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                            targetMargin = 0.6,
                        ),
                        DrawdownMarginTriggerTier(
                            enterDrawdownPct = 0.25,
                            exitDrawdownPct = 0.15,
                            triggerMargin = 0.6,
                            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                            targetMargin = 0.8,
                        ),
                    ),
                ),
            ),
            null,
            mapOf("SPY" to rising, "REF" to reference),
            dates,
            emptyMap(),
        )

        assertApprox(0.8, requireNotNull(result.marginPoints)[1].value, label = "deepest drawdown BL tier wins")
        assertTrue(result.actionPoints?.any { it.date == "2024-01-02" && it.type == "BUY_LOW" } == true)
    }

    @Test
    fun activeDrawdownSellHighOverridesBaseSellHigh() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 3),
        )
        val falling = mapOf(dates[0] to 1.0, dates[1] to 0.5, dates[2] to 0.5)
        val reference = mapOf(dates[0] to 1.0, dates[1] to 0.8, dates[2] to 0.8)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.4,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                drawdownSellOnHighMargin = DrawdownMarginTriggerAction(
                    portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
                    referenceTicker = "REF",
                    enterDrawdownPct = 0.1,
                    triggerMargin = 0.4,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 1.0,
                ),
            ),
            null,
            mapOf("SPY" to falling, "REF" to reference),
            dates,
            emptyMap(),
        )

        assertApprox(1.0, requireNotNull(result.marginPoints)[1].value, label = "drawdown SH target overrides base")
        assertTrue(result.actionPoints?.any { it.date == "2024-01-02" && it.type == "SELL_HIGH" } == true)
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
                upperRebalanceMode = MarginRebalanceMode.PROPORTIONAL.name,
                lowerRebalanceMode = MarginRebalanceMode.PROPORTIONAL.name,
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
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.4,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                buyTheDip = null,
                sellOnSurge = null,
                portfolioRebalanceUseComfortZone = false,
                useComfortZone = false,
                comfortZoneLow = 0.4,
                comfortZoneHigh = 0.6,
                buyCooldownAfterSellHighDays = 0,
                sellCooldownAfterBuyLowDays = 0,
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
                upperRebalanceMode = MarginRebalanceMode.PROPORTIONAL.name,
                lowerRebalanceMode = MarginRebalanceMode.PROPORTIONAL.name,
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
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.4,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                buyTheDip = null,
                sellOnSurge = null,
                portfolioRebalanceUseComfortZone = false,
                useComfortZone = false,
                comfortZoneLow = 0.4,
                comfortZoneHigh = 0.6,
                buyCooldownAfterSellHighDays = 0,
                sellCooldownAfterBuyLowDays = 0,
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
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
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
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
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
    fun vShape_buyTheDip_basePortfolio() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = vShapeCurve(dates)
        val series = mapOf("SPY" to prices)

        // Reference: mirrors PeakDeviationChecker advance/check ordering and OnceExecutor.
        // The first date is seeded before the loop, then each day is fed before check(i).
        // OnceExecutor fires execute(eligible()) every day triggered == true (stateless).
        // eligible = max(0, equity*(limit - currentRatio)); when currentRatio >= limit, eligible=0.
        var H = 15_000.0; var CB = -5_000.0
        var runningPeak = prices[dates[0]]!!
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

        for (scope in listOf(DipSurgeScope.BASE_PORTFOLIO, DipSurgeScope.INDIVIDUAL_STOCK)) {
            val r = RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5, marginSpread = 0.0,
                    rebalancePeriod = RebalancePeriodOverride.NONE, comfortHigh = 1.5,
                    buyTheDip = DipSurgeConfig(
                        scope = scope, allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
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
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
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
    fun buyTheDipBasePortfolioSteppedUsesReferenceValueNotAccountGross() {
        val dates = days(LocalDate.of(2024, 1, 2), 4)
        val prices = dates.zip(listOf(1.0, 0.84, 0.79, 0.74)).toMap()
        val series = mapOf("SPY" to prices)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.1)),
                    method = ExecutionMethod.Stepped(portions = 3, additionalPct = 0.05),
                    limit = 1.5,
                    coolingOffDays = 0,
                    minAdjustmentPct = 0.0,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val buyDipActions = result.actionPoints.orEmpty().filter { it.type == "BUY_DIP" }
        assertEquals(3, buyDipActions.size, "Each stepped BD portion should use the base reference path, not account gross exposure")
    }

    @Test
    fun buyTheDipBasePortfolioReferenceIsDailyRebalanced() {
        val dates = days(LocalDate.of(2024, 1, 2), 3)
        val series = mapOf(
            "AAA" to dates.zip(listOf(1.0, 0.5, 0.25)).toMap(),
            "BBB" to dates.zip(listOf(1.0, 1.0, 1.0)).toMap(),
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            twoStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.4)),
                    method = ExecutionMethod.Once,
                    limit = 1.5,
                    coolingOffDays = 0,
                    minAdjustmentPct = 0.0,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val buyDipActions = result.actionPoints.orEmpty().filter { it.type == "BUY_DIP" }
        assertEquals(
            listOf("2024-01-04"),
            buyDipActions.map { it.date },
            "Base portfolio reference should rebalance daily before checking the drawdown trigger",
        )
    }

    @Test
    fun buyTheDipBasePortfolioCanUseSingleTickerReference() {
        val dates = days(LocalDate.of(2024, 1, 2), 3)
        val series = mapOf(
            "SPY" to flatCurve(dates),
            "VT" to dates.zip(listOf(1.0, 0.9, 0.5)).toMap(),
        )

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.5,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    referenceTicker = "VT",
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.4)),
                    method = ExecutionMethod.Once,
                    limit = 1.5,
                    coolingOffDays = 0,
                    minAdjustmentPct = 0.0,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val buyDipActions = result.actionPoints.orEmpty().filter { it.type == "BUY_DIP" }
        assertEquals(
            listOf("2024-01-04"),
            buyDipActions.map { it.date },
            "Reference ticker should drive the base-portfolio trigger while trades still apply to the strategy portfolio",
        )
    }

    @Test
    fun sellOnSurgePortfolioTriggerCanUseStrategyGrossSource() {
        val dates = listOf(
            LocalDate.of(2024, 1, 30),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
        )
        val series = mapOf("SPY" to flatCurve(dates))
        val cashflow = CashflowConfig(amount = 1_000.0, frequency = CashflowFrequency.MONTHLY)

        fun run(source: PortfolioTriggerSource) =
            RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5,
                    marginSpread = 0.0,
                    rebalancePeriod = RebalancePeriodOverride.NONE,
                    sellOnSurge = DipSurgeConfig(
                        scope = DipSurgeScope.BASE_PORTFOLIO,
                        allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                        portfolioSource = source,
                        triggers = listOf(PriceMoveTrigger.PeakDeviation(0.05)),
                        method = ExecutionMethod.Once,
                        limit = 0.4,
                        coolingOffDays = 0,
                        minAdjustmentPct = 0.0,
                    ),
                ),
                cashflow,
                series,
                dates,
                emptyMap(),
            )

        val strategyGrossActions = run(PortfolioTriggerSource.STRATEGY_GROSS).actionPoints.orEmpty()
        val referenceActions = run(PortfolioTriggerSource.REFERENCE_PORTFOLIO).actionPoints.orEmpty()

        assertTrue(
            strategyGrossActions.any { it.date == "2024-02-01" && it.type == "SELL_SURGE" },
            "Strategy-gross source should include the cashflow-funded gross exposure increase",
        )
        assertFalse(
            referenceActions.any { it.type == "SELL_SURGE" },
            "Independent reference should ignore strategy cashflows",
        )
    }

    @Test
    fun sellOnSurgePortfolioTriggerCanUseStrategyValueSource() {
        val dates = listOf(
            LocalDate.of(2024, 1, 30),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
        )
        val series = mapOf("SPY" to flatCurve(dates))
        val cashflow = CashflowConfig(amount = 1_000.0, frequency = CashflowFrequency.MONTHLY)

        fun run(source: PortfolioTriggerSource) =
            RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5,
                    marginSpread = 0.0,
                    rebalancePeriod = RebalancePeriodOverride.NONE,
                    sellOnSurge = DipSurgeConfig(
                        scope = DipSurgeScope.BASE_PORTFOLIO,
                        allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                        portfolioSource = source,
                        triggers = listOf(PriceMoveTrigger.PeakDeviation(0.08)),
                        method = ExecutionMethod.Once,
                        limit = 0.4,
                        coolingOffDays = 0,
                        minAdjustmentPct = 0.0,
                    ),
                ),
                cashflow,
                series,
                dates,
                emptyMap(),
            )

        val strategyValueActions = run(PortfolioTriggerSource.STRATEGY_VALUE).actionPoints.orEmpty()
        val strategyGrossActions = run(PortfolioTriggerSource.STRATEGY_GROSS).actionPoints.orEmpty()

        assertTrue(
            strategyValueActions.any { it.date == "2024-02-01" && it.type == "SELL_SURGE" },
            "Strategy-value source should use stock gross value plus cash",
        )
        assertFalse(
            strategyGrossActions.any { it.type == "SELL_SURGE" },
            "Strategy-gross source should ignore cash when this threshold is above the gross-only move",
        )
    }

    @Test
    fun buyTheDipSkipsAdjustmentBelowMinimum() {
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
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.15)),
                    method = ExecutionMethod.Once,
                    limit = 0.51,
                    minAdjustmentPct = 0.02,
                ),
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        assertNull(result.actionPoints, "BD should not trade or mark when the adjustment is below the configured minimum")
    }

    @Test
    fun buyTheDipCooldownStartsOnlyAfterActualPurchase() {
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
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.75,
                ),
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
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
        assertTrue(
            actions.any { it.date == dates[3].toString() && it.type == "BUY_DIP" },
            "BD should run on the SH day because previous zero-eligible dip triggers do not start cooldown",
        )
    }

    @Test
    fun sellHighStartsGlobalBuyCooldownForBuyLowAndBuyTheDip() {
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
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.75,
                ),
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.8,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.85,
                ),
                buyTheDip = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    triggers = listOf(PriceMoveTrigger.VsRunningAvg(nDays = 1, pct = 0.0)),
                    method = ExecutionMethod.Once,
                    limit = 0.85,
                    coolingOffDays = 10,
                ),
                buyCooldownAfterSellHighDays = 10,
            ),
            null,
            series,
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        assertTrue(actions.any { it.date == dates[3].toString() && it.type == "SELL_HIGH" })
        assertFalse(actions.any { it.type == "BUY_DIP" }, "SH should block BD during the global buy cooldown")
        assertFalse(actions.any { it.type == "BUY_LOW" }, "SH should block BL during the global buy cooldown")
    }

    @Test
    fun sellHighDefersBuyDirectionMarginRebalanceUntilGlobalCooldownExpires() {
        val dates = days(LocalDate.of(2024, 1, 30), 13)
        val prices = dates.mapIndexed { i, date ->
            date to when (i) {
                0 -> 1.0
                1 -> 0.5
                else -> 1.0
            }
        }.toMap()

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.85,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.8,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                buyCooldownAfterSellHighDays = 10,
            ),
            null,
            mapOf("SPY" to prices),
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        assertTrue(actions.any { it.date == "2024-01-31" && it.type == "SELL_HIGH" })
        assertFalse(actions.any { it.date == "2024-02-01" && it.type == "MARGIN_REBALANCE" })
        assertTrue(actions.any { it.date == "2024-02-11" && it.type == "MARGIN_REBALANCE" })
        assertApprox(0.2, requireNotNull(result.marginPoints)[2].value, label = "monthly buy rebalance waits during SH cooldown")
        assertApprox(0.85, requireNotNull(result.marginPoints)[12].value, label = "monthly buy rebalance runs at deferred date")
    }

    @Test
    fun sellHighDefersBuyDirectionDrawdownMarginRebalanceUntilGlobalCooldownExpires() {
        val dates = days(LocalDate.of(2024, 1, 30), 13)
        val prices = dates.mapIndexed { i, date ->
            date to when (i) {
                0 -> 1.0
                1 -> 0.5
                else -> 1.0
            }
        }.toMap()

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.85,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.NONE,
                useComfortZone = false,
                sellOnHighMargin = MarginTriggerAction(
                    deviationPct = 0.8,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                drawdownMarginOverride = DrawdownMarginOverrideConfig(
                    enabled = true,
                    portfolioSource = PortfolioTriggerSource.STRATEGY_GROSS,
                    enterDrawdownPct = 0.1,
                    exitDrawdownPct = 0.0,
                    targetMargin = 1.0,
                    rebalancePeriod = RebalancePeriodOverride.DAILY,
                    rebalanceOnEnter = true,
                    tradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
                ),
                buyCooldownAfterSellHighDays = 10,
            ),
            null,
            mapOf("SPY" to prices),
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        assertTrue(actions.any { it.date == "2024-01-31" && it.type == "SELL_HIGH" })
        assertFalse(actions.any { it.date == "2024-02-01" && it.type == "DRAWDOWN_MR" })
        assertTrue(actions.any { it.date == "2024-02-11" && it.type == "DRAWDOWN_MR" })
        assertApprox(0.2, requireNotNull(result.marginPoints)[2].value, label = "DD-MR buy waits during SH cooldown")
        assertApprox(1.0, requireNotNull(result.marginPoints)[12].value, label = "DD-MR buy runs at deferred date")
    }

    @Test
    fun buyLowBlocksSellDirectionMarginRebalanceAndSellOnSurgeDuringGlobalCooldown() {
        val dates = listOf(
            LocalDate.of(2024, 1, 30),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 1),
        )
        val prices = mapOf(dates[0] to 1.0, dates[1] to 2.0, dates[2] to 1.0)

        val result = RebalanceStrategyService.runStrategyResultForTest(
            singleStockPortfolio(),
            strategy(
                marginRatio = 0.15,
                marginSpread = 0.0,
                rebalancePeriod = RebalancePeriodOverride.MONTHLY,
                useComfortZone = false,
                buyOnLowMargin = MarginTriggerAction(
                    deviationPct = 0.2,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    targetMargin = 0.5,
                ),
                sellOnSurge = DipSurgeConfig(
                    scope = DipSurgeScope.BASE_PORTFOLIO,
                    allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
                    triggers = listOf(PriceMoveTrigger.PeakDeviation(0.1)),
                    method = ExecutionMethod.Once,
                    limit = 0.15,
                    coolingOffDays = 10,
                ),
                sellCooldownAfterBuyLowDays = 10,
            ),
            null,
            mapOf("SPY" to prices),
            dates,
            emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        assertTrue(actions.any { it.date == "2024-01-31" && it.type == "BUY_LOW" })
        assertFalse(actions.any { it.type == "SELL_SURGE" }, "BL should block SS during the global sell cooldown")
        assertApprox(2.0, requireNotNull(result.marginPoints)[2].value, label = "monthly sell rebalance blocked after BL")
    }

    @Test
    fun reverseV_sellOnSurge_basePortfolio() {
        val dates = days(LocalDate.of(2024, 1, 2), 21)
        val prices = reverseVCurve(dates)
        val series = mapOf("SPY" to prices)

        // Reference: mirrors PeakDeviationChecker for SELL direction (runningTrough).
        // marginRatio=0.5 needed so currentRatio > limit=0.2 → eligible > 0 when sell fires.
        // The first date is seeded before the loop, then each day is fed before check(i).
        // check(i, SELL): (cur - trough)/trough > 0.15
        // eligible = max(0, equity*(currentRatio - limit)); fires only when currentRatio > 0.2.
        var H = 15_000.0; var CB = -5_000.0
        var runningTrough = prices[dates[0]]!!
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

        for (scope in listOf(DipSurgeScope.BASE_PORTFOLIO, DipSurgeScope.INDIVIDUAL_STOCK)) {
            val r = RebalanceStrategyService.runStrategyResultForTest(
                singleStockPortfolio(),
                strategy(
                    marginRatio = 0.5, marginSpread = 0.0,
                    rebalancePeriod = RebalancePeriodOverride.NONE, comfortHigh = 1.5,
                    sellOnSurge = DipSurgeConfig(
                        scope = scope, allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
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

    @Test
    fun derivedStrategy_usesOnlyPriorBaseMarginWhenComputingTarget() {
        val dates = days(LocalDate.of(2024, 1, 2), 3)
        val prices = flatCurve(dates)
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                referenceLower = 0.0,
                referenceUpper = 1.0,
                targetLower = 0.0,
                targetUpper = 1.0,
                sigmoidSteepness = 100.0,
            ),
            absoluteDeviationPct = 0.10,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(0.5, 0.5, 1.0),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        assertFalse(
            result.actionPoints.orEmpty().any { it.type == "BUY_LOW" },
            "derived BL must not see the base margin value recorded at the same day's close",
        )
        requireNotNull(result.marginPoints)
            .forEach { assertApprox(0.5, it.value, label = it.date) }
    }

    @Test
    fun derivedStrategy_adaptiveLowSigmoidUsesCurrentBaseMarginAsLowerAnchor() {
        val dates = days(LocalDate.of(2024, 1, 2), 2)
        val prices = flatCurve(dates)
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.ADAPTIVE_LOW_SIGMOID,
                referenceLower = 0.50,
                referenceUpper = 1.00,
                targetLower = 0.40,
                targetUpper = 1.00,
                sigmoidSteepness = 100.0,
            ),
            absoluteDeviationPct = 0.10,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(0.30, 0.30),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        assertApprox(0.30, requireNotNull(result.marginPoints).first().value, eps = 1e-6)
    }

    @Test
    fun derivedStrategy_marginCoverageReferenceInvertsStepDirection() {
        val dates = days(LocalDate.of(2024, 1, 2), 2)
        val prices = flatCurve(dates)
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            marginReferenceMetric = DerivedMarginReferenceMetric.MARGIN_COVERAGE,
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.STEP,
                stepBaseTarget = 0.80,
                steps = listOf(DerivedTargetStepConfig(referenceMargin = 2.00, targetMargin = 0.20)),
            ),
            absoluteDeviationPct = 0.01,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.8, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(0.50, 0.50),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        assertApprox(0.20, requireNotNull(result.marginPoints).first().value, eps = 1e-6)
    }

    @Test
    fun derivedStrategy_marginCoverageHysteresisStairsMatchesEquivalentMarginConfig() {
        val dates = days(LocalDate.of(2024, 1, 2), 4)
        val prices = flatCurve(dates)
        val baseMargins = listOf(0.60, 0.40, 0.40, 1.10)
        val marginDerived = DerivedSubStrategyConfig(
            label = "margin-derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.HYSTERESIS_STAIRS,
                targetUpper = 1.00,
                stepBaseTarget = 1.00,
                steps = listOf(DerivedTargetStepConfig(referenceMargin = 0.50, targetMargin = 0.50)),
            ),
            absoluteDeviationPct = 0.05,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )
        val coverageDerived = marginDerived.copy(
            label = "coverage-derived",
            marginReferenceMetric = DerivedMarginReferenceMetric.MARGIN_COVERAGE,
            scale = marginDerived.scale.copy(
                stepBaseTarget = 1.00,
                steps = listOf(DerivedTargetStepConfig(referenceMargin = 2.00, targetMargin = 0.50)),
            ),
        )

        fun run(derived: DerivedSubStrategyConfig) =
            RebalanceStrategyService.runDerivedStrategyResultForTest(
                singleStockPortfolio(),
                strategy(marginRatio = 0.8, marginSpread = 0.0),
                derived,
                baseMarginSeries = baseMargins,
                cashflow = null,
                seriesMap = mapOf("SPY" to prices),
                dates = dates,
                effrx = emptyMap(),
            )

        val marginResult = run(marginDerived)
        val coverageResult = run(coverageDerived)
        requireNotNull(marginResult.marginPoints).zip(requireNotNull(coverageResult.marginPoints))
            .forEachIndexed { i, (marginPoint, coveragePoint) ->
                assertApprox(marginPoint.value, coveragePoint.value, eps = 1e-9, label = "margin[$i]")
            }
        assertEquals(marginResult.actionPoints?.map { it.type }, coverageResult.actionPoints?.map { it.type })
    }

    @Test
    fun derivedStrategy_canUseStandaloneTickerMarginFromSameBaseStrategy() {
        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-derived-standalone-margin-test")
        AppDirs.dataDir = tempDataDir
        try {
            val dates = days(LocalDate.of(2024, 1, 2), 3)
            val tickerDir = tempDataDir.resolve(".ticker").toFile().also { it.mkdirs() }
            val today = LocalDate.now()
            BacktestService.writeSimCsv(
                tickerDir.resolve("DRVMA-$today.csv"),
                dates.zip(listOf(100.0, 50.0, 50.0)).toMap() + (today to 50.0),
            )
            BacktestService.writeSimCsv(
                tickerDir.resolve("DRVMB-$today.csv"),
                dates.zip(listOf(100.0, 150.0, 150.0)).toMap() + (today to 150.0),
            )

            val derived = DerivedSubStrategyConfig(
                label = "standalone",
                marginReferenceSource = DerivedMarginReferenceSource.STANDALONE_TICKER,
                marginReferenceTicker = "DRVMA",
                scale = DerivedTargetScaleConfig(
                    function = DerivedTargetScaleFunction.STEP,
                    stepBaseTarget = 0.50,
                    steps = listOf(DerivedTargetStepConfig(referenceMargin = 1.00, targetMargin = 0.20)),
                ),
                absoluteDeviationPct = 0.0,
                buyDeviationPct = 0.0,
                sellDeviationPct = 0.0,
                timeoutDays = 0,
                maxMargin = 2.0,
                allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
            )
            val portfolio = PortfolioConfig(
                label = "test",
                tickers = listOf(TickerWeight("DRVMA", 0.5), TickerWeight("DRVMB", 0.5)),
                rebalanceStrategy = RebalanceStrategy.NONE,
                marginStrategies = emptyList(),
            )
            val strategy = strategy(marginRatio = 0.5, marginSpread = 0.0)
                .copy(derivedSubStrategies = listOf(derived))

            val result = RebalanceStrategyService.run(
                RebalanceStrategyRequest(
                    fromDate = dates.first().toString(),
                    toDate = dates.last().toString(),
                    portfolio = portfolio,
                    cashflow = null,
                    strategies = listOf(strategy),
                    startingBalance = 10_000.0,
                )
            )

            val curves = result.portfolios.last().curves
            val baseMargins = requireNotNull(curves[0].marginPoints).map { it.value }
            val derivedMargins = requireNotNull(curves[1].marginPoints).map { it.value }
            assertApprox(0.5, baseMargins[2], eps = 1e-3, label = "parent base margin stays balanced")
            assertApprox(0.2, derivedMargins[2], eps = 1e-6, label = "variant follows standalone ticker margin")
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun derivedStrategy_hysteresisStepSkipsAdjustmentBetweenRefHighAndRefLowAndResetsAboveThreshold() {
        val dates = days(LocalDate.of(2024, 1, 2), 9)
        val prices = dates.mapIndexed { i, date ->
            date to when (i) {
                0, 1, 2 -> 1.0
                3 -> 4.0 / 3.0
                else -> 0.95
            }
        }.toMap()
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.HYSTERESIS_STEP,
                referenceLower = 0.60,
                referenceUpper = 1.00,
                targetLower = 0.30,
                targetUpper = 0.80,
                stepBaseTarget = 1.10,
            ),
            buyDeviationPct = 0.05,
            sellDeviationPct = 0.05,
            timeoutDays = 0,
            maxMargin = 1.50,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(1.20, 1.20, 1.20, 0.90, 0.90, 0.90, 0.50, 0.80, 1.20),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        val actions = result.actionPoints.orEmpty()
        val stage2Dates = dates.subList(3, 6).map { it.toString() }.toSet()
        assertFalse(
            actions.any { it.date in stage2Dates && (it.type == "BUY_LOW" || it.type == "SELL_HIGH") },
            "stage 2 has no target and should avoid BL/SH",
        )
        val margins = requireNotNull(result.marginPoints).map { it.value }
        assertApprox(0.80, margins[1], label = "default target high")
        assertApprox(0.50, margins[3], label = "below ref high leaves post-price margin untouched")
        assertTrue(margins[4] > 0.80, "stage 2 does not sell down after drifting above target high")
        assertTrue(margins[5] > 0.80, "stage 2 remains paused instead of returning to target high")
        assertApprox(0.30, margins[6], label = "below ref low uses target low")
        assertApprox(0.30, margins[7], label = "stage 3 does not roll back to stage 2 between ref low and ref high")
        assertApprox(0.80, margins[8], label = "above reset threshold returns to target high")
    }

    @Test
    fun derivedStrategy_hysteresisStairsFiresEachDescendingStepOnceAndResetsAboveThreshold() {
        val dates = days(LocalDate.of(2024, 1, 2), 8)
        val prices = flatCurve(dates)
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.HYSTERESIS_STAIRS,
                targetUpper = 0.80,
                stepBaseTarget = 0.95,
                steps = listOf(
                    DerivedTargetStepConfig(referenceMargin = 0.70, targetMargin = 0.50),
                    DerivedTargetStepConfig(referenceMargin = 0.60, targetMargin = 0.20),
                ),
            ),
            buyDeviationPct = 0.0,
            sellDeviationPct = 0.0,
            timeoutDays = 0,
            maxMargin = 1.50,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(1.20, 0.75, 0.65, 0.62, 0.55, 0.58, 0.96, 0.55),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        val margins = requireNotNull(result.marginPoints).map { it.value }
        assertApprox(0.80, margins[1], label = "before first stair keeps reset target")
        assertApprox(0.50, margins[2], label = "crossing first stair targets first step")
        assertApprox(0.50, margins[3], label = "staying above second stair pauses after first step")
        assertApprox(0.20, margins[4], label = "crossing second stair targets second step")
        assertApprox(0.20, margins[5], label = "remaining below crossed stair stays paused")
        assertApprox(0.80, margins[6], label = "reset threshold restores high target")
        assertApprox(0.20, margins[7], label = "after reset, crossing both stairs uses deepest target")
    }

    @Test
    fun derivedStrategy_hysteresisStairsUsesExactTargetWhenPortfolioRebalanceRunsOnCrossingDay() {
        val dates = days(LocalDate.of(2024, 1, 2), 3)
        val prices = flatCurve(dates)
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.HYSTERESIS_STAIRS,
                targetUpper = 1.00,
                stepBaseTarget = 0.95,
                steps = listOf(DerivedTargetStepConfig(referenceMargin = 0.60, targetMargin = 0.60)),
            ),
            absoluteDeviationPct = 0.05,
            buyDeviationPct = 0.05,
            sellDeviationPct = 0.05,
            timeoutDays = 20,
            maxMargin = 1.10,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(rebalance = RebalanceStrategy.DAILY),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(1.20, 0.55, 0.55),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        val margins = requireNotNull(result.marginPoints).map { it.value }
        assertApprox(0.60, margins[1], label = "stair target should not stop at target plus deviation")
        assertApprox(0.60, margins[2], label = "after firing once, no-target stage leaves margin unchanged")
    }

    @Test
    fun derivedStrategy_hysteresisStairsStageOneUsesNormalDeviationBand() {
        val dates = days(LocalDate.of(2024, 1, 2), 3)
        val prices = dates.mapIndexed { i, date -> date to if (i == 0) 1.0 else 0.9 }.toMap()
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.HYSTERESIS_STAIRS,
                targetUpper = 1.00,
                stepBaseTarget = 0.95,
                steps = listOf(DerivedTargetStepConfig(referenceMargin = 0.60, targetMargin = 0.60)),
            ),
            absoluteDeviationPct = 0.05,
            buyDeviationPct = 0.05,
            sellDeviationPct = 0.05,
            timeoutDays = 20,
            maxMargin = 1.10,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(rebalance = RebalanceStrategy.DAILY),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(1.20, 0.75, 0.75),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        val margins = requireNotNull(result.marginPoints).map { it.value }
        assertApprox(1.05, margins[1], label = "stage 1 should use target plus deviation, not exact reset target")
        assertApprox(1.05, margins[2], label = "stage 1 should not force an exact reset every day")
    }

    @Test
    fun derivedStrategy_hysteresisStairsRefBuyLowResetFollowsReferenceUntilNextDrop() {
        val dates = days(LocalDate.of(2024, 1, 2), 8)
        val prices = flatCurve(dates)
        val derived = DerivedSubStrategyConfig(
            label = "derived",
            scale = DerivedTargetScaleConfig(
                function = DerivedTargetScaleFunction.HYSTERESIS_STAIRS_REF_BL_RESET,
                steps = listOf(
                    DerivedTargetStepConfig(referenceMargin = 0.70, targetMargin = 0.50),
                    DerivedTargetStepConfig(referenceMargin = 0.60, targetMargin = 0.20),
                ),
            ),
            absoluteDeviationPct = 0.0,
            buyDeviationPct = 0.0,
            sellDeviationPct = 0.0,
            timeoutDays = 0,
            maxMargin = 1.50,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
        )

        val result = RebalanceStrategyService.runDerivedStrategyResultForTest(
            singleStockPortfolio(),
            strategy(marginRatio = 0.5, marginSpread = 0.0),
            derived,
            baseMarginSeries = listOf(1.00, 0.75, 0.65, 0.55, 0.58, 0.80, 0.82, 0.55),
            baseBuyLowEventSeries = listOf(false, false, false, false, false, true, false, false),
            cashflow = null,
            seriesMap = mapOf("SPY" to prices),
            dates = dates,
            effrx = emptyMap(),
        )

        val margins = requireNotNull(result.marginPoints).map { it.value }
        assertApprox(0.75, margins[1], label = "before first stair follows reference margin")
        assertApprox(0.50, margins[2], label = "first downward stair still fires")
        assertApprox(0.20, margins[3], label = "second downward stair still fires")
        assertApprox(0.20, margins[4], label = "after final stair pauses")
        assertApprox(0.80, margins[5], label = "reference BL resets to current reference margin")
        assertApprox(0.82, margins[6], label = "after reset follows reference margin")
        assertApprox(0.20, margins[7], label = "after reset can drop through stairs again")
    }
}
