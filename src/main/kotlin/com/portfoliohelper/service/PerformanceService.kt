package com.portfoliohelper.service

import com.portfoliohelper.util.appJson
import kotlinx.serialization.Serializable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

private fun CashFlowEntry.isExternalTransfer() = type == "Deposits/Withdrawals"

object PerformanceService {

    @Serializable
    data class ChartData(
        val dates: List<String>,
        val twrSeries: List<Double>,
        val mwrSeries: List<Double>?,
        val positionSeries: List<Double>?,
        val navSeries: List<Double>,
        val marginUtilSeries: List<Double>
    )

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            socketTimeoutMillis  = 15_000
        }
    }

    /**
     * Builds chart data for the given snapshot range.
     * Snapshots must be pre-fetched via [PortfolioSnapshotRepository.getSnapshots] (includes pre-range row).
     */
    suspend fun buildChartData(snapshots: List<PortfolioSnapshotRepository.FullSnapshot>): ChartData {
        if (snapshots.size < 2) return empty()

        // The first snapshot is the pre-range "T-1" used as period start
        val t0     = snapshots.first()
        val series = snapshots.drop(1)

        val dates  = series.map { it.header.date }
        val navs   = series.map { it.header.netLiqValue }

        // ── TWR ─────────────────────────────────────────────────────────────
        val twrSeries = buildList {
            var cumulative = 1.0
            var prevNav = t0.header.netLiqValue
            for (snap in series) {
                val externalFlow = snap.cashFlows.filter { it.isExternalTransfer() }.sumOf { it.amount * it.fxRateToBase }
                val adjustedStart = prevNav
                if (adjustedStart == 0.0) { add(cumulative - 1.0); prevNav = snap.header.netLiqValue; continue }
                val r = (snap.header.netLiqValue - externalFlow) / adjustedStart - 1.0
                cumulative *= (1.0 + r)
                add(cumulative - 1.0)
                prevNav = snap.header.netLiqValue
            }
        }

        // ── MWR (IRR via bisection) ─────────────────────────────────────────
        val allFlows = snapshots.flatMap { s -> s.cashFlows.filter { it.isExternalTransfer() }.map { s.header.date to it } }
        val mwrSeries = if (allFlows.isNotEmpty()) {
            buildMwrSeries(snapshots, series.map { it.header })
        } else null

        // ── Position return (ex-cash) ────────────────────────────────────────
        val positionSeries = buildPositionSeries(t0, series)

        // ── Margin utilisation ───────────────────────────────────────────────
        val marginUtilSeries = series.map { snap ->
            val cash = snap.header.cashBase
            val nav  = snap.header.netLiqValue
            if (cash < 0 && nav > 0) abs(cash) / nav else 0.0
        }

        // Prepend T0 anchor so all return series start at 0%
        val t0Margin = t0.header.cashBase.let { cash ->
            if (cash < 0 && t0.header.netLiqValue > 0) abs(cash) / t0.header.netLiqValue else 0.0
        }
        return ChartData(
            dates            = listOf(t0.header.date) + dates,
            twrSeries        = listOf(0.0) + twrSeries,
            mwrSeries        = mwrSeries?.let { listOf(0.0) + it },
            positionSeries   = positionSeries?.let { listOf(0.0) + it },
            navSeries        = listOf(t0.header.netLiqValue) + navs,
            marginUtilSeries = listOf(t0Margin) + marginUtilSeries
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MWR: running IRR series (recalculated from T0 to each day Ti)
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildMwrSeries(
        all: List<PortfolioSnapshotRepository.FullSnapshot>,
        series: List<PortfolioSnapshotRepository.SnapshotRow>
    ): List<Double> {
        val t0Date = LocalDate.parse(all.first().header.date)
        val t0Nav  = all.first().header.netLiqValue

        // Collect all external cash flows with their offset in years
        val flowsByDate: Map<String, Double> = all.drop(1).associate { snap ->
            snap.header.date to snap.cashFlows.filter { it.isExternalTransfer() }.sumOf { it.amount * it.fxRateToBase }
        }

        return series.map { row ->
            val tDate = LocalDate.parse(row.date)
            val T = ChronoUnit.DAYS.between(t0Date, tDate) / 365.25

            // Cash flow list: [-startNAV at t=0, external flows at their t, +endNAV at T]
            val cashFlows = mutableListOf(Pair(0.0, -t0Nav))
            for (snap in all.drop(1)) {
                val snapDate = LocalDate.parse(snap.header.date)
                if (!snapDate.isAfter(tDate)) {
                    val flowAmt = flowsByDate[snap.header.date] ?: 0.0
                    if (flowAmt != 0.0) {
                        val t = ChronoUnit.DAYS.between(t0Date, snapDate) / 365.25
                        cashFlows.add(Pair(t, -flowAmt))
                    }
                }
            }
            cashFlows.add(Pair(T, row.netLiqValue))

            val annualized = bisectIRR(cashFlows) ?: 0.0
            (1.0 + annualized).pow(T) - 1.0
        }
    }

    /** Bisection method to find IRR. Returns annualised rate or null if no solution. */
    private fun bisectIRR(flows: List<Pair<Double, Double>>): Double? {
        fun npv(r: Double) = flows.sumOf { (t, cf) -> cf / (1.0 + r).pow(t) }

        val lo0 = -0.9999
        val hi0 = 500.0
        if (npv(lo0) * npv(hi0) > 0) return null

        var lo = lo0; var hi = hi0
        repeat(100) {
            val mid = (lo + hi) / 2.0
            if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
        }
        return (lo + hi) / 2.0
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Position return: weighted daily returns using Yahoo Finance adj. close
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun buildPositionSeries(
        t0: PortfolioSnapshotRepository.FullSnapshot,
        series: List<PortfolioSnapshotRepository.FullSnapshot>
    ): List<Double>? {
        val allSnapshots = listOf(t0) + series
        val symbols = allSnapshots.flatMap { it.positions }.map { it.symbol }.toSet()
        if (symbols.isEmpty()) return null

        val startDate = LocalDate.parse(allSnapshots.first().header.date)
        val endDate   = LocalDate.parse(allSnapshots.last().header.date)
        val startEpoch = startDate.toEpochDay() * 86_400
        val endEpoch   = (endDate.toEpochDay() + 1) * 86_400

        // Fetch historical adj-close prices per symbol
        val priceMap: Map<String, Map<String, Double>> = symbols.associateWith { symbol ->
            fetchAdjClose(symbol, startEpoch, endEpoch)
        }

        if (priceMap.values.all { it.isEmpty() }) return null

        // Build date list covering all snapshot dates
        val result = mutableListOf<Double>()
        var cumulative = 1.0

        for (i in 1 until allSnapshots.size) {
            val prevSnap = allSnapshots[i - 1]
            val currSnap = allSnapshots[i]
            val prevDate = prevSnap.header.date
            val currDate = currSnap.header.date

            val positions = prevSnap.positions
            val totalPositionValue = positions.sumOf { abs(it.positionValue) }
            if (totalPositionValue == 0.0) { result.add(cumulative - 1.0); continue }

            var dayReturn = 0.0
            for (pos in positions) {
                val prices = priceMap[pos.symbol] ?: continue
                val p0 = closestPrice(prices, prevDate) ?: continue
                val p1 = closestPrice(prices, currDate) ?: continue
                if (p0 == 0.0) continue
                val weight = abs(pos.positionValue) / totalPositionValue
                dayReturn += weight * (p1 / p0 - 1.0)
            }
            cumulative *= (1.0 + dayReturn)
            result.add(cumulative - 1.0)
        }

        return result.takeIf { it.isNotEmpty() }
    }

    /** Fetches adjusted close prices from Yahoo Finance; returns map of YYYY-MM-DD → adjClose. */
    private suspend fun fetchAdjClose(symbol: String, period1: Long, period2: Long): Map<String, Double> {
        return try {
            val url = "https://query2.finance.yahoo.com/v8/finance/chart/$symbol?period1=$period1&period2=$period2&interval=1d&events=adjclose"
            val body = httpClient.get(url) {
                header("User-Agent", "Mozilla/5.0")
            }.bodyAsText()

            val json    = appJson.parseToJsonElement(body).jsonObject
            val result  = json["chart"]?.jsonObject?.get("result")?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyMap()
            val timestamps = result["timestamp"]?.jsonArray?.map { it.jsonPrimitive.long } ?: return emptyMap()
            val adjClose   = result["indicators"]?.jsonObject
                ?.get("adjclose")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("adjclose")?.jsonArray ?: return emptyMap()

            timestamps.zip(adjClose).mapNotNull { (ts, price) ->
                val date = LocalDate.ofEpochDay(ts / 86_400).toString()
                val p    = price.jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                date to p
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Returns the price for [date], or the closest prior date's price (carry-forward). */
    private fun closestPrice(prices: Map<String, Double>, date: String): Double? {
        if (prices.isEmpty()) return null
        return prices[date] ?: prices.entries
            .filter { it.key <= date }
            .maxByOrNull { it.key }
            ?.value
    }

    private fun empty() = ChartData(emptyList(), emptyList(), null, null, emptyList(), emptyList())

    fun shutdown() = httpClient.close()
}
