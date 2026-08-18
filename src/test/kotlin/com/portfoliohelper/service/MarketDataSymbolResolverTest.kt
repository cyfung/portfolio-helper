package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals

class MarketDataSymbolResolverTest {
    @Test
    fun `numeric broker symbol is padded to the exchange minimum before suffix is appended`() {
        val resolved = MarketDataSymbolResolver.resolve(
            symbol = "50",
            exchange = "SEHK",
            exchangeSuffixes = mapOf("SEHK" to ".HK"),
            exchangeSymbolMinWidths = mapOf("SEHK" to 4),
        )

        assertEquals("0050.HK", resolved)
    }

    @Test
    fun `minimum width preserves longer and nonnumeric symbols`() {
        val suffixes = mapOf("SEHK" to ".HK")
        val minimumWidths = mapOf("SEHK" to 4)

        assertEquals("80700.HK", MarketDataSymbolResolver.resolve("80700", "SEHK", suffixes, minimumWidths))
        assertEquals("A50.HK", MarketDataSymbolResolver.resolve("A50", "SEHK", suffixes, minimumWidths))
    }

    @Test
    fun `exchange normalization and symbol rules are independent`() {
        assertEquals(
            "0050",
            MarketDataSymbolResolver.resolve(
                symbol = " 50 ",
                exchange = " sehk ",
                exchangeSuffixes = emptyMap(),
                exchangeSymbolMinWidths = mapOf("SEHK" to 4),
            ),
        )
        assertEquals(
            "50.HK",
            MarketDataSymbolResolver.resolve(
                symbol = "50",
                exchange = "SEHK",
                exchangeSuffixes = mapOf("SEHK" to ".HK"),
                exchangeSymbolMinWidths = emptyMap(),
            ),
        )
    }
}
