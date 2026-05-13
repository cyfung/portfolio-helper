package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbkrMarginRateServiceTest {

    @Test
    fun parserReportsFormulaOnlyBenchmarkRowsByCurrency() {
        val html = """
            <table>
              <thead>
                <tr><th>Currency</th><th>Tier</th><th>Rate Charged:</th></tr>
              </thead>
              <tbody>
                <tr><td>USD</td><td>0 <= 100,000</td><td>BM + 1.5%</td></tr>
                <tr><td></td><td>100,000 <= 1,000,000</td><td>BM + 1%</td></tr>
                <tr><td>AED</td><td>0 <= 350,000</td><td>6.070% (BM + 2.5%)</td></tr>
                <tr><td></td><td>350,000 <= 3,500,000</td><td>5.570% (BM + 2%)</td></tr>
              </tbody>
            </table>
        """.trimIndent()

        val parsed = IbkrMarginRateService.parseRatesFromHtml(html)
        val rates = parsed.rates

        assertTrue(parsed.tableFound)
        assertTrue("USD" in parsed.currencyErrors)
        assertEquals(6.070, rates.getValue("AED").tiers[0].rate)
        assertEquals(350_000.0, rates.getValue("AED").tiers[0].upTo)
        assertEquals(5.570, rates.getValue("AED").tiers[1].rate)
        assertEquals(3_500_000.0, rates.getValue("AED").tiers[1].upTo)
    }

    @Test
    fun parserAcceptsResolvedUsdRows() {
        val html = """
            <table>
              <thead>
                <tr><th>Currency</th><th>Tier</th><th>Rate Charged:</th></tr>
              </thead>
              <tbody>
                <tr><td>USD</td><td>0 <= 100,000</td><td>6.830% (BM + 1.5%)</td></tr>
                <tr><td></td><td>100,000 <= 1,000,000</td><td>6.330% (BM + 1%)</td></tr>
              </tbody>
            </table>
        """.trimIndent()

        val parsed = IbkrMarginRateService.parseRatesFromHtml(html)
        val usd = parsed.rates.getValue("USD")

        assertTrue(parsed.currencyErrors.isEmpty())
        assertEquals(6.830, usd.tiers[0].rate)
        assertEquals(100_000.0, usd.tiers[0].upTo)
        assertEquals(6.330, usd.tiers[1].rate)
        assertEquals(1_000_000.0, usd.tiers[1].upTo)
    }

    @Test
    fun parserDoesNotReportAbsentCurrenciesAsErrors() {
        val html = """
            <table>
              <thead>
                <tr><th>Currency</th><th>Tier</th><th>Rate Charged:</th></tr>
              </thead>
              <tbody>
                <tr><td>AED</td><td>0 <= 350,000</td><td>6.070% (BM + 2.5%)</td></tr>
              </tbody>
            </table>
        """.trimIndent()

        val parsed = IbkrMarginRateService.parseRatesFromHtml(html)

        assertTrue("HKD" !in parsed.rates)
        assertTrue("HKD" !in parsed.currencyErrors)
    }
}
