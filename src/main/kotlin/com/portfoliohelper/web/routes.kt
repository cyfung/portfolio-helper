package com.portfoliohelper.web

import com.portfoliohelper.APP_VERSION
import com.portfoliohelper.AppConfig
import com.portfoliohelper.service.*
import com.portfoliohelper.service.UpdateService.toResponseJson
import com.portfoliohelper.util.appJson
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
    (json["portfolios"] as? JsonArray)?.map { pel ->
        val pObj = pel.jsonObject
        PortfolioConfig(
            label = pObj["label"]?.jsonPrimitive?.contentOrNull ?: "Portfolio",
            tickers = (pObj["tickers"] as? JsonArray)?.map { el ->
                val obj = el.jsonObject
                TickerWeight(
                    ticker = obj["ticker"]!!.jsonPrimitive.content,
                    weight = obj["weight"]!!.jsonPrimitive.double
                )
            } ?: emptyList(),
            rebalanceStrategy = runCatching {
                RebalanceStrategy.valueOf(pObj["rebalanceStrategy"]!!.jsonPrimitive.content)
            }.getOrDefault(RebalanceStrategy.YEARLY),
            marginStrategies = (pObj["marginStrategies"] as? JsonArray)?.map { mel ->
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

private fun parseSinglePortfolioConfig(pObj: JsonObject): PortfolioConfig = PortfolioConfig(
    label = pObj["label"]?.jsonPrimitive?.contentOrNull ?: "Portfolio",
    tickers = (pObj["tickers"] as? JsonArray)?.map { el ->
        val obj = el.jsonObject
        TickerWeight(obj["ticker"]!!.jsonPrimitive.content, obj["weight"]!!.jsonPrimitive.double)
    } ?: emptyList(),
    rebalanceStrategy = runCatching {
        RebalanceStrategy.valueOf(pObj["rebalanceStrategy"]!!.jsonPrimitive.content)
    }.getOrDefault(RebalanceStrategy.YEARLY),
    marginStrategies = (pObj["marginStrategies"] as? JsonArray)?.map { mel ->
        val mObj = mel.jsonObject
        MarginConfig(
            marginRatio          = mObj["marginRatio"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            marginSpread         = mObj["marginSpread"]?.jsonPrimitive?.doubleOrNull ?: 0.015,
            marginDeviationUpper = mObj["marginDeviationUpper"]?.jsonPrimitive?.doubleOrNull ?: 0.05,
            marginDeviationLower = mObj["marginDeviationLower"]?.jsonPrimitive?.doubleOrNull ?: 0.05,
            upperRebalanceMode   = runCatching {
                MarginRebalanceMode.valueOf(mObj["upperRebalanceMode"]?.jsonPrimitive?.contentOrNull ?: "PROPORTIONAL")
            }.getOrDefault(MarginRebalanceMode.PROPORTIONAL),
            lowerRebalanceMode   = runCatching {
                MarginRebalanceMode.valueOf(mObj["lowerRebalanceMode"]?.jsonPrimitive?.contentOrNull ?: "PROPORTIONAL")
            }.getOrDefault(MarginRebalanceMode.PROPORTIONAL)
        )
    } ?: emptyList(),
    includeNoMargin = pObj["includeNoMargin"]?.jsonPrimitive?.booleanOrNull ?: true
)

private fun parsePriceMoveTrigger(obj: JsonObject): PriceMoveTrigger {
    val pct = obj["pct"]?.jsonPrimitive?.double ?: 0.0
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "VS_N_DAYS_AGO"  -> PriceMoveTrigger.VsNDaysAgo(obj["nDays"]?.jsonPrimitive?.int ?: 20, pct)
        "VS_RUNNING_AVG" -> PriceMoveTrigger.VsRunningAvg(obj["nDays"]?.jsonPrimitive?.int ?: 20, pct)
        else             -> PriceMoveTrigger.PeakDeviation(pct)
    }
}

private fun parseExecutionMethod(obj: JsonObject): ExecutionMethod =
    when (obj["method"]?.jsonPrimitive?.contentOrNull) {
        "CONSECUTIVE" -> ExecutionMethod.Consecutive(obj["days"]?.jsonPrimitive?.int ?: 7)
        "STEPPED"     -> ExecutionMethod.Stepped(
            portions      = obj["portions"]?.jsonPrimitive?.int ?: 3,
            additionalPct = obj["additionalPct"]?.jsonPrimitive?.double ?: 0.05
        )
        else          -> ExecutionMethod.Once
    }

private fun parseDipSurgeConfig(obj: JsonObject): DipSurgeConfig = DipSurgeConfig(
    scope         = runCatching { DipSurgeScope.valueOf(obj["scope"]?.jsonPrimitive?.content ?: "INDIVIDUAL_STOCK") }
                        .getOrDefault(DipSurgeScope.INDIVIDUAL_STOCK),
    allocStrategy = obj["allocStrategy"]?.jsonPrimitive?.contentOrNull?.let {
        runCatching { MarginRebalanceMode.valueOf(it) }.getOrNull()
    },
    triggers      = (obj["triggers"] as? JsonArray)?.map { parsePriceMoveTrigger(it.jsonObject) } ?: emptyList(),
    method        = (obj["method"] as? JsonObject)?.let { parseExecutionMethod(it) } ?: ExecutionMethod.Once,
    limit         = obj["limit"]?.jsonPrimitive?.doubleOrNull ?: 0.15
)

private fun parseMarginTriggerAction(obj: JsonObject): MarginTriggerAction = MarginTriggerAction(
    deviationPct  = obj["deviationPct"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    allocStrategy = obj["allocStrategy"]?.jsonPrimitive?.contentOrNull?.let {
        runCatching { MarginRebalanceMode.valueOf(it) }.getOrNull()
    } ?: MarginRebalanceMode.PROPORTIONAL
)

private fun parseRebalStrategyConfig(obj: JsonObject): RebalStrategyConfig = RebalStrategyConfig(
    label                      = obj["label"]?.jsonPrimitive?.contentOrNull ?: "Strategy",
    marginRatio                = obj["marginRatio"]?.jsonPrimitive?.doubleOrNull ?: 0.5,
    marginSpread               = obj["marginSpread"]?.jsonPrimitive?.doubleOrNull ?: 0.015,
    rebalancePeriod            = runCatching {
        RebalancePeriodOverride.valueOf(obj["rebalancePeriod"]?.jsonPrimitive?.content ?: "INHERIT")
    }.getOrDefault(RebalancePeriodOverride.INHERIT),
    cashflowImmediateInvestPct = obj["cashflowImmediateInvestPct"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
    cashflowScaling            = runCatching {
        CashflowScaling.valueOf(obj["cashflowScaling"]?.jsonPrimitive?.content ?: "SCALED_BY_TARGET_MARGIN")
    }.getOrDefault(CashflowScaling.SCALED_BY_TARGET_MARGIN),
    deviationMode              = runCatching {
        DeviationMode.valueOf(obj["deviationMode"]?.jsonPrimitive?.content ?: "ABSOLUTE")
    }.getOrDefault(DeviationMode.ABSOLUTE),
    sellOnHighMargin           = (obj["sellOnHighMargin"] as? JsonObject)?.let { parseMarginTriggerAction(it) },
    buyOnLowMargin             = (obj["buyOnLowMargin"] as? JsonObject)?.let { parseMarginTriggerAction(it) },
    buyTheDip                  = (obj["buyTheDip"] as? JsonObject)?.let { parseDipSurgeConfig(it) },
    sellOnSurge                = (obj["sellOnSurge"] as? JsonObject)?.let { parseDipSurgeConfig(it) },
    comfortZoneLow              = obj["comfortZoneLow"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    comfortZoneHigh             = obj["comfortZoneHigh"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
)

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
private fun JsonObject.parseDisplayName() =
    this["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
private suspend fun ApplicationCall.receiveDisplayNameAndSlug(): Pair<String, String>? {
    val json = Json.parseToJsonElement(receiveText()).jsonObject
    val displayName = json.parseDisplayName() ?: return null
    val slug = displayName.toSlug().takeIf { it.isNotBlank() } ?: return null
    return displayName to slug
}
private fun JsonArray.parseCashEntries() = mapNotNull { el ->
    val obj = el.jsonObject
    val label = obj["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val currency = obj["currency"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: return@mapNotNull null
    val marginFlag = obj["marginFlag"]?.jsonPrimitive?.booleanOrNull ?: false
    if (currency == "P") {
        val ref = obj["portfolioRef"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val sign = if ((obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 1.0) < 0) -1.0 else 1.0
        com.portfoliohelper.model.CashEntry(label, "P", marginFlag, sign, portfolioRef = ref)
    } else {
        val amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        com.portfoliohelper.model.CashEntry(label, currency, marginFlag, amount)
    }
}

@Serializable
private data class SavedBacktestPortfolio(val name: String, val config: JsonElement)

@Serializable
private data class TwsPositionItem(val symbol: String, val qty: Double)

@Serializable
private data class TwsSnapshotResponse(
    val account: String,
    val positions: List<TwsPositionItem>,
    val cashBalances: Map<String, Double>,
    val accruedCash: Map<String, Double>
)

// ── SPA API DTOs ─────────────────────────────────────────────────────────────

@Serializable
private data class PortfolioOptionDto(val slug: String, val name: String, val seqOrder: Double)

@Serializable
private data class StockDto(
    val label: String,
    val amount: Double,
    val targetWeight: Double,
    val letf: String,
    val groups: String
)

@Serializable
private data class CashDto(
    val label: String,
    val currency: String,
    val amount: Double,
    val marginFlag: Boolean,
    val portfolioRef: String? = null
)

@Serializable
private data class PortfolioConfigDto(
    val rebalTargetUsd: Double,
    val marginTargetPct: Double,
    val marginTargetUsd: Double,
    val allocAddMode: String,
    val allocReduceMode: String,
    val virtualBalanceEnabled: Boolean,
    val dividendCalcUpToDate: String,
    val dividendStartDate: String
)

@Serializable
private data class AppConfigDto(
    val version: String,
    val showStockDisplayCurrency: Boolean,
    val afterHoursGray: Boolean,
    val displayCurrencies: List<String>,
    val hasUpdate: Boolean,
    val latestVersion: String?,
    val downloadPhase: String,
    val isJpackageInstall: Boolean,
    val autoUpdate: Boolean,
    val privacyScalePct: String,
    val privacyScaleEnabled: Boolean
)

@Serializable
private data class PortfolioDataResponse(
    val portfolioId: String,
    val portfolioName: String,
    val allPortfolios: List<PortfolioOptionDto>,
    val stocks: List<StockDto>,
    val cash: List<CashDto>,
    val config: PortfolioConfigDto,
    val appConfig: AppConfigDto
)

private val LOAN_COMPARE_FIELDS = listOf(
    "loanAmount", "numPeriods", "periodLength", "payment", "rateApy", "rateFlat", "extraCashflows"
)

private fun loanEntryKey(obj: JsonObject): String =
    LOAN_COMPARE_FIELDS.joinToString("|") { obj[it]?.toString() ?: "" }

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

        // ── Portfolio data API (replaces server-injected JS globals) ────────────
        get("/api/portfolio/data") {
            val all = ManagedPortfolio.getAll()
            val slug = call.request.queryParameters["portfolio"]
            val entry = (if (slug != null) ManagedPortfolio.getBySlug(slug) else all.firstOrNull())
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val stocks = entry.getStocks()
            val cashEntries = entry.getCash()
            val portfolioConf = entry.getAllConfig()
            val privacyScalePct = if (AppConfig.privacyScaleEnabled) AppConfig.privacyScalePct else null

            fun scaleQty(q: Double) =
                if (privacyScalePct != null) kotlin.math.round(q * privacyScalePct / 100.0) else q

            val savedRebalTargetUsd = portfolioConf["rebalTarget"]?.toDoubleOrNull() ?: 0.0
            val displayRebalTarget =
                if (privacyScalePct != null) savedRebalTargetUsd * privacyScalePct / 100.0
                else savedRebalTargetUsd

            val displayCurrencies: List<String> = buildList {
                add("USD")
                all.asSequence().flatMap { it.getCash() }
                    .map { it.currency.uppercase() }
                    .distinct().filter { it != "P" && it != "USD" }
                    .sorted().toList().forEach { add(it) }
            }

            val updateInfo = UpdateService.getInfo()

            val response = PortfolioDataResponse(
                portfolioId = entry.slug,
                portfolioName = entry.name,
                allPortfolios = all.map { PortfolioOptionDto(it.slug, it.name, it.seqOrder) },
                stocks = stocks.map { stock ->
                    StockDto(
                        label = stock.label,
                        amount = scaleQty(stock.amount),
                        targetWeight = stock.targetWeight ?: 0.0,
                        letf = stock.letfComponents?.joinToString(",") { "${it.first},${it.second}" } ?: "",
                        groups = stock.groups.joinToString(";") { "${it.first} ${it.second}" }
                    )
                },
                cash = cashEntries.map { CashDto(it.label, it.currency, it.amount, it.marginFlag, it.portfolioRef) },
                config = PortfolioConfigDto(
                    rebalTargetUsd = displayRebalTarget,
                    marginTargetPct = portfolioConf["marginTarget"]?.toDoubleOrNull() ?: 0.0,
                    marginTargetUsd = portfolioConf["marginTargetUsd"]?.toDoubleOrNull() ?: 0.0,
                    allocAddMode = portfolioConf["allocAddMode"] ?: "PROPORTIONAL",
                    allocReduceMode = portfolioConf["allocReduceMode"] ?: "PROPORTIONAL",
                    virtualBalanceEnabled = portfolioConf["virtualBalance"] == "true",
                    dividendCalcUpToDate = portfolioConf["dividendCalcUpToDate"] ?: "",
                    dividendStartDate = portfolioConf["dividendStartDate"] ?: ""
                ),
                appConfig = AppConfigDto(
                    version = APP_VERSION,
                    showStockDisplayCurrency = AppConfig.showStockDisplayCurrency,
                    afterHoursGray = AppConfig.afterHoursGray,
                    displayCurrencies = displayCurrencies,
                    hasUpdate = updateInfo.hasUpdate,
                    latestVersion = updateInfo.latestVersion,
                    downloadPhase = updateInfo.download.phase.name,
                    isJpackageInstall = updateInfo.isJpackageInstall,
                    autoUpdate = AppConfig.autoUpdate,
                    privacyScalePct = AppConfig.get(AppConfig.KEY_PRIVACY_SCALE_PCT),
                    privacyScaleEnabled = AppConfig.privacyScaleEnabled
                )
            )

            call.respondText(appJson.encodeToString(response), ContentType.Application.Json)
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
                        SavedBacktestPortfolio(
                            name = it[SavedBacktestPortfoliosTable.name],
                            config = Json.parseToJsonElement(it[SavedBacktestPortfoliosTable.config])
                        )
                    }
            }
            call.respondText(appJson.encodeToString(rows), ContentType.Application.Json)
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
                SavedBacktestPortfolio(finalName, config)
            }
            call.respondText(appJson.encodeToString(saved), ContentType.Application.Json)
        }

        post("/api/backtest/run") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject
                if (json["saveSettings"]?.jsonPrimitive?.booleanOrNull != false)
                    runCatching { saveBacktestSettings(json, "backtest.settings") }

                val fromDate =
                    json["fromDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val toDate =
                    json["toDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                val portfolios = parsePortfolioConfigs(json)

                val cashflow = (json["cashflow"] as? JsonObject)?.let { cf ->
                    CashflowConfig(
                        amount = cf["amount"]?.jsonPrimitive?.double ?: 0.0,
                        frequency = runCatching {
                            CashflowFrequency.valueOf(cf["frequency"]?.jsonPrimitive?.content ?: "NONE")
                        }.getOrDefault(CashflowFrequency.NONE)
                    )
                }

                val result =
                    BacktestService.runMulti(MultiBacktestRequest(fromDate, toDate, portfolios, cashflow))

                call.respondText(appJson.encodeToString(result), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"error\":\"${e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        post("/api/rebalance-strategy/run") {
            try {
                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject

                val fromDate = json["fromDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val toDate   = json["toDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                val portfolio = (json["portfolio"] as? JsonObject)?.let { parseSinglePortfolioConfig(it) }
                    ?: throw IllegalArgumentException("Missing portfolio")

                val cashflow = (json["cashflow"] as? JsonObject)?.let { cf ->
                    CashflowConfig(
                        amount = cf["amount"]?.jsonPrimitive?.double ?: 0.0,
                        frequency = runCatching {
                            CashflowFrequency.valueOf(cf["frequency"]?.jsonPrimitive?.content ?: "NONE")
                        }.getOrDefault(CashflowFrequency.NONE)
                    )
                }

                val strategies = (json["strategies"] as? JsonArray)?.map { el ->
                    parseRebalStrategyConfig(el.jsonObject)
                } ?: emptyList()

                val result = RebalanceStrategyService.run(
                    RebalanceStrategyRequest(fromDate, toDate, portfolio, cashflow, strategies)
                )
                call.respondText(appJson.encodeToString(result), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"error\":\"${e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"")}\"}",
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

                call.respondText(appJson.encodeToString(result), ContentType.Application.Json)
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

                PortfolioMasterService.get(portfolioEntry.slug)?.refreshStocks()
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

                PortfolioMasterService.get(portfolioEntry.slug)?.refreshCashEntries()
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
                val stockRows = (root["stocks"] as? JsonArray)?.let { parsePositionRows(it) } ?: emptyList()
                val cashEntries = (root["cash"] as? JsonArray)?.parseCashEntries() ?: emptyList()

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

                PortfolioMasterService.get(portfolioEntry.slug)?.refreshConfig()
                PortfolioMasterService.get(portfolioEntry.slug)?.refreshStocks()
                PortfolioMasterService.get(portfolioEntry.slug)?.refreshCashEntries()
                PortfolioUpdateBroadcaster.broadcastReload()
                MarketDataCoordinator.refresh()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        patch("/api/portfolio/dividend-start-date") {
            try {
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@patch call.respond(HttpStatusCode.NotFound)
                val root = Json.parseToJsonElement(call.receiveText()).jsonObject
                val date = root["date"]?.jsonPrimitive?.contentOrNull
                val pid = portfolioEntry.serialId
                transaction {
                    if (!date.isNullOrBlank()) {
                        PortfolioCfgTable.upsert {
                            it[PortfolioCfgTable.portfolioId] = pid
                            it[PortfolioCfgTable.cfgKey] = "dividendStartDate"
                            it[PortfolioCfgTable.cfgValue] = date
                        }
                    } else {
                        PortfolioCfgTable.deleteWhere {
                            (PortfolioCfgTable.portfolioId eq pid) and (PortfolioCfgTable.cfgKey eq "dividendStartDate")
                        }
                    }
                }
                PortfolioMasterService.get(portfolioEntry.slug)?.refreshConfig()
                PortfolioUpdateBroadcaster.broadcastReload()
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
                        when (key) {
                            "rebalTarget"     -> { portfolioEntry.saveConfig("marginTarget", ""); portfolioEntry.saveConfig("marginTargetUsd", "") }
                            "marginTarget"    -> { portfolioEntry.saveConfig("rebalTarget", ""); portfolioEntry.saveConfig("marginTargetUsd", "") }
                            "marginTargetUsd" -> { portfolioEntry.saveConfig("rebalTarget", ""); portfolioEntry.saveConfig("marginTarget", "") }
                        }
                    }
                } else {
                    // Batch JSON mode (from config page)
                    val json = Json.parseToJsonElement(body).jsonObject
                    for ((k, v) in json) {
                        portfolioEntry.saveConfig(k, v.jsonPrimitive.contentOrNull ?: "")
                    }
                }
                PortfolioMasterService.get(portfolioEntry.slug)?.refreshConfig()
                call.respondOk()
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        // Create a new portfolio
        post("/api/portfolio/create") {
            try {
                val (displayName, slug) = call.receiveDisplayNameAndSlug()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val portfolio = try {
                    PortfolioMasterService.create(slug, displayName)
                } catch (e: IllegalArgumentException) {
                    return@post call.respondText(
                        "{\"status\":\"error\",\"message\":\"${e.message}\"}",
                        ContentType.Application.Json, HttpStatusCode.Conflict
                    )
                }
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
                val (newName, newSlug) = call.receiveDisplayNameAndSlug()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                try {
                    PortfolioMasterService.rename(portfolio, newSlug, newName)
                } catch (e: IllegalArgumentException) {
                    return@post call.respondText(
                        "{\"status\":\"error\",\"message\":\"${e.message}\"}",
                        ContentType.Application.Json, HttpStatusCode.Conflict
                    )
                }
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
                try {
                    PortfolioMasterService.delete(portfolio)
                } catch (e: IllegalStateException) {
                    return@delete call.respondText(
                        "{\"status\":\"error\",\"message\":\"${e.message}\"}",
                        ContentType.Application.Json, HttpStatusCode.Forbidden
                    )
                }
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

        // Trigger an immediate DB backup for a portfolio (called before opening the restore UI or virtual rebalance)
        post("/api/backup/trigger") {
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val label = call.request.queryParameters["label"]?.takeIf { it.isNotBlank() } ?: ""
            val force = call.request.queryParameters["force"] == "true"
            BackupService.saveToDb(portfolioEntry, label, force = force)
            call.respondOk()
        }

        // List DB backups for a portfolio — [{id, savedAt, label}, ...] newest first
        get("/api/backup/list-db") {
            val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val entries = BackupService.listDbBackups(portfolioEntry)
            call.respondText(appJson.encodeToString(entries), ContentType.Application.Json)
        }

        // Restore a portfolio from a DB backup row
        post("/api/backup/restore-db") {
            try {
                val portfolioEntry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                BackupService.restoreFromDb(portfolioEntry, id)
                PortfolioMasterService.get(portfolioEntry.slug)?.refreshStocks()
                PortfolioMasterService.get(portfolioEntry.slug)?.refreshCashEntries()
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
                    call.respondText(appJson.encodeToString(result), ContentType.Application.Json)
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

                val response = TwsSnapshotResponse(
                    account = snapshot.account,
                    positions = snapshot.positions.map { pos ->
                        TwsPositionItem(symbolWithSuffix(pos.exchange, pos.symbol), pos.qty)
                    },
                    cashBalances = snapshot.summary.cashBalances,
                    accruedCash = snapshot.summary.accruedCash
                )
                call.respondText(appJson.encodeToString(response), ContentType.Application.Json)
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
            call.respondText(UpdateService.getInfo().toResponseJson(), ContentType.Application.Json)
        }

        post("/api/admin/check-update") {
            try {
                UpdateService.checkForUpdate()
                call.respondText(UpdateService.getInfo().toResponseJson(), ContentType.Application.Json)
            } catch (_: Exception) {
                call.respondText(
                    UpdateService.getInfo().toResponseJson(),
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
                delay(500.milliseconds)
                UpdateService.applyUpdate()
            }
        }

        post("/api/admin/restart") {
            call.respondText("""{"status":"restarting"}""", ContentType.Application.Json)
            GlobalScope.launch(Dispatchers.IO) {
                delay(500.milliseconds)
                UpdateService.relaunchSelf()
            }
        }

        // ── Portfolio IB Config ───────────────────────────────────────────────
        get("/api/portfolio/{slug}/ibkr-config") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val cfg = portfolio.getConfig("ibkrConfig")
            call.respondText(cfg ?: """{"token":"","queryId":"","twsAccount":""}""", ContentType.Application.Json)
        }

        post("/api/portfolio/{slug}/ibkr-config") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val token      = body["token"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val queryId    = body["queryId"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val twsAccount = body["twsAccount"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            if (token.isBlank() || queryId.isBlank()) {
                call.respondText("""{"error":"token and queryId are required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val value = buildJsonObject { put("token", token); put("queryId", queryId); put("twsAccount", twsAccount) }.toString()
            portfolio.saveConfig("ibkrConfig", value)
            call.respondOk()
        }

        // ── Performance ───────────────────────────────────────────────────────
        post("/api/performance/ingest/{slug}") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val cfgStr = portfolio.getConfig("ibkrConfig")
                ?: return@post call.respondText("""{"error":"IB config not set"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            val cfg     = Json.parseToJsonElement(cfgStr).jsonObject
            val token   = cfg["token"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val queryId = cfg["queryId"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            if (token.isBlank() || queryId.isBlank()) {
                call.respondText("""{"error":"token or queryId missing in IB config"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            try {
                val xml       = withContext(Dispatchers.IO) { FlexQueryService.fetch(token, queryId) }
                val snapshots = FlexXmlParser.parse(xml)
                val written   = withContext(Dispatchers.IO) { PortfolioSnapshotRepository.ingest(portfolio.serialId, snapshots) }
                call.respondText("""{"written":$written}""", ContentType.Application.Json)
            } catch (e: FlexParseException) {
                call.respondText(
                    """{"error":"${e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"")}"}""",
                    ContentType.Application.Json, HttpStatusCode.UnprocessableEntity
                )
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        post("/api/performance/ingest-xml/{slug}") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@post call.respond(HttpStatusCode.NotFound)
            try {
                val xml       = call.receiveText()
                val snapshots = FlexXmlParser.parse(xml)
                val written   = withContext(Dispatchers.IO) { PortfolioSnapshotRepository.ingest(portfolio.serialId, snapshots) }
                call.respondText("""{"written":$written}""", ContentType.Application.Json)
            } catch (e: FlexParseException) {
                call.respondText(
                    """{"error":"${e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"")}"}""",
                    ContentType.Application.Json, HttpStatusCode.UnprocessableEntity
                )
            } catch (e: Exception) {
                call.respondApiError(e)
            }
        }

        get("/api/performance/gaps/{slug}") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val dates = withContext(Dispatchers.IO) { PortfolioSnapshotRepository.getDates(portfolio.serialId) }
            if (dates.size < 2) {
                call.respondText("[]", ContentType.Application.Json)
                return@get
            }
            val firstDate = LocalDate.parse(dates.first())
            val lastDate  = LocalDate.parse(dates.last())
            val snapshotSet = dates.map { LocalDate.parse(it) }.toSet()

            // Load VT trading days for the snapshot period — same source used by backtest
            val vtTradingDays = withContext(Dispatchers.IO) {
                try {
                    BacktestService.loadNormalizedSeries("VT", firstDate)
                        .keys
                        .filter { it in firstDate..lastDate }
                        .sorted()
                } catch (_: Exception) { emptyList() }
            }

            val gaps = buildJsonArray {
                if (vtTradingDays.isEmpty()) return@buildJsonArray
                val vtIndex = vtTradingDays.withIndex().associate { (i, d) -> d to i }
                val missing = vtTradingDays.filter { it !in snapshotSet }
                if (missing.isEmpty()) return@buildJsonArray

                var gapStart = missing[0]
                var gapPrev  = missing[0]
                for (i in 1 until missing.size) {
                    val curr = missing[i]
                    if (vtIndex[curr] == vtIndex[gapPrev]!! + 1) {
                        gapPrev = curr
                    } else {
                        add(buildJsonObject {
                            put("from", gapStart.toString())
                            put("to", gapPrev.toString())
                            put("days", (vtIndex[gapPrev]!! - vtIndex[gapStart]!! + 1).toLong())
                        })
                        gapStart = curr
                        gapPrev  = curr
                    }
                }
                add(buildJsonObject {
                    put("from", gapStart.toString())
                    put("to", gapPrev.toString())
                    put("days", (vtIndex[gapPrev]!! - vtIndex[gapStart]!! + 1).toLong())
                })
            }
            call.respondText(gaps.toString(), ContentType.Application.Json)
        }

        get("/api/performance/chart/{slug}") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val allDates = withContext(Dispatchers.IO) { PortfolioSnapshotRepository.getDates(portfolio.serialId) }
            if (allDates.isEmpty()) {
                call.respondText("""{"dates":[],"twrSeries":[],"navSeries":[],"marginUtilSeries":[]}""", ContentType.Application.Json)
                return@get
            }
            val from = call.request.queryParameters["from"] ?: allDates.first()
            val to   = call.request.queryParameters["to"]   ?: allDates.last()
            val snapshots = withContext(Dispatchers.IO) { PortfolioSnapshotRepository.getSnapshots(portfolio.serialId, from, to) }
            val chart = PerformanceService.buildChartData(snapshots)
            val scalePct = if (AppConfig.privacyScaleEnabled) AppConfig.privacyScalePct else null
            val scaledChart = if (scalePct != null)
                chart.copy(navSeries = chart.navSeries.map { it * scalePct / 100.0 })
            else chart
            call.respondText(appJson.encodeToString(scaledChart), ContentType.Application.Json)
        }

        get("/api/performance/snapshots/{slug}") {
            val portfolio = ManagedPortfolio.resolve(call.parameters["slug"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val dates = withContext(Dispatchers.IO) { PortfolioSnapshotRepository.getDates(portfolio.serialId) }
            call.respondText(buildJsonObject { put("dates", buildJsonArray { dates.forEach { add(it) } }) }.toString(), ContentType.Application.Json)
        }

        // Serve static files (CSS, JS, SPA assets)
        staticResources("/static", "static")

        // SPA catch-all — serve index.html for all non-API, non-static routes
        // React Router handles client-side navigation
        get("{...}") {
            val stream = application.environment.classLoader
                .getResourceAsStream("static/index.html")
            if (stream != null) {
                call.respondText(stream.bufferedReader().readText(), ContentType.Text.Html)
            } else {
                call.respond(HttpStatusCode.NotFound, "index.html not found — build the frontend first")
            }
        }
    }
}
