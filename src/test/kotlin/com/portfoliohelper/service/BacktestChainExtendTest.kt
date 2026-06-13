package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestChainExtendTest {
    @Test
    fun chainExtendFromAnchorPreservesCachedHistoryAndAppendsYahooReturns() {
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
        assertEquals(100.0, extended[mar2])
        assertClose(110.0, extended[mar3])
        assertClose(99.0, extended[mar4])
    }

    private fun assertClose(expected: Double, actual: Double?) {
        assertTrue(actual != null && abs(expected - actual) <= 1e-9, "Expected $expected but was $actual")
    }
}
