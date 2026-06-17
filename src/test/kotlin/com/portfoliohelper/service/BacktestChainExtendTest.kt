package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BacktestChainExtendTest {
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

    private fun assertClose(expected: Double, actual: Double?) {
        assertTrue(actual != null && abs(expected - actual) <= 1e-9, "Expected $expected but was $actual")
    }
}
