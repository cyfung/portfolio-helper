package com.portfoliohelper.web

import com.portfoliohelper.AppConfig
import com.portfoliohelper.service.*
import com.portfoliohelper.service.UpdateService.toJson
import com.portfoliohelper.service.db.CashTable
import com.portfoliohelper.service.db.GlobalSettingsTable
import com.portfoliohelper.service.db.PositionsTable
import com.portfoliohelper.service.db.SavedBacktestPortfoliosTable
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.tws.PortfolioSnapshot
import com.portfoliohelper.util.appJson
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class PairedDeviceDto(val id: String, val name: String, val pairedAt: Long, val lastIp: String)

private fun loadBacktestSettings(settingsKey: String): String = transaction {
    fun get(k: String) = GlobalSettingsTable.selectAll()
        .where { GlobalSettingsTable.key eq k }
        .firstOrNull()?.get(GlobalSettingsTable.value)
    val settings = get(settingsKey)?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    val portfolios = get("backtest.portfolios")?.let { Json.parseToJsonElement(it) } ?: JsonArray(emptyList())
    buildJsonObject { settings.forEach { (k, v) -> put(k, v) }; put("portfolios", portfolios) }.toString()
}

private fun saveBacktestSettings(json: JsonObject, settingsKey: String) = transaction {
    fun upsert(k: String, v: String) = GlobalSettingsTable.upsert {
        it[GlobalSettingsTable.key] = k; it[GlobalSettingsTable.value] = v
    }
    upsert("backtest.portfolios", (json["portfolios"] ?: JsonArray(emptyList())).toString())
    upsert(settingsKey, buildJsonObject { json.forEach { (k, v) -> if (k != "portfolios") put(k, v) } }.toString())
}

private val cashKeyKnownFlags = setOf("M")

private val LOAN_COMPARE_FIELDS = listOf(
    "loanAmount", "numPeriods", "periodLength", "payment", "rateApy", "rateFlat", "extraCashflows"
)

private fun loanEntryKey(obj: JsonObject): String =
    LOAN_COMPARE_FIELDS.joinToString("|") { obj[it]?.toString() ?: "" }

