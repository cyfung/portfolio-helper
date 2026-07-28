package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BacktestWarningTest {
    @Test
    fun filterWarningToRange_excludesCachedOccurrencesOutsideTheAnalysisRange() {
        val warning = AnalysisWarning(
            WarningCategory.NULL_DATA,
            "Yahoo adjusted-close data for VXUS contains unsupported null rows; " +
                    "invalid null rows: 2025-01-02, 2026-01-02;",
            occurrences = 2,
        )

        assertEquals(
            AnalysisWarning(
                WarningCategory.NULL_DATA,
                "Yahoo adjusted-close data for VXUS contains unsupported null rows; " +
                        "invalid null rows: 2026-01-02;",
                occurrences = 1,
            ),
            BacktestService.filterWarningToRange(
                warning,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
            ),
        )
        assertEquals(
            null,
            BacktestService.filterWarningToRange(
                warning,
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31),
            ),
        )
    }

    @Test
    fun canonicalizeTickerWarning_removesVolatileYahooNullRowRange() {
        val warnings = listOf(
            "Yahoo adjusted-close data for 0050.TW contains unsupported null rows " +
                    "for range 1990-01-01..2026-06-20 (currentTradingDate=2026-06-18); " +
                    "invalid null rows: 2016-01-30, 2016-06-04;",
            "Yahoo adjusted-close data for 0050.TW contains unsupported null rows " +
                    "for range 2006-06-23..2026-06-23 (currentTradingDate=2026-06-23); " +
                    "invalid null rows: 2016-01-30, 2016-06-04;"
        )

        assertEquals(
            listOf("Yahoo adjusted-close data for 0050.TW contains unsupported null rows; invalid null rows: 2016-01-30, 2016-06-04;"),
            warnings.map { BacktestService.canonicalizeTickerWarning(it) }.distinct()
        )
    }

    @Test
    fun canonicalizeTickerWarning_normalizesOldWeekdayYahooNullRowText() {
        val warning = "Yahoo adjusted-close data for AVGS.L contains unsupported null rows " +
                "for range 1990-01-01..2026-06-22 (currentTradingDate=2026-06-22); " +
                "invalid weekday null rows: 2025-10-24;"

        assertEquals(
            "Yahoo adjusted-close data for AVGS.L contains unsupported null rows; invalid null rows: 2025-10-24;",
            BacktestService.canonicalizeTickerWarning(warning)
        )
    }
}
