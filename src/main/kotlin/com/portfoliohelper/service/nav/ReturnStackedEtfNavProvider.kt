package com.portfoliohelper.service.nav

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

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
            val response: HttpResponse = httpClient.get(url)

            if (response.status.value != 200) {
                logger.warn("HTTP ${response.status.value} fetching NAV for $symbol from $url")
                return null
            }

            val html = response.bodyAsText()
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
    val asOfDate: String?
)

internal fun parseReturnStackedNav(html: String): ReturnStackedNavSnapshot? {
    val text = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ")
    val section = text.substringAfter("Fund Data & Pricing", missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.substringBefore("Performance")
        ?.substringBefore("Top 10 Holdings")
        ?: return null

    val nav = Regex("""(?i)\bNAV\s+\$?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""")
        .find(section)
        ?.groupValues
        ?.get(1)
        ?.replace(",", "")
        ?.toDoubleOrNull()
        ?: return null

    val asOfDate = Regex("""(?i)\bAs of\s+(\d{1,2}/\d{1,2}/\d{4})""")
        .find(section)
        ?.groupValues
        ?.get(1)

    return ReturnStackedNavSnapshot(nav = nav, asOfDate = asOfDate)
}
