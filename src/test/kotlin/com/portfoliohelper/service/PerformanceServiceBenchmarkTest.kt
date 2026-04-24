package com.portfoliohelper.service

import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class PerformanceServiceBenchmarkTest {

    /** Builds N days of synthetic snapshots with a deposit every 90 days. */
    private fun buildSnapshots(days: Int): List<PortfolioSnapshotRepository.FullSnapshot> {
        val start = LocalDate.of(2021, 1, 1)
        var nav = 100_000.0
        return (0..days).map { i ->
            val date = start.plusDays(i.toLong()).toString()
            nav *= 1.0003  // ~11% annual growth
            val deposit = if (i > 0 && i % 90 == 0) 10_000.0 else 0.0
            nav += deposit
            val cashFlows = if (deposit != 0.0)
                listOf(CashFlowEntry(fxRateToBase = 1.0, amount = deposit, type = "Deposits/Withdrawals"))
            else emptyList()
            PortfolioSnapshotRepository.FullSnapshot(
                header = PortfolioSnapshotRepository.SnapshotRow(
                    id = i, portfolioId = 1, date = date,
                    netLiqValue = nav, cashBase = -5_000.0, contentHash = ""
                ),
                positions = emptyList(),   // no positions → skips Yahoo Finance fetch
                cashFlows = cashFlows
            )
        }
    }

    @Test
    fun `buildChartData with 252 days completes under 500ms`() {
        val snapshots = buildSnapshots(252)
        val elapsed = runBlocking {
            val t0 = System.currentTimeMillis()
            PerformanceService.buildChartData(snapshots)
            System.currentTimeMillis() - t0
        }
        println("buildChartData (252 days, MWR): ${elapsed}ms")
        assertTrue(elapsed < 500L, "Expected < 500ms but took ${elapsed}ms")
    }

    @Test
    fun `buildChartData with 756 days (3Y) completes under 1000ms`() {
        val snapshots = buildSnapshots(756)
        val elapsed = runBlocking {
            val t0 = System.currentTimeMillis()
            PerformanceService.buildChartData(snapshots)
            System.currentTimeMillis() - t0
        }
        println("buildChartData (756 days, MWR): ${elapsed}ms")
        assertTrue(elapsed < 1000L, "Expected < 1000ms but took ${elapsed}ms")
    }
}
