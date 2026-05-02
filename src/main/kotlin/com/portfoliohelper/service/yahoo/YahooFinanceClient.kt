package com.portfoliohelper.service.yahoo

import com.portfoliohelper.AppConfig
import com.portfoliohelper.util.appJson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
            val includeSma = !symbol.endsWith("=X")
            val maxSmaDays = maxOf(AppConfig.smaDays1, AppConfig.smaDays2)
            val range = if (!includeSma) "1d" else when {
                maxSmaDays <= 63 -> "3mo"
                maxSmaDays <= 126 -> "6mo"
                maxSmaDays <= 252 -> "1y"
                maxSmaDays <= 504 -> "2y"
                maxSmaDays <= 1260 -> "5y"
                else -> "10y"
            }
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=$range"
            val response: HttpResponse = httpClient.get(url)

            if (response.status.value != 200) {
                throw YahooFinanceException("HTTP ${response.status.value} for $symbol")
            }

            val body = response.bodyAsText()
            return parseQuoteResponse(symbol, body, includeSma)

        } catch (e: YahooFinanceException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fetch quote for $symbol", e)
            throw YahooFinanceException("Failed to fetch $symbol: ${e.message}", e)
        }
    }

    private fun parseQuoteResponse(symbol: String, jsonBody: String, includeSma: Boolean): YahooQuote {
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
            val gmtoffset = regularPeriod?.gmtoffset
            val closes = if (includeSma) {
                result.indicators?.quote?.firstOrNull()?.close
                    ?.filterNotNull()
                    ?.filter { it > 0.0 }
                    ?: emptyList()
            } else {
                emptyList()
            }
            val (smaDays1, smaDays2) = AppConfig.smaDays
            val sma1 = computeSma(closes, smaDays1)
            val sma2 = computeSma(closes, smaDays2)

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
                gmtoffset = gmtoffset,
                isMarketClosed = isMarketClosed,
                currency = currency,
                sma1 = sma1,
                sma2 = sma2
            )
        } catch (e: YahooFinanceException) {
            throw e
        } catch (e: Exception) {
            throw YahooFinanceException("Failed to parse response for $symbol", e)
        }
    }

    private fun computeSma(closes: List<Double>, days: Int): Double? {
        if (days <= 0 || closes.size < days) return null
        return closes.takeLast(days).average()
    }

    fun shutdown() {
        httpClient.close()
    }
}
