package com.portfoliohelper.service

import com.portfoliohelper.APP_VERSION
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

class FlexParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object FlexQueryService {

    private val logger = LoggerFactory.getLogger(FlexQueryService::class.java)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis  = 60_000
        }
    }

    private const val BASE_URL = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
    private const val SEND_URL = "$BASE_URL/SendRequest"
    private const val GET_URL  = "$BASE_URL/GetStatement"
    private const val FLEX_VERSION = "3"

    /** Fetches IBKR Flex Query XML using the two-step API (request → poll). */
    suspend fun fetch(token: String, queryId: String): String {
        // Step 1 — submit the query
        val sendXml = client.get(SEND_URL) {
            ibkrFlexHeaders()
            parameter("t", token)
            parameter("q", queryId)
            parameter("v", FLEX_VERSION)
        }.bodyAsText()

        val status = tag(sendXml, "Status")
        if (status != "Success") {
            val code = tag(sendXml, "ErrorCode") ?: "?"
            val msg  = tag(sendXml, "ErrorMessage") ?: sendXml
            throw FlexParseException("Flex query rejected (status=$status, code=$code): $msg")
        }

        val refCode = tag(sendXml, "ReferenceCode")
            ?: throw FlexParseException("Missing ReferenceCode in Flex response")

        // Step 2 — poll up to 10 times (8 s apart); large date ranges can take 60+ s
        repeat(10) { attempt ->
            delay(8_000.milliseconds)
            val xml = client.get(GET_URL) {
                ibkrFlexHeaders()
                parameter("t", token)
                parameter("q", refCode)
                parameter("v", FLEX_VERSION)
            }.bodyAsText()

            val s2 = tag(xml, "Status")
            val code = tag(xml, "ErrorCode")
            when {
                s2 == "Processing" || s2 == "Warn" || isRetryableGetStatementError(s2, code) ->
                    logger.info(
                        "Flex statement not ready (attempt ${attempt + 1}/10, status=$s2, code=${code ?: "?"}): " +
                            (tag(xml, "ErrorMessage") ?: "")
                    )
                s2 != null && s2 != "Success" -> throw FlexParseException(
                    "Flex result error (status=$s2, code=${code ?: "?"}): ${tag(xml, "ErrorMessage") ?: xml}"
                )
                else -> return xml
            }
        }
        throw FlexParseException("Flex query timed out after 10 polling attempts (ref=$refCode)")
    }

    private fun HttpRequestBuilder.ibkrFlexHeaders() {
        header(HttpHeaders.UserAgent, "portfolio-helper/$APP_VERSION")
    }

    private fun isRetryableGetStatementError(status: String?, code: String?): Boolean {
        if (status != "Fail") return false
        return code in setOf(
            "1001",
            "1004",
            "1005",
            "1006",
            "1007",
            "1008",
            "1009",
            "1018",
            "1019",
            "1021",
        )
    }

    /** Extracts text content of the first occurrence of <tag>...</tag>. */
    private fun tag(xml: String, name: String): String? {
        val open  = xml.indexOf("<$name>").takeIf { it >= 0 } ?: return null
        val close = xml.indexOf("</$name>", open).takeIf { it >= 0 } ?: return null
        return xml.substring(open + name.length + 2, close).trim().ifEmpty { null }
    }

    fun shutdown() = client.close()
}
