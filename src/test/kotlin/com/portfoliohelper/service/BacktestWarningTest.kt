package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals

class BacktestWarningTest {
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
