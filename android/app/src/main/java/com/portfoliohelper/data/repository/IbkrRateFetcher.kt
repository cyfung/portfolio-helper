package com.portfoliohelper.data.repository

import android.util.Log
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class IbkrRateTier(val upTo: Double?, val rate: Double)
data class IbkrTierSpread(val upTo: Double?, val spread: Double)
data class IbkrBenchmarkRate(val rate: Double, val effectiveDate: LocalDate, val source: String)
data class IbkrCurrencyRates(
    val currency: String,
    val tiers: List<IbkrRateTier>,
    val benchmarkRate: Double,
    val benchmarkEffectiveDate: LocalDate,
    val benchmarkSource: String
) { val firstTierResolvedMarginRate: Double get() = tiers.first().rate }

data class IbkrRatesSnapshot(
    val rates: Map<String, IbkrCurrencyRates>,
    val lastFetch: Long,
    val currencyErrors: Map<String, String> = emptyMap()
)

object IbkrRateFetcher {
    data class TierSpreadParseResult(
        val spreads: Map<String, List<IbkrTierSpread>>,
        val currencyErrors: Map<String, String>,
        val tableFound: Boolean
    )
    data class ResolveResult(val rates: Map<String, IbkrCurrencyRates>, val currencyErrors: Map<String, String>)

    private const val TAG = "IbkrRateFetcher"
    private const val MARGIN_RATES_URL = "https://www.interactivebrokers.com/en/trading/margin-rates.php"
    private const val MONTHLY_BENCHMARKS_URL = "https://www.interactivebrokers.com/en/accounts/fees/monthlyInterestRates.php"
    private const val EFFECTIVE_BENCHMARKS_URL = "https://www.interactivebrokers.com/en/trading/margin-benchmarks.php"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private val compactDate = DateTimeFormatter.BASIC_ISO_DATE
    private val numberRegex = Regex("[\\d,]+")
    private val spreadRegex = Regex("(?i)\\bBM\\s*\\+\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*%")
    private val percentRegex = Regex("^\\s*(?:\\(([+-]?\\d+(?:\\.\\d+)?)\\)|([+-]?\\d+(?:\\.\\d+)?))\\s*\\*?\\s*%")

    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastGoodSnapshot: IbkrRatesSnapshot? = null