/** Parse a cash key (e.g. "Cash.USD.M") + raw value string into a CashEntry for DB insertion. */
private fun parseCashEntryFromKeyValue(
    key: String, valueStr: String
): com.portfoliohelper.model.CashEntry? {
    val allParts = key.split(".")
    val mutableParts = allParts.toMutableList()
    val flags = mutableSetOf<String>()
    while (mutableParts.isNotEmpty() && mutableParts.last().uppercase() in cashKeyKnownFlags) {
        flags.add(mutableParts.removeLast().uppercase())
    }
    if (mutableParts.size < 2) return null
    val currency = mutableParts.last().uppercase()
    val label = mutableParts.dropLast(1).joinToString(".")
    val marginFlag = "M" in flags
    return if (currency == "P") {
        val trimmed = valueStr.trim()
        val sign = if (trimmed.startsWith("-")) -1.0 else 1.0
        val portfolioId = trimmed.trimStart('+', '-').lowercase()
        if (portfolioId.isEmpty()) null
        else com.portfoliohelper.model.CashEntry(
            label, "P", marginFlag, amount = sign, portfolioRef = portfolioId
        )
    } else {
        val amount = valueStr.toDoubleOrNull() ?: return null
        com.portfoliohelper.model.CashEntry(label, currency, marginFlag, amount)
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun Application.configureRouting() {
    val httpsPort = System.getenv("PORTFOLIO_HELPER_PORT")?.toIntOrNull() ?: 8443
    val httpPort = System.getenv("PORTFOLIO_HELPER_HTTP_PORT")?.toIntOrNull() ?: 8080

    install(SSE)

    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.local.localPort == httpPort) {
            val host = call.request.host()
            val path = call.request.uri
            call.respondRedirect("https://$host:$httpsPort$path", permanent = true)
            return@intercept finish()
        }
    }

    routing {

        // Android Sync Endpoints
        configureSyncRoutes()

        get("/") {
            val all = ManagedPortfolio.getAll()
            val default = all.first()
            call.renderPortfolioPage(default, all, default.slug)
        }

        get("/portfolio/{name}") {
            val slug = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val entry =
                ManagedPortfolio.getBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.renderPortfolioPage(entry, ManagedPortfolio.getAll(), slug)
        }

        get("/loan") {
            call.renderLoanCalculatorPage()
        }

        get("/backtest") {
            call.renderBacktestPage()
        }

        get("/montecarlo") {
            call.renderMonteCarloPage()
        }

        get("/config") {
            call.renderConfigPage()
        }

        get("/api/backtest/settings") {
            call.respondText(loadBacktestSettings("backtest.settings"), ContentType.Application.Json)
        }

        get("/api/montecarlo/settings") {
            call.respondText(loadBacktestSettings("backtest.mc-settings"), ContentType.Application.Json)
        }

        get("/api/backtest/savedPortfolios") {
            val rows = transaction {
                SavedBacktestPortfoliosTable.selectAll()
                    .orderBy(SavedBacktestPortfoliosTable.createdAt)
                    .map { buildJsonObject {
                        put("name", it[SavedBacktestPortfoliosTable.name])
                        put("config", Json.parseToJsonElement(it[SavedBacktestPortfoliosTable.config]))
                    } }
            }
            call.respondText(JsonArray(rows).toString(), ContentType.Application.Json)
        }

        delete("/api/backtest/savedPortfolios") {
            val name = call.request.queryParameters["name"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            transaction { SavedBacktestPortfoliosTable.deleteWhere { SavedBacktestPortfoliosTable.name eq name } }
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        post("/api/backtest/savedPortfolios") {
            val body = call.receiveText()
            val entry = Json.parseToJsonElement(body).jsonObject
            val name = entry["name"]?.jsonPrimitive?.contentOrNull ?: return@post call.respond(HttpStatusCode.BadRequest)
            val config = entry["config"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val saved = transaction {
                val takenNames = SavedBacktestPortfoliosTable.selectAll()
                    .map { it[SavedBacktestPortfoliosTable.name] }.toSet()
                var finalName = name; var counter = 2
                while (finalName in takenNames) { finalName = "$name ($counter)"; counter++ }
                SavedBacktestPortfoliosTable.insert {
                    it[SavedBacktestPortfoliosTable.name] = finalName
                    it[SavedBacktestPortfoliosTable.config] = config.toString()
                    it[SavedBacktestPortfoliosTable.createdAt] = System.currentTimeMillis()
                }
                buildJsonObject { put("name", finalName); put("config", config) }
            }
            call.respondText(saved.toString(), ContentType.Application.Json)
        }

        post("/api/backtest/run") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject
                runCatching { saveBacktestSettings(json, "backtest.settings") }

                val fromDate =
                    json["fromDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val toDate =
                    json["toDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                val portfolios = json["portfolios"]?.jsonArray?.map { pel ->
                    val pObj = pel.jsonObject
                    PortfolioConfig(
                        label = pObj["label"]?.jsonPrimitive?.contentOrNull
                        ?: "Portfolio",
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
                                marginRatio = mObj["marginRatio"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.0,
                                marginSpread = mObj["marginSpread"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.015,
                                marginDeviationUpper = mObj["marginDeviationUpper"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.05,
                                marginDeviationLower = mObj["marginDeviationLower"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.05,
                                upperRebalanceMode = runCatching {
                                    MarginRebalanceMode.valueOf(
                                        mObj["upperRebalanceMode"]?.jsonPrimitive?.contentOrNull
                                            ?: "PROPORTIONAL"
                                    )
                                }.getOrDefault(MarginRebalanceMode.PROPORTIONAL),
                                lowerRebalanceMode = runCatching {
                                    MarginRebalanceMode.valueOf(
                                        mObj["lowerRebalanceMode"]?.jsonPrimitive?.contentOrNull
                                            ?: "PROPORTIONAL"
                                    )
                                }.getOrDefault(MarginRebalanceMode.PROPORTIONAL)
                            )
                        } ?: emptyList(),
                        includeNoMargin = pObj["includeNoMargin"]?.jsonPrimitive?.booleanOrNull
                            ?: true)
                } ?: emptyList()

                val result =
                    BacktestService.runMulti(MultiBacktestRequest(fromDate, toDate, portfolios))

                // Serialise result to JSON manually
                fun serializeStats(s: BacktestStats) = buildString {
                    append("{\"cagr\":${s.cagr},\"maxDrawdown\":${s.maxDrawdown},\"sharpe\":${s.sharpe}")
                    append(",\"ulcerIndex\":${s.ulcerIndex},\"upi\":${s.upi}")
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
                            val escapedCurveLabel =
                                cr.label.replace("\\", "\\\\").replace("\"", "\\\"")
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

        get("/api/montecarlo/progress") {
            val (completed, total) = MonteCarloService.getProgress()
            call.respondText(
                """{"completed":$completed,"total":$total}""", ContentType.Application.Json
            )
        }

        post("/api/montecarlo/run") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject

                runCatching { saveBacktestSettings(json, "backtest.mc-settings") }

                val fromDate =
                    json["fromDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val toDate =
                    json["toDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val minChunkYears = json["minChunkYears"]?.jsonPrimitive?.doubleOrNull ?: 3.0
                val maxChunkYears = json["maxChunkYears"]?.jsonPrimitive?.doubleOrNull ?: 8.0
                val simulatedYears = json["simulatedYears"]?.jsonPrimitive?.intOrNull ?: 20
                val numSimulations = json["numSimulations"]?.jsonPrimitive?.intOrNull ?: 500

                val portfolios = json["portfolios"]?.jsonArray?.map { pel ->
                    val pObj = pel.jsonObject
                    PortfolioConfig(
                        label = pObj["label"]?.jsonPrimitive?.contentOrNull
                        ?: "Portfolio",
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
                                marginRatio = mObj["marginRatio"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.0,
                                marginSpread = mObj["marginSpread"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.015,
                                marginDeviationUpper = mObj["marginDeviationUpper"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.05,
                                marginDeviationLower = mObj["marginDeviationLower"]?.jsonPrimitive?.doubleOrNull
                                    ?: 0.05,
                                upperRebalanceMode = runCatching {
                                    MarginRebalanceMode.valueOf(
                                        mObj["upperRebalanceMode"]?.jsonPrimitive?.contentOrNull
                                            ?: "PROPORTIONAL"
                                    )
                                }.getOrDefault(MarginRebalanceMode.PROPORTIONAL),
                                lowerRebalanceMode = runCatching {
                                    MarginRebalanceMode.valueOf(
                                        mObj["lowerRebalanceMode"]?.jsonPrimitive?.contentOrNull
                                            ?: "PROPORTIONAL"
                                    )
                                }.getOrDefault(MarginRebalanceMode.PROPORTIONAL)
                            )
                        } ?: emptyList(),
                        includeNoMargin = pObj["includeNoMargin"]?.jsonPrimitive?.booleanOrNull
                            ?: true)
                } ?: emptyList()

                val request = MonteCarloRequest(
                    fromDate,
                    toDate,
                    minChunkYears,
                    maxChunkYears,
                    simulatedYears,
                    numSimulations,
                    portfolios
                )
                val result = MonteCarloService.runMonteCarlo(request)

                fun serializePoints(pts: List<Double>) =
                    "[${pts.joinToString(",") { "%.4f".format(it) }}]"

                val resultJson = buildString {
                    append("{\"simulatedYears\":${result.simulatedYears}")
                    append(",\"numSimulations\":${result.numSimulations}")
                    append(",\"portfolios\":[")
                    result.portfolios.forEachIndexed { pi, pr ->
                        if (pi > 0) append(",")
                        val pLabel = pr.label.replace("\\", "\\\\").replace("\"", "\\\"")
                        append("{\"label\":\"$pLabel\",\"curves\":[")
                        pr.curves.forEachIndexed { ci, cr ->
                            if (ci > 0) append(",")
                            val cLabel = cr.label.replace("\\", "\\\\").replace("\"", "\\\"")
                            append("{\"label\":\"$cLabel\",\"percentilePaths\":[")
                            cr.percentilePaths.forEachIndexed { ppi, pp ->
                                if (ppi > 0) append(",")
                                append("{\"percentile\":${pp.percentile}")
                                append(",\"points\":${serializePoints(pp.points)}")
                                append(",\"endValue\":${pp.endValue}")
                                append(",\"cagr\":${pp.cagr}")
                                append(",\"maxDrawdown\":${pp.maxDrawdown}")
                                append(",\"sharpe\":${"%.4f".format(pp.sharpe)}")
                                append(",\"ulcerIndex\":${"%.4f".format(pp.ulcerIndex)}")
                                append(",\"upi\":${"%.4f".format(pp.upi)}")
                                append("}")
                            }
                            append("]")
                            fun serializeDoubleList(lst: List<Double>) =
                                "[${lst.joinToString(",") { "%.6f".format(it) }}]"
                            append(",\"maxDdPercentiles\":${serializeDoubleList(cr.maxDdPercentiles)}")
                            append(",\"sharpePercentiles\":${serializeDoubleList(cr.sharpePercentiles)}")
                            append(",\"ulcerPercentiles\":${serializeDoubleList(cr.ulcerPercentiles)}")
                            append(",\"upiPercentiles\":${serializeDoubleList(cr.upiPercentiles)}")
                            append("}")
                        }
                        append("]}")
                    }
                    append("]}")
                }
                call.respondText(resultJson, ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"error\":\"${e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Update portfolio positions — client sends full state: [{symbol, amount, targetWeight, letf, groups}]
        post("/api/portfolio/update") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry =
                    ManagedPortfolio.getBySlug(portfolioId) ?: return@post call.respond(
                        HttpStatusCode.NotFound
                    )

                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray

                data class PositionRow(
                    val symbol: String,
                    val amount: Double,
                    val targetWeight: Double,
                    val letf: String,
                    val groups: String
                )

                val rows = updates.mapNotNull { el ->
                    val obj = el.jsonObject
                    val symbol = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val amount = obj["amount"]?.jsonPrimitive?.double ?: return@mapNotNull null
                    PositionRow(
                        symbol = symbol,
                        amount = amount,
                        targetWeight = obj["targetWeight"]?.jsonPrimitive?.double ?: 0.0,
                        letf = obj["letf"]?.jsonPrimitive?.content ?: "",
                        groups = obj["groups"]?.jsonPrimitive?.content ?: ""
                    )
                }

                transaction {
                    PositionsTable.deleteWhere { PositionsTable.portfolioId eq portfolioEntry.serialId }
                    PositionsTable.batchInsert(rows) { row ->
                        this[PositionsTable.portfolioId] = portfolioEntry.serialId
                        this[PositionsTable.symbol] = row.symbol
                        this[PositionsTable.amount] = row.amount
                        this[PositionsTable.targetWeight] = row.targetWeight
                        this[PositionsTable.letf] = row.letf
                        this[PositionsTable.groups] = row.groups
                    }
                }

                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Update cash — full-state write: [{key, value}]; replaces all existing entries for the portfolio
        post("/api/cash/update") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry =
                    ManagedPortfolio.getBySlug(portfolioId) ?: return@post call.respond(
                        HttpStatusCode.NotFound
                    )

                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray

                val entries = updates.mapNotNull { el ->
                    val obj = el.jsonObject
                    val key = obj["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val value = obj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    parseCashEntryFromKeyValue(key, value)
                }

                transaction {
                    CashTable.deleteWhere { CashTable.portfolioId eq portfolioEntry.serialId }
                    CashTable.batchInsert(entries) { entry ->
                        this[CashTable.portfolioId] = portfolioEntry.serialId
                        this[CashTable.label] = entry.label
                        this[CashTable.currency] = entry.currency
                        this[CashTable.marginFlag] = entry.marginFlag
                        this[CashTable.amount] = entry.amount
                        this[CashTable.portfolioRef] = entry.portfolioRef
                    }
                }

                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Loan calculation history — stored in global_settings DB table (newest first, max 5 entries)
        get("/api/loan/history") {
            val value = transaction {
                GlobalSettingsTable.selectAll().where { GlobalSettingsTable.key eq "loan.history" }
                    .firstOrNull()?.get(GlobalSettingsTable.value)
            }
            call.respondText(value ?: "[]", ContentType.Application.Json)
        }

        post("/api/loan/save") {
            try {
                val body = call.receiveText()
                val newEntry = Json.parseToJsonElement(body)
                val newKey = loanEntryKey(newEntry.jsonObject)
                transaction {
                    val existing = GlobalSettingsTable.selectAll()
                        .where { GlobalSettingsTable.key eq "loan.history" }
                        .firstOrNull()?.get(GlobalSettingsTable.value)
                        ?.let { raw -> Json.parseToJsonElement(raw).jsonArray
                            .filter { el -> el is JsonObject && loanEntryKey(el.jsonObject) != newKey }
                            .toMutableList() }
                        ?: mutableListOf<JsonElement>()
                    existing.add(0, newEntry)
                    GlobalSettingsTable.upsert {
                        it[GlobalSettingsTable.key] = "loan.history"
                        it[GlobalSettingsTable.value] = JsonArray(existing.take(5)).toString()
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

        // Generic per-portfolio config store: POST /api/portfolio-config/save?portfolio=X&key=<key>
        // Persists to PortfolioCfgTable in SQLite (previously portfolio.conf file).
        // rebalTarget (USD) and marginTarget (%) are mutually exclusive — setting one clears the other.
        // Also accepts JSON body (no key param) for batch updates (e.g. twsAccount from config page).
        post("/api/portfolio-config/save") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val key = call.request.queryParameters["key"]
                val portfolioEntry =
                    ManagedPortfolio.getBySlug(portfolioId) ?: return@post call.respond(
                        HttpStatusCode.NotFound
                    )
                val body = call.receiveText().trim()

                if (key != null) {
                    // Single-key mode: plain text body
                    if (body.isEmpty()) {
                        portfolioEntry.saveConfig(key, "")
                    } else {
                        portfolioEntry.saveConfig(key, body)
                        if (key == "rebalTarget") portfolioEntry.saveConfig("marginTarget", "")
                        else if (key == "marginTarget") portfolioEntry.saveConfig("rebalTarget", "")
                    }
                } else {
                    // Batch JSON mode (from config page)
                    val json = Json.parseToJsonElement(body).jsonObject
                    for ((k, v) in json) {
                        portfolioEntry.saveConfig(k, v.jsonPrimitive.contentOrNull ?: "")
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

        // Global app config save
        post("/api/config/save") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject
                val updates =
                    json.entries.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
                AppConfig.save(updates)
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Device Management Endpoints (Localhost only)

        // Generate a PIN to display in the server UI — user enters it on their Android device to pair
        post("/api/pairing/generate") {
            val pin = PairingService.generatePin()
            call.respondText("{\"pin\":\"$pin\"}", ContentType.Application.Json)
        }

        get("/api/paired-devices") {
            val dtos = PairingService.getPairedClients().map { client ->
                PairedDeviceDto(
                    id = client.id,
                    name = client.name,
                    pairedAt = client.pairedAt,
                    lastIp = client.lastIp
                )
            }
            call.respondText(appJson.encodeToString(dtos), ContentType.Application.Json)
        }



        post("/api/unpair-device") {
            val deviceId = call.request.queryParameters["deviceId"]
            if (deviceId != null) PairingService.unpairClient(deviceId)
            else PairingService.unpairAll()
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        // Server-Sent Events (SSE) endpoint for streaming price updates
        sse("/api/prices/stream") {
            heartbeat {
                period = 100.milliseconds
                event = ServerSentEvent("heartbeat")
            }
            handleSseStream()
        }

        // Trigger an immediate DB backup for a portfolio
        post("/api/backup/trigger") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = ManagedPortfolio.getBySlug(portfolioId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val label = call.request.queryParameters["label"]?.takeIf { it.isNotBlank() } ?: ""
            BackupService.saveToDb(portfolioEntry, label)
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        // List DB backups for a portfolio — [{id, savedAt, label}, ...] newest first
        get("/api/backup/list-db") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = ManagedPortfolio.getBySlug(portfolioId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val entries = BackupService.listDbBackups(portfolioEntry)
            val json = "[" + entries.joinToString(",") {
                "{\"id\":${it.id},\"createdAt\":${it.createdAt},\"label\":\"${it.label}\"}"
            } + "]"
            call.respondText(json, ContentType.Application.Json)
        }

        // Restore a portfolio from a DB backup row
        post("/api/backup/restore-db") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val portfolioEntry = ManagedPortfolio.getBySlug(portfolioId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                BackupService.restoreFromDb(portfolioEntry, id)
                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Delete a single DB backup by id
        delete("/api/backup/delete-db") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = ManagedPortfolio.getBySlug(portfolioId)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val id = call.request.queryParameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            BackupService.deleteFromDb(portfolioEntry, id)
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        // Delete all DB backups for a portfolio
        delete("/api/backup/delete-all") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = ManagedPortfolio.getBySlug(portfolioId)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            BackupService.deleteAllFromDb(portfolioEntry)
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }

        // Export portfolio as JSON download (includes snapshotUsd for P entries)
        get("/api/backup/export-json") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            val portfolioEntry = ManagedPortfolio.getBySlug(portfolioId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val json = BackupService.exportJson(portfolioEntry) { e ->
                when (e.currency) {
                    "USD" -> e.amount
                    "P"   -> {
                        val ref = ManagedPortfolio.getBySlug(e.portfolioRef ?: return@exportJson null)
                            ?: return@exportJson null
                        e.amount * YahooMarketDataService.getCurrentPortfolio(ref.getStocks()).totalValue
                    }
                    else  -> YahooMarketDataService.getQuote("${e.currency}USD=X")
                        ?.regularMarketPrice?.let { e.amount * it }
                }
            }
            val filename = "backup-${portfolioEntry.slug}.json"
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"$filename\""
            )
            call.respondText(json, ContentType.Application.Json)
        }

        // Import CSV / TXT / ZIP / JSON file — validates and returns parsed data for edit-mode population
        post("/api/backup/import-file") {
            val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
            ManagedPortfolio.getBySlug(portfolioId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            try {
                val multipart = call.receiveMultipart()
                var bytes: ByteArray? = null
                var filename = "upload"
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "file") {
                        filename = part.originalFileName ?: "upload"
                        bytes = part.provider().toByteArray()
                    }
                    part.dispose()
                }
                val fileBytes = bytes
                    ?: return@post call.respondText(
                        "{\"error\":\"No file uploaded\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                val result = BackupService.parseImportFile(fileBytes, filename)
                if (result.error != null) {
                    call.respondText(
                        "{\"error\":\"${result.error.replace("\"", "\\\"")}\"}",
                        ContentType.Application.Json
                    )
                } else {
                    val json = buildString {
                        append("{")
                        var first = true
                        if (result.stocks != null) {
                            first = false
                            append("\"stocks\":[")
                            result.stocks.forEachIndexed { i, s ->
                                if (i > 0) append(",")
                                val sym = (s["symbol"] as String).replace("\"", "\\\"")
                                val letf = (s["letf"] as String).replace("\"", "\\\"")
                                val grp = (s["groups"] as String).replace("\"", "\\\"")
                                append("{\"symbol\":\"$sym\",\"amount\":${s["amount"]},\"targetWeight\":${s["targetWeight"]},\"letf\":\"$letf\",\"groups\":\"$grp\"}")
                            }
                            append("]")
                        }
                        if (result.cashKeys != null) {
                            if (!first) append(",")
                            append("\"cash\":[")
                            result.cashKeys.forEachIndexed { i, c ->
                                if (i > 0) append(",")
                                val k = (c["key"] ?: "").replace("\"", "\\\"")
                                val v = (c["value"] ?: "").replace("\"", "\\\"")
                                append("{\"key\":\"$k\",\"value\":\"$v\"}")
                            }
                            append("]")
                        }
                        append("}")
                    }
                    call.respondText(json, ContentType.Application.Json)
                }
            } catch (e: Exception) {
                call.respondText(
                    "{\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Manual reload for IBKR margin rates
        post("/api/margin-rates/reload") {
            if (!IbkrMarginRateService.canReload()) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    "Reload not allowed within 10 minutes of last fetch"
                )
                return@post
            }
            IbkrMarginRateService.reloadNow()
            call.respond(HttpStatusCode.OK, IbkrMarginRateService.getLastFetchMillis().toString())
        }

        get("/api/tws/snapshot") {
            try {
                val host = AppConfig.twsHost
                val port = AppConfig.twsPort
                val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
                val account = ManagedPortfolio.getBySlug(portfolioId)?.getTwsAccount()
                val snapshot = withContext(Dispatchers.IO) {
                    PortfolioSnapshot.fetch(
                        host, port, account = account
                    )
                }

                val exchangeSuffixMap = AppConfig.exchangeSuffixes

                fun symbolWithSuffix(exchange: String, symbol: String): String =
                    symbol + (exchangeSuffixMap[exchange] ?: "")

                val positionsJson = snapshot.positions.joinToString(",", "[", "]") { pos ->
                    val sym = symbolWithSuffix(pos.exchange, pos.symbol)
                    "{\"symbol\":\"$sym\",\"qty\":${pos.qty}}"
                }

                fun mapToJson(m: Map<String, Double>): String =
                    m.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }

                val json = buildString {
                    append("{")
                    append("\"account\":\"${snapshot.account}\",")
                    append("\"positions\":$positionsJson,")
                    append("\"cashBalances\":${mapToJson(snapshot.summary.cashBalances)},")
                    append("\"accruedCash\":${mapToJson(snapshot.summary.accruedCash)}")
                    append("}")
                }
                call.respondText(json, ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"error\":\"${e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Admin
        get("/api/admin/update-info") {
            call.respondText(UpdateService.getInfo().toJson(), ContentType.Application.Json)
        }

        post("/api/admin/check-update") {
            try {
                UpdateService.checkForUpdate()
                call.respondText(UpdateService.getInfo().toJson(), ContentType.Application.Json)
            } catch (_: Exception) {
                call.respondText(
                    UpdateService.getInfo().toJson(),
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            }
        }

        post("/api/admin/download-update") {
            if (!UpdateService.isJpackageInstall) {
                call.respondText(
                    """{"status":"not-supported"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict
                )
                return@post
            }
            if (UpdateService.getInfo().download.phase != UpdateService.DownloadPhase.IDLE) {
                call.respondText(
                    """{"status":"already-downloading"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict
                )
                return@post
            }
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { UpdateService.downloadUpdate() }
            }
            call.respond(HttpStatusCode.Accepted, """{"status":"started"}""")
        }

        post("/api/admin/apply-update") {
            if (!UpdateService.isJpackageInstall) {
                call.respondText(
                    """{"status":"not-supported"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict
                )
                return@post
            }
            call.respondText("""{"status":"applying"}""", ContentType.Application.Json)
            GlobalScope.launch(Dispatchers.IO) {
                delay(500)
                UpdateService.applyUpdate()
            }
        }

        post("/api/admin/restart") {
            call.respondText("""{"status":"restarting"}""", ContentType.Application.Json)
            GlobalScope.launch(Dispatchers.IO) {
                delay(500)
                UpdateService.relaunchSelf()
            }
        }

        // Serve static files (CSS, JS)
        staticResources("/static", "static")
    }
}
