package com.portfoliohelper.service.nav

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReturnStackedEtfNavProviderTest {
    @Test
    fun `parses nav from fund data pricing section`() {
        val html = """
            <html>
              <body>
                <h2>Fund Data &amp; Pricing</h2>
                <p>As of 06/22/2026</p>
                <table>
                  <tr><th>Name</th><th>Value</th></tr>
                  <tr><td>Net Assets</td><td>${'$'}465.87m</td></tr>
                  <tr><td>NAV</td><td>${'$'}32.87</td></tr>
                  <tr><td>Shares Outstanding</td><td>14,175,000</td></tr>
                </table>
                <h2>Performance</h2>
                <table>
                  <tr><td>RSST NAV</td><td>6.44</td></tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        val nav = parseReturnStackedNav(html)

        assertEquals(32.87, nav?.nav)
        assertEquals(LocalDate.of(2026, 6, 22), nav?.asOfDate)
    }

    @Test
    fun `ignores performance nav rows outside pricing section`() {
        val html = """
            <html>
              <body>
                <h2>Performance</h2>
                <table>
                  <tr><td>RSST NAV</td><td>6.44</td></tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        assertNull(parseReturnStackedNav(html))
    }

    @Test
    fun `rejects pricing nav when date is missing`() {
        val html = """
            <html>
              <body>
                <h2>Fund Data &amp; Pricing</h2>
                <table>
                  <tr><td>NAV</td><td>${'$'}32.87</td></tr>
                </table>
                <h2>Performance</h2>
              </body>
            </html>
        """.trimIndent()

        assertNull(parseReturnStackedNav(html))
    }

    @Test
    fun `parses month-name date from pricing section`() {
        val html = """
            <html>
              <body>
                <h2>Fund Data &amp; Pricing</h2>
                <p>As of June 24, 2026</p>
                <dl>
                  <dt>Net Asset Value</dt>
                  <dd>${'$'}45.12</dd>
                </dl>
                <h2>Performance</h2>
              </body>
            </html>
        """.trimIndent()

        val nav = parseReturnStackedNav(html)

        assertEquals(45.12, nav?.nav)
        assertEquals(LocalDate.of(2026, 6, 24), nav?.asOfDate)
    }

    @Test
    fun `parses shorter pricing heading`() {
        val html = """
            <html>
              <body>
                <h2>Pricing</h2>
                <p>As of 06/24/2026</p>
                <table>
                  <tr><td>NAV</td><td>${'$'}45.12</td></tr>
                </table>
                <h2>Holdings</h2>
              </body>
            </html>
        """.trimIndent()

        val nav = parseReturnStackedNav(html)

        assertEquals(45.12, nav?.nav)
        assertEquals(LocalDate.of(2026, 6, 24), nav?.asOfDate)
    }
}
