package com.portfoliohelper.data.repository

import android.util.Log
import com.portfoliohelper.data.model.AllocMode
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.GroupRow
import com.portfoliohelper.data.model.MarketPrice
import com.portfoliohelper.data.model.Position
import kotlin.math.abs

object PortfolioCalculator {

    private const val TAG = "PortfolioCalculator"

    // ── Portfolio totals ──────────────────────────────────────────────────────

    data class PortfolioTotals(
        val stockGrossValue: Double,
        val prevStockGrossValue: Double,
        val cashTotalUsd: Double,
        val marginUsd: Double,
        val marginPct: Double,       // margin as % of equity+margin
        val dayChangeDollars: Double,
        val dayChangePct: Double,
        val isReady: Boolean = true
    )

    /**
     * Computes portfolio totals using a unified prices map that includes both
     * stock symbols and FX pairs (e.g., "HKDUSD=X").
     */
    fun computeTotals(
        positions: List<Position>,
        cashEntries: List<CashEntry>,
        prices: Map<String, YahooQuote>
    ): PortfolioTotals {
        var allReady = true
        var total = 0.0
        var prevTotal = 0.0

        for (pos in positions) {
            val quote = prices[pos.symbol]
            if (quote == null) {
                allReady = false
                continue
            }
            
            val currency = quote.currency ?: "USD"
            val isPence = currency.length == 3 && currency[2].isLowerCase()
            val normalizedCcy = if (isPence) currency.uppercase() else currency

            val rate = if (normalizedCcy == "USD") 1.0 else {
                val pair = "${normalizedCcy}USD=X"
                val fxQuote = prices[pair]
                if (fxQuote == null) {
                    allReady = false
                    null
                } else {
                    fxQuote.regularMarketPrice ?: fxQuote.previousClose
                }
            }

            if (rate == null) {
                allReady = false
                continue
            }

            val multiplier = if (isPence) rate / 100.0 else rate

            val mark = (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier
            val prev = (quote.previousClose ?: quote.regularMarketPrice ?: 0.0) * multiplier
            total += mark * pos.quantity
            prevTotal += prev * pos.quantity
        }

        var cashTotalUsd = 0.0
        var marginUsd = 0.0
        for (e in cashEntries) {
            val rate = if (e.currency == "USD") 1.0 else {
                val pair = "${e.currency}USD=X"
                val quote = prices[pair]
                if (quote == null) {
                    allReady = false
                    null
                } else {
                    quote.regularMarketPrice ?: quote.previousClose
                }
            }
            
            if (rate == null) {
                allReady = false
                continue
            }
            val usd = e.amount * rate
            cashTotalUsd += usd
            if (e.isMargin) {
                marginUsd += usd
            }
        }
        
        val equity = total + marginUsd
        val marginPct = if (equity != 0.0) abs(marginUsd / equity) * 100.0 else 0.0
        val change = total - prevTotal
        val changePct = if (prevTotal != 0.0) (change / prevTotal) * 100.0 else 0.0
        
        return PortfolioTotals(total, prevTotal, cashTotalUsd, marginUsd, marginPct, change, changePct, allReady)
    }

    // ── Market Data Fetching & Caching ────────────────────────────────────────

    /**
     * Fetches fresh quotes for all positions and FX pairs, updating the DB cache.
     * Falls back to DB cache if network fetch fails for a specific symbol.
     */
    suspend fun fetchAndCacheMarketData(
        db: AppDatabase,
        positions: List<Position>,
        cashEntries: List<CashEntry>
    ): Map<String, YahooQuote> {
        val initialSymbols = positions.filter { !it.isDeleted }.map { it.symbol }.distinct().toMutableList()
        val cashCurrencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
        cashCurrencies.forEach { initialSymbols.add("${it}USD=X") }

        val results = mutableMapOf<String, YahooQuote>()
        val pendingSymbols = initialSymbols.toMutableSet()
        val processedSymbols = mutableSetOf<String>()

        while (pendingSymbols.isNotEmpty()) {
            val symbol = pendingSymbols.first()
            pendingSymbols.remove(symbol)
            processedSymbols.add(symbol)

            try {
                val quote = YahooFinanceClient.fetchQuote(symbol)
                val price = quote.regularMarketPrice ?: quote.previousClose
                if (price != null) {
                    db.marketPriceDao().upsert(
                        MarketPrice(symbol, price, quote.previousClose, quote.isMarketClosed, currency = quote.currency)
                    )
                    // Push to live service if it's active (updates UI in real-time)
                    YahooMarketDataService.updateCache(symbol, quote)
                }
                results[symbol] = quote

                // If this is a stock with a non-USD currency, we might need its FX rate
                val stockCcyRaw = quote.currency
                if (stockCcyRaw != null && stockCcyRaw != "USD" && !stockCcyRaw.endsWith("=X")) {
                    val isPence = stockCcyRaw.length == 3 && stockCcyRaw[2].isLowerCase()
                    val normalizedCcy = if (isPence) stockCcyRaw.uppercase() else stockCcyRaw
                    
                    val fxPair = "${normalizedCcy}USD=X"
                    if (!processedSymbols.contains(fxPair)) {
                        pendingSymbols.add(fxPair)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch $symbol, falling back to DB: ${e.message}")
                db.marketPriceDao().get(symbol)?.let { cached ->
                    val quote = YahooQuote(symbol, cached.price, cached.previousClose, cached.isMarketClosed, cached.currency)
                    results[symbol] = quote
                    
                    val stockCcyRaw = cached.currency
                    if (stockCcyRaw != null && stockCcyRaw != "USD" && !stockCcyRaw.endsWith("=X")) {
                        val isPence = stockCcyRaw.length == 3 && stockCcyRaw[2].isLowerCase()
                        val normalizedCcy = if (isPence) stockCcyRaw.uppercase() else stockCcyRaw
                        
                        val fxPair = "${normalizedCcy}USD=X"
                        if (!processedSymbols.contains(fxPair)) {
                            pendingSymbols.add(fxPair)
                        }
                    }
                }
            }
        }

        return results
    }

    /**
     * Loads all cached market data from the database.
     */
    suspend fun loadCachedMarketData(db: AppDatabase): Map<String, YahooQuote> {
        return db.marketPriceDao().getAll().associate { cached ->
            cached.symbol to YahooQuote(cached.symbol, cached.price, cached.previousClose, cached.isMarketClosed, cached.currency)
        }
    }

    // ── Allocation ────────────────────────────────────────────────────────────

    fun computeAllocations(
        delta: Double,
        positions: List<Position>,
        prices: Map<String, YahooQuote>,
        mode: AllocMode
    ): Map<String, Double> {
        val totalVal = positions.sumOf { pos ->
            val quote = prices[pos.symbol]
            if (quote == null) return@sumOf 0.0
            
            val currency = quote.currency ?: "USD"
            val isPence = currency.length == 3 && currency[2].isLowerCase()
            val normalizedCcy = if (isPence) currency.uppercase() else currency

            val rate = if (normalizedCcy == "USD") 1.0 else {
                val pair = "${normalizedCcy}USD=X"
                prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
            }
            val multiplier = if (isPence) rate / 100.0 else rate
            (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier * pos.quantity
        }

        return when (mode) {
            AllocMode.PROPORTIONAL -> positions.associate { pos ->
                pos.symbol to (pos.targetWeight / 100.0) * delta
            }

            AllocMode.CURRENT_WEIGHT -> positions.associate { pos ->
                val quote = prices[pos.symbol]
                if (quote == null) return@associate pos.symbol to 0.0
                
                val currency = quote.currency ?: "USD"
                val isPence = currency.length == 3 && currency[2].isLowerCase()
                val normalizedCcy = if (isPence) currency.uppercase() else currency

                val rate = if (normalizedCcy == "USD") 1.0 else {
                    val pair = "${normalizedCcy}USD=X"
                    prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
                }
                val multiplier = if (isPence) rate / 100.0 else rate
                val markPrice = (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier
                val w = if (totalVal > 0) markPrice * pos.quantity / totalVal else 0.0
                pos.symbol to w * delta
            }

            AllocMode.UNDERVALUED_PRIORITY -> computeUndervaluedFirst(
                positions,
                prices,
                totalVal,
                delta
            )

            AllocMode.WATERFALL -> computeWaterfall(positions, prices, totalVal, delta)
        }
    }

    private fun computeUndervaluedFirst(
        positions: List<Position>,
        prices: Map<String, YahooQuote>,
        totalVal: Double,
        delta: Double
    ): Map<String, Double> {
        val finalTotal = totalVal + delta
        val sign = if (delta >= 0) 1.0 else -1.0
        val alloc = positions.associate { it.symbol to 0.0 }.toMutableMap()
        val eligible = positions.filter { it.targetWeight > 0 }
        val sorted = eligible.sortedBy { pos ->
            val quote = prices[pos.symbol] ?: return@sortedBy 0.0
            
            val currency = quote.currency ?: "USD"
            val isPence = currency.length == 3 && currency[2].isLowerCase()
            val normalizedCcy = if (isPence) currency.uppercase() else currency

            val rate = if (normalizedCcy == "USD") 1.0 else {
                val pair = "${normalizedCcy}USD=X"
                prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
            }
            val multiplier = if (isPence) rate / 100.0 else rate
            val curVal = (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier * pos.quantity
            sign * ((curVal / finalTotal) - (pos.targetWeight / 100.0))
        }
        var remaining = abs(delta)
        for (pos in sorted) {
            if (remaining <= 0) break
            val quote = prices[pos.symbol] ?: continue
            
            val currency = quote.currency ?: "USD"
            val isPence = currency.length == 3 && currency[2].isLowerCase()
            val normalizedCcy = if (isPence) currency.uppercase() else currency

            val rate = if (normalizedCcy == "USD") 1.0 else {
                val pair = "${normalizedCcy}USD=X"
                prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
            }
            val multiplier = if (isPence) rate / 100.0 else rate
            val curVal = (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier * pos.quantity
            val target = finalTotal * (pos.targetWeight / 100.0)
            val amount = minOf(remaining, maxOf(0.0, (target - curVal) * sign))
            alloc[pos.symbol] = amount * sign
            remaining -= amount
        }
        if (remaining > 0) {
            val totalW = eligible.sumOf { it.targetWeight }
            eligible.forEach { pos ->
                alloc[pos.symbol] =
                    (alloc[pos.symbol] ?: 0.0) + (pos.targetWeight / totalW) * remaining * sign
            }
        }
        return alloc
    }

    private fun computeWaterfall(
        positions: List<Position>,
        prices: Map<String, YahooQuote>,
        totalVal: Double,
        delta: Double
    ): Map<String, Double> {
        val finalTotal = totalVal + delta
        val sign = if (delta >= 0) 1.0 else -1.0
        val eligible = positions.filter { it.targetWeight > 0 }
        val alloc = eligible.associate { it.symbol to 0.0 }.toMutableMap()
        val dev = eligible.associate { pos ->
            val quote = prices[pos.symbol] ?: return@associate pos.symbol to 0.0
            
            val currency = quote.currency ?: "USD"
            val isPence = currency.length == 3 && currency[2].isLowerCase()
            val normalizedCcy = if (isPence) currency.uppercase() else currency

            val rate = if (normalizedCcy == "USD") 1.0 else {
                val pair = "${normalizedCcy}USD=X"
                prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
            }
            val multiplier = if (isPence) rate / 100.0 else rate
            val curVal = (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier * pos.quantity
            pos.symbol to ((curVal / finalTotal) - (pos.targetWeight / 100.0))
        }.toMutableMap()
        val sorted = eligible.sortedBy { sign * (dev[it.symbol] ?: 0.0) }
        var remaining = abs(delta)
        for (i in sorted.indices) {
            if (remaining <= 0) break
            val groupDev = dev[sorted[0].symbol] ?: 0.0
            val nextDev = if (i + 1 < sorted.size) dev[sorted[i + 1].symbol]
                ?: 0.0 else sign * Double.MAX_VALUE
            val groupSize = i + 1
            val costToLevel = (nextDev - groupDev) * sign * finalTotal * groupSize
            if (remaining >= costToLevel) {
                for (j in 0..i) {
                    alloc[sorted[j].symbol] =
                        (alloc[sorted[j].symbol] ?: 0.0) + (nextDev - groupDev) * finalTotal
                    dev[sorted[j].symbol] = nextDev
                }
                remaining -= costToLevel
            } else {
                val perStock = remaining / groupSize
                for (j in 0..i) {
                    alloc[sorted[j].symbol] = (alloc[sorted[j].symbol] ?: 0.0) + perStock * sign
                    dev[sorted[j].symbol] =
                        (dev[sorted[j].symbol] ?: 0.0) + (perStock / finalTotal) * sign
                }
                remaining = 0.0
            }
        }
        if (remaining > 0) {
            val totalW = eligible.sumOf { it.targetWeight }
            eligible.forEach { pos ->
                alloc[pos.symbol] =
                    (alloc[pos.symbol] ?: 0.0) + (pos.targetWeight / totalW) * remaining * sign
            }
        }
        return alloc
    }

    // ── Groups ────────────────────────────────────────────────────────────────

    fun computeGroups(
        positions: List<Position>,
        prices: Map<String, YahooQuote>,
        portfolioTotal: Double
    ): List<GroupRow> {
        data class Acc(
            var mktVal: Double = 0.0,
            var prevMktVal: Double = 0.0,
            var targetWeight: Double = 0.0,
            val members: MutableList<String> = mutableListOf()
        )

        val map = mutableMapOf<String, Acc>()
        for (pos in positions) {
            if (pos.groups.isBlank()) continue
            val entries = parseGroups(pos.groups, pos.symbol)
            val quote = prices[pos.symbol]
            if (quote == null) continue
            
            val currency = quote.currency ?: "USD"
            val isPence = currency.length == 3 && currency[2].isLowerCase()
            val normalizedCcy = if (isPence) currency.uppercase() else currency

            val rate = if (normalizedCcy == "USD") 1.0 else {
                val pair = "${normalizedCcy}USD=X"
                prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
            }
            val multiplier = if (isPence) rate / 100.0 else rate

            val mark = (quote.regularMarketPrice ?: quote.previousClose ?: 0.0) * multiplier
            val close = (quote.previousClose ?: quote.regularMarketPrice ?: 0.0) * multiplier
            val mktVal = mark * pos.quantity
            val prevMktVal = close * pos.quantity
            for ((mult, name) in entries) {
                val acc = map.getOrPut(name) { Acc() }
                acc.mktVal += mktVal * mult
                acc.prevMktVal += prevMktVal * mult
                acc.targetWeight += pos.targetWeight * mult
                if (!acc.members.contains(pos.symbol)) acc.members.add(pos.symbol)
            }
        }
        return map.map { (name, acc) ->
            GroupRow(name, acc.mktVal, acc.prevMktVal, acc.targetWeight, acc.members)
        }
    }

    private fun parseGroups(raw: String, symbol: String): List<Pair<Double, String>> {
        if (raw.isBlank()) return listOf(1.0 to symbol)
        return raw.split(";").mapNotNull { entry ->
            val trimmed = entry.trim()
            val spaceIdx = trimmed.indexOf(' ')
            if (spaceIdx < 0) return@mapNotNull null
            val mult = trimmed.substring(0, spaceIdx).toDoubleOrNull() ?: return@mapNotNull null
            val name = trimmed.substring(spaceIdx + 1).trim()
            if (name.isEmpty()) null else mult to name
        }
    }
}
