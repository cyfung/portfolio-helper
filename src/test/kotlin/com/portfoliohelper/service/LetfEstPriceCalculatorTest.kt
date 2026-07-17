package com.portfoliohelper.service

import com.portfoliohelper.service.yahoo.YahooQuote
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LetfEstPriceCalculatorTest {
    @Test
    fun `AVGS L estimates blended AVUV AVDV move since London close time`() {
        val londonDate = LocalDate.of(2026, 6, 15)
        val londonOffset = ZoneOffset.ofHours(1)
        val londonCloseInstant = Instant.ofEpochSecond(
            londonDate.atTime(LocalTime.of(16, 30)).toEpochSecond(londonOffset)
        )
        val priceTrace = mutableListOf<String>()
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "AVUV" -> {
                        priceTrace += "current AVUV @ latest = 102.00"
                        quote(
                            "AVUV",
                            londonDate,
                            mark = 102.0,
                            previousClose = 99.0,
                            offset = ZoneOffset.ofHours(-4),
                            closeTime = LocalTime.of(16, 0)
                        )
                    }
                    "AVDV" -> {
                        priceTrace += "current AVDV @ latest = 47.50"
                        quote(
                            "AVDV",
                            londonDate,
                            mark = 47.5,
                            previousClose = 46.0,
                            offset = ZoneOffset.ofHours(-4),
                            closeTime = LocalTime.of(16, 0)
                        )
                    }
                    else -> null
                }
            },
            historicalPriceProvider = { _, _, _ -> error("History should not be needed for target-close estimate") },
            intradayPriceAtOrBeforeProvider = { sym, at ->
                when {
                    sym == "AVUV" && at == londonCloseInstant -> {
                        priceTrace += "baseline AVUV @ $at = 100.00"
                        100.0
                    }
                    sym == "AVDV" && at == londonCloseInstant -> {
                        priceTrace += "baseline AVDV @ $at = 50.00"
                        50.0
                    }
                    else -> null
                }
            },
            currentEpochSecondProvider = { londonCloseInstant.epochSecond + 60 }
        )

        priceTrace += "target AVGS.L @ 2026-06-15T15:30:00Z = 100.00"
        val est = calculator.compute(
            components = listOf(0.7 to "AVUV", 0.3 to "AVDV"),
            quote = quote(
                "AVGS.L",
                londonDate,
                mark = 100.0,
                previousClose = 98.0,
                isMarketClosed = true,
                offset = londonOffset,
                closeTime = LocalTime.of(16, 30)
            ),
            nav = null,
            navDate = null
        )

        assertEquals(99.9, est!!, 0.000001)
        assertEquals(
            listOf(
                "target AVGS.L @ 2026-06-15T15:30:00Z = 100.00",
                "current AVUV @ latest = 102.00",
                "baseline AVUV @ 2026-06-15T15:30:00Z = 100.00",
                "current AVDV @ latest = 47.50",
                "baseline AVDV @ 2026-06-15T15:30:00Z = 50.00"
            ),
            priceTrace
        )
    }

    @Test
    fun `closed target market estimates reference move since target close time`() {
        val londonDate = LocalDate.of(2026, 6, 15)
        val londonOffset = ZoneOffset.ofHours(1)
        val londonClose = londonDate.atTime(LocalTime.of(16, 30)).toEpochSecond(londonOffset)
        val calls = mutableListOf<Pair<String, Instant>>()
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote(
                        "SPY",
                        londonDate,
                        mark = 202.0,
                        previousClose = 195.0,
                        offset = ZoneOffset.ofHours(-4),
                        closeTime = LocalTime.of(16, 0)
                    )
                    else -> null
                }
            },
            historicalPriceProvider = { _, _, _ -> error("History should not be needed for target-close estimate") },
            intradayPriceAtOrBeforeProvider = { sym, at ->
                calls += sym to at
                if (sym == "SPY" && at == Instant.ofEpochSecond(londonClose)) 200.0 else null
            },
            currentEpochSecondProvider = { londonClose + 60 }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote(
                "LSPY.L",
                londonDate,
                mark = 100.0,
                previousClose = 98.0,
                isMarketClosed = true,
                offset = londonOffset,
                closeTime = LocalTime.of(16, 30)
            ),
            nav = null,
            navDate = null
        )

        assertEquals(101.0, est!!, 0.000001)
        assertEquals(listOf("SPY" to Instant.ofEpochSecond(londonClose)), calls)
    }

    @Test
    fun `closed target market uses reference previous close when current mark is unavailable`() {
        val londonDate = LocalDate.of(2026, 6, 15)
        val londonOffset = ZoneOffset.ofHours(1)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote(
                        "SPY",
                        londonDate,
                        mark = null,
                        previousClose = 198.0,
                        offset = ZoneOffset.ofHours(-4),
                        closeTime = LocalTime.of(16, 0),
                        isMarketClosed = true
                    )
                    else -> null
                }
            },
            historicalPriceProvider = { _, _, _ -> error("History should not be needed for target-close estimate") },
            intradayPriceAtOrBeforeProvider = { sym, _ -> if (sym == "SPY") 200.0 else null },
            currentEpochSecondProvider = {
                londonDate.atTime(LocalTime.of(16, 31)).toEpochSecond(londonOffset)
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote(
                "LSPY.L",
                londonDate,
                mark = 100.0,
                previousClose = 98.0,
                isMarketClosed = true,
                offset = londonOffset,
                closeTime = LocalTime.of(16, 30)
            ),
            nav = null,
            navDate = null
        )

        assertEquals(99.0, est!!, 0.000001)
    }

    @Test
    fun `closed target market with stale nav uses intraday reference price at nav date close`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val navDate = LocalDate.of(2026, 6, 12)
        val parisOffset = ZoneOffset.ofHours(1)
        val navDateCloseInstant = Instant.ofEpochSecond(
            navDate.atTime(LocalTime.of(16, 30)).toEpochSecond(parisOffset)
        )
        val intradayCalls = mutableListOf<Pair<String, Instant>>()
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "AVUV" -> quote("AVUV", stockDate, mark = 102.0, previousClose = 99.0)
                    else -> null
                }
            },
            historicalPriceProvider = { _, _, _ ->
                error("Intraday reference price should be preferred for NAV-dated estimate")
            },
            intradayPriceAtOrBeforeProvider = { sym, at ->
                intradayCalls += sym to at
                if (sym == "AVUV" && at == navDateCloseInstant) 100.0 else null
            },
            currentEpochSecondProvider = {
                stockDate.atTime(LocalTime.of(16, 31)).toEpochSecond(parisOffset)
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "AVUV"),
            quote = quote(
                "DBMF.PA",
                stockDate,
                mark = 50.0,
                previousClose = 49.0,
                isMarketClosed = true,
                offset = parisOffset,
                closeTime = LocalTime.of(16, 30)
            ),
            nav = 50.0,
            navDate = navDate
        )

        assertEquals(51.0, est!!, 0.000001)
        assertEquals(listOf("AVUV" to navDateCloseInstant), intradayCalls)
    }

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
    fun `previous trading day nav tries historical adjusted close for references first`() {
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
                if (sym == "SPY" && start == navDate && end == navDate) {
                    mapOf(navDate to 100.0)
                } else {
                    emptyMap()
                }
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 51.0, previousClose = 49.0),
            nav = 50.0,
            navDate = navDate
        )

        assertEquals(55.0, est!!, 0.000001)
        assertEquals(listOf("SPY:2026-06-12..2026-06-12"), historyCalls)
    }

    @Test
    fun `previous trading day nav falls back to quote previous close when adjusted close is unavailable`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val navDate = LocalDate.of(2026, 6, 12)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote("SPY", stockDate, mark = 110.0, previousClose = 100.0)
                    else -> null
                }
            },
            historicalPriceProvider = { sym, start, end ->
                if (sym == "CTA" && start == navDate && end == stockDate.minusDays(1)) {
                    mapOf(navDate to 50.0)
                } else {
                    emptyMap()
                }
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 51.0, previousClose = 49.0),
            nav = 50.0,
            navDate = navDate
        )

        assertEquals(55.0, est!!, 0.000001)
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

    @Test
    fun `missing nav tries historical adjusted close route before quote previous close`() {
        val stockDate = LocalDate.of(2026, 6, 15)
        val referenceDate = LocalDate.of(2026, 6, 12)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { sym ->
                when (sym) {
                    "SPY" -> quote("SPY", stockDate, mark = 120.0, previousClose = 90.0)
                    else -> null
                }
            },
            historicalPriceProvider = { sym, start, end ->
                when {
                    sym == "CTA" && start == stockDate.minusDays(10) && end == stockDate.minusDays(1) ->
                        mapOf(referenceDate to 50.0)
                    sym == "CTA" && start == referenceDate && end == referenceDate ->
                        mapOf(referenceDate to 50.0)
                    sym == "SPY" && start == referenceDate && end == referenceDate ->
                        mapOf(referenceDate to 100.0)
                    else -> emptyMap()
                }
            }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote("CTA", stockDate, mark = 60.0, previousClose = 49.0),
            nav = null,
            navDate = null
        )

        assertEquals(60.0, est!!, 0.000001)
    }

    @Test
    fun `nav date is compared with mark price date when available`() {
        val navDate = LocalDate.of(2026, 6, 15)
        val tradingPeriodDate = LocalDate.of(2026, 6, 16)
        val calculator = LetfEstPriceCalculator(
            quoteProvider = { error("Reference quote should not be needed when mark price date equals NAV date") },
            historicalPriceProvider = { _, _, _ -> error("History should not be needed when mark price date equals NAV date") }
        )

        val est = calculator.compute(
            components = listOf(1.0 to "SPY"),
            quote = quote(
                "CTA",
                tradingPeriodDate,
                mark = 51.0,
                previousClose = 49.0,
                markPriceDate = navDate
            ),
            nav = 50.0,
            navDate = navDate
        )

        assertEquals(50.0, est!!, 0.000001)
    }

    private fun quote(
        symbol: String,
        tradingDate: LocalDate,
        mark: Double?,
        previousClose: Double,
        markPriceDate: LocalDate? = null,
        isMarketClosed: Boolean = false,
        offset: ZoneOffset = ZoneOffset.ofHours(-4),
        closeTime: LocalTime = LocalTime.of(16, 0)
    ): YahooQuote {
        val tradingEnd = tradingDate.atTime(closeTime).toEpochSecond(offset)
        return YahooQuote(
            symbol = symbol,
            regularMarketPrice = mark,
            previousClose = previousClose,
            tradingPeriodEnd = tradingEnd,
            gmtoffset = offset.totalSeconds,
            isMarketClosed = isMarketClosed,
            markPriceDate = markPriceDate
        )
    }
}
