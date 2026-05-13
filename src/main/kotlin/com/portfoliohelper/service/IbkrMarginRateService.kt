package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

object IbkrMarginRateService {

    data class RateTier(
        val upTo: Double?,   // null = unlimited (top tier); in the loan currency
        val rate: Double     // percentage e.g. 5.14 for 5.14%
    )

    data class CurrencyRates(
        val currency: String,
        val tiers: List<RateTier>   // ordered lowest to highest threshold
    ) {
        val baseRate: Double get() = tiers.first().rate
    }

    data class RatesSnapshot(
        val rates: Map<String, CurrencyRates>,
        val lastFetch: Long,
        val errorMessage: String? = null,
        val currencyErrors: Map<String, String> = emptyMap()
    )

    data class ParseResult(
        val rates: Map<String, CurrencyRates>,
        val currencyErrors: Map<String, String>,
        val tableFound: Boolean
    )

    private val logger = LoggerFactory.getLogger(IbkrMarginRateService::class.java)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val numberRegex = Regex("[\\d,]+")
    private val chargedRateRegex = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*%")
    private const val ERROR_RETRY_MS = 5 * 60 * 1000L
    private const val TABLE_MISSING_ERROR = "Could not find IBKR margin rates table on page."

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

    fun canReload(): Boolean {
        val last = _ratesFlow.value.lastFetch
        return last == 0L || System.currentTimeMillis() - last > 10 * 60 * 1000L
    }

    suspend fun reloadNow() = kotlinx.coroutines.withContext(Dispatchers.IO) { fetchRates() }

    fun shutdown() {
        serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun fetchRates(): Boolean {
        try {
            val doc = Jsoup.connect("https://www.interactivebrokers.com/en/trading/margin-rates.php")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(30_000)
                .get()

            val parsed = parseRatesFromHtml(doc.outerHtml())
            if (!parsed.tableFound) {
                _ratesFlow.value = RatesSnapshot(emptyMap(), 0L, TABLE_MISSING_ERROR)
                logger.warn(TABLE_MISSING_ERROR)
                return false
            }

            return if (parsed.rates.isNotEmpty() || parsed.currencyErrors.isNotEmpty()) {
                val now = System.currentTimeMillis()
                _ratesFlow.value = RatesSnapshot(
                    rates = parsed.rates,
                    lastFetch = now,
                    currencyErrors = parsed.currencyErrors
                )
                if (parsed.currencyErrors.isEmpty()) {
                    logger.info("Fetched IBKR margin rates for ${parsed.rates.size} currencies: ${parsed.rates.keys.sorted()}")
                } else {
                    logger.warn("Fetched IBKR margin rates with parse errors for currencies: ${parsed.currencyErrors.keys.sorted()}")
                }
                parsed.currencyErrors.isEmpty()
            } else {
                val message = "IBKR margin rates page parsed but no valid rates found."
                _ratesFlow.value = RatesSnapshot(emptyMap(), 0L, message)
                logger.warn(message)
                false
            }
        } catch (e: Exception) {
            val message = "Failed to fetch IBKR margin rates: ${e.message}"
            _ratesFlow.value = RatesSnapshot(emptyMap(), 0L, message)
            logger.warn(message)
            return false
        }
    }

    internal fun parseRatesFromHtml(html: String): ParseResult {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull { tbl ->
            tbl.selectFirst("th")?.ownText()?.trim() == "Currency"
        } ?: return ParseResult(emptyMap(), emptyMap(), tableFound = false)

        val rows = table.select("tbody tr")
        val currencyTiers = mutableMapOf<String, MutableList<RateTier>>()
        val presentCurrencies = mutableSetOf<String>()
        val failedCurrencies = mutableSetOf<String>()
        var currentCurrency: String? = null

        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 3) continue

            val cell0 = cells[0].text().trim()
            val cell1 = cells[1].text().trim()
            val cell2 = cells[2].text().trim()

            if (cell0.isNotEmpty()) {
                currentCurrency = cell0.uppercase()
                presentCurrencies.add(currentCurrency)
            }
            val ccy = currentCurrency ?: continue

            val rateMatch = chargedRateRegex.find(cell2)
            val rate = rateMatch?.groupValues?.get(1)?.toDoubleOrNull()
            if (rate == null) {
                failedCurrencies.add(ccy)
                continue
            }

            val numbers = numberRegex.findAll(cell1)
                .mapNotNull { it.value.replace(",", "").toDoubleOrNull() }
                .toList()
            val upTo = if (numbers.size >= 2) numbers[1] else null

            currencyTiers.getOrPut(ccy) { mutableListOf() }.add(RateTier(upTo = upTo, rate = rate))
        }

        failedCurrencies.addAll(presentCurrencies - currencyTiers.keys)

        val rates = currencyTiers
            .filter { it.value.isNotEmpty() && it.key !in failedCurrencies }
            .map { (ccy, tiers) -> ccy to CurrencyRates(currency = ccy, tiers = tiers) }
            .toMap()
        val errors = failedCurrencies.associateWith { ccy ->
            "IBKR margin rate for $ccy is unavailable because the page only shows a benchmark formula, not a resolved charged rate."
        }

        return ParseResult(rates, errors, tableFound = true)
    }
}
