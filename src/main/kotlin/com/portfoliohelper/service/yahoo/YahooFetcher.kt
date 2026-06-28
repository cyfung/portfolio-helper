package com.portfoliohelper.service.yahoo

import com.portfoliohelper.util.appJson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class YahooHistoricalDataException(message: String) : IllegalStateException(message)

data class YahooAdjustedCloseResult(
    val prices: Map<LocalDate, Double>,
    val warnings: List<String> = emptyList()
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
        val body = executeTextRequest(url) { "HTTP ${it.code} for $ticker" }
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
        val body = executeTextRequest(url) { "HTTP ${it.code} for $ticker dividends" }
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

    private fun fetchCurrentQuote(ticker: String): YahooQuote {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1d&range=1d"
        val body = executeTextRequest(url) { "HTTP ${it.code} for $ticker quote" }
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

    private fun executeTextRequest(url: String, errorMessage: (okhttp3.Response) -> String): String {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { errorMessage(resp) }
            resp.body!!.string()
        }
    }
}
