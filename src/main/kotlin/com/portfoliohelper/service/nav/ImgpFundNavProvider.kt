package com.portfoliohelper.service.nav

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.time.LocalDate

abstract class ImgpFundNavProvider : NavProvider {
    abstract val isin: String

    companion object {
        private val logger = LoggerFactory.getLogger(ImgpFundNavProvider::class.java)

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
            val url = "https://www.imgp.com/funds/"
            val response: HttpResponse = httpClient.get(url)

            if (response.status.value != 200) {
                logger.warn("HTTP ${response.status.value} fetching NAV for $symbol from $url")
                return null
            }

            val navSnapshot = parseImgpFundNav(response.bodyAsText(), isin) ?: run {
                logger.warn("Could not parse NAV from iMGP funds page for $symbol ($isin)")
                return null
            }

            logger.info("Fetched NAV for $symbol from iMGP: ${navSnapshot.nav} as of ${navSnapshot.asOfDate}")

            NavData(
                symbol = symbol,
                nav = navSnapshot.nav,
                asOfDate = navSnapshot.asOfDate,
                lastFetchTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.error("Failed to fetch NAV for $symbol from iMGP: ${e.message}", e)
            null
        }
    }
}

internal data class ImgpFundNavSnapshot(
    val nav: Double,
    val asOfDate: LocalDate
)

internal fun parseImgpFundNav(html: String, isin: String): ImgpFundNavSnapshot? {
    val text = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ")
    val isinMatch = Regex("""(?i)\bISIN:\s*${Regex.escape(isin)}\b""").find(text) ?: return null
    val rowText = text.substring(isinMatch.range.first)
        .substringBefore(" Share Class Name:", missingDelimiterValue = text.substring(isinMatch.range.first))
    val match = Regex(
        """(?i)\b([A-Z]{3})\s+([0-9][0-9,]*(?:\.[0-9]+)?)\s+as\s+of\s+([A-Z][a-z]{2}\s+\d{1,2},\s+\d{4})"""
    ).find(rowText) ?: return null
    val nav = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
    val asOfDate = parseNavDateValue(match.groupValues[3]) ?: return null

    return ImgpFundNavSnapshot(
        nav = nav,
        asOfDate = asOfDate
    )
}
