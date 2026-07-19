package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BacktestDateAlignmentTest {
    @Test
    fun intersectDates_usesUnionStartingAtLatestTickerStartDate() {
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

        assertEquals(listOf(jan2, jan3, jan4), dates)
    }

    @Test
    fun intersectDates_endsOnLastDateWhereAtLeastOneTickerHasData() {
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

        assertEquals(listOf(jan2, jan3, jan4, jan5), dates)
    }

    @Test
    fun intersectDates_doesNotExtendEndDateUsingCarryForwardData() {
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

        assertEquals(listOf(jan2, jan3, jan4, jan5), dates)
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

    @Test
    fun rebalanceFlags_useFirstAvailableDateAfterPeriodChange() {
        val dates = listOf(
            LocalDate.of(2023, 12, 29),
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 31),
            LocalDate.of(2024, 2, 2),
            LocalDate.of(2024, 3, 1),
        )

        val monthly = BacktestService.rebalanceFlags(dates, RebalanceStrategy.MONTHLY)
        val quarterly = BacktestService.rebalanceFlags(dates, RebalanceStrategy.QUARTERLY)
        val yearly = BacktestService.rebalanceFlags(dates, RebalanceStrategy.YEARLY)

        assertFalse(monthly[0])
        assertTrue(monthly[1], "Monthly rebalance should use Jan 2 when Jan 1 has no data")
        assertFalse(monthly[2])
        assertTrue(monthly[3], "Monthly rebalance should use Feb 2 when Feb 1 has no data")
        assertTrue(monthly[4])

        assertTrue(quarterly[1], "Quarterly rebalance should use first available Q1 date")
        assertFalse(quarterly[3])
        assertTrue(yearly[1], "Yearly rebalance should use first available new-year date")
    }

    @Test
    fun rebalanceFlags_areYearAwareForSparseSameMonthOrQuarterData() {
        val dates = listOf(
            LocalDate.of(2024, 2, 1),
            LocalDate.of(2025, 2, 3),
        )

        assertTrue(BacktestService.rebalanceFlags(dates, RebalanceStrategy.MONTHLY)[1])
        assertTrue(BacktestService.rebalanceFlags(dates, RebalanceStrategy.QUARTERLY)[1])
        assertTrue(BacktestService.rebalanceFlags(dates, RebalanceStrategy.HALF_YEARLY)[1])
        assertTrue(BacktestService.rebalanceFlags(dates, RebalanceStrategy.YEARLY)[1])
    }
}
