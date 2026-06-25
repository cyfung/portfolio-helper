package com.portfoliohelper.service.nav

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImgpFundNavProviderTest {
    @Test
    fun `parses DBMF NAV by ISIN from iMGP fund list`() {
        val html = """
            <html>
              <body>
                <a>
                  Share Class Name: iMGP DBi Managed Futures Fund R EUR UCITS ETF
                  ISIN: LU2951555403
                  Inception Date: Mar 24, 2025
                  Inception Date: EUR 104.83 as of Nov 07, 2025
                </a>
                <a>
                  Share Class Name: iMGP DBi Managed Futures Fund R USD UCITS ETF
                  ISIN: LU2951555585
                  Inception Date: Mar 07, 2025
                  Inception Date: USD 112.81 as of Nov 07, 2025
                </a>
              </body>
            </html>
        """.trimIndent()

        val nav = parseImgpFundNav(html, "LU2951555585")

        assertEquals(112.81, nav?.nav)
        assertEquals(LocalDate.of(2025, 11, 7), nav?.asOfDate)
    }

    @Test
    fun `parses DBMFE NAV by ISIN from iMGP fund list`() {
        val html = """
            <html>
              <body>
                <a>
                  Share Class Name: iMGP DBi Managed Futures Fund R EUR UCITS ETF
                  ISIN: LU2951555403
                  Inception Date: Mar 24, 2025
                  Inception Date: EUR 104.83 as of Nov 07, 2025
                </a>
                <a>
                  Share Class Name: iMGP DBi Managed Futures Fund R USD UCITS ETF
                  ISIN: LU2951555585
                  Inception Date: Mar 07, 2025
                  Inception Date: USD 112.81 as of Nov 07, 2025
                </a>
              </body>
            </html>
        """.trimIndent()

        val nav = parseImgpFundNav(html, "LU2951555403")

        assertEquals(104.83, nav?.nav)
        assertEquals(LocalDate.of(2025, 11, 7), nav?.asOfDate)
    }

    @Test
    fun `does not borrow price from next share class when selected ISIN has no NAV`() {
        val html = """
            <html>
              <body>
                <a>
                  Share Class Name: iMGP DBi Managed Futures Fund R EUR UCITS ETF
                  ISIN: LU2951555403
                  Inception Date: Mar 24, 2025
                  Inception Date: -
                </a>
                <a>
                  Share Class Name: iMGP DBi Managed Futures Fund R USD UCITS ETF
                  ISIN: LU2951555585
                  Inception Date: Mar 07, 2025
                  Inception Date: USD 112.81 as of Nov 07, 2025
                </a>
              </body>
            </html>
        """.trimIndent()

        assertNull(parseImgpFundNav(html, "LU2951555403"))
    }
}
