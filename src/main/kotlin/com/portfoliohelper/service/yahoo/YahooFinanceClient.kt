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
            val previousClose = meta?.get("chartPreviousClose")?.jsonPrimitive?.doubleOrNull

            return YahooQuote(
                symbol = symbol,
                regularMarketPrice = regularMarketPrice,
                previousClose = previousClose
            )
        } catch (e: Exception) {
            throw YahooFinanceException("Failed to parse response for $symbol", e)
        }
    }

    fun shutdown() {
        httpClient.close()
    }
}
