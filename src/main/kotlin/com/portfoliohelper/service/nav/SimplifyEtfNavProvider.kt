package com.portfoliohelper.service.nav

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

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
            val doc = Jsoup.parse(html)

            // Find the NAV section: <h3>NAV</h3> followed by <p>$ XX.XX</p>
            val navHeading = doc.select("h3:containsOwn(NAV)").first() ?: run {
                logger.warn("Could not find NAV heading for $symbol")
                return null
            }

            val navParagraph = navHeading.nextElementSibling() ?: run {
                logger.warn("Could not find NAV value element for $symbol")
                return null
            }

            val navText = navParagraph.text().trim()
            val navValue = navText.replace("$", "").replace(",", "").trim().toDoubleOrNull() ?: run {
                logger.warn("Could not parse NAV value '$navText' for $symbol")
                return null
            }

            logger.info("Fetched NAV for $symbol: $navValue")

            NavData(
                symbol = symbol,
                nav = navValue,
                asOfDate = null,
                lastFetchTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.error("Failed to fetch NAV for $symbol: ${e.message}", e)
            null
        }
    }
}
