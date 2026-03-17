package com.portfoliohelper.web

import com.portfoliohelper.AppConfig
import com.portfoliohelper.service.*
import com.portfoliohelper.service.UpdateService.toJson
import com.portfoliohelper.service.db.GlobalSettingsTable
import com.portfoliohelper.service.db.PortfolioCfgTable
import com.portfoliohelper.service.db.SavedBacktestPortfoliosTable
import com.portfoliohelper.tws.PortfolioSnapshot
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds

private fun loadBacktestSettings(settingsKey: String): String = transaction {
    fun get(k: String) = GlobalSettingsTable.selectAll()
        .where { GlobalSettingsTable.key eq k }
        .firstOrNull()?.get(GlobalSettingsTable.value)

    val settings =
        get(settingsKey)?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    val portfolios =
        get("backtest.portfolios")?.let { Json.parseToJsonElement(it) } ?: JsonArray(emptyList())
    buildJsonObject {
        settings.forEach { (k, v) -> put(k, v) }; put(
        "portfolios",
        portfolios
    )
    }.toString()
}

private fun saveBacktestSettings(json: JsonObject, settingsKey: String) = transaction {
    fun upsert(k: String, v: String) = GlobalSettingsTable.upsert {
        it[GlobalSettingsTable.key] = k; it[GlobalSettingsTable.value] = v
    }
    upsert("backtest.portfolios", (json["portfolios"] ?: JsonArray(emptyList())).toString())
    upsert(
        settingsKey,
        buildJsonObject { json.forEach { (k, v) -> if (k != "portfolios") put(k, v) } }.toString()
    )
}

private val cashKeyKnownFlags = setOf("M")

private fun parsePositionRows(arr: JsonArray): List<BackupStock> = arr.mapNotNull { el ->
    val obj = el.jsonObject
    val symbol = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
    val amount = obj["amount"]?.jsonPrimitive?.double ?: return@mapNotNull null
    BackupStock(
        symbol = symbol,
        amount = amount,
        targetWeight = obj["targetWeight"]?.jsonPrimitive?.double ?: 0.0,
        letf = obj["letf"]?.jsonPrimitive?.content ?: "",
        groups = obj["groups"]?.jsonPrimitive?.content ?: ""
    )
}

private fun parsePortfolioConfigs(json: JsonObject): List<PortfolioConfig> =
    json["portfolios"]?.jsonArray?.map { pel ->
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
            } ?: emptyList(),
            includeNoMargin = pObj["includeNoMargin"]?.jsonPrimitive?.booleanOrNull ?: true
        )
    } ?: emptyList()

private suspend fun ApplicationCall.respondOk() =
    respondText("{\"status\":\"ok\"}", ContentType.Application.Json)

private suspend fun ApplicationCall.respondApiError(
    e: Exception,
    status: HttpStatusCode = HttpStatusCode.InternalServerError
) = respondText(
    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
    ContentType.Application.Json,
    status
)

private fun String.toSlug() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
private fun JsonObject.parseSlug() =
    this["name"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }?.toSlug()?.takeIf { it.isNotBlank() }
