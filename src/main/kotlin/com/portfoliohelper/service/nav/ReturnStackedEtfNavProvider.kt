package com.portfoliohelper.service.nav

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.time.LocalDate

abstract class ReturnStackedEtfNavProvider : NavProvider {
    abstract val slug: String

    companion object {
        private val logger = LoggerFactory.getLogger(ReturnStackedEtfNavProvider::class.java)

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
            val url = "https://www.returnstackedetfs.com/$slug/"
            val response: HttpResponse = httpClient.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 PortfolioHelper/1.0")
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }

            val html = response.bodyAsText()
            if (html.contains("/.well-known/sgcaptcha/", ignoreCase = true)) {
                logger.warn("Return Stacked page for $symbol returned a captcha interstitial")
                return null
            }

            if (response.status.value != 200) {
                logger.warn("HTTP ${response.status.value} fetching NAV for $symbol from $url")
                return null
            }

            val navSnapshot = parseReturnStackedNav(html) ?: run {
                logger.warn("Could not parse NAV from Return Stacked page for $symbol")
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

internal data class ReturnStackedNavSnapshot(
    val nav: Double,
    val asOfDate: LocalDate
)

internal fun parseReturnStackedNav(html: String): ReturnStackedNavSnapshot? {
    val text = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ")
    val section = extractReturnStackedPricingSection(text) ?: return null
    val nav = parseReturnStackedNavValue(section) ?: return null
    val asOfDate = parseReturnStackedAsOfDate(section) ?: return null

    return ReturnStackedNavSnapshot(nav = nav, asOfDate = asOfDate)
}

private fun extractReturnStackedPricingSection(text: String): String? {
    val start = listOf("Fund Data & Pricing", "Fund Data", "Pricing")
        .firstNotNullOfOrNull { marker ->
            text.substringAfter(marker, missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
        }
        ?: return null

    return start
        .substringBefore("Performance")
        .substringBefore("Top 10 Holdings")
        .substringBefore("Holdings")
}

private fun parseReturnStackedNavValue(section: String): Double? {
    return listOf(
        Regex("""(?i)\bNAV\s+\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)"""),
        Regex("""(?i)\bNet\s+Asset\s+Value\s+\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""")
    ).firstNotNullOfOrNull { pattern ->
        pattern.find(section)
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
    }
}

private fun parseReturnStackedAsOfDate(section: String): LocalDate? {
    return listOf(
        Regex("""(?i)\bAs\s+of\s+(\d{1,2}/\d{1,2}/\d{4})"""),
        Regex("""(?i)\bAs\s+of\s+([A-Z][a-z]{2,8}\s+\d{1,2},\s+\d{4})""")
    ).firstNotNullOfOrNull { pattern ->
        pattern.find(section)
            ?.groupValues
            ?.get(1)
            ?.let(::parseNavDateValue)
    }
}
