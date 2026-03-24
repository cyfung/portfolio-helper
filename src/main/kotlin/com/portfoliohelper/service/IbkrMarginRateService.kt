package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.portfoliohelper.AppConfig
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

object IbkrMarginRateService {

    data class RateTier(
        val upTo: Double?,   // null = unlimited (top tier); in the loan currency
        val rate: Double     // percentage e.g. 5.14 for 5.14%
    )

    data class CurrencyRates(
        val currency: String,
        val tiers: List<RateTier>   // ordered lowest → highest threshold
    ) {
        val baseRate: Double get() = tiers.first().rate
    }

    data class RatesSnapshot(val rates: Map<String, CurrencyRates>, val lastFetch: Long)

    private val logger = LoggerFactory.getLogger(IbkrMarginRateService::class.java)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val numberRegex = Regex("[\\d,]+")
    private val rateRegex = Regex("(\\d+\\.\\d+)%")

    private val _ratesFlow = MutableStateFlow(RatesSnapshot(emptyMap(), 0L))
    val ratesFlow: StateFlow<RatesSnapshot> = _ratesFlow

    fun initialize() {
        serviceScope.launch {
            fetchRates()
            while (isActive) {
                delay(AppConfig.ibkrRateIntervalMs)
                fetchRates()
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

    private fun fetchRates() {
        try {
            val doc = Jsoup.connect("https://www.interactivebrokers.com/en/trading/margin-rates.php")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(30_000)
                .get()

            val table = doc.select("table").firstOrNull { tbl ->
                tbl.selectFirst("th")?.ownText()?.trim() == "Currency"
            } ?: run {
                logger.warn("Could not find IBKR margin rates table on page")
                return
            }

            val rows = table.select("tbody tr")
            val currencyTiers = mutableMapOf<String, MutableList<RateTier>>()
            var currentCurrency: String? = null

            for (row in rows) {
                val cells = row.select("td")
                if (cells.size < 3) continue

                val cell0 = cells[0].text().trim()
                val cell1 = cells[1].text().trim()
                val cell2 = cells[2].text().trim()

                // Currency column: non-empty starts a new group
                if (cell0.isNotEmpty()) {
                    currentCurrency = cell0.uppercase()
                }
                val ccy = currentCurrency ?: continue

                // Parse rate from cell[2]
                val rateMatch = rateRegex.find(cell2) ?: continue
                val rate = rateMatch.groupValues[1].toDoubleOrNull() ?: continue

                // Parse upper bound from cell[1]
                // Tier cell examples: "0 - 100,000", "> 100,000", "100,000 - 1,000,000"
                val numbers = numberRegex.findAll(cell1)
                    .mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()
                val upTo: Double? = when {
                    numbers.size >= 2 -> numbers[1]   // "X - Y" → upper bound is Y
                    numbers.size == 1 -> {
                        // Could be "> X" (no upper) or a standalone number
                        if (cell1.contains(">") || cell1.contains("above", ignoreCase = true)) null
                        else null  // single number without context → treat as no upper
                    }
                    else -> null  // last/unlimited tier
                }

                currencyTiers.getOrPut(ccy) { mutableListOf() }.add(RateTier(upTo = upTo, rate = rate))
            }

            val newRates = currencyTiers
                .filter { it.value.isNotEmpty() }
                .map { (ccy, tiers) -> ccy to CurrencyRates(currency = ccy, tiers = tiers) }
                .toMap()

            if (newRates.isNotEmpty()) {
                _ratesFlow.value = RatesSnapshot(newRates, System.currentTimeMillis())
                logger.info("Fetched IBKR margin rates for ${newRates.size} currencies: ${newRates.keys.sorted()}")
            } else {
                logger.warn("IBKR margin rates page parsed but no valid rates found")
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch IBKR margin rates: ${e.message}")
        }
    }

}
