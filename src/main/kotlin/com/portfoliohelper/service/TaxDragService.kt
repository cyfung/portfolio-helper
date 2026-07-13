package com.portfoliohelper.service

import com.portfoliohelper.service.yahoo.YahooCloseDividendHistory
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlin.math.pow

@Serializable
data class TaxDragRequest(
    val withholdingTaxPct: Double = 30.0,
    val tickers: List<String> = emptyList(),
    val commonPeriodTickers: List<String> = emptyList()
)

@Serializable
data class TaxDragAnnualResult(
    val year: Int,
    val fromDate: String,
    val toDate: String,
    val days: Long,
    val startPrice: Double,
    val endPrice: Double,
    val dividend: Double,
    val withheldTax: Double,
    val priceReturn: Double,
    val grossReturn: Double,
    val afterTaxReturn: Double,
    val grossEndingValue: Double,
    val afterTaxEndingValue: Double
)

@Serializable
data class TaxDragTickerResult(
    val ticker: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val days: Long = 0,
    val withholdingTaxPct: Double,
    val cagrGross: Double? = null,
    val cagrAfterTax: Double? = null,
    val cagrDrag: Double? = null,
    val effectiveExpenseRatio: Double? = null,
    val backtestExpenseRatio: Double? = null,
    val endingValueGross: Double? = null,
    val endingValueAfterTax: Double? = null,
    val totalDividend: Double = 0.0,
    val totalWithheldTax: Double = 0.0,
    val annual: List<TaxDragAnnualResult> = emptyList(),
    val commonPeriod: Boolean = false,
    val error: String? = null
)

@Serializable
data class TaxDragResponse(
    val results: List<TaxDragTickerResult>
)

object TaxDragService {
    fun calculate(request: TaxDragRequest): TaxDragResponse {
        val taxPct = request.withholdingTaxPct.coerceIn(0.0, 100.0)
        val tickers = request.tickers
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
        val commonPeriodTickers = request.commonPeriodTickers
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }

