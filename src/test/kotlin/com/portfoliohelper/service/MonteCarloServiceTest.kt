package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MonteCarloServiceTest {
    private fun tradingDays(start: LocalDate, end: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var date = start
        while (!date.isAfter(end)) {
            if (date.dayOfWeek.value <= 5) dates += date
            date = date.plusDays(1)
        }
        return dates
    }

    private fun risingSeries(dates: List<LocalDate>): Map<LocalDate, Double> =
        dates.mapIndexed { index, date -> date to (100.0 + index) }.toMap()

    private fun writeFullTicker(tempDataDir: java.nio.file.Path, ticker: String, series: Map<LocalDate, Double>) {
        val tickerDir = tempDataDir.resolve(".ticker-full").toFile().also { it.mkdirs() }
        val lastDate = series.keys.maxOrNull() ?: error("Series must not be empty")
        BacktestService.writeSimCsv(tickerDir.resolve("$ticker-$lastDate.csv"), series)
    }

    private fun monteCarloTestStrategy(derived: DerivedSubStrategyConfig): RebalStrategyConfig =
        RebalStrategyConfig(
            label = "strategy",
            marginRatio = 0.5,
            marginSpread = 0.0,
            rebalancePeriod = RebalancePeriodOverride.NONE,
            cashflowImmediateInvestPct = 1.0,
            cashflowScaling = CashflowScaling.NO_SCALING,
            deviationMode = DeviationMode.ABSOLUTE,
            sellOnHighMargin = null,
            buyOnLowMargin = null,
            buyTheDip = null,
            sellOnSurge = null,
            derivedSubStrategies = listOf(derived),
        )

    @Test
    fun monteCarloWithFixedSeedIsDeterministic() = runBlocking {
        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-mc-test")
        AppDirs.dataDir = tempDataDir
        try {
            val request = MonteCarloRequest(
                fromDate = "2020-01-01",
                toDate = "2021-12-31",
                minChunkYears = 0.1,
                maxChunkYears = 0.25,
                simulatedYears = 1,
                numSimulations = 8,
                portfolios = listOf(
                    PortfolioConfig(
                        label = "SPY",
                        tickers = listOf(TickerWeight("SPY", 1.0)),
                        rebalanceStrategy = RebalanceStrategy.MONTHLY,
                        marginStrategies = listOf(
                            MarginConfig(
                                marginRatio = 0.5,
                                marginSpread = 0.0,
                                marginDeviationUpper = 0.05,
                                marginDeviationLower = 0.05,
                            )
                        ),
                        includeNoMargin = true,
                    )
                ),
                seed = 1234L,
            )

            val first = MonteCarloService.runMonteCarlo(request)
            val second = MonteCarloService.runMonteCarlo(request)

            assertEquals(first.seed, second.seed)
            assertEquals(first.portfolios, second.portfolios)
            val progress = MonteCarloService.getProgress()
            assertEquals("complete", progress.phase)
            assertEquals(8, progress.details.first { it.label == "Simulations" }.value.toInt())
            assertTrue(progress.done)
            val runState = MonteCarloService.getRunState()
            assertEquals(second, runState.result)
            assertEquals(null, runState.error)
            assertTrue(first.portfolios.single().curves.all { it.percentilePaths.all { path -> path.points.size == 253 } })
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun monteCarloBootstrapPoolIncludesStandaloneDerivedReferenceTicker() = runBlocking {
        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-mc-derived-reference-test")
        AppDirs.dataDir = tempDataDir
        try {
            val toDate = LocalDate.of(2020, 12, 31)
            writeFullTicker(
                tempDataDir,
                "AAA",
                risingSeries(tradingDays(LocalDate.of(2020, 1, 1), toDate)),
            )
            writeFullTicker(
                tempDataDir,
                "DRVREF",
                risingSeries(tradingDays(LocalDate.of(2020, 11, 23), toDate)),
            )

            val derived = DerivedSubStrategyConfig(
                label = "standalone ref",
                marginReferenceSource = DerivedMarginReferenceSource.STANDALONE_TICKER,
                marginReferenceTicker = "DRVREF",
                scale = DerivedTargetScaleConfig(
                    function = DerivedTargetScaleFunction.STEP,
                    stepBaseTarget = 0.50,
                    steps = listOf(DerivedTargetStepConfig(referenceMargin = 0.60, targetMargin = 0.40)),
                ),
            )
            val request = MonteCarloRequest(
                fromDate = "2020-01-01",
                toDate = toDate.toString(),
                minChunkYears = 0.1,
                maxChunkYears = 0.1,
                simulatedYears = 1,
                numSimulations = 1,
                portfolios = listOf(
                    PortfolioConfig(
                        label = "AAA",
                        tickers = listOf(TickerWeight("AAA", 1.0)),
                        rebalanceStrategy = RebalanceStrategy.NONE,
                        marginStrategies = emptyList(),
                        rebalanceStrategies = listOf(monteCarloTestStrategy(derived)),
                    )
                ),
                seed = 1234L,
            )

            val error = assertFailsWith<IllegalStateException> {
                MonteCarloService.runMonteCarlo(request)
            }
            assertTrue(
                error.message?.contains("Historical pool too small") == true,
                "Expected standalone derived reference ticker to constrain the bootstrap pool, got: ${error.message}"
            )
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }
}
