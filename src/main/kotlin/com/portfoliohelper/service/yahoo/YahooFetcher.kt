package com.portfoliohelper.service.yahoo

import com.portfoliohelper.util.appJson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.SortedMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit

open class YahooHistoricalDataException(message: String) : IllegalStateException(message)

class YahooTickerNotFoundException(
    val ticker: String,
    yahooMessage: String
) : YahooHistoricalDataException("Yahoo has no ticker '$ticker': $yahooMessage")

internal fun yahooChartException(ticker: String, error: YahooChartError): YahooHistoricalDataException {
    val code = error.code.orEmpty()
    val description = error.description.orEmpty()
    val message = listOf(code, description).filter { it.isNotBlank() }.joinToString(": ")
        .ifBlank { "unknown Yahoo chart error" }
    val notFound = code.equals("Not Found", ignoreCase = true) ||
            description.contains("No data found", ignoreCase = true) ||
            description.contains("may be delisted", ignoreCase = true) ||
            description.contains("No such ticker", ignoreCase = true)
    return if (notFound) {
        YahooTickerNotFoundException(ticker, message)
    } else {
        YahooHistoricalDataException("Yahoo chart error for $ticker: $message")
    }
}

data class YahooAdjustedCloseResult(
    val prices: Map<LocalDate, Double>,
    val warnings: List<String> = emptyList(),
    val currency: String? = null
)

data class YahooCloseDividendHistory(
    val closes: SortedMap<LocalDate, Double>,
    val dividends: Map<LocalDate, Double>
)