        return TaxDragResponse(
            tickers.map { ticker ->
                runCatching { calculateTicker(ticker, taxPct) }
                    .getOrElse { err ->
                        TaxDragTickerResult(
                            ticker = ticker,
                            withholdingTaxPct = taxPct,
                            error = err.message ?: "Unable to calculate tax drag for $ticker"
                        )
                    }
            } + calculateCommonPeriodTickers(commonPeriodTickers, taxPct)
        )
    }

    internal fun calculateTicker(ticker: String, withholdingTaxPct: Double): TaxDragTickerResult {
        val history = YahooHistoricalFetcher.fetchCloseDividendHistory(ticker)
        return calculateTicker(ticker, withholdingTaxPct, history, commonPeriod = false)
    }

    private fun calculateCommonPeriodTickers(tickers: List<String>, withholdingTaxPct: Double): List<TaxDragTickerResult> {
        if (tickers.isEmpty()) return emptyList()

        data class LoadedHistory(val ticker: String, val history: YahooCloseDividendHistory)

        val loaded = mutableListOf<LoadedHistory>()
        val errors = mutableListOf<TaxDragTickerResult>()

        for (ticker in tickers) {
            runCatching {
                val history = YahooHistoricalFetcher.fetchCloseDividendHistory(ticker)
                if (history.closes.size < 2) {
                    throw IllegalArgumentException("Yahoo returned fewer than two close prices for $ticker")
                }
                loaded.add(LoadedHistory(ticker, history))
            }.getOrElse { err ->
                errors.add(
                    TaxDragTickerResult(
                        ticker = ticker,
                        withholdingTaxPct = withholdingTaxPct,
                        commonPeriod = true,
                        error = err.message ?: "Unable to load common-period history for $ticker"
                    )
                )
            }
        }

        if (loaded.isEmpty()) return errors

        val commonStart = loaded.maxOf { it.history.closes.firstKey() }
        val commonEnd = loaded.minOf { it.history.closes.lastKey() }
        if (!commonStart.isBefore(commonEnd)) {
            return errors + loaded.map { row ->
                TaxDragTickerResult(
                    ticker = row.ticker,
                    withholdingTaxPct = withholdingTaxPct,
                    commonPeriod = true,
                    error = "No overlapping Yahoo date range for common-period tickers"
                )
            }
        }

        return errors + loaded.map { row ->
            runCatching {
                calculateTicker(
                    ticker = row.ticker,
                    withholdingTaxPct = withholdingTaxPct,
                    history = sliceHistory(row.history, commonStart, commonEnd),
                    commonPeriod = true
                )
            }.getOrElse { err ->
                TaxDragTickerResult(
                    ticker = row.ticker,
                    withholdingTaxPct = withholdingTaxPct,
                    commonPeriod = true,
                    error = err.message ?: "Unable to calculate common-period tax drag for ${row.ticker}"
                )
            }
        }
    }

    private fun calculateTicker(
        ticker: String,
        withholdingTaxPct: Double,
        history: YahooCloseDividendHistory,
        commonPeriod: Boolean
    ): TaxDragTickerResult {
        val closes = history.closes
        if (closes.size < 2) {
            throw IllegalArgumentException("Yahoo returned fewer than two close prices for $ticker")
        }

        val taxRate = withholdingTaxPct / 100.0
        val startDate = closes.firstKey()
        val endDate = closes.lastKey()
        val totalDays = ChronoUnit.DAYS.between(startDate, endDate)
        if (totalDays <= 0) {
            throw IllegalArgumentException("Yahoo returned an invalid date range for $ticker")
        }

        var grossValue = 1.0
        var afterTaxValue = 1.0
        val annualRows = closes.entries
            .groupBy { it.key.year }
            .toSortedMap()
            .mapNotNull { (year, entries) ->
                val sorted = entries.sortedBy { it.key }
                val first = sorted.firstOrNull() ?: return@mapNotNull null
                val last = sorted.lastOrNull() ?: return@mapNotNull null
                val periodStart = first.key
                val periodEnd = last.key
                val periodDays = ChronoUnit.DAYS.between(periodStart, periodEnd)
                val startPrice = first.value
                val endPrice = last.value
                val dividend = history.dividends
                    .filterKeys { date -> date in periodStart..periodEnd }
                    .values
                    .sum()
                val withheldTax = dividend * taxRate
                val priceReturn = (endPrice - startPrice) / startPrice
                val grossReturn = (endPrice - startPrice + dividend) / startPrice
                val afterTaxReturn = (endPrice - startPrice + dividend - withheldTax) / startPrice

                grossValue *= 1.0 + grossReturn
                afterTaxValue *= 1.0 + afterTaxReturn

                TaxDragAnnualResult(
                    year = year,
                    fromDate = periodStart.toString(),
                    toDate = periodEnd.toString(),
                    days = periodDays,
                    startPrice = startPrice,
                    endPrice = endPrice,
                    dividend = dividend,
                    withheldTax = withheldTax,
                    priceReturn = priceReturn,
                    grossReturn = grossReturn,
                    afterTaxReturn = afterTaxReturn,
                    grossEndingValue = grossValue,
                    afterTaxEndingValue = afterTaxValue
                )
            }

        val cagrGross = annualizedReturn(grossValue, totalDays)
        val cagrAfterTax = annualizedReturn(afterTaxValue, totalDays)
        val effectiveExpenseRatio = effectiveExpenseRatio(grossValue, afterTaxValue, totalDays)
        val tradingIntervals = closes.size - 1
        val backtestExpenseRatio = backtestExpenseRatio(grossValue, afterTaxValue, tradingIntervals)
        val totalDividend = annualRows.sumOf { it.dividend }
        val totalWithheldTax = annualRows.sumOf { it.withheldTax }

        return TaxDragTickerResult(
            ticker = ticker,
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            days = totalDays,
            withholdingTaxPct = withholdingTaxPct,
            cagrGross = cagrGross,
            cagrAfterTax = cagrAfterTax,
            cagrDrag = cagrGross - cagrAfterTax,
            effectiveExpenseRatio = effectiveExpenseRatio,
            backtestExpenseRatio = backtestExpenseRatio,
            endingValueGross = grossValue,
            endingValueAfterTax = afterTaxValue,
            totalDividend = totalDividend,
            totalWithheldTax = totalWithheldTax,
            annual = annualRows,
            commonPeriod = commonPeriod
        )
    }

    private fun sliceHistory(
        history: YahooCloseDividendHistory,
        startDate: LocalDate,
        endDate: LocalDate
    ): YahooCloseDividendHistory {
        val closes = TreeMap<LocalDate, Double>()
        closes.putAll(history.closes.filterKeys { date -> date in startDate..endDate })
        return YahooCloseDividendHistory(
            closes = closes,
            dividends = history.dividends.filterKeys { date -> date in startDate..endDate }
        )
    }

    private fun annualizedReturn(endingValue: Double, days: Long): Double =
        endingValue.pow(365.25 / days.toDouble()) - 1.0

    private fun effectiveExpenseRatio(grossEndingValue: Double, afterTaxEndingValue: Double, days: Long): Double =
        1.0 - (afterTaxEndingValue / grossEndingValue).pow(365.25 / days.toDouble())

    private fun backtestExpenseRatio(
        grossEndingValue: Double,
        afterTaxEndingValue: Double,
        tradingIntervals: Int
    ): Double =
        252.0 * (1.0 - (afterTaxEndingValue / grossEndingValue).pow(1.0 / tradingIntervals.toDouble()))
}
