package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InflationSeriesTest {
    @Test
    fun `interpolates monthly CPI and carries the latest observation forward`() {
        val observations = sortedMapOf(
            LocalDate.parse("2024-01-01") to 100.0,
            LocalDate.parse("2024-02-01") to 103.1,
        )

        val factors = InflationSeries.factorsFor(
            listOf(
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-01-16"),
                LocalDate.parse("2024-02-01"),
                LocalDate.parse("2024-03-01"),
            ),
            observations,
        )

        assertTrue(factors.available)
        assertEquals(listOf(1.0, 1.015, 1.031, 1.031), factors.factors.map { round(it, 3) })
    }

    @Test
    fun `rejects inflation adjustment when results begin before CPI coverage`() {
        val factors = InflationSeries.factorsFor(
            listOf(LocalDate.parse("1946-12-31"), LocalDate.parse("1947-01-02")),
            sortedMapOf(LocalDate.parse("1947-01-01") to 21.48),
        )

        assertFalse(factors.available)
        assertTrue(factors.reason.orEmpty().contains("1947-01-01"))
        assertEquals(emptyList(), factors.factors)
    }

    @Test
    fun `Monte Carlo samples inflation with chunks and does not add a boundary return`() {
        val factors = MonteCarloIndexedSimulation.inflationFactors(
            MonteCarloIndexedPath(intArrayOf(0, 1, -1, 2)),
            doubleArrayOf(0.01, 0.02, 0.03),
        )

        assertEquals(listOf(1.0, 1.01, 1.0302, 1.0302, 1.061106), factors.map { round(it, 6) })
    }

    @Test
    fun `backtest result reports values and cash flows in start-date purchasing power`() {
        val nominal = MultiBacktestResult(
            portfolios = listOf(
                PortfolioResult(
                    "Portfolio",
                    listOf(
                        CurveResult(
                            "No margin",
                            listOf(
                                DataPoint("2024-01-01", 10_000.0),
                                DataPoint("2025-01-01", 11_000.0),
                            ),
                            BacktestStats(0.1, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 11_000.0),
                        ),
                    ),
                ),
            ),
        )

        val adjusted = InflationAdjustment.backtestResult(
            nominal,
            effrx = emptyMap(),
            observations = sortedMapOf(
                LocalDate.parse("2024-01-01") to 100.0,
                LocalDate.parse("2025-01-01") to 110.0,
            ),
        )

        val curve = adjusted.inflationAdjusted!!.portfolios.single().curves.single()
        assertEquals(10_000.0, curve.points.first().value)
        assertEquals(10_000.0, curve.points.last().value)
        assertEquals(0.0, round(curve.stats.cagr, 9))
    }

    private fun round(value: Double, decimals: Int): Double {
        val scale = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * scale) / scale
    }
}
