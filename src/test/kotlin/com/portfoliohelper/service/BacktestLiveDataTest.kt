package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class BacktestLiveDataTest {
    @Test
    fun liveKmlmResourceSeriesExtendsToLatestWithoutLosingSyntheticHistory() {
        if (System.getProperty("liveYahoo") != "true" && System.getenv("LIVE_YAHOO") != "true") return

        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-live-kmlm-resource-")
        try {
            AppDirs.dataDir = tempDataDir

            val series = BacktestService.loadNormalizedSeries("KMLM", LocalDate.of(1990, 1, 1))
            val dates = series.keys.sorted()
            val tickerFiles = tempDataDir.resolve(".ticker").toFile()
                .listFiles()
                ?.map { it.name }
                ?.sorted()
                .orEmpty()

            println("KMLM resource extension first=${dates.firstOrNull()} last=${dates.lastOrNull()} rows=${dates.size}")
            println("tickerFiles=${tickerFiles.joinToString()}")

            assertTrue(
                dates.firstOrNull()?.isBefore(LocalDate.of(2000, 1, 1)) == true,
                "KMLM should keep bundled synthetic history before 2000, first=${dates.firstOrNull()}"
            )
            assertTrue(
                dates.lastOrNull()?.isAfter(LocalDate.now().minusDays(14)) == true,
                "KMLM should extend close to today, last=${dates.lastOrNull()}"
            )
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun liveBacktestDiagnostic_singleTickerIncludesLatestYahooDate() {
        if (System.getProperty("liveYahoo") != "true" && System.getenv("LIVE_YAHOO") != "true") return

        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-live-backtest-")
        try {
            AppDirs.dataDir = tempDataDir

            val end = LocalDate.now()
            val tickers = (System.getenv("LIVE_YAHOO_TICKERS") ?: "SPY")
                .split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }

            for (ticker in tickers) {
                val yahooPrices = YahooHistoricalFetcher.fetchAdjustedClose(ticker, end.minusDays(10), end)
                val yahooDates = yahooPrices.keys.sorted()
                println("Yahoo $ticker dates=${yahooDates.joinToString()}")
                println("Yahoo $ticker last=${yahooDates.lastOrNull()} value=${yahooDates.lastOrNull()?.let { yahooPrices[it] }}")
            }

            val result = BacktestService.runMulti(
                MultiBacktestRequest(
                    fromDate = end.minusDays(10).toString(),
                    toDate = end.toString(),
                    portfolios = listOf(
                        PortfolioConfig(
                            label = "${tickers.joinToString("/")} live",
                            tickers = tickers.map { TickerWeight(it, 1.0) },
                            rebalanceStrategy = RebalanceStrategy.NONE,
                            marginStrategies = emptyList(),
                        )
                    )
                )
            )
            val points = result.portfolios.single().curves.single().points
            val dates = points.map { it.date }
            val tickerFiles = tempDataDir.resolve(".ticker").toFile()
                .listFiles()
                ?.map { it.name }
                ?.sorted()
                .orEmpty()

            println("Backtest ${tickers.joinToString(",")} diagnostic to=$end")
            println("points=${dates.joinToString()}")
            println("last=${dates.lastOrNull()} value=${points.lastOrNull()?.value}")
            println("tickerFiles=${tickerFiles.joinToString()}")

            assertTrue(points.isNotEmpty(), "Backtest returned no SPY points")
            assertTrue(
                LocalDate.parse(dates.last()) >= end.minusDays(3),
                "Backtest latest date ${dates.last()} is stale for end=$end"
            )
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }
}
