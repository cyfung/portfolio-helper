package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

object IbkrMarginRateService {
    data class RateTier(val upTo: Double?, val rate: Double)
    data class TierSpread(val upTo: Double?, val spread: Double)
    data class BenchmarkRate(val rate: Double, val effectiveDate: LocalDate, val source: String)

    data class CurrencyRates(
        val currency: String,
        val tiers: List<RateTier>,
        val benchmarkRate: Double,
        val benchmarkEffectiveDate: LocalDate,
        val benchmarkSource: String
    ) {
        val firstTierResolvedMarginRate: Double get() = tiers.first().rate
    }

    data class RatesSnapshot(
        val rates: Map<String, CurrencyRates>,
        val lastFetch: Long,
        val errorMessage: String? = null,
        val currencyErrors: Map<String, String> = emptyMap()
    )

    data class TierSpreadParseResult(
        val spreads: Map<String, List<TierSpread>>,
        val currencyErrors: Map<String, String>,
        val tableFound: Boolean
    )

    data class ResolveResult(
        val rates: Map<String, CurrencyRates>,
        val currencyErrors: Map<String, String>
    )

    private val logger = LoggerFactory.getLogger(IbkrMarginRateService::class.java)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compactDate = DateTimeFormatter.BASIC_ISO_DATE
    private val numberRegex = Regex("[\\d,]+")
    private val spreadRegex = Regex("(?i)\\bBM\\s*\\+\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*%")
    private val percentRegex = Regex("^\\s*(?:\\(([+-]?\\d+(?:\\.\\d+)?)\\)|([+-]?\\d+(?:\\.\\d+)?))\\s*\\*?\\s*%")
    private const val ERROR_RETRY_MS = 5 * 60 * 1000L
    private const val MARGIN_RATES_URL = "https://www.interactivebrokers.com/en/trading/margin-rates.php"
    private const val MONTHLY_BENCHMARKS_URL = "https://www.interactivebrokers.com/en/accounts/fees/monthlyInterestRates.php"
    private const val EFFECTIVE_BENCHMARKS_URL = "https://www.interactivebrokers.com/en/trading/margin-benchmarks.php"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

    private val _ratesFlow = MutableStateFlow(RatesSnapshot(emptyMap(), 0L))
    val ratesFlow: StateFlow<RatesSnapshot> = _ratesFlow

    fun initialize() {
        serviceScope.launch {
            var lastFetchOk = fetchRates()
            while (isActive) {
                val normalDelay = AppConfig.ibkrRateIntervalMs
                delay(if (lastFetchOk) normalDelay else minOf(normalDelay, ERROR_RETRY_MS))
                lastFetchOk = fetchRates()
            }
        }
    }

