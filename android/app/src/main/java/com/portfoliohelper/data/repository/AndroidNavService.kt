package com.portfoliohelper.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

data class NavSnapshot(
    val symbol: String,
    val nav: Double,
    val asOfDate: LocalDate?,
    val lastFetchTime: Long = System.currentTimeMillis()
)

object AndroidNavService {
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }

    private val providers = mapOf<String, suspend () -> NavSnapshot?>(
        "CTA" to { fetchSimplify("CTA", "cta-simplify-managed-futures-strategy-etf") },
        "CTAP" to { fetchSimplify("CTAP", "ctap-simplify-us-equity-plus-managed-futures-strategy-etf") },
        "RSIT" to { fetchReturnStacked("RSIT", "rsit-international-stocks-managed-futures") },
        "RSST" to { fetchReturnStacked("RSST", "rsst-return-stacked-us-stocks-managed-futures") },
        "DBMF.PA" to { fetchImgp("DBMF.PA", "LU2951555585") },
        "DBMFE.PA" to { fetchImgp("DBMFE.PA", "LU2951555403") },
    )

    fun supports(symbol: String): Boolean = symbol.uppercase() in providers

    fun supportedSymbols(symbols: Iterable<String>): List<String> =
        symbols.map { it.uppercase() }.distinct().filter { it in providers }

    suspend fun fetchNavs(symbols: Iterable<String>): Map<String, NavSnapshot> = coroutineScope {
        supportedSymbols(symbols).map { symbol ->
            async {
                val snapshot = runCatching { providers[symbol]?.invoke() }.getOrNull()
                symbol to snapshot
            }
        }.mapNotNull { deferred ->
            val (symbol, snapshot) = deferred.await()
            snapshot?.let { symbol to it }
        }.toMap()
    }

    private suspend fun fetchSimplify(symbol: String, slug: String): NavSnapshot? {
        val html = httpClient.get("https://www.simplify.us/etfs/$slug").bodyAsText()
        val parsed = parseSimplifyNav(html) ?: return null
        return NavSnapshot(symbol, parsed.nav, parsed.asOfDate)
    }

    private suspend fun fetchReturnStacked(symbol: String, slug: String): NavSnapshot? {
        val html = httpClient.get("https://www.returnstackedetfs.com/$slug/") {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 PortfolioHelper/1.0")
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.bodyAsText()
        val parsed = parseReturnStackedNav(html) ?: return null
        return NavSnapshot(symbol, parsed.nav, parsed.asOfDate)
    }

    private suspend fun fetchImgp(symbol: String, isin: String): NavSnapshot? {
        val html = httpClient.get("https://www.imgp.com/funds/").bodyAsText()
        val parsed = parseImgpFundNav(html, isin) ?: return null
        return NavSnapshot(symbol, parsed.nav, parsed.asOfDate)
    }

    private data class ParsedNav(val nav: Double, val asOfDate: LocalDate)

    private fun parseSimplifyNav(html: String): ParsedNav? {
        val doc = Jsoup.parse(html)
        return parseFundOverviewNav(doc) ?: parseLegacyNav(doc)
    }

    private fun parseFundOverviewNav(doc: Document): ParsedNav? {
        val header = doc.select(".c-fund-overview__cell-header")
            .firstOrNull { it.text().trim().startsWith("NAV Per Share", ignoreCase = true) }
            ?: return null
        val row = header.parent()
        val valueElement = row?.selectFirst(".c-fund-overview__cell-data") ?: header.nextElementSibling() ?: return null
        return parseNavSnapshot(header.text(), valueElement.text())
    }

    private fun parseLegacyNav(doc: Document): ParsedNav? {
        val navHeading = doc.select("h3").firstOrNull { it.text().trim().startsWith("NAV", ignoreCase = true) }
            ?: return null
        val navParagraph = navHeading.nextElementSibling() ?: return null
        return parseNavSnapshot(navHeading.text(), navParagraph.text())
    }

    private fun parseNavSnapshot(labelText: String, valueText: String): ParsedNav? {
        val nav = parseDollarValue(valueText) ?: return null
        val asOfDate = parseAsOfDate(labelText) ?: parseAsOfDate(valueText) ?: return null
        return ParsedNav(nav, asOfDate)
    }

    private fun parseReturnStackedNav(html: String): ParsedNav? {
        val text = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ")
        val section = listOf("Fund Data & Pricing", "Fund Data", "Pricing")
            .firstNotNullOfOrNull { marker -> text.substringAfter(marker, "").takeIf { it.isNotBlank() } }
            ?.substringBefore("Performance")
            ?.substringBefore("Top 10 Holdings")
            ?.substringBefore("Holdings")
            ?: return null
        val nav = listOf(
            Regex("""(?i)\bNAV\s+\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)"""),
            Regex("""(?i)\bNet\s+Asset\s+Value\s+\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""")
        ).firstNotNullOfOrNull { it.find(section)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }
            ?: return null
        val asOfDate = listOf(
            Regex("""(?i)\bAs\s+of\s+(\d{1,2}/\d{1,2}/\d{4})"""),
            Regex("""(?i)\bAs\s+of\s+([A-Z][a-z]{2,8}\s+\d{1,2},\s+\d{4})""")
        ).firstNotNullOfOrNull { it.find(section)?.groupValues?.get(1)?.let(::parseNavDateValue) }
            ?: return null
        return ParsedNav(nav, asOfDate)
    }

    private fun parseImgpFundNav(html: String, isin: String): ParsedNav? {
        val text = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ")
        val isinMatch = Regex("""(?i)\bISIN:\s*${Regex.escape(isin)}\b""").find(text) ?: return null
        val rowText = text.substring(isinMatch.range.first)
            .substringBefore(" Share Class Name:", missingDelimiterValue = text.substring(isinMatch.range.first))
        val match = Regex(
            """(?i)\b([A-Z]{3})\s+([0-9][0-9,]*(?:\.[0-9]+)?)\s+as\s+of\s+([A-Z][a-z]{2}\s+\d{1,2},\s+\d{4})"""
        ).find(rowText) ?: return null
        val nav = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val asOfDate = parseNavDateValue(match.groupValues[3]) ?: return null
        return ParsedNav(nav, asOfDate)
    }

    private fun parseAsOfDate(text: String): LocalDate? =
        Regex("""(?i)\bas\s+of\s+(\d{1,2}/\d{1,2}/\d{4})""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.let(::parseNavDateValue)

    private fun parseDollarValue(text: String): Double? =
        Regex("""\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
}

private val navDateFormatters: List<DateTimeFormatter> = listOf(
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("M/d/uuuu").toFormatter(Locale.US),
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, uuuu").toFormatter(Locale.US),
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM d, uuuu").toFormatter(Locale.US),
    DateTimeFormatter.ISO_LOCAL_DATE
)

private fun parseNavDateValue(value: String): LocalDate? =
    navDateFormatters.firstNotNullOfOrNull { formatter ->
        runCatching { LocalDate.parse(value.trim(), formatter) }.getOrNull()
    }
