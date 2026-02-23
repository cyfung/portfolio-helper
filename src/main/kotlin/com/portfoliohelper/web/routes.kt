package com.portfoliohelper.web

import com.portfoliohelper.service.BackupService
import com.portfoliohelper.service.IbkrMarginRateService
import com.portfoliohelper.service.PortfolioRegistry
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.nav.NavData
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.service.yahoo.YahooQuote
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.File
import java.io.FileWriter

private val cashKeyKnownFlags = setOf("M", "E")

private fun formatQty(amount: Double) =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

private val LOAN_COMPARE_FIELDS =
    listOf("loanAmount", "numPeriods", "periodLength", "payment", "rateApy", "rateFlat", "extraCashflows")

private fun loanEntryKey(obj: JsonObject): String =
    LOAN_COMPARE_FIELDS.joinToString("|") { obj[it]?.toString() ?: "" }

private fun normalizeCashKey(raw: String): String {
    val parts = raw.split(".").toMutableList()
    val flags = mutableListOf<String>()
    while (parts.isNotEmpty() && parts.last().uppercase() in cashKeyKnownFlags) {
        flags.add(0, parts.removeLast().uppercase())
    }
    if (parts.isNotEmpty()) {
        parts[parts.size - 1] = parts[parts.size - 1].uppercase()
    }
    return (parts + flags).joinToString(".")
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.renderPortfolioPage(
                PortfolioRegistry.main(),
                PortfolioRegistry.entries,
                "main"
            )
        }

        get("/portfolio/{name}") {
            val id = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val entry = PortfolioRegistry.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.renderPortfolioPage(entry, PortfolioRegistry.entries, id)
        }

        get("/loan") {
            call.renderLoanCalculatorPage()
        }

        // Update portfolio CSV — client sends full state: [{symbol, amount, targetWeight, letf}]
        post("/api/portfolio/update") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray

                val csvPath = portfolioEntry.csvPath
                FileWriter(File(csvPath)).use { writer ->
                    val csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader("stock_label", "amount", "target_weight", "letf")
                        .build()
                    CSVPrinter(writer, csvFormat).use { printer ->
                        for (element in updates) {
                            val obj = element.jsonObject
                            val symbol = obj["symbol"]?.jsonPrimitive?.content ?: continue
                            val amount = obj["amount"]?.jsonPrimitive?.double ?: continue
                            val targetWeight = obj["targetWeight"]?.jsonPrimitive?.double ?: 0.0
                            val letf = obj["letf"]?.jsonPrimitive?.content ?: ""
                            val weightStr = if (targetWeight > 0) "%.2f".format(targetWeight) else ""
                            printer.printRecord(symbol, formatQty(amount), weightStr, letf)
                        }
                    }
                }

                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Update cash.txt — full-state write: [{key, value}]; preserves comments; deletes unlisted entries
        post("/api/cash/update") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray
                val cashPath = portfolioEntry.cashPath
                val file = File(cashPath)

                // Build update map: normalizedKey -> (payloadKey, rawValue)
                val updateMap = mutableMapOf<String, Pair<String, String>>()
                for (el in updates) {
                    val obj = el.jsonObject
                    val key = obj["key"]!!.jsonPrimitive.content
                    val value = obj["value"]!!.jsonPrimitive.content
                    updateMap[normalizeCashKey(key)] = key to value
                }

                val keysSeen = mutableSetOf<String>()
                val outputLines = mutableListOf<String>()

                if (file.exists()) {
                    file.readLines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            outputLines.add(line)
                            return@forEach
                        }
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx < 0) {
                            outputLines.add(line)
                            return@forEach
                        }
                        val rawKey = trimmed.substring(0, eqIdx).trim()
                        val normalizedKey = normalizeCashKey(rawKey)
                        val entry = updateMap[normalizedKey]
                        if (entry != null) {
                            outputLines.add("$rawKey=${entry.second}")
                            keysSeen.add(normalizedKey)
                        }
                        // else: not in update list → deleted → skip
                    }
                }

                // Append new entries (key not seen in existing file)
                for ((normalizedKey, pair) in updateMap) {
                    if (normalizedKey !in keysSeen) {
                        outputLines.add("${pair.first}=${pair.second}")
                    }
                }

                file.writeText(outputLines.joinToString("\n") + if (outputLines.isNotEmpty()) "\n" else "")
                // File watcher detects change → SSE reload → page refresh
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Loan calculation history — stored in data/.loan/history.json (newest first, max 5 entries)
        get("/api/loan/history") {
            val histFile = File("data/.loan/history.json")
            call.respondText(
                if (histFile.exists()) histFile.readText() else "[]",
                ContentType.Application.Json
            )
        }

        post("/api/loan/save") {
            try {
                val body = call.receiveText()
                val newEntry = Json.parseToJsonElement(body)
                val histFile = File("data/.loan/history.json")
                histFile.parentFile.mkdirs()
                val newKey = loanEntryKey(newEntry.jsonObject)
                val existing = if (histFile.exists())
                    Json.parseToJsonElement(histFile.readText()).jsonArray
                        .filter { it is JsonObject && loanEntryKey(it.jsonObject) != newKey }
                        .toMutableList()
                else mutableListOf()
                existing.add(0, newEntry)
                histFile.writeText(JsonArray(existing.take(5)).toString())
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json, HttpStatusCode.InternalServerError
                )
            }
        }

        // Persist rebalance target (USD value) to data/rebal-target.txt alongside the portfolio CSV
        post("/api/rebal-target/save") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val value = call.receiveText().trim().toDoubleOrNull()
                val rebalFile = File(portfolioEntry.csvPath).resolveSibling("rebal-target.txt")
                if (value == null || value <= 0) rebalFile.delete() else rebalFile.writeText(value.toString())
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json, HttpStatusCode.InternalServerError
                )
            }
        }

        // Server-Sent Events (SSE) endpoint for streaming price updates
        get("/api/prices/stream") {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.headers.append(HttpHeaders.ContentType, "text/event-stream")
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")

            val channel = Channel<String>(Channel.BUFFERED)

            // Register callback for price updates
            val callback: (String, YahooQuote) -> Unit = { symbol, quote ->
                val json = buildString {
                    append("{")
                    append("\"symbol\":\"$symbol\",")
                    append("\"markPrice\":${quote.regularMarketPrice},")
                    append("\"lastClosePrice\":${quote.previousClose},")
                    append("\"isMarketClosed\":${quote.isMarketClosed},")
                    append("\"tradingPeriodEnd\":${quote.tradingPeriodEnd},")
                    append("\"timestamp\":${quote.lastUpdateTime}")
                    append("}")
                }
                channel.trySend("data: $json\n\n")
            }

            YahooMarketDataService.onPriceUpdate(callback)

            // Register callback for NAV updates
            val navCallback: (String, NavData) -> Unit = { symbol, navData ->
                val json = buildString {
                    append("{")
                    append("\"type\":\"nav\",")
                    append("\"symbol\":\"$symbol\",")
                    append("\"nav\":${navData.nav},")
                    append("\"timestamp\":${navData.lastFetchTime}")
                    append("}")
                }
                channel.trySend("data: $json\n\n")
            }

            NavService.onNavUpdate(navCallback)

            // Listen for portfolio reload events
            launch {
                PortfolioUpdateBroadcaster.reloadEvents.collect {
                    val json = "{\"type\":\"reload\",\"timestamp\":${it.timestamp}}"
                    channel.send("data: $json\n\n")
                }
            }

            // Stream updates to client
            try {
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    writeFully(":keepalive\n\n".toByteArray(Charsets.UTF_8))
                    flush()

                    for (message in channel) {
                        writeFully(message.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            } catch (_: Exception) {
                // Client disconnected
                channel.close()
            }
        }

        // Trigger an immediate backup for a portfolio (called before opening the restore UI)
        post("/api/backup/trigger") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = PortfolioRegistry.get(portfolioId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            BackupService.backupNow(portfolioEntry)
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        // List available backups for a portfolio
        get("/api/backup/list") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = PortfolioRegistry.get(portfolioId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val dates = BackupService.listBackups(portfolioEntry)
            val json = "[${dates.joinToString(",") { "\"$it\"" }}]"
            call.respondText(json, ContentType.Application.Json)
        }

        // Restore a portfolio from a dated backup ZIP
        post("/api/backup/restore") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val date = call.request.queryParameters["date"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing date parameter")
                BackupService.restoreBackup(portfolioEntry, date)
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json, HttpStatusCode.InternalServerError
                )
            }
        }

        // Manual reload for IBKR margin rates
        post("/api/margin-rates/reload") {
            if (!IbkrMarginRateService.canReload()) {
                call.respond(HttpStatusCode.TooManyRequests, "Reload not allowed within 10 minutes of last fetch")
                return@post
            }
            IbkrMarginRateService.reloadNow()
            call.respond(HttpStatusCode.OK, IbkrMarginRateService.getLastFetchMillis().toString())
        }

        // Serve static files (CSS, JS)
        staticResources("/static", "static")
    }
}