    fun getLastFetchMillis(): Long = _ratesFlow.value.lastFetch
    fun canReload(): Boolean = _ratesFlow.value.lastFetch.let { it == 0L || System.currentTimeMillis() - it > 10 * 60 * 1000L }
    suspend fun reloadNow() = kotlinx.coroutines.withContext(Dispatchers.IO) { fetchRates() }
    fun shutdown() { serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun fetchRates(): Boolean {
        val previous = _ratesFlow.value
        return try {
            val spreadDoc = fetchDocument(MARGIN_RATES_URL)
            val spreads = parseTierSpreadsFromHtml(spreadDoc.outerHtml())
            if (!spreads.tableFound) return retainPrevious(previous, "Could not find IBKR margin-rate tier table on page.")

            val benchmarkSets = buildList {
                fetchOptional(MONTHLY_BENCHMARKS_URL)?.let { add(parseMonthlyBenchmarksFromHtml(it.outerHtml())) }
                fetchOptional(EFFECTIVE_BENCHMARKS_URL)?.let { add(parseEffectiveBenchmarksFromHtml(it.outerHtml())) }
            }.filter { it.isNotEmpty() }
            if (benchmarkSets.isEmpty()) return retainPrevious(previous, "No valid IBKR benchmark rates were available from either benchmark page.")

            val resolved = resolveRates(spreads, benchmarkSets)
            if (resolved.rates.isEmpty()) return retainPrevious(previous, resolved.currencyErrors.values.joinToString(" ").ifBlank { "No IBKR margin rates could be resolved." })

            val mergedRates = previous.rates + resolved.rates
            _ratesFlow.value = RatesSnapshot(mergedRates, System.currentTimeMillis(), currencyErrors = resolved.currencyErrors)
            logger.info("Fetched resolved IBKR margin rates for ${resolved.rates.keys.sorted()}")
            resolved.currencyErrors.isEmpty()
        } catch (e: Exception) {
            retainPrevious(previous, "Failed to fetch IBKR margin rates: ${e.message}")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(30_000).get()

    private fun fetchOptional(url: String): Document? = try {
        fetchDocument(url)
    } catch (e: Exception) {
        logger.warn("IBKR benchmark source $url was unavailable: ${e.message}")
        null
    }

    private fun retainPrevious(previous: RatesSnapshot, message: String): Boolean {
        _ratesFlow.value = if (previous.rates.isEmpty()) {
            previous.copy(errorMessage = message)
        } else {
            previous.copy(
                errorMessage = null,
                currencyErrors = previous.rates.keys.associateWith { "$message Using the previously resolved $it rate." }
            )
        }
        logger.warn(message)
        return false
    }

    internal fun parseTierSpreadsFromHtml(html: String): TierSpreadParseResult {
        val table = Jsoup.parse(html).select("table").firstOrNull { it.selectFirst("th")?.ownText()?.trim() == "Currency" }
            ?: return TierSpreadParseResult(emptyMap(), emptyMap(), false)
        val currencySpreads = mutableMapOf<String, MutableList<TierSpread>>()
        val errors = mutableMapOf<String, String>()
        var currentCurrency: String? = null
        for (row in table.select("tbody tr")) {
            val cells = row.select("td")
            if (cells.size < 3) continue
            if (cells[0].text().isNotBlank()) currentCurrency = cells[0].text().trim().uppercase()
            val currency = currentCurrency ?: continue
            val spread = spreadRegex.find(cells[2].text())?.groupValues?.get(1)?.toDoubleOrNull()
            if (spread == null) {
                errors[currency] = "IBKR margin-rate tier spread for $currency could not be parsed."
                continue
            }
            val bounds = numberRegex.findAll(cells[1].text()).mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()
            currencySpreads.getOrPut(currency) { mutableListOf() }.add(TierSpread(if (bounds.size >= 2) bounds[1] else null, spread))
        }
        val valid = currencySpreads.filterKeys { it !in errors }.mapValues { it.value.toList() }
        return TierSpreadParseResult(valid, errors, true)
    }

    internal fun parseMonthlyBenchmarksFromHtml(html: String): Map<String, BenchmarkRate> {
        val table = Jsoup.parse(html).select("table").firstOrNull { table -> table.select("th").any { it.text().trim().equals("Date", true) } }
            ?: return emptyMap()
        val headers = table.select("thead th").map { it.text().trim().uppercase() }
        val dateIndex = headers.indexOfFirst { it == "DATE" }
        if (dateIndex < 0) return emptyMap()
        val results = mutableMapOf<String, BenchmarkRate>()
        for (row in table.select("tbody tr")) {
            val cells = row.select("td")
            if (dateIndex >= cells.size) continue
            val date = parseDate(cells[dateIndex].text()) ?: continue
            headers.forEachIndexed { index, currency ->
                if (index == dateIndex || index >= cells.size || !currency.matches(Regex("[A-Z]{3}"))) return@forEachIndexed
                val text = cells[index].text().trim()
                if ('*' in text) return@forEachIndexed
                val rate = parsePercent(text) ?: return@forEachIndexed
                val candidate = BenchmarkRate(rate, date, "monthly-interest-rates")
                if (results[currency]?.effectiveDate?.isBefore(date) != false) results[currency] = candidate
            }
        }
        return results
    }

    internal fun parseEffectiveBenchmarksFromHtml(html: String): Map<String, BenchmarkRate> {
        val table = Jsoup.parse(html).select("table").firstOrNull { table ->
            val headers = table.select("th").map { it.text().trim().lowercase() }
            headers.any { it == "currency" } && headers.any { it == "rate" } && headers.any { it == "effective date" }
        } ?: return emptyMap()
        val headers = table.select("thead th").map { it.text().trim().lowercase() }
        val currencyIndex = headers.indexOf("currency")
        val rateIndex = headers.indexOf("rate")
        val dateIndex = headers.indexOf("effective date")
        val results = mutableMapOf<String, BenchmarkRate>()
        for (row in table.select("tbody tr")) {
            val cells = row.select("td")
            if (listOf(currencyIndex, rateIndex, dateIndex).any { it < 0 || it >= cells.size }) continue
            val currency = cells[currencyIndex].text().trim().uppercase()
            if (!currency.matches(Regex("[A-Z]{3}"))) continue
            val rate = parsePercent(cells[rateIndex].text()) ?: continue
            val date = parseDate(cells[dateIndex].text()) ?: continue
            val candidate = BenchmarkRate(rate, date, "margin-benchmarks")
            if (results[currency]?.effectiveDate?.isBefore(date) != false) results[currency] = candidate
        }
        return results
    }

    internal fun resolveRates(spreads: TierSpreadParseResult, benchmarkSets: List<Map<String, BenchmarkRate>>): ResolveResult {
        val rates = mutableMapOf<String, CurrencyRates>()
        val errors = spreads.currencyErrors.toMutableMap()
        for ((currency, tiers) in spreads.spreads) {
            val candidates = benchmarkSets.mapNotNull { it[currency] }
            if (candidates.isEmpty()) {
                errors[currency] = "No valid IBKR benchmark rate is available for $currency."
                continue
            }
            val latestDate = candidates.maxOf { it.effectiveDate }
            val latest = candidates.filter { it.effectiveDate == latestDate }
            if (latest.map { it.rate }.distinct().size > 1) {
                errors[currency] = "IBKR benchmark sources conflict for $currency on $latestDate."
                continue
            }
            val benchmark = latest.first()
            rates[currency] = CurrencyRates(
                currency,
                tiers.map { RateTier(it.upTo, benchmark.rate + it.spread) },
                benchmark.rate,
                benchmark.effectiveDate,
                latest.joinToString("+") { it.source }
            )
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
    } catch (_: DateTimeParseException) {
        null
    }
}
