package com.portfoliohelper.service

import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reusable diagnostic for comparing a momentum-gated derived strategy against the matching
 * non-momentum variant.
 *
 * This is intentionally print-oriented rather than assertion-oriented: run it when you want to
 * reproduce an exported setup and inspect how much the momentum delay helped or hurt. To reuse it
 * with another export, update [exportedFullMappedPortfolio] and [exportedStrategies].
 */
class RebalanceMomentumComparisonDiagnosticTest {
    private data class StrategyPair(val label: String, val nonMomentum: CurveResult, val momentum: CurveResult)
    private data class DelayWindow(val startIndex: Int, val endIndex: Int)

    @Test
    fun `compare exported momentum variants against non momentum by percentage impact`() {
        val result = RebalanceStrategyService.run(
            RebalanceStrategyRequest(
                fromDate = null,
                toDate = null,
                portfolio = exportedFullMappedPortfolio(),
                cashflow = null,
                strategies = exportedStrategies(),
                startingBalance = 10_000.0,
                includeActionDiagnostics = true,
            )
        )

        assertEquals(3, result.portfolios.size, "Expected base portfolio plus the two exported strategies")

        val pairs = result.portfolios.drop(1).map { strategyResult ->
            val nonMomentum = strategyResult.curves.single { it.label.endsWith("/ keep 0") }
            val momentum = strategyResult.curves.single { it.label.endsWith("/ keep 0 mom") }
            StrategyPair(nonMomentum.label.substringBefore(" / keep 0"), nonMomentum, momentum)
        }

        println()
        println("Momentum delay percentage impact")
        println("Formula: relative = momentumValue / nonMomentumValue - 1")
        println("Window impact: change in value spread divided by non-momentum value before delay")
        if (result.warnings.isNotEmpty()) {
            println("Warnings:")
            result.warnings.forEach { println("  $it") }
        }
        println()

        pairs.forEach(::printPairComparison)
    }

    private fun printPairComparison(pair: StrategyPair) {
        val finalPct = relativeDeltaPct(pair.momentum.points.last().value, pair.nonMomentum.points.last().value)
        val windows = delayWindows(pair.nonMomentum, pair.momentum)

        println("strategy=${pair.label}")
        println("finalRelativeImpact=${signedPct(finalPct)}")

        windows.forEach { window ->
            val start = pair.nonMomentum.points[window.startIndex]
            val end = pair.nonMomentum.points[window.endIndex]
            val anchorIndex = (window.startIndex - 1).coerceAtLeast(0)
            val anchorMomentumValue = pair.momentum.points[anchorIndex].value
            val anchorNonMomentumValue = pair.nonMomentum.points[anchorIndex].value
            val startSpread = anchorMomentumValue - anchorNonMomentumValue
            val endSpread = pair.momentum.points[window.endIndex].value - end.value
            val endPct = relativeDeltaPct(pair.momentum.points[window.endIndex].value, end.value)
            val impactPct = (endSpread - startSpread) / anchorNonMomentumValue * 100.0
            val tradingDays = window.endIndex - window.startIndex + 1
            val calendarDays = ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(start.date),
                java.time.LocalDate.parse(end.date),
            )

            println(
                "  delay ${start.date}..${end.date} " +
                    "tradingDays=$tradingDays calendarDays=$calendarDays " +
                    "impact=${signedPct(impactPct)} " +
                    "relativeEnd=${signedPct(endPct)}"
            )
        }

