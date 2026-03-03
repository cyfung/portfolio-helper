package com.portfoliohelper.web

import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.BacktestService
import com.portfoliohelper.service.BacktestStats
import com.portfoliohelper.service.DataPoint
import com.portfoliohelper.service.MarginConfig
import com.portfoliohelper.service.MultiBacktestRequest
import com.portfoliohelper.service.PortfolioConfig
import com.portfoliohelper.service.BackupService
import com.portfoliohelper.service.IbkrMarginRateService
import com.portfoliohelper.service.PortfolioRegistry
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.MarginRebalanceMode
import com.portfoliohelper.service.RebalanceStrategy
import com.portfoliohelper.service.TickerWeight
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

        get("/backtest") {
            call.renderBacktestPage()
        }

        get("/api/backtest/settings") {
            val f = AppDirs.dataDir.resolve(".backtest/settings.json").toFile()
            call.respondText(if (f.exists()) f.readText() else "{}", ContentType.Application.Json)
        }

        post("/api/backtest/run") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject
                runCatching {
                    val dir = AppDirs.dataDir.resolve(".backtest").toFile(); dir.mkdirs()
                    File(dir, "settings.json").writeText(body)
                }

                val fromDate = json["fromDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val toDate = json["toDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                val portfolios = json["portfolios"]?.jsonArray?.map { pel ->
                    val pObj = pel.jsonObject
                    PortfolioConfig(
                        label = pObj["label"]?.jsonPrimitive?.contentOrNull ?: "Portfolio",
                        tickers = pObj["tickers"]?.jsonArray?.map { el ->
                            val obj = el.jsonObject
                            TickerWeight(
                                ticker = obj["ticker"]!!.jsonPrimitive.content,
                                weight = obj["weight"]!!.jsonPrimitive.double
                            )
                        } ?: emptyList(),
                        rebalanceStrategy = runCatching {
                            RebalanceStrategy.valueOf(pObj["rebalanceStrategy"]!!.jsonPrimitive.content)
                        }.getOrDefault(RebalanceStrategy.YEARLY),
                        marginStrategies = pObj["marginStrategies"]?.jsonArray?.map { mel ->
                            val mObj = mel.jsonObject
                            MarginConfig(
                                marginRatio = mObj["marginRatio"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                marginSpread = mObj["marginSpread"]?.jsonPrimitive?.doubleOrNull ?: 0.015,
                                marginDeviationUpper = mObj["marginDeviationUpper"]?.jsonPrimitive?.doubleOrNull ?: 0.05,
                                marginDeviationLower = mObj["marginDeviationLower"]?.jsonPrimitive?.doubleOrNull ?: 0.05,
                                upperRebalanceMode = runCatching {
                                    MarginRebalanceMode.valueOf(
                                        mObj["upperRebalanceMode"]?.jsonPrimitive?.contentOrNull ?: "PROPORTIONAL"
                                    )
                                }.getOrDefault(MarginRebalanceMode.PROPORTIONAL),
                                lowerRebalanceMode = runCatching {
                                    MarginRebalanceMode.valueOf(
                                        mObj["lowerRebalanceMode"]?.jsonPrimitive?.contentOrNull ?: "PROPORTIONAL"
                                    )
                                }.getOrDefault(MarginRebalanceMode.PROPORTIONAL)
                            )
                        } ?: emptyList()
                    )
                } ?: emptyList()

                val result = BacktestService.runMulti(MultiBacktestRequest(fromDate, toDate, portfolios))

                // Serialise result to JSON manually
                fun serializeStats(s: BacktestStats) = buildString {
                    append("{\"cagr\":${s.cagr},\"maxDrawdown\":${s.maxDrawdown},\"sharpe\":${s.sharpe}")
                    append(",\"endingValue\":${s.endingValue}")
                    append(",\"marginUpperTriggers\":${s.marginUpperTriggers ?: "null"}")
                    append(",\"marginLowerTriggers\":${s.marginLowerTriggers ?: "null"}")
                    append("}")
                }
                fun serializePoints(pts: List<DataPoint>) =
                    "[${pts.joinToString(",") { "{\"date\":\"${it.date}\",\"value\":${it.value}}" }}]"

                val resultJson = buildString {
                    append("{\"portfolios\":[")
                    result.portfolios.forEachIndexed { pi, pr ->
                        if (pi > 0) append(",")
                        val escapedLabel = pr.label.replace("\\", "\\\\").replace("\"", "\\\"")
                        append("{\"label\":\"$escapedLabel\",\"curves\":[")
                        pr.curves.forEachIndexed { ci, cr ->
                            if (ci > 0) append(",")
                            val escapedCurveLabel = cr.label.replace("\\", "\\\\").replace("\"", "\\\"")
                            append("{\"label\":\"$escapedCurveLabel\",")
                            append("\"points\":${serializePoints(cr.points)},")
                            append("\"stats\":${serializeStats(cr.stats)}}")
                        }
                        append("]}")
                    }
                    append("]}")
                }
                call.respondText(resultJson, ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
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

        // Loan calculation history — stored in .loan/history.json under dataDir (newest first, max 5 entries)
        get("/api/loan/history") {
            val histFile = AppDirs.dataDir.resolve(".loan/history.json").toFile()
            call.respondText(
                if (histFile.exists()) histFile.readText() else "[]",
                ContentType.Application.Json
            )
        }

        post("/api/loan/save") {
            try {
                val body = call.receiveText()
                val newEntry = Json.parseToJsonElement(body)
                val histFile = AppDirs.dataDir.resolve(".loan/history.json").toFile()
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

            // Register callback for price updates; replay cached quotes immediately so
            // clients that connect after the first fetch don't wait for the next poll cycle
            val callback: (String, YahooQuote) -> Unit = { symbol, quote ->
                if (quote.regularMarketPrice != null) {
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
            }

            val unregisterPrice = YahooMarketDataService.onUpdateWithReplay(callback)

            // Register callback for NAV updates; replay cached NAV immediately so
            // clients that connect after a slow NAV fetch don't miss it until next poll
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

            val unregisterNav = NavService.onUpdateWithReplay(navCallback)

            // Listen for portfolio reload events
            val collectJob = launch {
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
            } finally {
                collectJob.cancel()
                unregisterPrice()
                unregisterNav()
                channel.close()
            }
        }

        // Trigger an immediate backup for a portfolio (called before opening the restore UI or virtual rebalance)
        post("/api/backup/trigger") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = PortfolioRegistry.get(portfolioId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val prefix    = call.request.queryParameters["prefix"]?.takeIf { it.isNotBlank() }
            val subfolder = call.request.queryParameters["subfolder"]?.takeIf { it.isNotBlank() }
            BackupService.backupNow(portfolioEntry, prefix, subfolder)
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        // List available backups grouped by subfolder ("default" = root .backup/ dir)
        get("/api/backup/list") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = PortfolioRegistry.get(portfolioId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val all = BackupService.listAllBackups(portfolioEntry)
            val json = buildString {
                append("{")
                all.entries.forEachIndexed { i, (key, dates) ->
                    if (i > 0) append(",")
                    append("\"$key\":[${dates.joinToString(",") { "\"$it\"" }}]")
                }
                append("}")
            }
            call.respondText(json, ContentType.Application.Json)
        }

        // Restore a portfolio from a dated backup ZIP (optional subfolder param)
        post("/api/backup/restore") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = PortfolioRegistry.get(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val date = call.request.queryParameters["date"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing date parameter")
                val subfolder = call.request.queryParameters["subfolder"]?.takeIf { it.isNotBlank() }
                BackupService.restoreBackup(portfolioEntry, date, subfolder)
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
