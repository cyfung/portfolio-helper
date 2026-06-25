package com.portfoliohelper.service.nav

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import java.time.LocalDate

abstract class SimplifyEtfNavProvider : NavProvider {
    abstract val slug: String

    companion object {
        private val logger = LoggerFactory.getLogger(SimplifyEtfNavProvider::class.java)

        val httpClient = HttpClient(CIO) {
            engine {
                requestTimeout = 15_000
            }
        }

        fun shutdown() {
            httpClient.close()
        }
    }

    override suspend fun fetchNav(): NavData? {
        return try {
            val url = "https://www.simplify.us/etfs/$slug"
            val response: HttpResponse = httpClient.get(url)

            if (response.status.value != 200) {
                logger.warn("HTTP ${response.status.value} fetching NAV for $symbol from $url")
                return null
            }

            val html = response.bodyAsText()
            val navSnapshot = parseSimplifyNav(html) ?: run {
                logger.warn("Could not parse NAV from Simplify page for $symbol")
                return null
            }

            logger.info("Fetched NAV for $symbol: ${navSnapshot.nav}")

            NavData(
                symbol = symbol,
                nav = navSnapshot.nav,
                asOfDate = navSnapshot.asOfDate,
                lastFetchTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.error("Failed to fetch NAV for $symbol: ${e.message}", e)
            null
        }
    }
}

internal data class SimplifyNavSnapshot(
    val nav: Double,
    val asOfDate: LocalDate
)

internal fun parseSimplifyNav(html: String): SimplifyNavSnapshot? {
    val doc = Jsoup.parse(html)
    return parseFundOverviewNav(doc) ?: parseLegacyNav(doc)
}

private fun parseFundOverviewNav(doc: Document): SimplifyNavSnapshot? {
    val header = doc.select(".c-fund-overview__cell-header")
        .firstOrNull { it.text().trim().startsWith("NAV Per Share", ignoreCase = true) }
        ?: return null

    val row = header.parent()
    val valueElement = row?.selectFirst(".c-fund-overview__cell-data")
        ?: header.nextElementSibling()
        ?: return null

    return parseNavSnapshot(header.text(), valueElement.text())
}

private fun parseLegacyNav(doc: Document): SimplifyNavSnapshot? {
    val navHeading = doc.select("h3")
        .firstOrNull { it.text().trim().startsWith("NAV", ignoreCase = true) }
        ?: return null
    val navParagraph = navHeading.nextElementSibling()
        ?: return null

    return parseNavSnapshot(navHeading.text(), navParagraph.text())
}

private fun parseNavSnapshot(labelText: String, valueText: String): SimplifyNavSnapshot? {
    val nav = parseDollarValue(valueText) ?: return null
    val asOfDate = parseAsOfDate(labelText) ?: parseAsOfDate(valueText) ?: return null
    return SimplifyNavSnapshot(
        nav = nav,
        asOfDate = asOfDate
    )
}

private fun parseAsOfDate(text: String): LocalDate? =
    Regex("""(?i)\bas\s+of\s+(\d{1,2}/\d{1,2}/\d{4})""")
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.let(::parseNavDateValue)

private fun parseDollarValue(text: String): Double? {
    return Regex("""\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""")
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.replace(",", "")
        ?.toDoubleOrNull()
}
