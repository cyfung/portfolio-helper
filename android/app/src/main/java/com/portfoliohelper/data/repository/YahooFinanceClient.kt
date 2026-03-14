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

data class YahooQuote(
    val symbol: String,
    val regularMarketPrice: Double?,
    val previousClose: Double?,
    val isMarketClosed: Boolean = false
)

object YahooFinanceClient {
    private const val TAG = "YahooFinanceClient"

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
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

    suspend fun fetchQuote(symbol: String): YahooQuote {
        try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=1d"
            val response: HttpResponse = httpClient.get(url)

            if (response.status.value != 200) {
                throw Exception("HTTP ${response.status.value} for $symbol")
            }

            val body = response.bodyAsText()
            return parseQuoteResponse(symbol, body)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch quote for $symbol", e)
            throw e
        }
    }

    private fun parseQuoteResponse(symbol: String, jsonBody: String): YahooQuote {
        val jsonElement = json.parseToJsonElement(jsonBody)
        val result = jsonElement.jsonObject["chart"]?.jsonObject?.get("result")?.jsonArray?.get(0)?.jsonObject
            ?: throw Exception("Invalid response structure for $symbol")

        val meta = result["meta"]?.jsonObject
        val regularMarketPrice = meta?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull
        val previousClose = meta?.get("chartPreviousClose")?.jsonPrimitive?.doubleOrNull

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

        return YahooQuote(
            symbol = symbol,
            regularMarketPrice = regularMarketPrice,
            previousClose = previousClose,
            isMarketClosed = isMarketClosed
        )
    }
}
