package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BacktestLetfDefinitionTest {
    @Test
    fun parseLETFDefinition_acceptsNegativeExpenseRatio() {
        val def = assertNotNull(BacktestService.parseLETFDefinition("2 QQQ E=-1.5"))

        assertEquals(-0.015, def.expenseRatio)
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
}
