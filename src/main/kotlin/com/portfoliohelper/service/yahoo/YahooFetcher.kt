/**
 * YahooFetcher.kt
 *
 * Fetches daily adjusted-close prices for VTI, CTA, and DBMF
 * from Yahoo Finance and writes them to a CSV file.
 *
 * Each row = one trading day where ALL three tickers have data.
 * Columns: date, vti_adj_close, cta_adj_close, dbmf_adj_close
 *
 * Requirements (add to build.gradle.kts):
 *   implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *   implementation("org.json:json:20240303")
 *
 * Run:
 *   kotlinc YahooFetcher.kt -include-runtime -cp okhttp-4.12.0.jar:json-20240303.jar -d fetcher.jar
 *   java -cp fetcher.jar:okhttp-4.12.0.jar:json-20240303.jar YahooFetcherKt
 *
 * Or just use Gradle (see README at bottom of this file).
 */

package com.portfoliohelper.service.yahoo

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.*

// ── Reusable historical fetcher ───────────────────────────────────────────────

object YahooHistoricalFetcher {
    private val logger = LoggerFactory.getLogger(YahooHistoricalFetcher::class.java)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    /** Fetches adjusted-close prices for [ticker] in the given date range. Returns date → price. */
    fun fetchAdjustedClose(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        val p1 = startDate.minusDays(5).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val p2 = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                "?period1=$p1&period2=$p2&interval=1d" +
                "&events=adjclose&includeAdjustedClose=true"

        logger.info("Fetching historical $ticker from $startDate to $endDate")

        val request = Request.Builder().url(url).build()
        val body = http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $ticker" }
            resp.body!!.string()
        }

        val root = JSONObject(body)
        val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val timestamps = result.getJSONArray("timestamp")
        val adjClose = result
            .getJSONObject("indicators")
            .getJSONArray("adjclose")
            .getJSONObject(0)
            .getJSONArray("adjclose")

        val prices = mutableMapOf<LocalDate, Double>()
        for (i in 0 until timestamps.length()) {
            if (adjClose.isNull(i)) continue
            val date = Instant.ofEpochSecond(timestamps.getLong(i))
                .atZone(ZoneOffset.UTC).toLocalDate()
            val price = adjClose.getDouble(i)
            if (date <= endDate) {
                prices[date] = price
            }
        }

        logger.info("Fetched ${prices.size} trading days for $ticker")
        return prices
    }

    /** Fetches dividend events for [ticker] in the given date range. Returns ex-date → amount per share. */
    fun fetchDividends(
        ticker: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Double> {
        val p1 = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val p2 = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                "?period1=$p1&period2=$p2&interval=1d&events=div"

        logger.info("Fetching dividends for $ticker from $startDate to $endDate")

        val request = Request.Builder().url(url).build()
        val body = http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $ticker dividends" }
            resp.body!!.string()
        }

        val root = JSONObject(body)
        val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val events = result.optJSONObject("events") ?: return emptyMap()
        val dividends = events.optJSONObject("dividends") ?: return emptyMap()

        val out = mutableMapOf<LocalDate, Double>()
        for (key in dividends.keys()) {
            val entry = dividends.getJSONObject(key)
            val ts = entry.getLong("date")
            val amount = entry.getDouble("amount")
            val date = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate()
            if (date in startDate..endDate) {
                out[date] = amount
            }
        }

        logger.info("Fetched ${out.size} dividend events for $ticker")
        return out
    }
}

// ── Config ────────────────────────────────────────────────────────────────────

val TICKERS = listOf("SPY")
val START_DATE: LocalDate = LocalDate.of(2007, 1, 1)   // CTA inception date
val END_DATE: LocalDate = LocalDate.now()
const val OUTPUT_CSV = "portfolio_prices.csv"

// ── HTTP client (standalone use) ──────────────────────────────────────────────

val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        // Yahoo Finance requires a browser-like User-Agent
        val req = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        chain.proceed(req)
    }
    .build()

// ── Data model ────────────────────────────────────────────────────────────────

data class PriceSeries(
    val ticker: String,
    val prices: Map<LocalDate, Double>   // date → adjusted close
)

// ── Yahoo Finance fetch ───────────────────────────────────────────────────────

fun fetchYahoo(ticker: String): PriceSeries {
    val prices = YahooHistoricalFetcher.fetchAdjustedClose(ticker, START_DATE, END_DATE)
    println("    → ${prices.size} trading days for $ticker")
    return PriceSeries(ticker, prices)
}

// ── CSV writer ────────────────────────────────────────────────────────────────

fun writeCsv(series: List<PriceSeries>, outputPath: String) {
    // Intersect dates where every ticker has a price
    val commonDates = series
        .map { it.prices.keys }
        .reduce { acc, dates -> acc intersect dates }
        .sorted()

    println("\nWriting $outputPath …")
    println("  Common trading days: ${commonDates.size}")
    println("  Range: ${commonDates.first()} → ${commonDates.last()}")

    val file = File(outputPath)
    file.bufferedWriter().use { out ->
        // Header
        val header = listOf("date") + series.map { it.ticker.lowercase() + "_adj_close" }
        out.write(header.joinToString(","))
        out.newLine()

        // Rows
        for (date in commonDates) {
            val row = mutableListOf(date.toString())
            for (s in series) {
                val price = s.prices[date]!!
                row.add("%.6f".format(price))
            }
            out.write(row.joinToString(","))
            out.newLine()
        }
    }

    println("  Done → ${file.absolutePath}")
}

// ── Main ──────────────────────────────────────────────────────────────────────

fun main() {
    println("=== Yahoo Finance Daily Price Fetcher ===")
    println("Tickers : ${TICKERS.joinToString(", ")}")
    println("From    : $START_DATE")
    println("To      : $END_DATE")
    println("Output  : $OUTPUT_CSV")
    println()

    val series = try {
        TICKERS.map { fetchYahoo(it) }
    } catch (e: Exception) {
        System.err.println("\nERROR: ${e.message}")
        System.err.println("Make sure okhttp and org.json are on the classpath.")
        return
    }

    writeCsv(series, OUTPUT_CSV)

    println()
    println("Preview (first 5 rows):")
    println("date,vti_adj_close,cta_adj_close,dbmf_adj_close")
    File(OUTPUT_CSV).bufferedReader().use { br ->
        br.readLine() // skip header
        repeat(5) { br.readLine()?.let { println(it) } }
    }

    println()
    println("All done. Load '$OUTPUT_CSV' into Excel, Python, R, or any tool for further analysis.")
}


/*
 * ─────────────────────────────────────────────────────────────────────────────
 * README — Gradle build (copy-paste into a new project)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * build.gradle.kts:
 * -----------------
 *
 *   plugins {
 *       kotlin("jvm") version "2.0.0"
 *       application
 *   }
 *
 *   application {
 *       mainClass.set("YahooFetcherKt")
 *   }
 *
 *   repositories { mavenCentral() }
 *
 *   dependencies {
 *       implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *       implementation("org.json:json:20240303")
 *   }
 *
 * Place YahooFetcher.kt in src/main/kotlin/ then run:
 *   ./gradlew run
 *
 * The file portfolio_prices.csv will be created in the project root.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CSV output format:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   date,vti_adj_close,cta_adj_close,dbmf_adj_close
 *   2022-03-07,209.123456,24.500000,21.340000
 *   2022-03-08,207.654321,24.320000,21.100000
 *   ...
 *
 * Only dates where ALL three tickers have traded are included.
 * CTA started on 2022-03-07 so that is the first row.
 * ─────────────────────────────────────────────────────────────────────────────
 */