object YahooHistoricalFetcher {
    private val logger = LoggerFactory.getLogger(YahooHistoricalFetcher::class.java)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    fun fetchAdjustedClose(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> =
        fetchAdjustedCloseWithWarnings(ticker, startDate, endDate).prices

    fun fetchAdjustedCloseWithWarnings(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): YahooAdjustedCloseResult {
        val p1 = startDate.minusDays(5).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val p2 = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                "?period1=$p1&period2=$p2&interval=1d" +
                "&events=history%7Cadjclose&includeAdjustedClose=true"

        logger.info("Fetching historical $ticker from $startDate to $endDate")
        val body = executeTextRequest(url, ticker) { "HTTP ${it.code} for $ticker" }
        val result = parseAdjustedCloseResponseWithWarnings(
            ticker,
            startDate,
            endDate,
            body,
            tailQuoteProvider = { fetchCurrentQuote(ticker) }
        )
        logger.info("Fetched ${result.prices.size} trading days for $ticker")
        return result
    }

    internal fun parseAdjustedCloseResponse(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        body: String,
        tailQuoteProvider: (() -> YahooQuote?)? = null
    ): Map<LocalDate, Double> =
        parseAdjustedCloseResponseWithWarnings(ticker, startDate, endDate, body, tailQuoteProvider).prices

    internal fun parseAdjustedCloseResponseWithWarnings(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        body: String,
        tailQuoteProvider: (() -> YahooQuote?)? = null
    ): YahooAdjustedCloseResult =
        YahooAdjustedCloseParser.parse(ticker, startDate, endDate, body, tailQuoteProvider)

    fun fetchDividends(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        val p1 = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val p2 = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                "?period1=$p1&period2=$p2&interval=1d&events=div"

        logger.info("Fetching dividends for $ticker from $startDate to $endDate")
        val body = executeTextRequest(url, ticker) { "HTTP ${it.code} for $ticker dividends" }
        val response = appJson.decodeFromString<YahooChartResponse>(body)
        val result = response.chart.result?.firstOrNull() ?: return emptyMap()
        val dividends = result.events?.dividends ?: return emptyMap()
        val out = dividends.values
            .mapNotNull { dividend ->
                val date = Instant.ofEpochSecond(dividend.date).atZone(ZoneOffset.UTC).toLocalDate()
                if (date in startDate..endDate) date to dividend.amount else null
            }
            .toMap()

        logger.info("Fetched ${out.size} dividend events for $ticker")
        return out
    }

    fun fetchCloseDividendHistory(
        ticker: String,
        startDate: LocalDate = LocalDate.of(1900, 1, 1),
        endDate: LocalDate = LocalDate.now(ZoneOffset.UTC)
    ): YahooCloseDividendHistory {
        val p1 = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val p2 = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                "?period1=$p1&period2=$p2&interval=1d&events=history%7Cdiv"

        logger.info("Fetching close/dividend history for $ticker from $startDate to $endDate")
        val body = executeTextRequest(url, ticker) { "HTTP ${it.code} for $ticker close/dividend history" }
        val response = appJson.decodeFromString<YahooChartResponse>(body)
        val result = response.chart.result?.firstOrNull()
            ?: throw YahooHistoricalDataException("No Yahoo chart result for $ticker")
        val timestamps = result.timestamp
            ?: throw YahooHistoricalDataException("No Yahoo timestamps for $ticker")
        val closeList = result.indicators?.quote?.firstOrNull()?.close
            ?: throw YahooHistoricalDataException("No Yahoo close prices for $ticker")

        val closes = TreeMap<LocalDate, Double>()
        timestamps.forEachIndexed { index, epoch ->
            val date = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate()
            val close = closeList.getOrNull(index)
            if (date in startDate..endDate && close != null && close > 0.0) {
                closes[date] = close
            }
        }

        val dividends = result.events?.dividends
            ?.values
            ?.mapNotNull { dividend ->
                val date = Instant.ofEpochSecond(dividend.date).atZone(ZoneOffset.UTC).toLocalDate()
                if (date in startDate..endDate) date to dividend.amount else null
            }
            ?.groupingBy { it.first }
            ?.fold(0.0) { acc, item -> acc + item.second }
            ?: emptyMap()

        logger.info("Fetched ${closes.size} closes and ${dividends.size} dividend dates for $ticker")
        return YahooCloseDividendHistory(closes, dividends)
    }

    private fun fetchCurrentQuote(ticker: String): YahooQuote {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1d&range=1d"
        val body = executeTextRequest(url, ticker) { "HTTP ${it.code} for $ticker quote" }
        val response = appJson.decodeFromString<YahooChartResponse>(body)
        val result = response.chart.result?.firstOrNull()
            ?: error("No quote result in Yahoo response for $ticker")
        val meta = result.meta
        val regular = meta?.currentTradingPeriod?.regular
        val markPriceDate = marketDate(meta?.regularMarketTime, regular?.gmtoffset)
            ?: marketDate(regular?.end, regular?.gmtoffset)
        return YahooQuote(
            symbol = ticker,
            regularMarketPrice = meta?.regularMarketPrice,
            previousClose = meta?.chartPreviousClose,
            tradingPeriodStart = regular?.start,
            tradingPeriodEnd = regular?.end,
            gmtoffset = regular?.gmtoffset,
            currency = meta?.currency,
            markPriceDate = markPriceDate
        )
    }

    private fun marketDate(epochSecond: Long?, gmtoffset: Int?): LocalDate? {
        if (epochSecond == null || gmtoffset == null) return null
        return Instant.ofEpochSecond(epochSecond)
            .atOffset(ZoneOffset.ofTotalSeconds(gmtoffset))
            .toLocalDate()
    }

    private fun executeTextRequest(
        url: String,
        ticker: String,
        errorMessage: (okhttp3.Response) -> String
    ): String {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { resp ->
            val body = resp.body!!.string()
            if (!resp.isSuccessful) {
                throw yahooHttpException(ticker, body) ?: IllegalStateException(errorMessage(resp))
            }
            body
        }
    }

    private fun yahooHttpException(ticker: String, body: String): YahooHistoricalDataException? =
        runCatching {
            appJson.decodeFromString<YahooChartResponse>(body).chart.error?.let { error ->
                yahooChartException(ticker, error) as? YahooTickerNotFoundException
            }
        }.getOrNull()
}
