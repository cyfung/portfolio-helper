package com.portfoliohelper.service.yahoo

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YahooHistoricalFetcherTest {
    @Test
    fun parseAdjustedCloseResponse_appendsRegularMarketPriceForCurrentTradingDate() {
        val body = """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 105.5,
                    "currentTradingPeriod": {
                      "regular": {
                        "gmtoffset": -14400,
                        "start": 1777901400,
                        "end": 1777924800
                      }
                    }
                  },
                  "timestamp": [1777815000],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [100.0]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val prices = YahooHistoricalFetcher.parseAdjustedCloseResponse(
            ticker = "SPY",
            startDate = LocalDate.of(2026, 5, 1),
            endDate = LocalDate.of(2026, 5, 4),
            body = body
        )

        assertEquals(100.0, prices[LocalDate.of(2026, 5, 3)])
        assertEquals(105.5, prices[LocalDate.of(2026, 5, 4)])
    }

    @Test
    fun liveYahooDiagnostic_currentRangeIncludesLatestAvailableYahooDate() {
        if (System.getProperty("liveYahoo") != "true" && System.getenv("LIVE_YAHOO") != "true") return

        val end = LocalDate.now()
        val start = end.minusDays(7)
        val prices = YahooHistoricalFetcher.fetchAdjustedClose("SPY", start, end)
        val dates = prices.keys.sorted()

        println("Yahoo SPY diagnostic $start to $end")
        println("dates=${dates.joinToString()}")
        println("last=${dates.lastOrNull()} value=${dates.lastOrNull()?.let { prices[it] }}")

        assertTrue(dates.isNotEmpty(), "Yahoo returned no SPY dates from $start to $end")
        assertTrue(dates.last() >= end.minusDays(3), "Yahoo latest date ${dates.last()} is stale for end=$end")
    }
}
