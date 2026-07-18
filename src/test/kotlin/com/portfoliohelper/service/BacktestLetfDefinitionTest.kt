package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class BacktestLetfDefinitionTest {
    @Test
    fun parseLETFDefinition_preservesZeroModifiers() {
        val def = assertNotNull(BacktestService.parseLETFDefinition("2 QQQ S=0 E=0 VOL=0"))

        assertEquals(0.0, def.spread)
        assertEquals(0.0, def.expenseRatio)
        assertEquals(0.0, def.volatilityAdjustment)
    }

    @Test
    fun parseLETFDefinition_rejectsInvalidModifiers() {
        assertFailsWith<IllegalArgumentException> {
            BacktestService.parseLETFDefinition("2 QQQ S=abc")
        }
        assertFailsWith<IllegalArgumentException> {
            BacktestService.parseLETFDefinition("2 QQQ R=abc")
        }
    }

    @Test
    fun parseLETFDefinition_acceptsNegativeExpenseRatio() {
        val def = assertNotNull(BacktestService.parseLETFDefinition("2 QQQ E=-1.5"))

        assertEquals(-0.015, def.expenseRatio)
    }

    @Test
    fun parseLETFDefinition_acceptsVolatilityAdjustment() {
        val def = assertNotNull(BacktestService.parseLETFDefinition("QQQ V=20"))

        assertEquals(0.20, def.volatilityAdjustment)
        assertEquals(listOf(LETFComponent("QQQ", 1.0)), def.components)
    }

    @Test
    fun computeLetfSeries_appliesNegativeExpenseRatioAsDailyCredit() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val jan2 = LocalDate.of(2026, 1, 2)
        val dates = listOf(jan1, jan2)
        val def = LETFDefinition(
            components = listOf(LETFComponent("QQQ", 1.0)),
            spread = 0.0,
            expenseRatio = -0.015,
        )

        val series = BacktestService.computeLetfSeries(
            def,
            componentSeriesMap = mapOf("QQQ" to mapOf(jan1 to 100.0, jan2 to 100.0)),
            dates = dates,
            effrx = emptyMap(),
            rebalanceStrategy = RebalanceStrategy.QUARTERLY,
        )

        assertEquals(10_000.0, series.getValue(jan1))
        assertEquals(10_000.0 * (1.0 + 0.015 / 252.0), series.getValue(jan2))
    }

    @Test
    fun computeLetfSeries_appliesVolatilityAdjustmentAndPreservesEndingValue() {
        val dates = (0..5).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
        val componentSeries = mapOf(
            "QQQ" to mapOf(
                dates[0] to 100.0,
                dates[1] to 105.0,
                dates[2] to 101.0,
                dates[3] to 112.0,
                dates[4] to 108.0,
                dates[5] to 118.0,
            )
        )
        val baseDef = LETFDefinition(
            components = listOf(LETFComponent("QQQ", 1.0)),
            spread = 0.0,
        )
        val adjustedDef = baseDef.copy(volatilityAdjustment = 0.50)

        val baseSeries = BacktestService.computeLetfSeries(
            baseDef,
            componentSeriesMap = componentSeries,
            dates = dates,
            effrx = emptyMap(),
            rebalanceStrategy = RebalanceStrategy.QUARTERLY,
        )
        val adjustedSeries = BacktestService.computeLetfSeries(
            adjustedDef,
            componentSeriesMap = componentSeries,
            dates = dates,
            effrx = emptyMap(),
            rebalanceStrategy = RebalanceStrategy.QUARTERLY,
        )

        val baseValues = dates.map { baseSeries.getValue(it) }
        val adjustedValues = dates.map { adjustedSeries.getValue(it) }

        assertEquals(baseValues.first(), adjustedValues.first(), absoluteTolerance = 1e-9)
        assertEquals(baseValues.last(), adjustedValues.last(), absoluteTolerance = 1e-7)
        assertEquals(
            annualVolatility(baseValues) * 1.5,
            annualVolatility(adjustedValues),
            absoluteTolerance = 1e-10,
        )
    }

    private fun annualVolatility(values: List<Double>): Double {
        val returns = (1 until values.size).map { i -> values[i] / values[i - 1] - 1.0 }
        val mean = returns.average()
        val variance = returns.sumOf { r -> (r - mean) * (r - mean) } / (returns.size - 1)
        return sqrt(variance) * sqrt(252.0)
    }
}
