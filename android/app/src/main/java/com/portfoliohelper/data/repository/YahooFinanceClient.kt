package com.portfoliohelper.data.repository

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import android.util.Log
import kotlinx.coroutines.*

data class YahooQuote(
    val symbol: String,
    val regularMarketPrice: Double?,
    val previousClose: Double?,
    val isMarketClosed: Boolean = false,
    val currency: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object YahooFinanceClient {
    private const val TAG = "YahooFinanceClient"

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Fetch a single quote using the chart API (stable).
     * Includes a single retry logic for transient failures.
     */
    suspend fun fetchQuote(symbol: String): YahooQuote {
        var lastException: Exception? = null
        
        repeat(2) { attempt ->
            try {
                // query2 is sometimes more reliable than query1
                val url = "https://query2.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=1d"
                
                val response: HttpResponse = httpClient.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    header("Accept", "*/*")
                    header("Connection", "keep-alive")
                }

                if (response.status.value == 200) {
                    val body = response.bodyAsText()
                    return parseChartResponse(symbol, body)
                } else {
                    throw Exception("HTTP ${response.status.value} for $symbol")
                }

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for $symbol: ${e.message}")
                if (attempt == 0) delay(500) // Small delay before retry
            }
        }
        
        throw lastException ?: Exception("Failed to fetch quote for $symbol")
    }

    /**
     * Fetch multiple quotes in parallel using the stable chart API.
     * This avoids the 401 Unauthorized errors often seen on the batch 'quote' API.
     */
    suspend fun fetchQuotes(symbols: List<String>): List<YahooQuote> = coroutineScope {
        symbols.map { symbol ->
            async {
                try {
                    fetchQuote(symbol)
                } catch (e: Exception) {
                    Log.e(TAG, "Parallel fetch permanently failed for $symbol: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun parseChartResponse(symbol: String, jsonBody: String): YahooQuote {
        val jsonElement = json.parseToJsonElement(jsonBody)
        val result = jsonElement.jsonObject["chart"]?.jsonObject?.get("result")?.jsonArray?.get(0)?.jsonObject
            ?: throw Exception("Invalid response structure for $symbol")

        val meta = result["meta"]?.jsonObject
        val regularMarketPrice = meta?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull
        val previousClose = meta?.get("chartPreviousClose")?.jsonPrimitive?.doubleOrNull
        val currency = meta?.get("currency")?.jsonPrimitive?.content

        val currentTradingPeriod = meta?.get("currentTradingPeriod")?.jsonObject
        val regularPeriod = currentTradingPeriod?.get("regular")?.jsonObject
        val tradingPeriodStart = regularPeriod?.get("start")?.jsonPrimitive?.longOrNull
        val tradingPeriodEnd = regularPeriod?.get("end")?.jsonPrimitive?.longOrNull

        val isMarketClosed = run {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val beforeOpen = tradingPeriodStart?.let { currentTimeSeconds < it } ?: true
            val afterClose = tradingPeriodEnd?.let { currentTimeSeconds >= it } ?: true
            beforeOpen || afterClose
        }

        return YahooQuote(symbol, regularMarketPrice, previousClose, isMarketClosed, currency, System.currentTimeMillis())
    }
}
