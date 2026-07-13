package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals

class FlexTradesParserTest {
    @Test
    fun `trade cancellations remove the cancelled original and keep replacement for buys and sells`() {
        val xml = """
            <FlexQueryResponse>
              <FlexStatements>
                <FlexStatement>
                  <Trades>
                    <Trade tradeDate="20260709" dateTime="20260708;213145" symbol="0050" buySell="BUY"
                           quantity="1000" tradePrice="106.35" currency="TWD" exchange="TWSE" assetCategory="STK"
                           tradeID="881002280" transactionID="4792349010" ibExecID="BUY-EXEC-1" transactionType="ExchTrade" />
                    <Trade tradeDate="20260709" dateTime="20260708;213145" symbol="0050" buySell="BUY (Ca.)"
                           quantity="-1000" tradePrice="106.35" currency="TWD" exchange="--" assetCategory="STK"
                           transactionID="4794629403" origTradeID="881002280" origTransactionID="4792349010"
                           transactionType="TradeCancel" notes="Ca" />
                    <Trade tradeDate="20260709" dateTime="20260708;213145" symbol="0050" buySell="BUY"
                           quantity="1000" tradePrice="106.35" currency="TWD" exchange="TWSE" assetCategory="STK"
                           tradeID="881002280" transactionID="4794629404" ibExecID="BUY-EXEC-1" transactionType="ExchTrade" />

                    <Trade tradeDate="20260709" dateTime="20260709;145017" symbol="AVUV" buySell="SELL"
                           quantity="-15" tradePrice="123.39" currency="USD" exchange="ARCA" assetCategory="STK"
                           tradeID="881100001" transactionID="4793000001" ibExecID="SELL-EXEC-1" transactionType="ExchTrade" />
                    <Trade tradeDate="20260709" dateTime="20260709;145017" symbol="AVUV" buySell="SELL (Ca.)"
                           quantity="15" tradePrice="123.39" currency="USD" exchange="--" assetCategory="STK"
                           transactionID="4793000002" origTradeID="881100001" origTransactionID="4793000001"
                           transactionType="TradeCancel" notes="Ca" />
                    <Trade tradeDate="20260709" dateTime="20260709;145017" symbol="AVUV" buySell="SELL"
                           quantity="-15" tradePrice="123.39" currency="USD" exchange="ARCA" assetCategory="STK"
                           tradeID="881100001" transactionID="4793000003" ibExecID="SELL-EXEC-1" transactionType="ExchTrade" />
                  </Trades>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
        """.trimIndent()

        val trades = FlexTradesParser.parse(xml)

        assertEquals(2, trades.size)
        assertEquals(listOf("0050" to "BUY", "AVUV" to "SELL"), trades.map { it.symbol to it.side })
        assertEquals(listOf(1000.0, -15.0), trades.map { it.quantity })
    }
}
