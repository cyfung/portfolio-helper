package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonteCarloServiceTest {
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
}
