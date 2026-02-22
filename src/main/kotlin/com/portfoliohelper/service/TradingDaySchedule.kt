package com.portfoliohelper.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Computes the next NAV fetch time based on US trading day boundaries.
 *
 * NAV for a given trading day is expected to be published by:
 *   tradingDayStart (9:30 AM ET) + 24 hours
 *
 * Logic:
 *   1. Find the most recent trading-day 9:30 AM ET that has already passed.
 *   2. Add 24 hours → candidateTime.
 *   3. If candidateTime is still in the future → schedule there.
 *   4. Otherwise → advance to the next trading day's 9:30 AM ET + 24 hours.
 *
 * "Trading day" is defined as Mon–Fri (holidays not modelled).
 */
object TradingDaySchedule {

    private val ET = ZoneId.of("America/New_York")
    private val MARKET_OPEN = LocalTime.of(9, 30)
    private val LOG_FMT = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss z")

    /** Milliseconds to delay until the next scheduled NAV fetch. Always ≥ 0. */
    fun nextNavFetchDelayMs(): Long {
        val next = computeNextFetchTime()
        val delayMs = next.toInstant().toEpochMilli() - System.currentTimeMillis()
        return maxOf(delayMs, 0L)
    }

    /** Human-readable description of when the next fetch is scheduled. */
    fun describeNextFetch(): String = LOG_FMT.format(computeNextFetchTime())

    fun computeNextFetchTime(now: ZonedDateTime = ZonedDateTime.now(ET)): ZonedDateTime {
        val lastStart = mostRecentTradingDayStart(now)
        val candidate = lastStart.plusHours(24)
        return if (candidate.isAfter(now)) candidate
        else nextTradingDayStart(lastStart.toLocalDate().plusDays(1)).plusHours(24)
    }

    /**
     * Returns the most recent weekday 9:30 AM ET that is <= now.
     * If today is a weekday but the 9:30 AM mark hasn't been reached yet,
     * returns the previous trading day's 9:30 AM.
     */
    private fun mostRecentTradingDayStart(now: ZonedDateTime): ZonedDateTime {
        var date = now.toLocalDate()
        // Snap back from weekend to Friday
        date = when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> date.minusDays(1)
            DayOfWeek.SUNDAY   -> date.minusDays(2)
            else               -> date
        }
        val candidateStart = date.atTime(MARKET_OPEN).atZone(ET)
        // If 9:30 AM today hasn't passed yet, back up to the previous trading day
        return if (candidateStart.isAfter(now)) {
            var prev = date.minusDays(1)
            while (prev.dayOfWeek == DayOfWeek.SATURDAY || prev.dayOfWeek == DayOfWeek.SUNDAY) {
                prev = prev.minusDays(1)
            }
            prev.atTime(MARKET_OPEN).atZone(ET)
        } else {
            candidateStart
        }
    }

    /** Returns the first weekday on or after [startDate] at 9:30 AM ET. */
    private fun nextTradingDayStart(startDate: LocalDate): ZonedDateTime {
        var date = startDate
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.plusDays(1)
        }
        return date.atTime(MARKET_OPEN).atZone(ET)
    }
}
