package com.portfoliohelper.service.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimplifyEtfNavProviderTest {
    @Test
    fun `parses nav from current fund overview row layout`() {
        val html = """
            <html>
              <body>
                <div class="c-fund-overview__row d-flex justify-content-between">
                  <div class="c-fund-overview__cell-header">Premium/Discount (%) as of 06/15/2026</div>
                  <div class="c-fund-overview__cell-data">0.37%</div>
                </div>
                <div class="c-fund-overview__row d-flex justify-content-between">
                  <div class="c-fund-overview__cell-header">NAV Per Share as of 06/15/2026</div>
                  <div class="c-fund-overview__cell-data">${'$'}27.76</div>
                </div>
                <div class="c-fund-overview__row d-flex justify-content-between">
                  <div class="c-fund-overview__cell-header">Market Price as of 06/15/2026</div>
                  <div class="c-fund-overview__cell-data">${'$'}27.86</div>
                </div>
              </body>
            </html>
        """.trimIndent()

        val nav = parseSimplifyNav(html)

        assertEquals(27.76, nav?.nav)
        assertEquals("06/15/2026", nav?.asOfDate)
    }

    @Test
    fun `keeps legacy nav heading fallback`() {
        val html = """
            <html>
              <body>
                <h3>NAV</h3>
                <p>${'$'}1,234.56</p>
              </body>
            </html>
        """.trimIndent()

        val nav = parseSimplifyNav(html)

        assertEquals(1234.56, nav?.nav)
        assertNull(nav?.asOfDate)
    }
}
