package com.portfoliohelper.service.yahoo

import java.io.File
import java.time.LocalDate

private val exportTickers = listOf("SPY")
private val exportStartDate: LocalDate = LocalDate.of(2007, 1, 1)
private val exportEndDate: LocalDate = LocalDate.now()
private const val exportOutputCsv = "portfolio_prices.csv"

private data class PriceSeries(
    val ticker: String,
    val prices: Map<LocalDate, Double>
)

private fun fetchYahoo(ticker: String): PriceSeries {
    val prices = YahooHistoricalFetcher.fetchAdjustedClose(ticker, exportStartDate, exportEndDate)
    println("    -> ${prices.size} trading days for $ticker")
    return PriceSeries(ticker, prices)
}

private fun writeCsv(series: List<PriceSeries>, outputPath: String) {
    val commonDates = series
        .map { it.prices.keys }
        .reduce { acc, dates -> acc intersect dates }
        .sorted()

    println()
    println("Writing $outputPath ...")
    println("  Common trading days: ${commonDates.size}")
    println("  Range: ${commonDates.first()} -> ${commonDates.last()}")

    val file = File(outputPath)
    file.bufferedWriter().use { out ->
        val header = listOf("date") + series.map { it.ticker.lowercase() + "_adj_close" }
        out.write(header.joinToString(","))
        out.newLine()

        for (date in commonDates) {
            val row = mutableListOf(date.toString())
            for (s in series) {
                row.add("%.6f".format(s.prices[date]!!))
            }
            out.write(row.joinToString(","))
            out.newLine()
        }
    }

    println("  Done -> ${file.absolutePath}")
}

fun main() {
    println("=== Yahoo Finance Daily Price Fetcher ===")
    println("Tickers : ${exportTickers.joinToString(", ")}")
    println("From    : $exportStartDate")
    println("To      : $exportEndDate")
    println("Output  : $exportOutputCsv")
    println()

    val series = try {
        exportTickers.map { fetchYahoo(it) }
    } catch (e: Exception) {
        System.err.println()
        System.err.println("ERROR: ${e.message}")
        return
    }

    writeCsv(series, exportOutputCsv)

    println()
    println("Preview (first 5 rows):")
    File(exportOutputCsv).bufferedReader().use { br ->
        println(br.readLine())
        repeat(5) { br.readLine()?.let { println(it) } }
    }

    println()
    println("All done. Load '$exportOutputCsv' into Excel, Python, R, or any tool for further analysis.")
}
