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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

data class YahooQuote(
    val symbol: String,
    val regularMarketPrice: Double?,
    val previousClose: Double?,
    val isMarketClosed: Boolean = false,
    val currency: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val gmtoffset: Int? = null,
    val localDate: String? = null,          // local mark-price date "YYYY-MM-DD"
    val tradingPeriodStart: Long? = null    // regular session start (Unix seconds)
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

    suspend fun fetchAdjustedClose(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        if (endDate < startDate) return emptyMap()
        val period1 = startDate.minusDays(5).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val period2 = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val url = "https://query2.finance.yahoo.com/v8/finance/chart/$symbol" +
                "?period1=$period1&period2=$period2&interval=1d" +
                "&events=history%7Cadjclose&includeAdjustedClose=true"
        val response: HttpResponse = httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            header("Accept", "application/json")
        }
        if (response.status.value != 200) return emptyMap()

        val result = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["chart"]?.jsonObject
            ?.get("result")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?: return emptyMap()
        val timestamps = result["timestamp"]?.jsonArray ?: return emptyMap()
        val adjClose = result["indicators"]?.jsonObject
            ?.get("adjclose")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("adjclose")?.jsonArray
            ?: return emptyMap()
        val close = result["indicators"]?.jsonObject
            ?.get("quote")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("close")?.jsonArray
        val meta = result["meta"]?.jsonObject

        val marketDate = meta?.currentTradingDate()
        val rows = timestamps.indices.mapNotNull { i ->
            val ts = timestamps.getOrNull(i)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val date = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate()
            if (date <= endDate) {
                YahooPriceRow(
                    date = date,
                    price = adjClose.getOrNull(i)?.jsonPrimitive?.doubleOrNull,
                    close = close?.getOrNull(i)?.jsonPrimitive?.doubleOrNull
                )
            } else {
                null
            }
        }
        val prices = rows.mapNotNull { row -> row.price?.let { row.date to it } }.toMap(mutableMapOf())

        val nullPlan = buildNullRowPlan(rows, marketDate)
        val needsTailQuote = nullPlan.currentTradingNullDate != null || nullPlan.previousCloseNullDate != null
        val tailQuote = if (needsTailQuote) runCatching { fetchQuote(symbol) }.getOrNull() else null

        nullPlan.previousCloseNullDate?.let { date ->
            tailQuote?.previousClose?.let { prices[date] = it }
        }

        val marketPrice = tailQuote?.regularMarketPrice ?: meta?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull
        if (nullPlan.currentTradingNullDate != null) {
            marketPrice?.let { prices[nullPlan.currentTradingNullDate] = it }
        } else if (marketDate != null && marketPrice != null && marketDate in startDate..endDate) {
            prices[marketDate] = marketPrice
        }

        repairSplitLikeAdjustedCloseBreaks(prices, rows)
        return prices
    }

    private fun buildNullRowPlan(rows: List<YahooPriceRow>, marketDate: LocalDate?): NullRowPlan {
        val nullRows = rows.filter { it.price == null }
        val currentTradingNullDate = marketDate?.takeIf { date -> nullRows.any { it.date == date } }
        val previousCloseNullDate = marketDate
            ?.let { date -> rows.filter { it.date < date }.maxByOrNull { it.date } }
            ?.takeIf { it.price == null }
            ?.date
        val latestPricedDate = rows.filter { it.price != null }.maxOfOrNull { it.date }
        val skippableTrailingNullDates = latestPricedDate
            ?.let { lastPriced ->
                nullRows
                    .filter {
                        it.date > lastPriced &&
                                it.date != currentTradingNullDate &&
                                it.date != previousCloseNullDate
                    }
                    .map { it.date }
                    .toSet()
            }
            ?: emptySet()
        return NullRowPlan(
            currentTradingNullDate = currentTradingNullDate,
            previousCloseNullDate = previousCloseNullDate,
            expectedNullDates = buildSet {
                currentTradingNullDate?.let { add(it) }
                previousCloseNullDate?.let { add(it) }
                addAll(skippableTrailingNullDates)
            }
        )
    }

    private fun JsonObject.currentTradingDate(): LocalDate? {
        val regular = get("currentTradingPeriod")?.jsonObject
            ?.get("regular")?.jsonObject
            ?: return null
        val gmtoffset = regular["gmtoffset"]?.jsonPrimitive?.intOrNull ?: 0
        val epochSecond = get("regularMarketTime")?.jsonPrimitive?.longOrNull
            ?: regular["end"]?.jsonPrimitive?.longOrNull
            ?: return null
        return Instant.ofEpochSecond(epochSecond)
            .atOffset(ZoneOffset.ofTotalSeconds(gmtoffset))
            .toLocalDate()
    }

    private fun repairSplitLikeAdjustedCloseBreaks(
        prices: MutableMap<LocalDate, Double>,
        rows: List<YahooPriceRow>
    ) {
        val pricedRows = rows
            .filter { row ->
                row.price?.takeIf { it > 0.0 } != null &&
                        row.close?.takeIf { it > 0.0 } != null &&
                        prices.containsKey(row.date)
            }
            .sortedBy { it.date }
        if (pricedRows.size < 2) return

        for (i in 1 until pricedRows.size) {
            val repair = splitRepairFor(pricedRows[i - 1], pricedRows[i], prices) ?: continue
            prices.keys.filter { it < repair.effectiveDate }.forEach { date ->
                prices[date] = (prices[date] ?: return@forEach) * repair.historicalMultiplier
            }
        }
    }

    private fun splitRepairFor(
        prev: YahooPriceRow,
        cur: YahooPriceRow,
        prices: Map<LocalDate, Double>
    ): SplitRepair? {
        val prevClose = prev.close ?: return null
        val curClose = cur.close ?: return null
        val prevAdj = prices[prev.date] ?: return null
        val curAdj = prices[cur.date] ?: return null
        val rawRatio = curClose / prevClose
        val adjRatio = curAdj / prevAdj
        val splitFactor = matchingSplitFactor(rawRatio, adjRatio, prevAdj / prevClose, curAdj / curClose)
            ?: return null
        val historicalMultiplier = if (rawRatio < 1.0) 1.0 / splitFactor else splitFactor
        return SplitRepair(cur.date, historicalMultiplier)
    }

    private fun matchingSplitFactor(
        rawRatio: Double,
        adjRatio: Double,
        prevAdjToClose: Double,
        curAdjToClose: Double
    ): Double? {
        if (rawRatio <= 0.0 || adjRatio <= 0.0 || prevAdjToClose <= 0.0 || curAdjToClose <= 0.0) return null
        val rawJump = if (rawRatio >= 1.0) rawRatio else 1.0 / rawRatio
        val adjJump = if (adjRatio >= 1.0) adjRatio else 1.0 / adjRatio
        if (rawJump < 1.2 || abs(rawJump - adjJump) / rawJump > 0.025) return null
        if (abs(prevAdjToClose - curAdjToClose) / prevAdjToClose > 0.025) return null
        return listOf(3.0, 4.0, 5.0, 10.0).firstOrNull { factor ->
            abs(rawJump - factor) / factor <= 0.025
        }
    }

    private fun computeLocalDate(epochSecond: Long?, gmtoffset: Int?): String? {
        if (epochSecond == null || gmtoffset == null) return null
        return Instant.ofEpochSecond(epochSecond)
            .atOffset(ZoneOffset.ofTotalSeconds(gmtoffset))
            .toLocalDate()
            .toString()
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
        val gmtoffset = regularPeriod?.get("gmtoffset")?.jsonPrimitive?.intOrNull
        val regularMarketTime = meta?.get("regularMarketTime")?.jsonPrimitive?.longOrNull
        val localDate = computeLocalDate(regularMarketTime, gmtoffset)
            ?: computeLocalDate(tradingPeriodEnd, gmtoffset)

        val isMarketClosed = run {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val beforeOpen = tradingPeriodStart?.let { currentTimeSeconds < it } ?: true
            val afterClose = tradingPeriodEnd?.let { currentTimeSeconds >= it } ?: true
            beforeOpen || afterClose
        }

        return YahooQuote(symbol, regularMarketPrice, previousClose, isMarketClosed, currency, System.currentTimeMillis(), gmtoffset, localDate, tradingPeriodStart)
    }

    private data class YahooPriceRow(
        val date: LocalDate,
        val price: Double?,
        val close: Double?
    )

    private data class NullRowPlan(
        val currentTradingNullDate: LocalDate?,
        val previousCloseNullDate: LocalDate?,
        @Suppress("unused") val expectedNullDates: Set<LocalDate>
    )

    private data class SplitRepair(
        val effectiveDate: LocalDate,
        val historicalMultiplier: Double
    )
}
