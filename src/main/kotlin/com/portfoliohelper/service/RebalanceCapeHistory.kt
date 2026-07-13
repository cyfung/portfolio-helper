package com.portfoliohelper.service

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

internal data class CapePoint(val date: LocalDate, val cape: Double)
internal data class CapeHistory(val points: List<CapePoint>) {
    private val valuations: List<Pair<Double, Double>?> = buildValuations()

    fun valuationFactor(date: LocalDate): Pair<Double, Double>? {
      if (points.isEmpty()) return null
      var lo = 0
      var hi = points.lastIndex
      var index = -1
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].date <= date) {
          index = mid
          lo = mid + 1
        } else {
          hi = mid - 1
        }
      }
      return if (index >= 0) valuations[index] else null
    }

    private fun buildValuations(): List<Pair<Double, Double>?> {
      val earningsYields = mutableListOf<Double>()
      return points.map { point ->
        val current = point.cape
        if (current <= 0.0) return@map null
        val currentYield = 1.0 / current
        val insertionIndex = earningsYields.binarySearch(currentYield).let { if (it >= 0) it else -it - 1 }
        earningsYields.add(insertionIndex, currentYield)

        fun percentile(p: Double): Double {
          if (earningsYields.size == 1) return earningsYields.first()
          val pos = p.coerceIn(0.0, 1.0) * (earningsYields.lastIndex)
          val lowerIndex = pos.toInt()
          val upperIndex = kotlin.math.ceil(pos).toInt()
          val lower = earningsYields[lowerIndex]
          val upper = earningsYields[upperIndex]
          return lower + (upper - lower) * (pos - lowerIndex)
        }

        val p5 = percentile(0.05)
        val median = percentile(0.50)
        val p95 = percentile(0.95)
        val spread = p95 - p5
        if (spread <= 0.0) return@map null

        val trimmedEp = (1.0 / current).coerceIn(p5, p95)
        val equityWeight = (1.0 + (trimmedEp - median) / spread).coerceIn(0.5, 1.5)
        (equityWeight - 0.5).coerceIn(0.0, 1.0) to current
      }
    }
  }

private val capeCache = mutableMapOf<CapeSource, CapeHistory>()

internal fun loadCapeHistory(source: CapeSource): CapeHistory =
      synchronized(capeCache) { capeCache[source] } ?: synchronized(capeCache) {
        capeCache[source] ?: run {
          val fileName =
              when (source) {
                CapeSource.US -> "us-cape-history.csv"
                CapeSource.WORLD -> "world-cape-history.csv"
              }
          val valueColumn =
              when (source) {
                CapeSource.US -> "us_cape"
                CapeSource.WORLD -> "world_cape"
              }
          val candidatePaths =
              listOf(
                  Path.of("frontend", "public", "data", fileName),
                  Path.of("build", "generated", "frontend", "static", "data", fileName),
                  Path.of("static", "data", fileName),
              )
          val text =
              candidatePaths.firstOrNull { Files.exists(it) }?.let { Files.readString(it) }
                  ?: Thread.currentThread().contextClassLoader
                      .getResource("static/data/$fileName")
                      ?.readText()
                  ?: error("CAPE history CSV not found: $fileName")
          val lines = text.trim().lineSequence().filter { it.isNotBlank() }.toList()
          val headers = lines.first().split(",")
          val dateIndex = headers.indexOf("date")
          val capeIndex = headers.indexOf(valueColumn)
          if (dateIndex < 0 || capeIndex < 0) error("Invalid CAPE CSV header: $fileName")
          val points =
              lines.drop(1).mapNotNull { line ->
                val cols = splitCsvLine(line)
                val date = cols.getOrNull(dateIndex)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val cape = cols.getOrNull(capeIndex)?.toDoubleOrNull()
                if (date != null && cape != null && cape > 0.0) CapePoint(date, cape) else null
              }.sortedBy { it.date }
          if (points.isEmpty()) error("No CAPE rows loaded from $fileName")
          CapeHistory(points).also { capeCache[source] = it }
        }
      }

private fun splitCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val ch = line[i]
      when {
        ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
          field.append('"')
          i++
        }
        ch == '"' -> inQuotes = !inQuotes
        ch == ',' && !inQuotes -> {
          fields.add(field.toString())
          field.clear()
        }
        else -> field.append(ch)
      }
      i++
    }
    fields.add(field.toString())
    return fields
}
