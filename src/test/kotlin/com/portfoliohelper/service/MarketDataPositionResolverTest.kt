package com.portfoliohelper.service

import com.portfoliohelper.tws.StockPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketDataPositionResolverTest {
    @Test
    fun `positions that resolve to the same market-data symbol are merged`() {
        val positions = listOf(
            stockPosition(symbol = "50", quantity = 3.0),
            stockPosition(symbol = "0050", quantity = 2.0),
        )

        val resolved = MarketDataPositionResolver.resolve(
            positions = positions,
            exchangeSuffixes = mapOf("SEHK" to ".HK"),
            exchangeSymbolMinWidths = mapOf("SEHK" to 4),
        )

        assertEquals(listOf(MarketDataPosition("0050.HK", 5.0)), resolved)
    }

    private fun stockPosition(symbol: String, quantity: Double) = StockPosition(
        symbol = symbol,
        exchange = "SEHK",
        currency = "HKD",
        qty = quantity,
        avgCost = 0.0,
        account = "U1",
    )
}
