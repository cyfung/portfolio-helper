package com.portfoliohelper.data.repository

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbkrRateFetcherTest {
    @Test
    fun `formula spreads and newest non-starred benchmark resolve a margin rate`() {
        val tierHtml = """
            <table><thead><tr><th>Currency</th><th>Tier</th><th>Rate Charged:</th></tr></thead><tbody>
              <tr><td>USD</td><td>0 &lt;= 100,000</td><td>6.830% (BM + 1.5%)</td></tr>
            </tbody></table>
        """.trimIndent()
        val monthlyHtml = """
            <table><thead><tr><th>Date</th><th>USD</th></tr></thead><tbody>
              <tr><td>20260910</td><td>4.250%</td></tr>
              <tr><td>20260911</td><td>4.375*%</td></tr>
            </tbody></table>
        """.trimIndent()

        val spreads = IbkrRateFetcher.parseTierSpreadsFromHtml(tierHtml)
        val benchmarks = IbkrRateFetcher.parseMonthlyBenchmarksFromHtml(monthlyHtml)
        val resolved = IbkrRateFetcher.resolveRates(spreads, listOf(benchmarks)).rates.getValue("USD")

        assertEquals(1.5, spreads.spreads.getValue("USD").single().spread)
        assertEquals(5.75, resolved.firstTierResolvedMarginRate)
        assertEquals(LocalDate.of(2026, 9, 10), resolved.benchmarkEffectiveDate)
    }

    @Test
    fun `newer effective benchmark page wins over monthly history`() {
        val effectiveHtml = """
            <table><thead><tr><th>Currency</th><th>Description for Effective Date</th><th>Rate</th><th>Effective Date</th></tr></thead><tbody>
              <tr><td>USD</td><td>USD benchmark</td><td>4.375%</td><td>20260912</td></tr>
            </tbody></table>
        """.trimIndent()
        val spreads = IbkrRateFetcher.TierSpreadParseResult(
            mapOf("USD" to listOf(IbkrTierSpread(null, 1.5))), emptyMap(), true
        )
        val monthly = mapOf("USD" to IbkrBenchmarkRate(4.25, LocalDate.of(2026, 9, 10), "monthly-interest-rates"))
        val effective = IbkrRateFetcher.parseEffectiveBenchmarksFromHtml(effectiveHtml)

        val usd = IbkrRateFetcher.resolveRates(spreads, listOf(monthly, effective)).rates.getValue("USD")

        assertEquals(LocalDate.of(2026, 9, 12), usd.benchmarkEffectiveDate)
        assertEquals(5.875, usd.firstTierResolvedMarginRate)
    }

    @Test
    fun `same-date conflict prevents resolving the affected currency`() {
        val date = LocalDate.of(2026, 9, 12)
        val spreads = IbkrRateFetcher.TierSpreadParseResult(
            mapOf("USD" to listOf(IbkrTierSpread(null, 1.5))), emptyMap(), true
        )
        val resolved = IbkrRateFetcher.resolveRates(spreads, listOf(
            mapOf("USD" to IbkrBenchmarkRate(4.25, date, "monthly-interest-rates")),
            mapOf("USD" to IbkrBenchmarkRate(4.375, date, "margin-benchmarks"))
        ))

        assertTrue("USD" !in resolved.rates)
        assertTrue(resolved.currencyErrors.getValue("USD").contains("conflict", true))
    }
}
