package com.portfoliohelper.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class IbkrRateTier(val upTo: Double?, val rate: Double)
data class IbkrCurrencyRates(val currency: String, val tiers: List<IbkrRateTier>) {
    val baseRate: Double get() = tiers.first().rate
}

data class IbkrRatesSnapshot(val rates: Map<String, IbkrCurrencyRates>, val lastFetch: Long)

object IbkrRateFetcher {
    private val numberRegex = Regex("[\\d,]+")
    private val rateRegex = Regex("(\\d+\\.\\d+)%")

    suspend fun fetch(): IbkrRatesSnapshot? = withContext(Dispatchers.IO) {
        try {
            val doc =
                Jsoup.connect("https://www.interactivebrokers.com/en/trading/margin-rates.php")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(30_000)
                    .get()

            val table = doc.select("table").firstOrNull { tbl ->
                tbl.selectFirst("th")?.ownText()?.trim() == "Currency"
            } ?: return@withContext null

            val rows = table.select("tbody tr")
            val currencyTiers = mutableMapOf<String, MutableList<IbkrRateTier>>()
            var currentCurrency: String? = null

            for (row in rows) {
                val cells = row.select("td")
                if (cells.size < 3) continue
                val cell0 = cells[0].text().trim()
                val cell1 = cells[1].text().trim()
                val cell2 = cells[2].text().trim()
                if (cell0.isNotEmpty()) currentCurrency = cell0.uppercase()
                val ccy = currentCurrency ?: continue
                val rate = rateRegex.find(cell2)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val numbers = numberRegex.findAll(cell1)
                    .mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()
                val upTo: Double? = if (numbers.size >= 2) numbers[1]
                else if (cell1.contains(">") || cell1.contains("above", ignoreCase = true)) null
                else null
                currencyTiers.getOrPut(ccy) { mutableListOf() }.add(IbkrRateTier(upTo, rate))
            }

            val newRates = currencyTiers
                .asSequence().filter { it.value.isNotEmpty() }
                .associate { (ccy, tiers) -> ccy to IbkrCurrencyRates(ccy, tiers) }

            if (newRates.isEmpty()) return@withContext null
            IbkrRatesSnapshot(newRates, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w("IbkrRateFetcher", "Failed to fetch IBKR rates: ${e.message}")
            null
        }
    }
}
