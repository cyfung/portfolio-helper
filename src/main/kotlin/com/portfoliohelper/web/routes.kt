package com.portfoliohelper.web

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths

private val cashKeyKnownFlags = setOf("M", "E")

private fun formatQty(amount: Double) =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

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

        // Update portfolio CSV with edited qty/weight values
        post("/api/portfolio/update") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray

                val csvPath = portfolioEntry.csvPath
                val path = Paths.get(csvPath)

                // Read existing CSV to preserve row order and letf column
                val existingRows = mutableListOf<Map<String, String>>()
                val headers = mutableListOf<String>()

                if (Files.exists(path)) {
                    BufferedReader(FileReader(path.toFile())).use { reader ->
                        val csvFormat = CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .build()
                        CSVParser(reader, csvFormat).use { parser ->
                            headers.addAll(parser.headerNames)
                            for (record in parser) {
                                val row = mutableMapOf<String, String>()
                                for ((i, h) in headers.withIndex()) {
                                    row[h] = if (i < record.size()) record.get(i) else ""
                                }
                                existingRows.add(row)
                            }
                        }
                    }
                }

                // Build update map: symbol -> {amount, targetWeight}
                val updateMap = mutableMapOf<String, Pair<Double, Double>>()
                for (element in updates) {
                    val obj = element.jsonObject
                    val symbol = obj["symbol"]?.jsonPrimitive?.content ?: continue
                    val amount = obj["amount"]?.jsonPrimitive?.double ?: continue
                    val targetWeight = obj["targetWeight"]?.jsonPrimitive?.double ?: 0.0
                    updateMap[symbol] = amount to targetWeight
                }

                // Write updated CSV
                FileWriter(path.toFile()).use { writer ->
                    val csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader(*headers.toTypedArray())
                        .build()
                    CSVPrinter(writer, csvFormat).use { printer ->
                        for (row in existingRows) {
                            val symbol = row["stock_label"] ?: ""
                            val update = updateMap[symbol]
                            val values = headers.map { header ->
                                when {
                                    header == "amount" && update != null -> formatQty(update.first)
                                    header == "target_weight" && update != null -> update.second.toString()
                                    else -> row[header] ?: ""
                                }
                            }
                            printer.printRecord(values)
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

        // Update cash.txt with edited amounts
        post("/api/cash/update") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray
                val cashPath = portfolioEntry.cashPath
                val file = File(cashPath)

                val updateMap = updates.associate { el ->
                    val obj = el.jsonObject
                    obj["key"]!!.jsonPrimitive.content to obj["amount"]!!.jsonPrimitive.double
                }

                val lines = file.readLines()
                val newLines = lines.map { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@map line
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx < 0) return@map line
                    val key = trimmed.substring(0, eqIdx).trim()
                    val normalizedKey = normalizeCashKey(key)
                    val newAmount = updateMap[normalizedKey]
                    if (newAmount != null) "$key=$newAmount" else line
                }
                file.writeText(newLines.joinToString("\n") + "\n")
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
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write(":keepalive\n\n")
                    flush()

                    for (message in channel) {
                        write(message)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // Client disconnected
                channel.close()
            }
        }

        // Serve static files (CSS, JS)
        staticResources("/static", "static")
    }
}
