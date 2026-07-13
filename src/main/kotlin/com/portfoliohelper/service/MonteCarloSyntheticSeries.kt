package com.portfoliohelper.service

import java.time.LocalDate

internal object MonteCarloSyntheticSeries {
    fun tradingDates(count: Int): List<LocalDate> {
        val dates = ArrayList<LocalDate>(count)
        var date = LocalDate.of(2000, 1, 3)
        while (dates.size < count) {
            if (date.dayOfWeek.value <= 5) dates.add(date)
            date = date.plusDays(1)
        }
        return dates
    }

    fun seriesMap(
        tickers: Set<String>,
        path: List<AssembledDay>,
        dates: List<LocalDate>
    ): Map<String, Map<LocalDate, Double>> {
        require(dates.size == path.size + 1) { "Synthetic date count must equal path size + 1" }
        val values = tickers.associateWith { 100.0 }.toMutableMap()
        val series = tickers.associateWith { linkedMapOf(dates.first() to 100.0) }
        path.forEachIndexed { index, day ->
            val date = dates[index + 1]
            for (ticker in tickers) {
                val ret = if (day.isChunkBoundary) 1.0 else (day.tickerReturns[ticker] ?: 1.0)
                val next = (values[ticker] ?: 100.0) * ret
                values[ticker] = next
                series[ticker]?.put(date, next)
            }
        }
        return series
    }

    fun effrxSeries(path: List<AssembledDay>, dates: List<LocalDate>): Map<LocalDate, Double> {
        require(dates.size == path.size + 1) { "Synthetic date count must equal path size + 1" }
        val series = linkedMapOf(dates.first() to 100.0)
        var value = 100.0
        path.forEachIndexed { index, day ->
            value *= 1.0 + if (day.isChunkBoundary) 0.0 else day.effrxRate
            series[dates[index + 1]] = value
        }
        return series
    }
}
