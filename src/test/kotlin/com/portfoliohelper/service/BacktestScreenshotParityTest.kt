package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import java.nio.file.Files
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BacktestScreenshotParityTest {
    private data class ScreenshotHolding(
        val ticker: String,
        val weightPct: Double,
        val last: Double,
        val mark: Double,
    )

    @Test
    fun screenshotPortfolioMove_matchesBacktestMarginCurveClosely() {
        val priorDate = LocalDate.of(2026, 6, 15)
        val markDate = LocalDate.of(2026, 6, 16)
        val today = LocalDate.now()

        val holdings = listOf(
            ScreenshotHolding("AVDV", 8.1, 107.80, 108.02),
            ScreenshotHolding("AVGS.L", 8.4, 28.65, 28.45),
            ScreenshotHolding("AVUV", 8.4, 123.18, 122.91),
            ScreenshotHolding("CTA", 13.1, 27.86, 27.36),
            ScreenshotHolding("CTAP", 22.3, 28.77, 28.29),
            ScreenshotHolding("DBMF.PA", 7.0, 130.20, 129.54),
            ScreenshotHolding("FMTM", 5.2, 42.26, 41.83),
            ScreenshotHolding("IMOM", 6.5, 44.52, 44.38),
            ScreenshotHolding("QMOM", 1.9, 79.85, 79.13),
            ScreenshotHolding("VXUS", 16.0, 86.98, 86.86),
            ScreenshotHolding("XMMO", 3.1, 171.30, 170.52),
        )

        val screenshotEndingValue = 7_128_140.17
        val screenshotDailyChange = -98_148.25
        val screenshotBorrowed = 4_438_475.16
        val screenshotStartingValue = screenshotEndingValue - screenshotDailyChange
        val screenshotStartingMarginRatio = screenshotBorrowed / screenshotStartingValue

        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-backtest-screenshot-")
        AppDirs.dataDir = tempDataDir
        try {
            val tickerDir = tempDataDir.resolve(".ticker").toFile().also { it.mkdirs() }
            BacktestService.writeSimCsv(
                tickerDir.resolve("EFFRX-$today.csv"),
                mapOf(priorDate to 10_000.0, markDate to 10_000.0, today to 10_000.0),
            )
            for (holding in holdings) {
                BacktestService.writeSimCsv(
                    tickerDir.resolve("${holding.ticker.uppercase()}-$today.csv"),
                    mapOf(
                        priorDate to holding.last,
                        markDate to holding.mark,
                        today to holding.mark,
                    ),
                )
            }

            val result = BacktestService.runMulti(
                MultiBacktestRequest(
                    fromDate = priorDate.toString(),
                    toDate = markDate.toString(),
                    startingBalance = screenshotStartingValue,
                    zeroMarginInterest = true,
                    portfolios = listOf(
                        PortfolioConfig(
                            label = "Screenshot 2026-06-16",
                            tickers = holdings.map { TickerWeight(it.ticker, it.weightPct) },
                            rebalanceStrategy = RebalanceStrategy.NONE,
                            marginStrategies = listOf(
                                MarginConfig(
                                    marginRatio = screenshotStartingMarginRatio,
                                    marginSpread = 0.0,
                                    marginDeviationUpper = 0.05,
                                    marginDeviationLower = 0.05,
                                    upperRebalanceMode = MarginRebalanceMode.PROPORTIONAL.name,
                                    lowerRebalanceMode = MarginRebalanceMode.PROPORTIONAL.name,
                                )
                            ),
                            includeNoMargin = false,
                        )
                    ),
                )
            )

            val curve = result.portfolios.single().curves.single()
            val endingPoint = curve.points.last()
            val endingMargin = assertNotNull(curve.marginPoints).last()
            val expectedEndingMarginRatio = screenshotBorrowed / screenshotEndingValue

            assertEquals(markDate.toString(), endingPoint.date)
            assertClose(screenshotEndingValue, endingPoint.value, tolerance = 2_000.0, label = "ending equity")
            assertClose(expectedEndingMarginRatio, endingMargin.value, tolerance = 0.0003, label = "ending margin ratio")
            assertTrue(
                curve.stats.marginUpperTriggers == 0 && curve.stats.marginLowerTriggers == 0,
                "A -1.36% equity day should stay inside the 5pp margin deviation band",
            )
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, label: String) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$label expected $expected +/- $tolerance but was $actual",
        )
    }
}
