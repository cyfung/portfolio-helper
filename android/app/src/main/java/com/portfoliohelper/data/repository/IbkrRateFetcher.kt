package com.portfoliohelper.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class IbkrRateTier(val upTo: Double?, val rate: Double)
data class IbkrCurrencyRates(val currency: String, val tiers: List<IbkrRateTier>) {
    val baseRate: Double get() = tiers.first().rate
}

data class IbkrRatesSnapshot(
    val rates: Map<String, IbkrCurrencyRates>,
    val lastFetch: Long,
    val currencyErrors: Map<String, String> = emptyMap()
)

object IbkrRateFetcher {
    private const val TAG = "IbkrRateFetcher"
    private const val TABLE_MISSING_ERROR = "Could not find IBKR margin rates table on page."

    private val numberRegex = Regex("[\\d,]+")
    private val chargedRateRegex = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*%")

    @Volatile
    var lastError: String? = null
        private set

    suspend fun fetch(): IbkrRatesSnapshot? = withContext(Dispatchers.IO) {
        try {
            val doc =
                Jsoup.connect("https://www.interactivebrokers.com/en/trading/margin-rates.php")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(30_000)
                    .get()

            val parsed = parseRatesFromHtml(doc.outerHtml())
            if (!parsed.tableFound) {
                lastError = TABLE_MISSING_ERROR
                Log.w(TAG, TABLE_MISSING_ERROR)
                return@withContext null
            }
            if (parsed.rates.isEmpty() && parsed.currencyErrors.isEmpty()) {
                val message = "IBKR margin rates page parsed but no valid rates found."
                lastError = message
                Log.w(TAG, message)
                return@withContext null
            }

            lastError = parsed.currencyErrors.takeIf { it.isNotEmpty() }
                ?.values
                ?.joinToString(" ")
            IbkrRatesSnapshot(parsed.rates, System.currentTimeMillis(), parsed.currencyErrors)
        } catch (e: Exception) {
            val message = "Failed to fetch IBKR rates: ${e.message}"
            lastError = message
            Log.w(TAG, message)
            null
        }
    }

    data class ParseResult(
        val rates: Map<String, IbkrCurrencyRates>,
        val currencyErrors: Map<String, String>,
        val tableFound: Boolean
    )

    internal fun parseRatesFromHtml(html: String): ParseResult {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull { tbl ->
            tbl.selectFirst("th")?.ownText()?.trim() == "Currency"
        } ?: return ParseResult(emptyMap(), emptyMap(), tableFound = false)

        val rows = table.select("tbody tr")
        val currencyTiers = mutableMapOf<String, MutableList<IbkrRateTier>>()
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

            val rate = chargedRateRegex.find(cell2)?.groupValues?.get(1)?.toDoubleOrNull()
            if (rate == null) {
                failedCurrencies.add(ccy)
                continue
            }
            val numbers = numberRegex.findAll(cell1)
                .mapNotNull { it.value.replace(",", "").toDoubleOrNull() }
                .toList()
            val upTo = if (numbers.size >= 2) numbers[1] else null

            currencyTiers.getOrPut(ccy) { mutableListOf() }.add(IbkrRateTier(upTo, rate))
        }

        failedCurrencies.addAll(presentCurrencies - currencyTiers.keys)

        val rates = currencyTiers
            .asSequence()
            .filter { it.value.isNotEmpty() && it.key !in failedCurrencies }
            .associate { (ccy, tiers) -> ccy to IbkrCurrencyRates(ccy, tiers) }
        val errors = failedCurrencies.associateWith { ccy ->
            "IBKR margin rate for $ccy is unavailable because the page only shows a benchmark formula, not a resolved charged rate."
        }

        return ParseResult(rates, errors, tableFound = true)
    }
}
