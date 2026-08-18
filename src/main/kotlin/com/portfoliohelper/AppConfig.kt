package com.portfoliohelper

import com.portfoliohelper.service.db.GlobalSettingsTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory

object AppConfig {
    const val KEY_OPEN_BROWSER        = "openBrowser"
    const val KEY_NAV_UPDATE_INTERVAL = "navUpdateInterval"
    const val KEY_EXCHANGE_SUFFIXES   = "exchangeSuffixes"
    const val KEY_EXCHANGE_SYMBOL_MIN_WIDTHS = "exchangeSymbolMinWidths"
    const val KEY_TWS_HOST            = "twsHost"
    const val KEY_TWS_PORT            = "twsPort"
    const val KEY_IBKR_RATE_INTERVAL  = "ibkrRateInterval"
    const val KEY_GITHUB_REPO         = "githubRepo"
    const val KEY_AUTO_UPDATE         = "autoUpdate"
    const val KEY_UPDATE_CHECK_INTERVAL = "updateCheckInterval"
    const val KEY_SHOW_STOCK_DISPLAY_CURRENCY = "showStockDisplayCurrency"
    const val KEY_DIVIDEND_SAFE_LAG_DAYS = "dividendSafeLagDays"
    const val KEY_PRIVACY_SCALE_PCT     = "privacyScalePct"
    const val KEY_PRIVACY_SCALE_ENABLED = "privacyScaleEnabled"
    const val KEY_AFTER_HOURS_GRAY      = "afterHoursGray"
    const val KEY_HYBRID_ALLOC_STRATEGIES = "hybridAllocStrategies"
    const val KEY_PORTFOLIO_COLUMN_MODES = "portfolioColumnModes"

    private val DEFAULTS = mapOf(
        KEY_OPEN_BROWSER        to "true",
        KEY_NAV_UPDATE_INTERVAL to "",
        KEY_EXCHANGE_SUFFIXES   to "LSE=.L,LSEETF=.L,SBF=.PA,AEB=.AS,ENEXT.BE=.BR,BVME=.MI,BVME.ETF=.MI,IBIS=.DE,IBIS2=.DE,FWB=.F,FWB2=.F,EBS=.SW,BME=.MC,SEHK=.HK,TSEJ=.T,ASX=.AX,TSE=.TO,VENTURE=.V,SGX=.SI,OSE=.OL,SFB=.ST,CPH=.CO,HEX=.HE,TWSE=.TW,TPEX=.TWO,TRWBUKETF=.L",
        KEY_EXCHANGE_SYMBOL_MIN_WIDTHS to "SEHK=4",
        KEY_TWS_HOST            to "127.0.0.1",
        KEY_TWS_PORT            to "7496",
        KEY_IBKR_RATE_INTERVAL  to "3600",
        KEY_GITHUB_REPO         to "cyfung/portfolio-helper",
        KEY_AUTO_UPDATE         to "true",
        KEY_UPDATE_CHECK_INTERVAL to "86400",
        KEY_SHOW_STOCK_DISPLAY_CURRENCY to "false",
        KEY_DIVIDEND_SAFE_LAG_DAYS to "5",
        KEY_PRIVACY_SCALE_PCT   to "",
        KEY_PRIVACY_SCALE_ENABLED to "true",
        KEY_AFTER_HOURS_GRAY    to "true",
        KEY_HYBRID_ALLOC_STRATEGIES to "",
        KEY_PORTFOLIO_COLUMN_MODES to "",
    )

    fun get(key: String): String {
        transaction {
            GlobalSettingsTable.selectAll()
                .where { GlobalSettingsTable.key eq key }
                .firstOrNull()?.get(GlobalSettingsTable.value)
        }?.let { return it }
        return DEFAULTS[key] ?: ""
    }

    fun save(updates: Map<String, String>) {
        transaction {
            updates.forEach { (k, v) ->
                GlobalSettingsTable.upsert {
                    it[GlobalSettingsTable.key] = k
                    it[GlobalSettingsTable.value] = v
                }
            }
        }
        if (KEY_PRIVACY_SCALE_PCT in updates || KEY_PRIVACY_SCALE_ENABLED in updates)
            _privacyScalePct.value = effectiveScale()
    }

    // Typed accessors
    val openBrowser: Boolean get() = get(KEY_OPEN_BROWSER).lowercase() != "false"
    val navUpdateInterval: Long? get() = get(KEY_NAV_UPDATE_INTERVAL).toLongOrNull()?.takeIf { it > 0 }
    val twsHost: String get() = get(KEY_TWS_HOST).ifBlank { "127.0.0.1" }
    val twsPort: Int    get() = get(KEY_TWS_PORT).toIntOrNull()?.takeIf { it > 0 } ?: 7496
    val ibkrRateIntervalMs: Long get() =
        (get(KEY_IBKR_RATE_INTERVAL).toLongOrNull()?.takeIf { it > 0 } ?: 3600L) * 1000L
    val githubRepo: String get() = get(KEY_GITHUB_REPO).ifBlank { "cyfung/portfolio-helper" }
    val autoUpdate: Boolean get() = get(KEY_AUTO_UPDATE).lowercase() != "false"
    val updateCheckIntervalMs: Long get() =
        (get(KEY_UPDATE_CHECK_INTERVAL).toLongOrNull()?.takeIf { it >= 60 } ?: 86400L) * 1000L
    val showStockDisplayCurrency: Boolean get() = get(KEY_SHOW_STOCK_DISPLAY_CURRENCY).lowercase() == "true"
    val dividendSafeLagDays: Long get() = get(KEY_DIVIDEND_SAFE_LAG_DAYS).toLongOrNull()?.takeIf { it >= 0 } ?: 5L
    val privacyScalePct: Double? get() = get(KEY_PRIVACY_SCALE_PCT).toDoubleOrNull()?.takeIf { it > 0 }
    val privacyScaleEnabled: Boolean get() = get(KEY_PRIVACY_SCALE_ENABLED).lowercase() != "false"
    private fun effectiveScale(): Double? = if (privacyScaleEnabled) privacyScalePct else null
    private val _privacyScalePct: MutableStateFlow<Double?> by lazy {
        MutableStateFlow(effectiveScale())
    }
    val privacyScalePctFlow: StateFlow<Double?> by lazy { _privacyScalePct.asStateFlow() }
    val afterHoursGray: Boolean get() = get(KEY_AFTER_HOURS_GRAY).lowercase() != "false"
    fun positiveDouble(key: String, default: Double): Double =
        runCatching { get(key) }.getOrDefault("").toDoubleOrNull()?.takeIf { it >= 0.0 } ?: default
    val exchangeSuffixes: Map<String, String>
        get() = get(KEY_EXCHANGE_SUFFIXES).split(",")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) null else part.substring(0, eq).trim().uppercase() to part.substring(eq + 1).trim()
            }
            .filter { it.first.isNotBlank() }
            .toMap()
    val exchangeSymbolMinWidths: Map<String, Int>
        get() = get(KEY_EXCHANGE_SYMBOL_MIN_WIDTHS).split(",")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                val exchange = if (eq < 0) "" else part.substring(0, eq).trim().uppercase()
                val width = if (eq < 0) null else part.substring(eq + 1).trim().toIntOrNull()
                if (exchange.isNotBlank() && width != null && width in 1..20) {
                    exchange to width
                } else {
                    if (part.isNotBlank()) logger.warn("Ignoring invalid exchange symbol minimum width: {}", part.trim())
                    null
                }
            }
            .toMap()

    private val logger = LoggerFactory.getLogger(AppConfig::class.java)
}
