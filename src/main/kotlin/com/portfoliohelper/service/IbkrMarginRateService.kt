package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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

        /** Blended interest rate for the given loan amount in this currency */
        fun blendedRate(amount: Double): Double {
            var remaining = amount
            var totalInterest = 0.0
            var prevUpTo = 0.0
            for (tier in tiers) {
                val capacity = if (tier.upTo != null) tier.upTo - prevUpTo else Double.MAX_VALUE
                val inTier = minOf(remaining, capacity)
                totalInterest += inTier * tier.rate / 100.0
                remaining -= inTier
                if (remaining <= 0) break
                prevUpTo = tier.upTo ?: 0.0
            }
            return if (amount > 0) (totalInterest / amount) * 100.0 else baseRate
        }

        /** Returns blended rate only if amount exceeds the base tier cap; otherwise null */
        fun blendedRateIfMultiTier(amount: Double): Double? {
            val baseCap = tiers.first().upTo ?: return null
            return if (amount > baseCap) blendedRate(amount) else null
        }
    }

    private val logger = LoggerFactory.getLogger(IbkrMarginRateService::class.java)
    private val ratesCache = ConcurrentHashMap<String, CurrencyRates>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val numberRegex = Regex("[\\d,]+")
    private val rateRegex = Regex("(\\d+\\.\\d+)%")

    fun initialize() {
        serviceScope.launch {
            fetchRates()
            while (isActive) {
                delay(24 * 60 * 60 * 1000L)
                fetchRates()
            }
        }
    }

    fun getRates(currency: String): CurrencyRates? = ratesCache[currency.uppercase()]

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
                val numbers = numberRegex.findAll(cell1).map { it.value.replace(",", "").toDoubleOrNull() }.filterNotNull().toList()
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
                ratesCache.clear()
                ratesCache.putAll(newRates)
                logger.info("Fetched IBKR margin rates for ${newRates.size} currencies: ${newRates.keys.sorted()}")
            } else {
                logger.warn("IBKR margin rates page parsed but no valid rates found")
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch IBKR margin rates: ${e.message}")
        }
    }
}
