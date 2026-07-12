package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.yahoo.YahooAdjustedCloseResult
import java.nio.file.Files
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BacktestChainExtendTest {
    @Test
    fun loadNormalizedSeriesReadsNewFullTickerCacheBeforeLegacyTickerCache() {
        val originalDataDir = AppDirs.dataDir
        val tempDataDir = Files.createTempDirectory("ib-viewer-full-ticker-cache-test-")
        try {
            AppDirs.dataDir = tempDataDir
            val today = LocalDate.now()
            val jan1 = LocalDate.of(2026, 1, 1)
            val jan2 = LocalDate.of(2026, 1, 2)
            val jan3 = LocalDate.of(2026, 1, 3)
            val fullSeries = mapOf(
                jan1 to 10_000.0,
                jan2 to 10_100.0,
                jan3 to 10_200.0,
                today to 10_300.0,
            )
            val legacySeries = fullSeries.mapValues { 1.0 }

            val legacyDir = tempDataDir.resolve(".ticker").toFile().also { it.mkdirs() }
            val fullDir = tempDataDir.resolve(".ticker-full").toFile().also { it.mkdirs() }
            BacktestService.writeSimCsv(legacyDir.resolve("FULLPATH-$today.csv"), legacySeries)
            BacktestService.writeSimCsv(fullDir.resolve("FULLPATH-$today.csv"), fullSeries)

            val loaded = BacktestService.loadNormalizedSeries("FULLPATH", LocalDate.of(1990, 1, 1))

            assertEquals(fullSeries, loaded)
        } finally {
            AppDirs.dataDir = originalDataDir
            tempDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun parseTickerChainKeepsLetfSegmentAsBaseTicker() {
        val chain = BacktestService.parseTickerChain("CTAP | 1 CTA 1 SPY E=1.5")

        assertEquals(listOf("CTAP", "1 CTA 1 SPY E=1.5"), chain)
        assertNull(BacktestService.parseLETFDefinition("CTAP | 1 CTA 1 SPY E=1.5"))
    }

    @Test
    fun parseTickerChainIgnoresExtendInsideSwapExpression() {
        assertNull(BacktestService.parseTickerChain("SWAP(CTAP | 1 CTA 1 SPY E=1.5, SSO)"))
    }

    @Test
    fun spliceTickerSeriesScalesBaseHistoryToOverwriteConnectionDate() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan3 = LocalDate.of(2026, 1, 3)
        val jan4 = LocalDate.of(2026, 1, 4)

        val base = mapOf(
            jan1 to 100.0,
            jan2 to 110.0,
            jan3 to 121.0,
            jan4 to 133.1,
        )
        val overwrite = mapOf(
            jan3 to 50.0,
            jan4 to 55.0,
        )

        val spliced = BacktestService.spliceTickerSeries(base, overwrite)

        assertClose(100.0 * (50.0 / 121.0), spliced[jan1])
        assertClose(110.0 * (50.0 / 121.0), spliced[jan2])
        assertEquals(50.0, spliced[jan3])
        assertEquals(55.0, spliced[jan4])
    }

    @Test
    fun computeTickerChainSeriesAppliesMultipleOverwritesFromRightToLeft() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan3 = LocalDate.of(2026, 1, 3)
        val jan4 = LocalDate.of(2026, 1, 4)
        val jan5 = LocalDate.of(2026, 1, 5)

        val d = mapOf(jan1 to 100.0, jan2 to 200.0)
        val c = mapOf(jan2 to 50.0, jan3 to 75.0)
        val b = mapOf(jan3 to 30.0, jan4 to 33.0)
        val a = mapOf(jan4 to 100.0, jan5 to 110.0)

        val chained = BacktestService.computeTickerChainSeries(
            listOf("A" to a, "B" to b, "C" to c, "D" to d)
        )

        assertClose(100.0 * (50.0 / 200.0) * (30.0 / 75.0) * (100.0 / 33.0), chained[jan1])
        assertClose(50.0 * (30.0 / 75.0) * (100.0 / 33.0), chained[jan2])
        assertClose(30.0 * (100.0 / 33.0), chained[jan3])
        assertEquals(100.0, chained[jan4])
        assertEquals(110.0, chained[jan5])
    }

    @Test
    fun computeTickerChainSeriesUsesBaseWhenOverwriteSegmentHasNoDates() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)

        val base = mapOf(jan1 to 100.0, jan2 to 110.0)
        val chained = BacktestService.computeTickerChainSeries(
            listOf("NEWER" to emptyMap(), "BASE" to base)
        )

        assertEquals(base, chained)
    }

    @Test
    fun chainExtendFromAnchorPreservesHistoryThroughAnchorAndRebuildsCachedTail() {
        val mar1 = LocalDate.of(2026, 3, 1)
        val mar2 = LocalDate.of(2026, 3, 2)
        val mar3 = LocalDate.of(2026, 3, 3)
        val mar4 = LocalDate.of(2026, 3, 4)

        val existing = mapOf(
            mar1 to 90.0,
            mar2 to 100.0,
        )
        val yahoo = mapOf(
            mar1 to 210.0,
            mar2 to 200.0,
            mar3 to 220.0,
            mar4 to 198.0,
        )

        val extended = BacktestService.chainExtendFromAnchor(existing, yahoo, mar2)

        assertEquals(90.0, extended[mar1])
        assertClose(90.0 * (200.0 / 210.0), extended[mar2])
        assertClose(90.0 * (220.0 / 210.0), extended[mar3])
        assertClose(90.0 * (198.0 / 210.0), extended[mar4])
    }

    @Test
    fun chainExtendRefreshesCachedLastDateWhenYahooHasNewSameDayPrice() {
        val mar1 = LocalDate.of(2026, 3, 1)
        val mar2 = LocalDate.of(2026, 3, 2)

        val existing = mapOf(
            mar1 to 90.0,
            mar2 to 100.0,
        )
        val yahoo = mapOf(
            mar1 to 90.0,
            mar2 to 99.0,
        )

        val extended = BacktestService.chainExtend(existing, yahoo, mar2)

        assertEquals(90.0, extended[mar1])
        assertClose(99.0, extended[mar2])
    }

    @Test
    fun chainExtendDropsCachedLastDateAndFillsMissingOverlapDate() {
        val jun12 = LocalDate.of(2026, 6, 12)
        val jun15 = LocalDate.of(2026, 6, 15)
        val jun16 = LocalDate.of(2026, 6, 16)

        val existing = mapOf(
            jun12 to 100.0,
            jun16 to 110.0,
        )
        val yahoo = mapOf(
            jun12 to 85.91,
            jun15 to 86.98,
            jun16 to 86.86,
        )

        val extended = BacktestService.chainExtend(existing, yahoo, jun16)

        assertEquals(100.0, extended[jun12])
        assertClose(100.0 * (86.98 / 85.91), extended[jun15])
        assertClose(100.0 * (86.86 / 85.91), extended[jun16])
    }

    @Test
    fun convertYahooAdjustedCloseToUsdAppliesSameDayAndPreviousAvailableFxRates() {
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan5 = LocalDate.of(2026, 1, 5)
        val result = YahooAdjustedCloseResult(
            prices = mapOf(
                jan2 to 100.0,
                jan5 to 110.0,
            ),
            currency = "EUR",
        )

        val converted = BacktestService.convertYahooAdjustedCloseToUsd("CL2.PA", result) { fxTicker, start, end ->
            assertEquals("EURUSD=X", fxTicker)
            assertEquals(jan2, start)
            assertEquals(jan5, end)
            mapOf(
                jan2.minusDays(1) to 1.10,
                jan2 to 1.20,
                jan5.minusDays(1) to 1.25,
            )
        }

        assertEquals(mapOf(jan2 to 120.0, jan5 to 137.5), converted)
    }

    @Test
    fun convertYahooAdjustedCloseToUsdLeavesUsdPricesUntouched() {
        val jan2 = LocalDate.of(2026, 1, 2)
        val prices = mapOf(jan2 to 100.0)
        val result = YahooAdjustedCloseResult(prices = prices, currency = "USD")
        var fxCalled = false

        val converted = BacktestService.convertYahooAdjustedCloseToUsd("SPY", result) { _, _, _ ->
            fxCalled = true
            emptyMap()
        }

        assertEquals(prices, converted)
        assertTrue(!fxCalled, "USD history should not fetch FX")
    }

    @Test
    fun currencyUsdConversionHandlesYahooSubUnitCurrencies() {
        val conversion = BacktestService.currencyUsdConversion("GBp")

        assertEquals(BacktestService.CurrencyUsdConversion("GBPUSD=X", 0.01), conversion)
    }

    @Test
    fun tickerSimPatternTreatsTickerAsLiteralText() {
        val pattern = BacktestService.tickerSimPattern("^GSPC")

        assertTrue(pattern.matches("^GSPC-2026-06-16.csv"))
        assertTrue(!pattern.matches("GSPC-2026-06-16.csv"))
    }

    @Test
    fun staleSameDayTickerCacheNeedsRefreshAfterFifteenMinutes() {
        val today = LocalDate.of(2026, 6, 16)
        val yesterday = today.minusDays(1)

        assertTrue(
            BacktestService.shouldRefreshCurrentTickerFile(16.minutes, today, today, today),
            "Same-day ticker cache older than 15 minutes should refresh",
        )
        assertTrue(
            !BacktestService.shouldRefreshCurrentTickerFile(15.minutes, today, today, today),
            "Same-day ticker cache at the TTL should still be reusable",
        )
        assertTrue(
            !BacktestService.shouldRefreshCurrentTickerFile(16.minutes, yesterday, today, today),
            "Missing today should use normal forward-extension logic, not same-day refresh",
        )
        assertTrue(
            !BacktestService.shouldRefreshCurrentTickerFile(16.minutes, today, today, today.plusDays(1)),
            "Historical toDate should not be refreshed just because the file is old",
        )
    }

    @Test
    fun freshCurrentTickerCacheCanBeReusedWhenYahooHasNoTodayRowYet() {
        val today = LocalDate.of(2026, 6, 16)
        val lastYahooDate = today.minusDays(3)
        val firstDate = LocalDate.of(2020, 1, 2)

        assertTrue(
            BacktestService.canReuseFreshTickerFile(
                1.minutes,
                firstDate,
                lastYahooDate,
                LocalDate.of(2021, 1, 1),
                today,
                today,
            ),
            "Fresh current-date probe files should not refetch immediately when Yahoo returned no today row",
        )
        assertTrue(
            !BacktestService.canReuseFreshTickerFile(
                16.minutes,
                firstDate,
                lastYahooDate,
                LocalDate.of(2021, 1, 1),
                today,
                today,
            ),
            "Stale current-date probe files should refresh after the TTL",
        )
        assertTrue(
            !BacktestService.canReuseFreshTickerFile(
                1.minutes,
                firstDate,
                lastYahooDate,
                LocalDate.of(2021, 1, 1),
                today.minusDays(1),
                today,
            ),
            "Historical requests still require the cache to cover the requested end date",
        )
        assertTrue(
            !BacktestService.canReuseFreshTickerFile(
                1.minutes,
                firstDate,
                lastYahooDate,
                LocalDate.of(2019, 1, 1),
                today,
                today,
            ),
            "Fresh files cannot satisfy starts before their first cached date",
        )
    }

    @Test
    fun freshFullTickerCacheCanBeReusedForRequestsBeforeTickerInception() {
        val today = LocalDate.of(2026, 6, 16)
        val lastYahooDate = today.minusDays(3)

        assertTrue(
            BacktestService.canReuseFreshFullTickerFile(
                1.minutes,
                lastYahooDate,
                today,
                today,
            ),
            "Full-history ticker caches can satisfy pre-inception requests without a prepend probe",
        )
        assertTrue(
            !BacktestService.canReuseFreshFullTickerFile(
                16.minutes,
                lastYahooDate,
                today,
                today,
            ),
            "Stale current-date probe files should refresh after the TTL",
        )
        assertTrue(
            BacktestService.canReuseFreshFullTickerFile(
                16.minutes,
                LocalDate.of(2026, 6, 12),
                LocalDate.of(2026, 6, 12),
                today,
            ),
            "Historical full-history caches should be reused even when their file mtime is stale",
        )
    }

    private fun assertClose(expected: Double, actual: Double?) {
        assertTrue(actual != null && abs(expected - actual) <= 1e-9, "Expected $expected but was $actual")
    }
}