        println()
    }

    /**
     * A delay window is any contiguous period where the momentum curve holds a different margin
     * than the matching non-momentum curve. That isolates the dates where momentum changed timing.
     */
    private fun delayWindows(nonMomentum: CurveResult, momentum: CurveResult): List<DelayWindow> {
        val nonMomentumMargins = requireNotNull(nonMomentum.marginPoints) {
            "Non-momentum curve has no margin points: ${nonMomentum.label}"
        }
        val momentumMargins = requireNotNull(momentum.marginPoints) {
            "Momentum curve has no margin points: ${momentum.label}"
        }
        assertEquals(nonMomentumMargins.map { it.date }, momentumMargins.map { it.date })

        val windows = mutableListOf<DelayWindow>()
        var startIndex: Int? = null

        nonMomentumMargins.indices.forEach { index ->
            val differs = abs(nonMomentumMargins[index].value - momentumMargins[index].value) > 1e-9
            if (differs && startIndex == null) {
                startIndex = index
            } else if (!differs && startIndex != null) {
                windows += DelayWindow(startIndex!!, index - 1)
                startIndex = null
            }
        }

        if (startIndex != null) windows += DelayWindow(startIndex!!, nonMomentumMargins.lastIndex)
        return windows
    }

    private fun relativeDeltaPct(momentumValue: Double, nonMomentumValue: Double): Double =
        (momentumValue / nonMomentumValue - 1.0) * 100.0

    private fun signedPct(value: Double): String = "%+.4f%%".format(value)

    /**
     * Exported portfolio after applying the "Full" ticker mapping from the 9093 instance.
     * Weights are left in percentage-space because [PortfolioConfig.mergeWeights] normalizes them.
     */
    private fun exportedFullMappedPortfolio() = PortfolioConfig(
        label = "Save Tax 1.1",
        tickers = listOf(
            TickerWeight("AVDV | DFISX | VT", 9.22671353251318),
            TickerWeight("AVGS.L | 0.7 DFSVX 0.3 DFISX | 0.7 VT 0.3 DFISX | 0.7 VT 0.3 VT", 8.831282952548332),
            TickerWeight("AVUV | DFSVX | VT", 8.304042179261861),
            TickerWeight("CTA | KMLM", 4.393673110720562),
            TickerWeight("DBMF.PA | DBMF", 21.968365553602812),
            TickerWeight("FMTM | SPMO | XMMO | VIMSX | VT", 5.0966608084358525),
            TickerWeight("IDMO | XMMO | VIMSX | VT", 4.797891036906854),
            TickerWeight("IMOM | XMMO | VIMSX | VT", 2.583479789103691),
            TickerWeight("KMLM", 4.393673110720562),
            TickerWeight("SPMO | XMMO | VIMSX | VT", 1.5289982425307558),
            TickerWeight("SSO | 2 SPY E=0.4", 9.84182776801406),
            TickerWeight("VWRA.L | VT E=0.2", 3.51493848857645),
            TickerWeight("VXUS E=0.15", 11.950790861159929),
            TickerWeight("XMMO | VIMSX | VT", 3.5676625659050965),
        ),
        rebalanceStrategy = RebalanceStrategy.YEARLY,
        marginStrategies = listOf(
            MarginConfig(
                marginRatio = 0.50,
                marginSpread = 0.01,
                marginDeviationUpper = 0.07,
                marginDeviationLower = 0.07,
                upperRebalanceMode = MarginRebalanceMode.FULL_REBALANCE.name,
                lowerRebalanceMode = MarginRebalanceMode.FULL_REBALANCE.name,
            )
        ),
        includeNoMargin = false,
    )

    private fun exportedStrategies() = listOf(
        exportedStrategy(
            label = "55-60 DD25 VT based - 30/27",
            steps = listOf(
                DerivedTargetStepConfig(referenceMargin = 0.30, targetMargin = 0.30),
                DerivedTargetStepConfig(referenceMargin = 0.27, targetMargin = 0.0),
            ),
        ),
        exportedStrategy(
            label = "55-60 DD25 VT based - 29/27/25",
            steps = listOf(
                DerivedTargetStepConfig(referenceMargin = 0.29, targetMargin = 0.42),
                DerivedTargetStepConfig(referenceMargin = 0.27, targetMargin = 0.20),
                DerivedTargetStepConfig(referenceMargin = 0.25, targetMargin = 0.0),
            ),
        ),
    )

    private fun exportedStrategy(label: String, steps: List<DerivedTargetStepConfig>) = RebalStrategyConfig(
        label = label,
        marginRatio = 0.15,
        marginSpread = 0.01,
        portfolioRebalancePeriod = RebalancePeriodOverride.INHERIT,
        portfolioRebalanceUseComfortZone = true,
        marginRebalanceEnabled = false,
        rebalancePeriod = RebalancePeriodOverride.MONTHLY,
        rebalanceAllocStrategy = MarginRebalanceMode.WATERFALL.name,
        marginRebalanceTradeDirection = MarginRebalanceTradeDirection.BUY_ONLY,
        marginRebalanceRestoreMargin = 0.85,
        cashflowImmediateInvestPct = 1.0,
        cashflowScaling = CashflowScaling.SCALED_BY_TARGET_MARGIN,
        cashflowScalingMargin = 0.15,
        deviationMode = DeviationMode.ABSOLUTE,
        sellOnHighMargin = MarginTriggerAction(
            deviationPct = 0.60,
            allocStrategy = MarginRebalanceMode.PROPORTIONAL.name,
            targetMargin = 0.55,
        ),
        buyOnLowMargin = null,
        drawdownBuyOnLowMargin = DrawdownMarginTriggerAction(
            portfolioSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
            referenceTicker = "VT",
            enterDrawdownPct = 0.25,
            exitDrawdownPct = 0.25,
            triggerMargin = 0.53,
            allocStrategy = MarginRebalanceMode.FULL_REBALANCE.name,
            targetMargin = 0.55,
        ),
        buyTheDip = null,
        sellOnSurge = null,
        useComfortZone = true,
        comfortZoneLow = 0.01,
        comfortZoneHigh = 0.55,
        buyCooldownAfterSellHighDays = 10,
        sellCooldownAfterBuyLowDays = 10,
        derivedSubStrategies = listOf(
            exportedDerivedSubStrategy("keep 0", DerivedTargetScaleFunction.HYSTERESIS_STAIRS, steps),
            exportedDerivedSubStrategy("keep 0 mom", DerivedTargetScaleFunction.HYSTERESIS_STAIRS_MOMENTUM, steps),
        ),
    )

    private fun exportedDerivedSubStrategy(
        label: String,
        function: DerivedTargetScaleFunction,
        steps: List<DerivedTargetStepConfig>,
    ) = DerivedSubStrategyConfig(
        label = label,
        marginReferenceSource = DerivedMarginReferenceSource.STANDALONE_TICKER,
        marginReferenceTicker = "VT",
        marginReferenceMetric = DerivedMarginReferenceMetric.MARGIN,
        scale = DerivedTargetScaleConfig(
            function = function,
            stepBaseTarget = 0.53,
            targetUpper = 0.55,
            momentumLookbackMonths = 3,
            steps = steps,
        ),
        absoluteDeviationPct = 0.05,
        buyDeviationPct = 0.05,
        sellDeviationPct = 0.05,
        timeoutDays = 10,
        maxMargin = 0.60,
        allocStrategy = MarginRebalanceMode.FULL_REBALANCE.name,
        buyAllocStrategy = MarginRebalanceMode.FULL_REBALANCE.name,
        sellAllocStrategy = MarginRebalanceMode.FULL_REBALANCE.name,
    )
}
