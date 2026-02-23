package com.portfoliohelper.service.yahoo

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object YahooFinanceClient {
    private val logger = LoggerFactory.getLogger(YahooFinanceClient::class.java)

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 10_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
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
            val jsonElement = json.parseToJsonElement(jsonBody)
            val result = jsonElement.jsonObject["chart"]?.jsonObject?.get("result")?.jsonArray?.get(0)?.jsonObject
                ?: throw YahooFinanceException("Invalid response structure for $symbol")

            val meta = result["meta"]?.jsonObject
            val regularMarketPrice = meta?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull

            // Extract previous close from meta
            val previousClose = meta?.get("chartPreviousClose")?.jsonPrimitive?.doubleOrNull

            // Extract trading hours to determine if market is closed
            val currentTradingPeriod = meta?.get("currentTradingPeriod")?.jsonObject
            val regularPeriod = currentTradingPeriod?.get("regular")?.jsonObject
            val tradingPeriodStart = regularPeriod?.get("start")?.jsonPrimitive?.longOrNull
            val tradingPeriodEnd = regularPeriod?.get("end")?.jsonPrimitive?.longOrNull

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
                isMarketClosed = isMarketClosed
            )
        } catch (e: Exception) {
            throw YahooFinanceException("Failed to parse response for $symbol", e)
        }
    }

    fun shutdown() {
        httpClient.close()
    }
}
