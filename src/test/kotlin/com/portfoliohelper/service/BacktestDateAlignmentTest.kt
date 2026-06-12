package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BacktestDateAlignmentTest {
    @Test
    fun intersectDates_usesUnionWithinCommonTickerDateRange() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan3 = LocalDate.of(2026, 1, 3)
        val jan4 = LocalDate.of(2026, 1, 4)

        val dates = BacktestService.intersectDates(
            listOf(
                mapOf(jan1 to 100.0, jan3 to 110.0),
                mapOf(jan2 to 200.0, jan4 to 230.0),
            ),
            from = jan1,
            to = jan4,
        )

        assertEquals(listOf(jan2, jan3), dates)
    }

    @Test
    fun intersectDates_endsOnEarliestTickerEndDate() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan3 = LocalDate.of(2026, 1, 3)
        val jan4 = LocalDate.of(2026, 1, 4)
        val jan5 = LocalDate.of(2026, 1, 5)

        val dates = BacktestService.intersectDates(
            listOf(
                mapOf(jan1 to 100.0, jan3 to 110.0, jan5 to 120.0),
                mapOf(jan2 to 200.0, jan4 to 230.0),
            ),
            from = jan1,
            to = jan5,
        )

        assertEquals(listOf(jan2, jan3, jan4), dates)
    }

    @Test
    fun intersectDates_doesNotExtendPastEarliestTickerEndDateUsingCarryForwardData() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan3 = LocalDate.of(2026, 1, 3)
        val jan4 = LocalDate.of(2026, 1, 4)
        val jan5 = LocalDate.of(2026, 1, 5)
        val jan6 = LocalDate.of(2026, 1, 6)

        val dates = BacktestService.intersectDates(
            listOf(
                mapOf(jan1 to 100.0, jan3 to 110.0, jan5 to 120.0),
                mapOf(jan2 to 200.0, jan4 to 230.0),
            ),
            from = jan1,
            to = jan6,
        )

        assertEquals(listOf(jan2, jan3, jan4), dates)
    }

    @Test
    fun buildReturnRatios_carriesForwardMissingTickerDates() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val jan3 = LocalDate.of(2026, 1, 3)
        val jan4 = LocalDate.of(2026, 1, 4)
        val dates = listOf(jan2, jan3, jan4)

        val ratios = BacktestService.buildReturnRatios(
            listOf("AAA", "BBB"),
            mapOf(
                "AAA" to mapOf(jan1 to 100.0, jan3 to 110.0),
                "BBB" to mapOf(jan2 to 200.0, jan4 to 230.0),
            ),
            dates,
        )

        assertEquals(1.0, ratios.getValue("AAA")[0])
        assertEquals(1.1, ratios.getValue("AAA")[1])
        assertEquals(1.0, ratios.getValue("AAA")[2])
        assertEquals(1.0, ratios.getValue("BBB")[0])
        assertEquals(1.0, ratios.getValue("BBB")[1])
        assertEquals(1.15, ratios.getValue("BBB")[2])
    }
}
