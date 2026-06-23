package com.portfoliohelper.service.yahoo

import java.time.LocalDate
import java.time.ZoneOffset
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
    fun parseAdjustedCloseResponse_fillsKnownTailNullFromQuotePreviousClose() {
        val jun12 = LocalDate.of(2026, 6, 12).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun15 = LocalDate.of(2026, 6, 15).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun16 = LocalDate.of(2026, 6, 16).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun16End = LocalDate.of(2026, 6, 16).atTime(16, 0).toEpochSecond(ZoneOffset.ofHours(-4))
        val body = """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 86.86,
                    "chartPreviousClose": 85.34,
                    "currentTradingPeriod": {
                      "regular": {
                        "gmtoffset": -14400,
                        "start": ${jun16End - 23400},
                        "end": $jun16End
                      }
                    }
                  },
                  "timestamp": [$jun12, $jun15, $jun16],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [85.91, null, 86.86]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val prices = YahooHistoricalFetcher.parseAdjustedCloseResponse(
            ticker = "VXUS",
            startDate = LocalDate.of(2026, 6, 12),
            endDate = LocalDate.of(2026, 6, 16),
            body = body,
            tailQuoteProvider = {
                YahooQuote(
                    symbol = "VXUS",
                    regularMarketPrice = 86.86,
                    previousClose = 86.98
                )
            }
        )

        assertEquals(85.91, prices[LocalDate.of(2026, 6, 12)])
        assertEquals(86.98, prices[LocalDate.of(2026, 6, 15)])
        assertEquals(86.86, prices[LocalDate.of(2026, 6, 16)])
    }

    @Test
    fun parseAdjustedCloseResponse_fillsCurrentTradingDateNullFromRegularMarketPrice() {
        val jun17 = LocalDate.of(2026, 6, 17).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun18 = LocalDate.of(2026, 6, 18).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun18End = LocalDate.of(2026, 6, 18).atTime(16, 0).toEpochSecond(ZoneOffset.ofHours(-4))
        val body = """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 30.92,
                    "chartPreviousClose": 30.67,
                    "currentTradingPeriod": {
                      "regular": {
                        "gmtoffset": -14400,
                        "start": ${jun18End - 23400},
                        "end": $jun18End
                      }
                    }
                  },
                  "timestamp": [$jun17, $jun18],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [30.67, null]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val result = YahooHistoricalFetcher.parseAdjustedCloseResponseWithWarnings(
            ticker = "DBMF",
            startDate = LocalDate.of(2026, 6, 17),
            endDate = LocalDate.of(2026, 6, 20),
            body = body
        )

        assertTrue(result.warnings.isEmpty(), "Expected no warnings, got ${result.warnings}")
        assertEquals(30.67, result.prices[LocalDate.of(2026, 6, 17)])
        assertEquals(30.92, result.prices[LocalDate.of(2026, 6, 18)])
    }

    @Test
    fun parseAdjustedCloseResponse_fillsTailNullWhenHistoricalResponseHasNoCurrentDayRow() {
        val jun15 = LocalDate.of(2026, 6, 15).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun16 = LocalDate.of(2026, 6, 16).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun17End = LocalDate.of(2026, 6, 17).atTime(16, 30).toEpochSecond(ZoneOffset.ofHours(1))
        val body = """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 28.80,
                    "currentTradingPeriod": {
                      "regular": {
                        "gmtoffset": 3600,
                        "start": ${jun17End - 30600},
                        "end": $jun17End
                      }
                    }
                  },
                  "timestamp": [$jun15, $jun16],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [28.645, null]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val prices = YahooHistoricalFetcher.parseAdjustedCloseResponse(
            ticker = "AVGS.L",
            startDate = LocalDate.of(2026, 6, 15),
            endDate = LocalDate.of(2026, 6, 17),
            body = body,
            tailQuoteProvider = {
                YahooQuote(
                    symbol = "AVGS.L",
                    regularMarketPrice = 28.80,
                    previousClose = 28.71
                )
            }
        )

        assertEquals(28.645, prices[LocalDate.of(2026, 6, 15)])
        assertEquals(28.71, prices[LocalDate.of(2026, 6, 16)])
        assertEquals(28.80, prices[LocalDate.of(2026, 6, 17)])
    }

    @Test
    fun parseAdjustedCloseResponse_warnsAndSkipsUnsupportedInteriorNullRows() {
        // Only current/tail nulls are known safe cases. Any other null is reported as a
        // data-quality warning and omitted.
        val jun10 = LocalDate.of(2026, 6, 10).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun11 = LocalDate.of(2026, 6, 11).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun12 = LocalDate.of(2026, 6, 12).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun16End = LocalDate.of(2026, 6, 16).atTime(16, 0).toEpochSecond(ZoneOffset.ofHours(-4))
        val body = """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 86.86,
                    "currentTradingPeriod": {
                      "regular": {
                        "gmtoffset": -14400,
                        "start": ${jun16End - 23400},
                        "end": $jun16End
                      }
                    }
                  },
                  "timestamp": [$jun10, $jun11, $jun12],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [84.0, null, 85.91]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val result = YahooHistoricalFetcher.parseAdjustedCloseResponseWithWarnings(
            ticker = "VXUS",
            startDate = LocalDate.of(2026, 6, 10),
            endDate = LocalDate.of(2026, 6, 16),
            body = body,
            tailQuoteProvider = {
                YahooQuote(
                    symbol = "VXUS",
                    regularMarketPrice = 86.86,
                    previousClose = 86.98
                )
            }
        )

        assertEquals(
            "Yahoo adjusted-close data for VXUS contains unsupported null rows " +
                    "for range 2026-06-10..2026-06-16 (currentTradingDate=2026-06-16); " +
                    "invalid null rows: 2026-06-11;",
            result.warnings.single()
        )
        assertEquals(
            mapOf(
                LocalDate.of(2026, 6, 10) to 84.0,
                LocalDate.of(2026, 6, 12) to 85.91,
                LocalDate.of(2026, 6, 16) to 86.86
            ),
            result.prices
        )
    }

    @Test
    fun parseAdjustedCloseResponse_warnsInteriorNullEvenWhenTailNullIsFillable() {
        val oct23 = LocalDate.of(2025, 10, 23).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val oct24 = LocalDate.of(2025, 10, 24).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val oct27 = LocalDate.of(2025, 10, 27).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun15 = LocalDate.of(2026, 6, 15).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun16 = LocalDate.of(2026, 6, 16).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jun17End = LocalDate.of(2026, 6, 17).atTime(16, 30).toEpochSecond(ZoneOffset.ofHours(1))
        val body = """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 28.80,
                    "currentTradingPeriod": {
                      "regular": {
                        "gmtoffset": 3600,
                        "start": ${jun17End - 30600},
                        "end": $jun17End
                      }
                    }
                  },
                  "timestamp": [$oct23, $oct24, $oct27, $jun15, $jun16],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [22.76, null, 22.98, 28.645, null]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val result = YahooHistoricalFetcher.parseAdjustedCloseResponseWithWarnings(
            ticker = "AVGS.L",
            startDate = LocalDate.of(2025, 10, 23),
            endDate = LocalDate.of(2026, 6, 17),
            body = body,
            tailQuoteProvider = {
                YahooQuote(
                    symbol = "AVGS.L",
                    regularMarketPrice = 28.80,
                    previousClose = 28.71
                )
            }
        )

        assertEquals(
            "Yahoo adjusted-close data for AVGS.L contains unsupported null rows " +
                    "for range 2025-10-23..2026-06-17 (currentTradingDate=2026-06-17); " +
                    "invalid null rows: 2025-10-24;",
            result.warnings.single()
        )
        assertEquals(28.71, result.prices[LocalDate.of(2026, 6, 16)])
        assertEquals(28.80, result.prices[LocalDate.of(2026, 6, 17)])
        assertTrue(LocalDate.of(2025, 10, 24) !in result.prices)
    }

    @Test
    fun parseAdjustedCloseResponse_ignoresWeekendNullRows() {
        val jan29 = LocalDate.of(2016, 1, 29).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val jan30 = LocalDate.of(2016, 1, 30).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val feb1 = LocalDate.of(2016, 2, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val body = """
            {
              "chart": {
                "result": [{
                  "timestamp": [$jan29, $jan30, $feb1],
                  "indicators": {
                    "adjclose": [{
                      "adjclose": [10.45, null, 10.82]
                    }]
                  }
                }]
              }
            }
        """.trimIndent()

        val prices = YahooHistoricalFetcher.parseAdjustedCloseResponse(
            ticker = "0050.TW",
            startDate = LocalDate.of(2016, 1, 29),
            endDate = LocalDate.of(2016, 2, 1),
            body = body
        )

        assertEquals(
            mapOf(
                LocalDate.of(2016, 1, 29) to 10.45,
                LocalDate.of(2016, 2, 1) to 10.82
            ),
            prices
        )
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
