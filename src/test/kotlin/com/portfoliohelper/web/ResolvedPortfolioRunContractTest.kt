package com.portfoliohelper.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ResolvedPortfolioRunContractTest {
    private val resolvedPortfolio = """
        {
          "label": "Resolved",
          "tickers": [
            {"ticker": "SPY", "weight": 125.0},
            {"ticker": "0.6 TLT 0.4 GLD", "weight": -25.0}
          ]
        }
    """.trimIndent()

    @Test
    fun `every analysis run boundary accepts resolved signed holding allocations`() {
        PortfolioRunBoundary.entries.forEach { boundary ->
            val portfolios = parseResolvedRunPortfolios(
                Json.parseToJsonElement(boundary.payload(resolvedPortfolio)).jsonObject,
                boundary,
            )

            assertEquals(listOf(125.0, -25.0), portfolios.single().tickers.map { it.weight })
        }
    }

    @Test
    fun `signed net may differ from 100 only within the explicit tolerance`() {
        val accepted = resolvedPortfolio.replace("-25.0", "-25.0000005")
        parseResolvedRunPortfolios(
            Json.parseToJsonElement(PortfolioRunBoundary.BACKTEST.payload(accepted)).jsonObject,
            PortfolioRunBoundary.BACKTEST,
        )

        val rejected = resolvedPortfolio.replace("-25.0", "-25.01")
        assertFailsWith<IllegalArgumentException> {
            parseResolvedRunPortfolios(
                Json.parseToJsonElement(PortfolioRunBoundary.BACKTEST.payload(rejected)).jsonObject,
                PortfolioRunBoundary.BACKTEST,
            )
        }
    }

    @Test
    fun `run boundaries reject unresolved rows and invalid allocations`() {
        val invalidRows = listOf(
            """{"ticker":"Child","weight":100,"isPortfolioRef":true}""",
            """{"type":"SWAP","ticker":"SPY","weight":100}""",
            """{"ticker":"SPY > TLT","weight":100}""",
            """{"ticker":"DUMMY","weight":100}""",
            """{"ticker":"SPY","weight":0}""",
            """{"ticker":" spy ","weight":100}""",
            """{"ticker":"SPY","weight":"NaN"}""",
            """{"ticker":"SPY","weight":"100"}""",
        )

        PortfolioRunBoundary.entries.forEach { boundary ->
            invalidRows.forEach { row ->
                val portfolio = """{"label":"Invalid","tickers":[$row]}"""
                assertFailsWith<IllegalArgumentException>("$boundary should reject $row") {
                    parseResolvedRunPortfolios(
                        Json.parseToJsonElement(boundary.payload(portfolio)).jsonObject,
                        boundary,
                    )
                }
            }
        }
    }

    private fun PortfolioRunBoundary.payload(portfolio: String): String = when (this) {
        PortfolioRunBoundary.BACKTEST,
        PortfolioRunBoundary.MONTE_CARLO,
        PortfolioRunBoundary.REBALANCE_SCORE_BATCH -> """{"portfolios":[$portfolio]}"""
        PortfolioRunBoundary.MARKET_TIMING,
        PortfolioRunBoundary.REBALANCE_STRATEGY -> """{"portfolio":$portfolio}"""
    }
}
