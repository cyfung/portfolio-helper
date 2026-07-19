package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class StatsUtilsTest {
    private fun assertApprox(expected: Double, actual: Double, eps: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= eps, "Expected $expected but was $actual (eps=$eps)")
    }

    @Test
    fun cashflowAdjustedCagrDoesNotCountFlatPortfolioDepositsAsReturn() {
        val values = listOf(100.0, 100.0, 200.0, 200.0)
        val cashflows = listOf(0.0, 0.0, 100.0, 0.0)

        val stats = computeStats(values, years = 1.0, rfAnnualized = 0.0, cashflows = cashflows)

        assertApprox(0.0, stats.cagr, eps = 1e-8)
        assertApprox(0.0, stats.annualVolatility, eps = 1e-8)
        assertApprox(0.0, stats.sharpe, eps = 1e-8)
    }

    @Test
    fun cashflowDateUsesFirstTradingDateAfterPeriodChange() {
        val dates = listOf(
            LocalDate.of(2026, 1, 30),
            LocalDate.of(2026, 2, 2),
            LocalDate.of(2026, 2, 3),
        )
        val cashflow = CashflowConfig(100.0, CashflowFrequency.MONTHLY)

        val amounts = BacktestService.cashflowAmounts(dates, cashflow)

        assertApprox(0.0, amounts[0])
        assertApprox(100.0, amounts[1])
        assertApprox(0.0, amounts[2])
    }

    @Test
    fun monteCarloCashflowAdjustedCagrDoesNotCountFlatPortfolioDepositsAsReturn() {
        val values = doubleArrayOf(100.0, 100.0, 200.0, 200.0)
        val cashflows = doubleArrayOf(0.0, 0.0, 100.0, 0.0)

        val stats = MonteCarloIndexedSimulation.computeStats(
            values,
            years = 1.0,
            rfAnnualized = 0.0,
            cashflows = cashflows,
        )

        assertApprox(0.0, stats.cagr, eps = 1e-8)
        assertApprox(0.0, stats.annualVolatility, eps = 1e-8)
        assertApprox(0.0, stats.sharpe, eps = 1e-8)
    }
}
