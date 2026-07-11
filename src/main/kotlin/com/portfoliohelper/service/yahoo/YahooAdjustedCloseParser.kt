package com.portfoliohelper.service.yahoo

import com.portfoliohelper.util.appJson
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal object YahooAdjustedCloseParser {
    private val logger = LoggerFactory.getLogger(YahooAdjustedCloseParser::class.java)
    private val loggedNullAdjustedCloseWarnings = ConcurrentHashMap.newKeySet<String>()
    private val loggedSplitRepairWarnings = ConcurrentHashMap.newKeySet<String>()
    private val commonSplitFactors = listOf(3.0, 4.0, 5.0, 10.0)

    fun parse(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        body: String,
        tailQuoteProvider: (() -> YahooQuote?)? = null
    ): YahooAdjustedCloseResult {
        val parsed = decodeResponse(ticker, body)
        val marketDate = parsed.result.meta?.currentTradingDate()
        val rows = buildPriceRows(parsed.timestamps, parsed.adjCloseList, parsed.closeList, endDate)
        val nullPlan = buildNullRowPlan(rows, marketDate)

        val warnings = mutableListOf<String>()
        val invalidNullRows = rows.filter { it.price == null && it.date !in nullPlan.expectedNullDates }
        if (invalidNullRows.isNotEmpty()) {
            warnings += logNullAdjustedCloseWarning(
                ticker,
                startDate,
                endDate,
                marketDate,
                "invalid null rows: ${invalidNullRows.joinToString { it.date.toString() }}"
            )
        }

        val prices = rows
            .mapNotNull { row -> row.price?.let { row.date to it } }
            .toMap(mutableMapOf())

        fillTailPrices(
            ticker = ticker,
            startDate = startDate,
            endDate = endDate,
            marketDate = marketDate,
            resultMeta = parsed.result.meta,
            nullPlan = nullPlan,
            prices = prices,
            warnings = warnings,
            tailQuoteProvider = tailQuoteProvider
        )

        warnings += repairSplitLikeAdjustedCloseBreaks(ticker, prices, rows)
        return YahooAdjustedCloseResult(prices, warnings, parsed.result.meta?.currency)
    }

    private fun decodeResponse(ticker: String, body: String): ParsedYahooResponse {
        val response = appJson.decodeFromString<YahooChartResponse>(body)
        response.chart.error?.let { error ->
            throw yahooChartException(ticker, error)
        }
        val result = response.chart.result?.firstOrNull()
            ?: throw YahooHistoricalDataException("No Yahoo chart result for $ticker")
        val timestamps = result.timestamp ?: throw YahooHistoricalDataException("No Yahoo timestamps for $ticker")
        val indicators = result.indicators ?: throw YahooHistoricalDataException("No Yahoo indicators for $ticker")
        val adjCloseList = indicators.adjClose?.firstOrNull()?.adjClose
            ?: throw YahooHistoricalDataException("No Yahoo adjclose data for $ticker")
        val closeList = indicators.quote?.firstOrNull()?.close
        return ParsedYahooResponse(result, timestamps, adjCloseList, closeList)
    }

    private fun buildPriceRows(
        timestamps: List<Long>,
        adjCloseList: List<Double?>,
        closeList: List<Double?>?,
        endDate: LocalDate
    ): List<YahooPriceRow> =
        timestamps.indices.mapNotNull { i ->
            val date = Instant.ofEpochSecond(timestamps[i]).atZone(ZoneOffset.UTC).toLocalDate()
            if (date <= endDate) {
                YahooPriceRow(
                    date = date,
                    price = adjCloseList.getOrNull(i),
                    close = closeList?.getOrNull(i)
                )
            } else {
                null
            }
        }

    private fun buildNullRowPlan(rows: List<YahooPriceRow>, marketDate: LocalDate?): NullRowPlan {
        val nullRows = rows.filter { it.price == null }
        val currentTradingNullDate = marketDate
            ?.takeIf { date -> nullRows.any { it.date == date } }
        val latestBeforeMarketDate = marketDate
            ?.let { date -> rows.filter { it.date < date }.maxByOrNull { it.date } }
        val previousCloseNullDate = latestBeforeMarketDate
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
        val expectedNullDates = buildSet {
            currentTradingNullDate?.let { add(it) }
            previousCloseNullDate?.let { add(it) }
            addAll(skippableTrailingNullDates)
        }
        return NullRowPlan(currentTradingNullDate, previousCloseNullDate, expectedNullDates)
    }

    private fun fillTailPrices(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        marketDate: LocalDate?,
        resultMeta: YahooMeta?,
        nullPlan: NullRowPlan,
        prices: MutableMap<LocalDate, Double>,
        warnings: MutableList<String>,
        tailQuoteProvider: (() -> YahooQuote?)?
    ) {
        val needsTailQuote = nullPlan.currentTradingNullDate != null || nullPlan.previousCloseNullDate != null
        val tailQuote = if (needsTailQuote) tailQuoteProvider?.invoke() else null

        fillPreviousClose(
            ticker = ticker,
            startDate = startDate,
            endDate = endDate,
            marketDate = marketDate,
            date = nullPlan.previousCloseNullDate,
            previousClose = tailQuote?.previousClose,
            prices = prices,
            warnings = warnings
        )

        fillCurrentTradingPrice(
            ticker = ticker,
            startDate = startDate,
            endDate = endDate,
            marketDate = marketDate,
            date = nullPlan.currentTradingNullDate,
            marketPrice = tailQuote?.regularMarketPrice ?: resultMeta?.regularMarketPrice,
            prices = prices,
            warnings = warnings
        )
    }

    private fun fillPreviousClose(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        marketDate: LocalDate?,
        date: LocalDate?,
        previousClose: Double?,
        prices: MutableMap<LocalDate, Double>,
        warnings: MutableList<String>
    ) {
        if (date == null) return
        if (previousClose == null) {
            warnings += logNullAdjustedCloseWarning(
                ticker,
                startDate,
                endDate,
                marketDate,
                "tail null row $date could not be filled because quote previousClose was null"
            )
            return
        }

        prices[date] = previousClose
        logger.info("Filled $ticker null adjclose tail row for $date from quote previousClose=$previousClose")
    }

    private fun fillCurrentTradingPrice(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        marketDate: LocalDate?,
        date: LocalDate?,
        marketPrice: Double?,
        prices: MutableMap<LocalDate, Double>,
        warnings: MutableList<String>
    ) {
        if (date != null) {
            if (marketPrice == null) {
                warnings += logNullAdjustedCloseWarning(
                    ticker,
                    startDate,
                    endDate,
                    marketDate,
                    "current trading date null row $date could not be filled because regularMarketPrice was null"
                )
                return
            }

            prices[date] = marketPrice
            logger.info("Filled $ticker null adjclose current trading row for $date from regularMarketPrice=$marketPrice")
            return
        }

        if (marketDate != null && marketPrice != null && marketDate in startDate..endDate) {
            prices[marketDate] = marketPrice
        }
    }

    private fun YahooMeta.currentTradingDate(): LocalDate? {
        val regular = currentTradingPeriod?.regular ?: return null
        val offset = ZoneOffset.ofTotalSeconds(regular.gmtoffset ?: 0)
        val epochSecond = regularMarketTime ?: regular.end ?: return null
        return Instant.ofEpochSecond(epochSecond).atOffset(offset).toLocalDate()
    }

    private fun logNullAdjustedCloseWarning(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        marketDate: LocalDate?,
        reason: String
    ): String {
        val message = "Yahoo adjusted-close data for $ticker contains unsupported null rows; $reason;"
        if (loggedNullAdjustedCloseWarnings.add("$ticker|$reason")) {
            logger.error("$message first seen for range $startDate..$endDate (currentTradingDate=$marketDate)")
        }
        return message
    }

    private fun repairSplitLikeAdjustedCloseBreaks(
        ticker: String,
        prices: MutableMap<LocalDate, Double>,
        rows: List<YahooPriceRow>
    ): List<String> {
        val pricedRows = rows
            .filter { row ->
                row.price?.takeIf { it > 0.0 } != null &&
                        row.close?.takeIf { it > 0.0 } != null &&
                        prices.containsKey(row.date)
            }
            .sortedBy { it.date }
        if (pricedRows.size < 2) return emptyList()

        val warnings = mutableListOf<String>()
        for (i in 1 until pricedRows.size) {
            val repair = splitRepairFor(pricedRows[i - 1], pricedRows[i], prices) ?: continue
            val datesToRepair = prices.keys.filter { it < repair.effectiveDate }
            for (date in datesToRepair) {
                prices[date] = (prices[date] ?: continue) * repair.historicalMultiplier
            }

            val message = "Yahoo adjusted-close data for $ticker contained split-like break on ${repair.effectiveDate}; " +
                    "repaired earlier prices by multiplier ${repair.historicalMultiplier} " +
                    "(detected split factor ${repair.splitFactor})."
            if (loggedSplitRepairWarnings.add("$ticker|${repair.effectiveDate}|${repair.splitFactor}")) {
                logger.warn(message)
            }
            warnings += message
        }
        return warnings
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
        return SplitRepair(cur.date, splitFactor, historicalMultiplier)
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

        return commonSplitFactors.firstOrNull { factor ->
            abs(rawJump - factor) / factor <= 0.025
        }
    }

    private data class ParsedYahooResponse(
        val result: YahooChartResult,
        val timestamps: List<Long>,
        val adjCloseList: List<Double?>,
        val closeList: List<Double?>?
    )

    private data class NullRowPlan(
        val currentTradingNullDate: LocalDate?,
        val previousCloseNullDate: LocalDate?,
        val expectedNullDates: Set<LocalDate>
    )

    private data class YahooPriceRow(
        val date: LocalDate,
        val price: Double?,
        val close: Double?
    )

    private data class SplitRepair(
        val effectiveDate: LocalDate,
        val splitFactor: Double,
        val historicalMultiplier: Double
    )
}
