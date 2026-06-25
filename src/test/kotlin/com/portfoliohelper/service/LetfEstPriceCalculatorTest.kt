package com.portfoliohelper.service

import com.portfoliohelper.service.yahoo.YahooQuote
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LetfEstPriceCalculatorTest {
    @Test
    fun `same-day nav returns nav without reference moves`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { error("Reference quote should not be needed for same-day NAV") },
            historicalPriceProvider = { _, _, _ -> error("History should not be needed for same-day NAV") }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 51.0, previousClose = 49.0),
            nav = 50.0,
            navDate = stockDate
        )

        assertEquals(50.0, est!!, 0.000001)
    }

    @Test
    fun `previous trading day nav uses current quote previous close for references`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val navDate = LocalDate.of(2026, 6, 12)
        val historyCalls = mutableListOf<String>()
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote("SPY", stockDate, mark = 110.0, previousClose = 100.0)
                    else -> null
                }
            },
            historicalPriceProvider = { sym, start, end ->
                historyCalls += "$sym:$start..$end"
                mapOf(navDate to 50.0)
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 51.0, previousClose = 49.0),
            nav = 50.0,
            navDate = navDate
        )

        assertEquals(55.0, est!!, 0.000001)
        assertEquals(listOf("CTA:2026-06-12..2026-06-14"), historyCalls)
    }

    @Test
    fun `older nav date uses historical adjusted close for references`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val navDate = LocalDate.of(2026, 6, 11)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote("SPY", stockDate, mark = 100.0, previousClose = 90.0)
                    else -> null
                }
            },
            historicalPriceProvider = { sym, start, end ->
                when {
                    sym == "CTA" && start == navDate && end == LocalDate.of(2026, 6, 14) ->
                        mapOf(navDate to 50.0, LocalDate.of(2026, 6, 12) to 51.0)
                    sym == "SPY" && start == navDate && end == navDate ->
                        mapOf(navDate to 80.0)
                    else -> emptyMap()
                }
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 51.0, previousClose = 49.0),
            nav = 50.0,
            navDate = navDate
        )

        assertEquals(62.5, est!!, 0.000001)
    }

    @Test
    fun `missing historical reference close makes stale nav estimate unavailable`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val navDate = LocalDate.of(2026, 6, 11)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote("SPY", stockDate, mark = 100.0, previousClose = 90.0)
                    else -> null
                }
            },
            historicalPriceProvider = { sym, _, _ ->
                if (sym == "CTA") mapOf(navDate to 50.0, LocalDate.of(2026, 6, 12) to 51.0) else emptyMap()
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 51.0, previousClose = 49.0),
            nav = 50.0,
            navDate = navDate
        )

        assertTrue(est == null)
    }

    private fun quote(
        symbol: String,
        tradingDate: LocalDate,
        mark: Double,
        previousClose: Double
    ): YahooQuote {
        val offset = ZoneOffset.ofHours(-4)
        val tradingEnd = tradingDate.atTime(LocalTime.of(16, 0)).toEpochSecond(offset)
        return YahooQuote(
            symbol = symbol,
            regularMarketPrice = mark,
            previousClose = previousClose,
            tradingPeriodEnd = tradingEnd,
            gmtoffset = offset.totalSeconds
        )
    }
}
