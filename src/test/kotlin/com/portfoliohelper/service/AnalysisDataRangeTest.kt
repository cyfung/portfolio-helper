package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisDataRangeTest {
    @Test
    fun `blank from reports the series with the latest history start`() {
        val series = mapOf(
            "A" to prices("2010-01-04", "2024-12-30"),
            "B" to prices("2015-06-18", "2024-12-30"),
            "C" to prices("2015-06-18", "2024-12-30"),
        )
        val dates = listOf(LocalDate.parse("2015-06-18"), LocalDate.parse("2024-12-30"))

        assertEquals(
            AnalysisDataRange(
                fromDate = "2015-06-18",
                toDate = "2024-12-30",
                startLimiters = listOf("B", "C"),
            ),
            analysisDataRange(
                series,
                dates,
                requestedFrom = null,
                effectiveTo = LocalDate.parse("2024-12-30"),
            ),
        )
    }

    @Test
    fun `explicit from ignores normal calendar alignment but reports unavailable history`() {
        val requestedFrom = LocalDate.parse("2020-01-01")
        val dates = listOf(LocalDate.parse("2020-01-06"), LocalDate.parse("2024-12-30"))
        val series = mapOf(
            "LONG_HISTORY" to prices("2019-12-31", "2024-12-30"),
            "NEW_A" to prices("2020-01-06", "2024-12-30"),
            "NEW_B" to prices("2020-01-06", "2024-12-30"),
        )

        assertEquals(
            listOf("NEW_A", "NEW_B"),
            analysisDataRange(
                series,
                dates,
                requestedFrom,
                effectiveTo = LocalDate.parse("2024-12-30"),
            ).startLimiters,
        )
    }

    @Test
    fun `explicit to reports series that end the usable range early`() {
        val dates = listOf(LocalDate.parse("2020-01-02"), LocalDate.parse("2024-12-20"))
        val series = mapOf(
            "A" to prices("2019-12-31", "2024-12-20"),
            "B" to prices("2019-12-31", "2024-12-20"),
            "C" to prices("2019-12-31", "2024-12-19"),
        )

        assertEquals(
            listOf("A", "B"),
            analysisDataRange(
                series,
                dates,
                requestedFrom = LocalDate.parse("2020-01-01"),
                effectiveTo = LocalDate.parse("2024-12-31"),
            ).endLimiters,
        )
    }

    @Test
    fun `blank to reports stale history but ignores normal market lag`() {
        val series = mapOf("A" to prices("2019-12-31", "2024-12-20"))
        val dates = listOf(LocalDate.parse("2020-01-02"), LocalDate.parse("2024-12-20"))

        assertEquals(
            listOf("A"),
            analysisDataRange(
                series,
                dates,
                requestedFrom = null,
                effectiveTo = LocalDate.parse("2024-12-31"),
            ).endLimiters,
        )
        assertEquals(
            emptyList(),
            analysisDataRange(
                series,
                dates,
                requestedFrom = null,
                effectiveTo = LocalDate.parse("2024-12-24"),
            ).endLimiters,
        )
    }

    @Test
    fun `blank from groups calendar-equivalent first observations`() {
        val series = mapOf(
            "US" to prices("2020-01-02", "2024-12-30"),
            "HK" to prices("2020-01-03", "2024-12-30"),
        )
        val dates = listOf(LocalDate.parse("2020-01-03"), LocalDate.parse("2024-12-30"))

        assertEquals(
            emptyList(),
            analysisDataRange(
                series,
                dates,
                requestedFrom = null,
                effectiveTo = LocalDate.parse("2024-12-30"),
            ).startLimiters,
        )
    }

    private fun prices(first: String, last: String): Map<LocalDate, Double> =
        mapOf(LocalDate.parse(first) to 1.0, LocalDate.parse(last) to 2.0)
}
