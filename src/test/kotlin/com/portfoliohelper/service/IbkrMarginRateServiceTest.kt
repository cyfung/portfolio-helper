package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbkrMarginRateServiceTest {
    @Test
    fun `margin-rate page supplies tier spreads rather than charged rates`() {
        val html = """
            <table><thead><tr><th>Currency</th><th>Tier</th><th>Rate Charged:</th></tr></thead><tbody>
              <tr><td>USD</td><td>0 &lt;= 100,000</td><td>6.830% (BM + 1.5%)</td></tr>
              <tr><td></td><td>100,000 &lt;= 1,000,000</td><td>BM + 1%</td></tr>
            </tbody></table>
        """.trimIndent()
        val parsed = IbkrMarginRateService.parseTierSpreadsFromHtml(html)
        assertTrue(parsed.tableFound)
        assertTrue(parsed.currencyErrors.isEmpty())
        assertEquals(1.5, parsed.spreads.getValue("USD")[0].spread)
        assertEquals(100_000.0, parsed.spreads.getValue("USD")[0].upTo)
        assertEquals(1.0, parsed.spreads.getValue("USD")[1].spread)
    }

    @Test
    fun `monthly history uses newest non-carried-forward benchmark per currency`() {
        val html = """
            <table><thead><tr><th>Date</th><th>USD</th><th>HKD</th></tr></thead><tbody>
              <tr><td>20260910</td><td>4.250%</td><td>3.125%</td></tr>
              <tr><td>20260911</td><td>4.375*%</td><td>3.250%</td></tr>
            </tbody></table>
        """.trimIndent()
        val rates = IbkrMarginRateService.parseMonthlyBenchmarksFromHtml(html)
        assertEquals(IbkrMarginRateService.BenchmarkRate(4.250, LocalDate.of(2026, 9, 10), "monthly-interest-rates"), rates["USD"])
        assertEquals(IbkrMarginRateService.BenchmarkRate(3.250, LocalDate.of(2026, 9, 11), "monthly-interest-rates"), rates["HKD"])
    }

    @Test
    fun `benchmark page parses signed rates and effective dates`() {
        val html = """
            <table><thead><tr><th>Currency</th><th>Description for Effective Date</th><th>Rate</th><th>Effective Date</th></tr></thead><tbody>
              <tr><td>USD</td><td>USD benchmark</td><td>4.375%</td><td>20260912</td></tr>
              <tr><td>JPY</td><td>JPY benchmark</td><td>(0.125)%</td><td>20260909</td></tr>
            </tbody></table>
        """.trimIndent()
        val rates = IbkrMarginRateService.parseEffectiveBenchmarksFromHtml(html)
        assertEquals(4.375, rates.getValue("USD").rate)
        assertEquals(-0.125, rates.getValue("JPY").rate)
        assertEquals(LocalDate.of(2026, 9, 12), rates.getValue("USD").effectiveDate)
    }

    @Test
    fun `newest benchmark wins and resolves every tier`() {
        val monthly = mapOf("USD" to IbkrMarginRateService.BenchmarkRate(4.25, LocalDate.of(2026, 9, 10), "monthly-interest-rates"))
        val effective = mapOf("USD" to IbkrMarginRateService.BenchmarkRate(4.375, LocalDate.of(2026, 9, 12), "margin-benchmarks"))
        val spreads = IbkrMarginRateService.TierSpreadParseResult(
            mapOf("USD" to listOf(IbkrMarginRateService.TierSpread(100_000.0, 1.5), IbkrMarginRateService.TierSpread(null, 1.0))), emptyMap(), true
        )
        val resolved = IbkrMarginRateService.resolveRates(spreads, listOf(monthly, effective))
        val usd = resolved.rates.getValue("USD")
        assertEquals(LocalDate.of(2026, 9, 12), usd.benchmarkEffectiveDate)
        assertEquals(5.875, usd.tiers[0].rate)
        assertEquals(5.375, usd.tiers[1].rate)
    }

    @Test
    fun `same-date benchmark conflict is reported per currency`() {
        val date = LocalDate.of(2026, 9, 12)
        val spreads = IbkrMarginRateService.TierSpreadParseResult(
            mapOf("USD" to listOf(IbkrMarginRateService.TierSpread(null, 1.5))), emptyMap(), true
        )
        val resolved = IbkrMarginRateService.resolveRates(
            spreads,
            listOf(
                mapOf("USD" to IbkrMarginRateService.BenchmarkRate(4.25, date, "monthly-interest-rates")),
                mapOf("USD" to IbkrMarginRateService.BenchmarkRate(4.375, date, "margin-benchmarks"))
            )
        )
        assertTrue("USD" !in resolved.rates)
        assertTrue(resolved.currencyErrors.getValue("USD").contains("conflict", ignoreCase = true))
    }
}