    suspend fun fetch(): IbkrRatesSnapshot? = withContext(Dispatchers.IO) {
        try {
            val spreads = parseTierSpreadsFromHtml(fetchDocument(MARGIN_RATES_URL).outerHtml())
            if (!spreads.tableFound) return@withContext retainLastGood("Could not find IBKR margin-rate tier table on page.")
            val benchmarkSets = buildList {
                fetchOptional(MONTHLY_BENCHMARKS_URL)?.let { add(parseMonthlyBenchmarksFromHtml(it.outerHtml())) }
                fetchOptional(EFFECTIVE_BENCHMARKS_URL)?.let { add(parseEffectiveBenchmarksFromHtml(it.outerHtml())) }
            }.filter { it.isNotEmpty() }
            if (benchmarkSets.isEmpty()) return@withContext retainLastGood("No valid IBKR benchmark rates were available from either benchmark page.")
            val resolved = resolveRates(spreads, benchmarkSets)
            if (resolved.rates.isEmpty()) return@withContext retainLastGood(resolved.currencyErrors.values.joinToString(" ").ifBlank { "No IBKR margin rates could be resolved." })
            lastError = resolved.currencyErrors.takeIf { it.isNotEmpty() }?.values?.joinToString(" ")
            val snapshot = IbkrRatesSnapshot((lastGoodSnapshot?.rates ?: emptyMap()) + resolved.rates, System.currentTimeMillis(), resolved.currencyErrors)
            lastGoodSnapshot = snapshot.copy(currencyErrors = emptyMap())
            snapshot
        } catch (e: Exception) {
            retainLastGood("Failed to fetch IBKR rates: ${e.message}")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(30_000).get()
    private fun fetchOptional(url: String): Document? = try { fetchDocument(url) } catch (e: Exception) {
        Log.w(TAG, "IBKR benchmark source $url unavailable", e); null
    }
    private fun retainLastGood(message: String): IbkrRatesSnapshot? {
        lastError = message
        Log.w(TAG, message)
        return lastGoodSnapshot?.let { snapshot ->
            snapshot.copy(currencyErrors = snapshot.rates.keys.associateWith { "$message Using the previously resolved $it rate." })
        }
    }

    internal fun parseTierSpreadsFromHtml(html: String): TierSpreadParseResult {
        val table = Jsoup.parse(html).select("table").firstOrNull { it.selectFirst("th")?.ownText()?.trim() == "Currency" }
            ?: return TierSpreadParseResult(emptyMap(), emptyMap(), false)
        val spreads = mutableMapOf<String, MutableList<IbkrTierSpread>>()
        val errors = mutableMapOf<String, String>()
        var currentCurrency: String? = null
        for (row in table.select("tbody tr")) {
            val cells = row.select("td"); if (cells.size < 3) continue
            if (cells[0].text().isNotBlank()) currentCurrency = cells[0].text().trim().uppercase()
            val currency = currentCurrency ?: continue
            val spread = spreadRegex.find(cells[2].text())?.groupValues?.get(1)?.toDoubleOrNull()
            if (spread == null) { errors[currency] = "IBKR margin-rate tier spread for $currency could not be parsed."; continue }
            val bounds = numberRegex.findAll(cells[1].text()).mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()
            spreads.getOrPut(currency) { mutableListOf() }.add(IbkrTierSpread(if (bounds.size >= 2) bounds[1] else null, spread))
        }
        return TierSpreadParseResult(spreads.filterKeys { it !in errors }.mapValues { it.value.toList() }, errors, true)
    }

    internal fun parseMonthlyBenchmarksFromHtml(html: String): Map<String, IbkrBenchmarkRate> {
        val table = Jsoup.parse(html).select("table").firstOrNull { it.select("th").any { th -> th.text().trim().equals("Date", true) } } ?: return emptyMap()
        val headers = table.select("thead th").map { it.text().trim().uppercase() }
        val dateIndex = headers.indexOf("DATE"); if (dateIndex < 0) return emptyMap()
        val result = mutableMapOf<String, IbkrBenchmarkRate>()
        for (row in table.select("tbody tr")) {
            val cells = row.select("td"); if (dateIndex >= cells.size) continue
            val date = parseDate(cells[dateIndex].text()) ?: continue
            headers.forEachIndexed { index, currency ->
                if (index == dateIndex || index >= cells.size || !currency.matches(Regex("[A-Z]{3}"))) return@forEachIndexed
                val text = cells[index].text().trim(); if ('*' in text) return@forEachIndexed
                val rate = parsePercent(text) ?: return@forEachIndexed
                if (result[currency]?.effectiveDate?.isBefore(date) != false) result[currency] = IbkrBenchmarkRate(rate, date, "monthly-interest-rates")
            }
        }
        return result
    }

    internal fun parseEffectiveBenchmarksFromHtml(html: String): Map<String, IbkrBenchmarkRate> {
        val table = Jsoup.parse(html).select("table").firstOrNull {
            val h = it.select("th").map { th -> th.text().trim().lowercase() }
            "currency" in h && "rate" in h && "effective date" in h
        } ?: return emptyMap()
        val headers = table.select("thead th").map { it.text().trim().lowercase() }
        val ci = headers.indexOf("currency"); val ri = headers.indexOf("rate"); val di = headers.indexOf("effective date")
        val result = mutableMapOf<String, IbkrBenchmarkRate>()
        for (row in table.select("tbody tr")) {
            val cells = row.select("td"); if (listOf(ci, ri, di).any { it < 0 || it >= cells.size }) continue
            val currency = cells[ci].text().trim().uppercase(); if (!currency.matches(Regex("[A-Z]{3}"))) continue
            val rate = parsePercent(cells[ri].text()) ?: continue
            val date = parseDate(cells[di].text()) ?: continue
            if (result[currency]?.effectiveDate?.isBefore(date) != false) result[currency] = IbkrBenchmarkRate(rate, date, "margin-benchmarks")
        }
        return result
    }

    internal fun resolveRates(spreads: TierSpreadParseResult, benchmarkSets: List<Map<String, IbkrBenchmarkRate>>): ResolveResult {
        val rates = mutableMapOf<String, IbkrCurrencyRates>(); val errors = spreads.currencyErrors.toMutableMap()
        for ((currency, tiers) in spreads.spreads) {
            val candidates = benchmarkSets.mapNotNull { it[currency] }
            if (candidates.isEmpty()) { errors[currency] = "No valid IBKR benchmark rate is available for $currency."; continue }
            val date = candidates.maxOf { it.effectiveDate }; val latest = candidates.filter { it.effectiveDate == date }
            if (latest.map { it.rate }.distinct().size > 1) { errors[currency] = "IBKR benchmark sources conflict for $currency on $date."; continue }
            val benchmark = latest.first()
            rates[currency] = IbkrCurrencyRates(currency, tiers.map { IbkrRateTier(it.upTo, benchmark.rate + it.spread) }, benchmark.rate, date, latest.joinToString("+") { it.source })
        }
        return ResolveResult(rates, errors)
    }

    private fun parsePercent(text: String): Double? {
        val match = percentRegex.find(text) ?: return null
        val value = (match.groupValues[1].ifBlank { match.groupValues[2] }).toDoubleOrNull() ?: return null
        return if (match.groupValues[1].isNotBlank()) -kotlin.math.abs(value) else value
    }
    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim(), compactDate).takeUnless { it.isAfter(LocalDate.now().plusDays(2)) }
    } catch (_: DateTimeParseException) { null }
}
