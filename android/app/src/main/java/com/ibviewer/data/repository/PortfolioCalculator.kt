package com.ibviewer.data.repository

import com.ibviewer.data.model.AllocMode
import com.ibviewer.data.model.GroupRow
import com.ibviewer.data.model.Position
import kotlin.math.abs

object PortfolioCalculator {

    // ── Portfolio totals ──────────────────────────────────────────────────────

    data class PortfolioTotals(
        val totalMktVal: Double,
        val totalPrevMktVal: Double,
        val marginPct: Double,       // margin as % of equity+margin
        val dayChangeDollars: Double,
        val dayChangePct: Double
    )

    fun computeTotals(
        positions: List<Position>,
        prices: Map<String, YahooQuote>,
        marginUsd: Double           // negative = loan
    ): PortfolioTotals {
        val total = positions.sumOf { pos ->
            val quote = prices[pos.symbol]
            (quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0) * pos.quantity
        }
        val prevTotal = positions.sumOf { pos ->
            val quote = prices[pos.symbol]
            (quote?.previousClose ?: quote?.regularMarketPrice ?: 0.0) * pos.quantity
        }
        val equity = total + marginUsd
        val marginPct = if (equity != 0.0) abs(marginUsd / equity) * 100.0 else 0.0
        val change = total - prevTotal
        val changePct = if (prevTotal != 0.0) change / prevTotal * 100.0 else 0.0
        return PortfolioTotals(total, prevTotal, marginPct, change, changePct)
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
            (quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0) * pos.quantity
        }

        return when (mode) {
            AllocMode.PROPORTIONAL -> positions.associate { pos ->
                pos.symbol to (pos.targetWeight / 100.0) * delta
            }

            AllocMode.CURRENT_WEIGHT -> positions.associate { pos ->
                val quote = prices[pos.symbol]
                val markPrice = quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0
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
            val quote = prices[pos.symbol]
            val curVal = (quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0) * pos.quantity
            sign * ((curVal / finalTotal) - (pos.targetWeight / 100.0))
        }
        var remaining = abs(delta)
        for (pos in sorted) {
            if (remaining <= 0) break
            val quote = prices[pos.symbol]
            val curVal = (quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0) * pos.quantity
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
            val quote = prices[pos.symbol]
            val curVal = (quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0) * pos.quantity
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
            val mark = quote?.regularMarketPrice ?: quote?.previousClose ?: 0.0
            val close = quote?.previousClose ?: quote?.regularMarketPrice ?: 0.0
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
