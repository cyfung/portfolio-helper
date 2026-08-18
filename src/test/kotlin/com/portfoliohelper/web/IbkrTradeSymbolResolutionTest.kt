package com.portfoliohelper.web

import com.portfoliohelper.service.IbkrTradeEntry
import com.portfoliohelper.tws.TwsExecution
import kotlin.test.Test
import kotlin.test.assertEquals

class IbkrTradeSymbolResolutionTest {
    @Test
    fun `TWS executions and Flex trades expose market-data symbols`() {
        val suffixes = mapOf("SEHK" to ".HK")
        val minimumWidths = mapOf("SEHK" to 4)
        val twsTrade = toIbkrTradeDto(
            index = 0,
            execution = twsExecution("50"),
            exchangeSuffixes = suffixes,
            exchangeSymbolMinWidths = minimumWidths,
        )
        val flexTrade = toIbkrTradeDto(
            index = 1,
            trade = flexTrade("50"),
            exchangeSuffixes = suffixes,
            exchangeSymbolMinWidths = minimumWidths,
        )

        assertEquals("0050.HK", twsTrade.symbol)
        assertEquals("0050.HK", flexTrade.symbol)
    }

    private fun twsExecution(symbol: String) = TwsExecution(
        execId = "exec-1",
        time = "20260818 12:00:00",
        account = "U1",
        symbol = symbol,
        secType = "STK",
        exchange = "SEHK",
        currency = "HKD",
        side = "BOT",
        shares = 1.0,
        price = 10.0,
        orderId = 1,
    )

    private fun flexTrade(symbol: String) = IbkrTradeEntry(
        tradeKey = "trade-1",
        tradeDate = "2026-08-18",
        tradeTime = "12:00:00",
        symbol = symbol,
        side = "BUY",
        quantity = 1.0,
        price = 10.0,
        currency = "HKD",
        exchange = "SEHK",
        assetCategory = "STK",
        commission = null,
        commissionCurrency = null,
        realizedPnl = null,
    )
}
