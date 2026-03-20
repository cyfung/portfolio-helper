package com.portfoliohelper.service.yahoo

import com.portfoliohelper.util.appJson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.decodeFromString
import org.slf4j.LoggerFactory

object YahooFinanceClient {
    private val logger = LoggerFactory.getLogger(YahooFinanceClient::class.java)

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    suspend fun fetchQuote(symbol: String): YahooQuote {
        try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=1d"
            val response: HttpResponse = httpClient.get(url)

            if (response.status.value != 200) {
                throw YahooFinanceException("HTTP ${response.status.value} for $symbol")
            }

            val body = response.bodyAsText()
            return parseQuoteResponse(symbol, body)

        } catch (e: YahooFinanceException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fetch quote for $symbol", e)
            throw YahooFinanceException("Failed to fetch $symbol: ${e.message}", e)
        }
    }

    private fun parseQuoteResponse(symbol: String, jsonBody: String): YahooQuote {
        try {
            val response = appJson.decodeFromString<YahooChartResponse>(jsonBody)
            val result = response.chart.result?.firstOrNull()
                ?: throw YahooFinanceException("Invalid response structure for $symbol")

            val meta = result.meta
            val regularMarketPrice = meta?.regularMarketPrice
            val previousClose = meta?.chartPreviousClose
            val currency = meta?.currency
            val regularPeriod = meta?.currentTradingPeriod?.regular
            val tradingPeriodStart = regularPeriod?.start
            val tradingPeriodEnd = regularPeriod?.end

            // Determine if market is closed (before open or after close).
            // Null trading period = unknown, assume closed.
            val isMarketClosed = run {
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val beforeOpen = tradingPeriodStart?.let { currentTimeSeconds < it } ?: true
                val afterClose = tradingPeriodEnd?.let { currentTimeSeconds >= it } ?: true
                beforeOpen || afterClose
            }

            return YahooQuote(
                symbol = symbol,
                regularMarketPrice = regularMarketPrice,
                previousClose = previousClose,
                tradingPeriodStart = tradingPeriodStart,
                tradingPeriodEnd = tradingPeriodEnd,
                isMarketClosed = isMarketClosed,
                currency = currency
            )
        } catch (e: YahooFinanceException) {
            throw e
        } catch (e: Exception) {
            throw YahooFinanceException("Failed to parse response for $symbol", e)
        }
    }

    fun shutdown() {
        httpClient.close()
    }
}
