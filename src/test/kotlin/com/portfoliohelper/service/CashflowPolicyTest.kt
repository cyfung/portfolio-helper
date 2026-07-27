package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashflowPolicyTest {
    private fun guardrail(
        frequency: CashflowFrequency = CashflowFrequency.MONTHLY,
        minimum: Double? = null,
    ) = CashflowConfig(
        amount = 0.0,
        frequency = frequency,
        mode = CashflowMode.GUARDRAIL_WITHDRAWAL,
        initialAnnualWithdrawal = 12_000.0,
        lowerWithdrawalRate = 0.03,
        upperWithdrawalRate = 0.06,
        minimumAnnualWithdrawal = minimum,
    )

    @Test
    fun `guardrail annual withdrawal is distributed by payment frequency`() {
        assertEquals(-1_000.0, CashflowPolicy.guardrailPayment(guardrail(), 12_000.0))
        assertEquals(-3_000.0, CashflowPolicy.guardrailPayment(guardrail(CashflowFrequency.QUARTERLY), 12_000.0))
        assertEquals(-12_000.0, CashflowPolicy.guardrailPayment(guardrail(CashflowFrequency.YEARLY), 12_000.0))
    }

    @Test
    fun `annual review applies inflation then one lower guardrail adjustment then minimum`() {
        val reviewed = CashflowPolicy.reviewedAnnualWithdrawal(
            config = guardrail(minimum = 13_000.0),
            priorAnnualWithdrawal = 10_000.0,
            portfolioValueBeforeWithdrawal = 1_000_000.0,
            completedYearRealInvestmentReturn = 0.01,
            completedYearInflationRate = 0.02,
        )

        assertEquals(13_000.0, reviewed)
    }

    @Test
    fun `annual review skips inflation after non-positive real return and applies one upper adjustment`() {
        val reviewed = CashflowPolicy.reviewedAnnualWithdrawal(
            config = guardrail(),
            priorAnnualWithdrawal = 12_000.0,
            portfolioValueBeforeWithdrawal = 100_000.0,
            completedYearRealInvestmentReturn = 0.0,
            completedYearInflationRate = 0.05,
        )

        assertEquals(10_800.0, reviewed)
    }

    @Test
    fun `guardrail boundaries are inclusive and deflation is symmetric after positive real return`() {
        val config = guardrail()
        assertEquals(
            12_000.0,
            CashflowPolicy.reviewedAnnualWithdrawal(config, 12_000.0, 400_000.0, 0.01, 0.0),
        )
        assertEquals(
            12_000.0,
            CashflowPolicy.reviewedAnnualWithdrawal(config, 12_000.0, 200_000.0, 0.01, 0.0),
        )
        assertEquals(
            11_400.0,
            CashflowPolicy.reviewedAnnualWithdrawal(config, 12_000.0, 300_000.0, 0.01, -0.05),
        )
    }

    @Test
    fun `withdrawal is clamped and depletion is absorbing`() {
        val (depleted, applied) = CashflowPolicy.applyToPortfolio(500.0, -1_000.0)
        val (stillDepleted, ignoredContribution) = CashflowPolicy.applyToPortfolio(depleted, 1_000.0)

        assertEquals(0.0, depleted)
        assertEquals(-500.0, applied)
        assertEquals(0.0, stillDepleted)
        assertEquals(0.0, ignoredContribution)
    }

    @Test
    fun `invalid guardrail values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            guardrail().copy(initialAnnualWithdrawal = 0.0).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            guardrail().copy(lowerWithdrawalRate = -0.01).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            guardrail().copy(upperWithdrawalRate = 0.03).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            guardrail().copy(minimumAnnualWithdrawal = -1.0).validate()
        }
    }

    @Test
    fun `runtime reviews on simulation-start anniversary before its withdrawal`() {
        val dates = listOf(
            LocalDate.of(2024, 7, 15),
            LocalDate.of(2024, 8, 1),
            LocalDate.of(2025, 7, 15),
        )
        val runtime = CashflowRuntime(guardrail(CashflowFrequency.MONTHLY), dates, listOf(1.0, 1.0, 1.02))

        assertEquals(-1_000.0, runtime.requestedCashflow(1, 1_000_000.0, 1.0))
        assertEquals(-1_122.0, runtime.requestedCashflow(2, 1_000_000.0, 1.05), 1e-9)
    }

    @Test
    fun `monte carlo path uses anniversary review for next yearly withdrawal`() {
        val dates = listOf(
            LocalDate.of(2024, 7, 15),
            LocalDate.of(2025, 1, 2),
            LocalDate.of(2025, 7, 15),
            LocalDate.of(2026, 1, 2),
        )
        val runtime = MonteCarloSimplePortfolioRuntime(
            tickers = listOf("TEST"),
            targetWeightMap = mapOf("TEST" to 1.0),
            returnIndexes = intArrayOf(0),
            weights = doubleArrayOf(1.0),
            rebalanceStrategy = RebalanceStrategy.NONE,
        )
        val values = MonteCarloIndexedSimulation.simulate(
            runtime = runtime,
            mc = null,
            path = MonteCarloIndexedPath(intArrayOf(0, 1, 2)),
            tickerReturnsByDay = arrayOf(doubleArrayOf(1.0), doubleArrayOf(1.1), doubleArrayOf(1.0)),
            effrxDailyRates = doubleArrayOf(0.0, 0.0, 0.0),
            startingBalance = 100_000.0,
            rebalanceFlags = BooleanArray(dates.size),
            cashflowConfig = guardrail(CashflowFrequency.YEARLY),
            dates = dates,
            inflationFactors = listOf(1.0, 1.01, 1.02, 1.02),
        )
        val marginValues = MonteCarloIndexedSimulation.simulate(
            runtime = runtime,
            mc = MarginConfig(0.0, 0.0, 1.0, 1.0),
            path = MonteCarloIndexedPath(intArrayOf(0, 1, 2)),
            tickerReturnsByDay = arrayOf(doubleArrayOf(1.0), doubleArrayOf(1.1), doubleArrayOf(1.0)),
            effrxDailyRates = doubleArrayOf(0.0, 0.0, 0.0),
            startingBalance = 100_000.0,
            rebalanceFlags = BooleanArray(dates.size),
            cashflowConfig = guardrail(CashflowFrequency.YEARLY),
            dates = dates,
            inflationFactors = listOf(1.0, 1.01, 1.02, 1.02),
        )

        assertEquals(85_784.0, values.last(), 1e-9)
        assertEquals(values.last(), marginValues.last(), 1e-9)
    }
}
