package com.portfoliohelper.service

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

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

    private const val SEND_URL = "https://ndcdyn.interactivebrokers.com/Universal/servlet/FlexStatementService.SendRequest"
    private const val GET_URL  = "https://ndcdyn.interactivebrokers.com/Universal/servlet/FlexStatementService.GetStatement"

    /** Fetches IBKR Flex Query XML using the two-step API (request → poll). */
    suspend fun fetch(token: String, queryId: String): String {
        // Step 1 — submit the query
        val sendXml = client.get(SEND_URL) {
            parameter("t", token)
            parameter("q", queryId)
            parameter("v", "3")
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
            delay(8_000)
            val xml = client.get(GET_URL) {
                parameter("t", token)
                parameter("q", refCode)
                parameter("v", "3")
            }.bodyAsText()

            val s2 = tag(xml, "Status")
            when {
                s2 == "Processing" || s2 == "Warn" ->
                    logger.info("Flex still processing (attempt ${attempt + 1}/10): ${tag(xml, "ErrorMessage") ?: ""}")
                s2 != null && s2 != "Success" -> throw FlexParseException(
                    "Flex result error (status=$s2): ${tag(xml, "ErrorMessage") ?: xml}"
                )
                else -> return xml
            }
        }
        throw FlexParseException("Flex query timed out after 10 polling attempts (ref=$refCode)")
    }

    /** Extracts text content of the first occurrence of <tag>...</tag>. */
    private fun tag(xml: String, name: String): String? {
        val open  = xml.indexOf("<$name>").takeIf { it >= 0 } ?: return null
        val close = xml.indexOf("</$name>", open).takeIf { it >= 0 } ?: return null
        return xml.substring(open + name.length + 2, close).trim().ifEmpty { null }
    }

    fun shutdown() = client.close()
}
