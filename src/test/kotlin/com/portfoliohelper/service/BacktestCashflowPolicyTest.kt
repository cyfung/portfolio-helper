package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BacktestCashflowPolicyTest {
    private val portfolio = PortfolioConfig(
        label = "test",
        tickers = listOf(TickerWeight("TEST", 1.0)),
        rebalanceStrategy = RebalanceStrategy.NONE,
        marginStrategies = emptyList(),
    )

    private fun dates(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()

    private fun flatSeries(dates: List<LocalDate>) =
        mapOf("TEST" to dates.associateWith { 1.0 })

    private fun guardrail(frequency: CashflowFrequency, annual: Double = 1_200.0) =
        CashflowConfig(
            frequency = frequency,
            mode = CashflowMode.GUARDRAIL_WITHDRAWAL,
            initialAnnualWithdrawal = annual,
            lowerWithdrawalRate = 0.0,
            upperWithdrawalRate = 1.0,
        )

    @Test
    fun `historical backtest anchors monthly and quarterly guardrail payments to simulation start`() {
        val dates = dates(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 4, 16))
        val monthly = BacktestService.computeNoMarginForTest(
            portfolio, flatSeries(dates), dates, 10_000.0, guardrail(CashflowFrequency.MONTHLY),
        )
        val quarterly = BacktestService.computeNoMarginForTest(
            portfolio, flatSeries(dates), dates, 10_000.0, guardrail(CashflowFrequency.QUARTERLY),
        )

        assertEquals(9_700.0, monthly.last())
        assertEquals(9_700.0, quarterly.last())
    }

    @Test
    fun `fixed cashflow keeps legacy calendar schedule and depletion is absorbing`() {
        val dates = dates(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 5))
        val contribution = BacktestService.computeNoMarginForTest(
            portfolio,
            flatSeries(dates),
            dates,
            10_000.0,
            CashflowConfig(100.0, CashflowFrequency.MONTHLY),
        )
        val withdrawal = BacktestService.computeNoMarginForTest(
            portfolio,
            flatSeries(dates),
            dates,
            10_000.0,
            CashflowConfig(-20_000.0, CashflowFrequency.MONTHLY),
        )

        assertEquals(10_200.0, contribution.last())
        assertEquals(0.0, withdrawal[dates.indexOf(LocalDate.of(2024, 2, 1))])
        assertEquals(0.0, withdrawal.last())
    }

    @Test
    fun `guardrail withdrawal clamps to capital and remains depleted`() {
        val dates = dates(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 20))
        val values = BacktestService.computeNoMarginForTest(
            portfolio,
            flatSeries(dates),
            dates,
            10_000.0,
            guardrail(CashflowFrequency.MONTHLY, annual = 120_000.0),
        )

        assertEquals(0.0, values[dates.indexOf(LocalDate.of(2024, 2, 15))])
        assertEquals(0.0, values.last())
    }
}
