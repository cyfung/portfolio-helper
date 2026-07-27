package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.SortedMap
import java.util.TreeMap
import kotlin.math.pow

data class InflationFactors(
    val available: Boolean,
    val factors: List<Double> = emptyList(),
    val reason: String? = null,
)

object InflationSeries {
    const val FRED_SERIES_ID = "CPIAUCSL"

    fun load(): SortedMap<LocalDate, Double> =
        EconomicSeriesCache.loadFredSeries(FRED_SERIES_ID)

    fun factorsFor(
        dates: List<LocalDate>,
        observations: SortedMap<LocalDate, Double>,
    ): InflationFactors {
        if (dates.isEmpty()) return InflationFactors(available = true)
        if (observations.isEmpty()) {
            return InflationFactors(false, reason = "Inflation adjustment is unavailable because CPI data could not be loaded.")
        }
        val firstObservation = observations.firstKey()
        if (dates.first() < firstObservation) {
            return InflationFactors(
                false,
                reason = "Inflation adjustment is unavailable before the first CPI observation ($firstObservation).",
            )
        }
        val levels = dates.map { interpolatedLevel(it, observations) }
        val base = levels.first()
        return InflationFactors(available = true, factors = levels.map { it / base })
    }

    private fun interpolatedLevel(
        date: LocalDate,
        observations: SortedMap<LocalDate, Double>,
    ): Double {
        val floor = observations.entries.lastOrNull { it.key <= date }
            ?: error("Date precedes CPI coverage")
        val ceiling = observations.entries.firstOrNull { it.key >= date }
        if (ceiling == null || floor.key == ceiling.key) return floor.value
        val span = ChronoUnit.DAYS.between(floor.key, ceiling.key).toDouble()
        val elapsed = ChronoUnit.DAYS.between(floor.key, date).toDouble()
        return floor.value + (ceiling.value - floor.value) * elapsed / span
    }
}

object InflationAdjustment {
    fun backtestResult(
        nominal: MultiBacktestResult,
        effrx: Map<LocalDate, Double>,
        cashflows: List<Double> = emptyList(),
        benchmarkValues: List<Double> = emptyList(),
        observations: SortedMap<LocalDate, Double> = InflationSeries.load(),
    ): MultiBacktestResult {
        val dates = nominal.portfolios.firstOrNull()?.curves?.firstOrNull()?.points
            ?.map { LocalDate.parse(it.date) }
            .orEmpty()
        val inflation = InflationSeries.factorsFor(dates, observations)
        if (!inflation.available) {
            return nominal.copy(inflationAdjustmentUnavailableReason = inflation.reason)
        }
        if (dates.size < 2) {
            return nominal.copy(inflationAdjusted = InflationAdjustedBacktestResult(nominal.portfolios))
        }
        val factors = inflation.factors
        val realCashflows = cashflows.mapIndexed { index, amount ->
            amount / factors.getOrElse(index) { factors.last() }
        }
        val realBenchmark = benchmarkValues.mapIndexed { index, value ->
            value / factors.getOrElse(index) { factors.last() }
        }
        val years = (dates.last().toEpochDay() - dates.first().toEpochDay()) / 365.25
        val inflationAnnualized = factors.last().pow(1.0 / years) - 1.0
        val nominalRf = BacktestService.computeRfAnnualized(effrx)
        val realRf = (1.0 + nominalRf) / (1.0 + inflationAnnualized) - 1.0
        val adjusted = nominal.portfolios.map { portfolio ->
            portfolio.copy(curves = portfolio.curves.map { curve ->
                val values = curve.points.mapIndexed { index, point -> point.value / factors[index] }
                val stats = computeStats(values, years, realRf, realCashflows, realBenchmark)
                curve.copy(
                    points = curve.points.mapIndexed { index, point ->
                        point.copy(value = point.value / factors[index])
                    },
                    stats = curve.stats.copy(
                        cagr = stats.cagr,
                        maxDrawdown = stats.maxDrawdown,
                        sharpe = stats.sharpe,
                        ulcerIndex = stats.ulcerIndex,
                        upi = stats.upi,
                        annualVolatility = stats.annualVolatility,
                        longestDrawdownDays = stats.longestDrawdownDays,
                        endingValue = values.last(),
                        sortino = stats.sortino,
                        averageDrawdown = stats.averageDrawdown,
                        calmar = stats.calmar,
                        beta = stats.beta,
                    ),
                )
            })
        }
        return nominal.copy(inflationAdjusted = InflationAdjustedBacktestResult(adjusted))
    }
}

/**
 * Persistent raw observations for non-investable economic data. Transformations
 * such as EFFR compounding or CPI interpolation remain in their domain modules.
 */
object EconomicSeriesCache {
    private const val CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    private val directory: File
        get() = AppDirs.dataDir.resolve(".economic-series").toFile()
    private val client = OkHttpClient()

    fun loadFredSeries(seriesId: String): SortedMap<LocalDate, Double> {
        directory.mkdirs()
        val cacheFile = File(directory, "FRED-${seriesId.uppercase()}.csv")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MILLIS) {
            return readCache(cacheFile)
        }
        return try {
            val fetched = fetchFredSeries(seriesId)
            cacheFile.writeText(buildString {
                appendLine("date,value")
                fetched.forEach { (date, value) -> appendLine("$date,$value") }
            })
            fetched
        } catch (_: Exception) {
            readCache(cacheFile)
        }
    }

    private fun fetchFredSeries(seriesId: String): SortedMap<LocalDate, Double> {
        val request = Request.Builder()
            .url("https://fred.stlouisfed.org/graph/fredgraph.csv?id=${seriesId.uppercase()}")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "FRED HTTP ${response.code}" }
            val rows = response.body?.string().orEmpty().lineSequence().drop(1)
            return rows.mapNotNull(::parseRow).toMap(TreeMap())
        }
    }

    private fun readCache(file: File): SortedMap<LocalDate, Double> =
        if (!file.exists()) TreeMap()
        else file.readLines().drop(1).mapNotNull(::parseRow).toMap(TreeMap())

    private fun parseRow(row: String): Pair<LocalDate, Double>? {
        val columns = row.trim().split(',', limit = 2)
        if (columns.size != 2) return null
        val date = runCatching { LocalDate.parse(columns[0]) }.getOrNull() ?: return null
        val value = columns[1].toDoubleOrNull() ?: return null
        return date to value
    }
}