private fun JsonArray.parseCashEntries() = mapNotNull { el ->
    val obj = el.jsonObject
    val key = obj["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
    val value = obj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
    parseCashEntryFromKeyValue(key, value)
}

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

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val exempt = path == "/admin" ||
                path == "/api/admin/login" ||
                path.startsWith("/api/sync/") ||
                path.startsWith("/static/")
        if (exempt) return@intercept

        // First-run: no sessions exist yet — auto-claim for this browser
        if (!AdminService.hasAnySessions()) {
            val ip = call.request.origin.remoteHost
            val ua = call.request.headers[HttpHeaders.UserAgent] ?: ""
            val token = AdminService.tryClaimFirstSession(ip, ua)
            if (token != null) {
                call.response.cookies.append(
                    Cookie(
                        name = AdminService.SESSION_COOKIE, value = token,
                        httpOnly = true, secure = true, path = "/",
                        maxAge = 10 * 365 * 24 * 60 * 60,
                        extensions = mapOf("SameSite" to "Strict")
                    )
                )
                return@intercept  // allow through with new cookie
            }
            // Another thread/browser beat us — fall through to normal cookie check
        }

        val token = call.request.cookies[AdminService.SESSION_COOKIE]
        if (token == null || !AdminService.validateSession(token)) {
            if (call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true) {
                call.respondRedirect("/admin")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Session required")
            }
            return@intercept finish()
        }
    }

    // Load persisted state from DB so it survives server restarts
    AdminService.loadSessionState()
    PairingService.loadFromDb()

    routing {

        // Android Sync Endpoints
        configureSyncRoutes()

        // Admin Endpoints
        configureAdminRoutes()

        get("/") {
            val all = ManagedPortfolio.getAll()
            val default = all.first()
            DividendService.maybeScheduleCalculation(default)
            call.renderPortfolioPage(default, all, default.slug)
        }

        get("/portfolio/{name}") {
            val slug = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val entry =
                ManagedPortfolio.getBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            DividendService.maybeScheduleCalculation(entry)
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
            call.respondText(
                loadBacktestSettings("backtest.settings"),
                ContentType.Application.Json
            )
        }

        get("/api/montecarlo/settings") {
            call.respondText(
                loadBacktestSettings("backtest.mc-settings"),
                ContentType.Application.Json
            )
        }

        get("/api/backtest/savedPortfolios") {
            val rows = transaction {
                SavedBacktestPortfoliosTable.selectAll()
                    .orderBy(SavedBacktestPortfoliosTable.createdAt)
                    .map {
                        buildJsonObject {
                            put("name", it[SavedBacktestPortfoliosTable.name])
                            put(
                                "config",
                                Json.parseToJsonElement(it[SavedBacktestPortfoliosTable.config])
                            )
                        }
                    }
            }
            call.respondText(JsonArray(rows).toString(), ContentType.Application.Json)
        }

        delete("/api/backtest/savedPortfolios") {
            val name = call.request.queryParameters["name"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest
            )
            transaction { SavedBacktestPortfoliosTable.deleteWhere { SavedBacktestPortfoliosTable.name eq name } }
            call.respondOk()
        }

        post("/api/backtest/savedPortfolios") {
            val body = call.receiveText()
            val entry = Json.parseToJsonElement(body).jsonObject
            val name = entry["name"]?.jsonPrimitive?.contentOrNull ?: return@post call.respond(
                HttpStatusCode.BadRequest
            )
            val config = entry["config"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val saved = transaction {
                val takenNames = SavedBacktestPortfoliosTable.selectAll()
                    .map { it[SavedBacktestPortfoliosTable.name] }.toSet()
                var finalName = name
                var counter = 2
                while (finalName in takenNames) {
                    finalName = "$name ($counter)"; counter++
                }
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

                val portfolios = parsePortfolioConfigs(json)

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
                        append("{\"label\":\"${pr.label.jsonEscape()}\",\"curves\":[")
                        pr.curves.forEachIndexed { ci, cr ->
                            if (ci > 0) append(",")
                            append("{\"label\":\"${cr.label.jsonEscape()}\",")
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

                val portfolios = parsePortfolioConfigs(json)

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
                        append("{\"label\":\"${pr.label.jsonEscape()}\",\"curves\":[")
                        pr.curves.forEachIndexed { ci, cr ->
                            if (ci > 0) append(",")
                            append("{\"label\":\"${cr.label.jsonEscape()}\",\"percentilePaths\":[")
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
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val rows = parsePositionRows(Json.parseToJsonElement(call.receiveText()).jsonArray)

                transaction { portfolioEntry.replacePositions(rows) }

                DividendService.invalidate(portfolioEntry)
                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Update cash — full-state write: [{key, value}]; replaces all existing entries for the portfolio
        post("/api/cash/update") {
            try {
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val entries = Json.parseToJsonElement(call.receiveText()).jsonArray.parseCashEntries()

                transaction { portfolioEntry.replaceCash(entries) }

                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Save stocks + cash + dividendStartDate in a single transaction
        post("/api/portfolio/save-all") {
            try {
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val root = Json.parseToJsonElement(call.receiveText()).jsonObject
                val stockRows = root["stocks"]?.jsonArray?.let { parsePositionRows(it) } ?: emptyList()
                val cashEntries = root["cash"]?.jsonArray?.parseCashEntries() ?: emptyList()

                val dividendStartDate = root["dividendStartDate"]?.jsonPrimitive?.contentOrNull

                val pid = portfolioEntry.serialId
                transaction {
                    portfolioEntry.replacePositions(stockRows)
                    portfolioEntry.replaceCash(cashEntries)
                    if (!dividendStartDate.isNullOrBlank()) {
                        PortfolioCfgTable.upsert {
                            it[PortfolioCfgTable.portfolioId] = pid
                            it[PortfolioCfgTable.cfgKey] = "dividendStartDate"
                            it[PortfolioCfgTable.cfgValue] = dividendStartDate
                        }
                    } else if (dividendStartDate != null) {
                        PortfolioCfgTable.deleteWhere {
                            (PortfolioCfgTable.portfolioId eq pid) and (PortfolioCfgTable.cfgKey eq "dividendStartDate")
                        }
                    }
                }

                DividendService.invalidate(portfolioEntry)
                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
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
                        ?.let { raw ->
                            Json.parseToJsonElement(raw).jsonArray
                                .filter { el -> el is JsonObject && loanEntryKey(el.jsonObject) != newKey }
                                .toMutableList()
                        }
                        ?: mutableListOf()
                    existing.add(0, newEntry)
                    GlobalSettingsTable.upsert {
                        it[GlobalSettingsTable.key] = "loan.history"
                        it[GlobalSettingsTable.value] = JsonArray(existing.take(5)).toString()
                    }
                }
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Generic per-portfolio config store: POST /api/portfolio-config/save?portfolio=X&key=<key>
        // Persists to PortfolioCfgTable in SQLite (previously portfolio.conf file).
        // rebalTarget (USD) and marginTarget (%) are mutually exclusive — setting one clears the other.
        // Also accepts JSON body (no key param) for batch updates (e.g. twsAccount from config page).
        post("/api/portfolio-config/save") {
            try {
                val key = call.request.queryParameters["key"]
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)
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
                    if (key == "dividendStartDate") DividendService.invalidate(portfolioEntry)
                } else {
                    // Batch JSON mode (from config page)
                    val json = Json.parseToJsonElement(body).jsonObject
                    for ((k, v) in json) {
                        portfolioEntry.saveConfig(k, v.jsonPrimitive.contentOrNull ?: "")
                    }
                    if (json.containsKey("dividendStartDate")) DividendService.invalidate(portfolioEntry)
                }
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Create a new portfolio
        post("/api/portfolio/create") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject
                val slug = json.parseSlug() ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (ManagedPortfolio.getBySlug(slug) != null) {
                    return@post call.respondText(
                        "{\"status\":\"error\",\"message\":\"A portfolio named '${slug}' already exists.\"}",
                        ContentType.Application.Json, HttpStatusCode.Conflict
                    )
                }
                val portfolio = ManagedPortfolio.create(slug)
                call.respondText(
                    "{\"status\":\"ok\",\"slug\":\"${portfolio.slug}\"}",
                    ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Rename a portfolio
        post("/api/portfolio/rename") {
            try {
                val portfolio = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject
                val newSlug = json.parseSlug() ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (newSlug != portfolio.slug && ManagedPortfolio.getBySlug(newSlug) != null) {
                    return@post call.respondText(
                        "{\"status\":\"error\",\"message\":\"A portfolio named '$newSlug' already exists.\"}",
                        ContentType.Application.Json, HttpStatusCode.Conflict
                    )
                }
                portfolio.rename(newSlug)
                PortfolioUpdateBroadcaster.broadcastReload()
                call.respondText(
                    "{\"status\":\"ok\",\"slug\":\"$newSlug\"}",
                    ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Remove a portfolio (first portfolio cannot be removed)
        delete("/api/portfolio/remove") {
            try {
                val portfolioId = call.request.queryParameters["portfolio"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val portfolio = ManagedPortfolio.getBySlug(portfolioId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                if (portfolio.serialId == ManagedPortfolio.firstSerialId()) {
                    return@delete call.respondText(
                        "{\"status\":\"error\",\"message\":\"The default portfolio cannot be removed.\"}",
                        ContentType.Application.Json, HttpStatusCode.Forbidden
                    )
                }
                portfolio.delete()
                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
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
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Generate a PIN to display in the server UI — user enters it on their Android device to pair
        post("/api/pairing/generate") {
            val pin = PairingService.generatePin()
            call.respondText("{\"pin\":\"$pin\"}", ContentType.Application.Json)
        }

        // Check whether a previously generated PIN is still active, was used, or has expired
        get("/api/pairing/status") {
            val pin = call.request.queryParameters["pin"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing pin parameter")
            val status = PairingService.getPinStatus(pin)
            call.respondText("{\"status\":\"${status.name.lowercase()}\"}", ContentType.Application.Json)
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
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val label = call.request.queryParameters["label"]?.takeIf { it.isNotBlank() } ?: ""
            BackupService.saveToDb(portfolioEntry, label)
            call.respondOk()
        }

        // List DB backups for a portfolio — [{id, savedAt, label}, ...] newest first
        get("/api/backup/list-db") {
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
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
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                BackupService.restoreFromDb(portfolioEntry, id)
                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Delete a single DB backup by id
        delete("/api/backup/delete-db") {
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val id = call.request.queryParameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            BackupService.deleteFromDb(portfolioEntry, id)
            call.respondOk()
        }

        // Delete all DB backups for a portfolio
        delete("/api/backup/delete-all") {
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            BackupService.deleteAllFromDb(portfolioEntry)
            call.respondOk()
        }

        // Export portfolio as JSON download (includes snapshotUsd for P entries)
        get("/api/backup/export-json") {
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val json = BackupService.exportJson(portfolioEntry)
            val filename = "backup-${portfolioEntry.slug}.json"
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"$filename\""
            )
            call.respondText(json, ContentType.Application.Json)
        }

        // Import CSV / TXT / ZIP / JSON file — validates and returns parsed data for edit-mode population
        post("/api/backup/import-file") {
            ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
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
                val account = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])?.getTwsAccount()
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
