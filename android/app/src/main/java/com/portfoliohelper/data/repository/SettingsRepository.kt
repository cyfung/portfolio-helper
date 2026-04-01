package com.portfoliohelper.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "portfoliohelper_prefs")

object PrefsKeys {
    val ALLOC_ADD_MODE          = stringPreferencesKey("alloc_add_mode")
    val ALLOC_REDUCE_MODE       = stringPreferencesKey("alloc_reduce_mode")
    val SYNC_SERVER_HOST        = stringPreferencesKey("sync_server_host")
    val SYNC_SERVER_PORT        = intPreferencesKey("sync_server_port")
    val SYNC_SERVER_NAME        = stringPreferencesKey("sync_server_name")
    val DEVICE_ID               = stringPreferencesKey("device_id")
    val SERVER_ASSIGNED_ID      = stringPreferencesKey("server_assigned_id")
    val AES_KEY                 = stringPreferencesKey("aes_key")
    val TLS_FINGERPRINT         = stringPreferencesKey("tls_fingerprint")
    val PNL_DISPLAY_MODE        = stringPreferencesKey("pnl_display_mode") // "NATIVE" or "DISPLAY"
    val DISPLAY_CURRENCY        = stringPreferencesKey("display_currency")
    val SELECTED_PORTFOLIO_ID   = intPreferencesKey("selected_portfolio_id")
    val MARGIN_CHECK_NOTIFICATIONS_ENABLED = booleanPreferencesKey("margin_check_notifications_enabled")
    val SCALING_PERCENT         = intPreferencesKey("scaling_percent")
    val AFTER_HOURS_GRAY        = booleanPreferencesKey("after_hours_gray")

    // Margin check stats
    val LAST_MARGIN_CHECK_TIME      = longPreferencesKey("last_margin_check_time")
    val LAST_MARGIN_CHECK_DATA_OLD  = longPreferencesKey("last_margin_check_data_old")
    val LAST_MARGIN_CHECK_TRIGGERED = stringPreferencesKey("last_margin_check_triggered") // comma-separated names
    val LAST_MARGIN_CHECK_ERROR     = stringPreferencesKey("last_margin_check_error")
    val MARGIN_CHECK_IS_RUNNING     = booleanPreferencesKey("margin_check_is_running")
    val MARGIN_CHECK_RUN_START      = longPreferencesKey("margin_check_run_start")
    val CURRENCY_SUGGESTION_THRESHOLD_USD = doublePreferencesKey("currency_suggestion_threshold_usd")
    val CURRENCY_SUGGESTION_TEXT    = stringPreferencesKey("currency_suggestion_text")
}

data class MarginCheckStats(
    val runTime: Long,
    val oldestDataTime: Long,
    val triggeredPortfolios: List<String>,
    val errorMessage: String? = null,
    val isRunning: Boolean = false,
    val runStartTime: Long = 0L,
    val currencySuggestionText: String? = null
)

class SettingsRepository(private val context: Context) {

    val syncServerInfo: Flow<SyncServerInfo?> = context.dataStore.data.map { prefs ->
        val name = prefs[PrefsKeys.SYNC_SERVER_NAME]
        if (name != null) {
            SyncServerInfo(
                name = name,
                host = prefs[PrefsKeys.SYNC_SERVER_HOST] ?: "",
                port = prefs[PrefsKeys.SYNC_SERVER_PORT] ?: 0
            )
        } else null
    }

    suspend fun getDeviceId(): String {
        val prefs = context.dataStore.data.first()
        var id = prefs[PrefsKeys.DEVICE_ID]
        if (id == null) {
            id = UUID.randomUUID().toString()
            context.dataStore.edit { it[PrefsKeys.DEVICE_ID] = id }
        }
        return id
    }

    suspend fun saveSyncServerInfo(info: SyncServerInfo?) {
        context.dataStore.edit { prefs ->
            if (info == null) {
                prefs.remove(PrefsKeys.SYNC_SERVER_NAME)
                prefs.remove(PrefsKeys.SYNC_SERVER_HOST)
                prefs.remove(PrefsKeys.SYNC_SERVER_PORT)
            } else {
                prefs[PrefsKeys.SYNC_SERVER_NAME] = info.name
                prefs[PrefsKeys.SYNC_SERVER_HOST] = info.host
                prefs[PrefsKeys.SYNC_SERVER_PORT] = info.port
            }
        }
    }

    suspend fun saveServerAssignedId(id: String) {
        context.dataStore.edit { it[PrefsKeys.SERVER_ASSIGNED_ID] = id }
    }

    suspend fun getServerAssignedId(): String? =
        context.dataStore.data.first()[PrefsKeys.SERVER_ASSIGNED_ID]

    suspend fun saveAesKey(key: String) {
        context.dataStore.edit { it[PrefsKeys.AES_KEY] = key }
    }

    suspend fun getAesKey(): String? =
        context.dataStore.data.first()[PrefsKeys.AES_KEY]

    suspend fun saveTlsFingerprint(fingerprint: String) {
        context.dataStore.edit { it[PrefsKeys.TLS_FINGERPRINT] = fingerprint }
    }

    suspend fun getTlsFingerprint(): String? =
        context.dataStore.data.first()[PrefsKeys.TLS_FINGERPRINT]

    val pnlDisplayMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.PNL_DISPLAY_MODE] ?: "NATIVE"
    }

    suspend fun savePnlDisplayMode(mode: String) {
        context.dataStore.edit { it[PrefsKeys.PNL_DISPLAY_MODE] = mode }
    }

    val displayCurrency: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.DISPLAY_CURRENCY] ?: "USD"
    }

    suspend fun saveDisplayCurrency(ccy: String) {
        context.dataStore.edit { it[PrefsKeys.DISPLAY_CURRENCY] = ccy }
    }

    val selectedPortfolioId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.SELECTED_PORTFOLIO_ID] ?: 0
    }

    suspend fun saveSelectedPortfolioId(id: Int) {
        context.dataStore.edit { it[PrefsKeys.SELECTED_PORTFOLIO_ID] = id }
    }

    val marginCheckNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.MARGIN_CHECK_NOTIFICATIONS_ENABLED] ?: true
    }

    suspend fun saveMarginCheckNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefsKeys.MARGIN_CHECK_NOTIFICATIONS_ENABLED] = enabled }
    }

    val scalingPercent: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.SCALING_PERCENT]
    }

    suspend fun saveScalingPercent(percent: Int?) {
        context.dataStore.edit { prefs ->
            if (percent == null) {
                prefs.remove(PrefsKeys.SCALING_PERCENT)
            } else {
                prefs[PrefsKeys.SCALING_PERCENT] = percent
            }
        }
    }

    val afterHoursGray: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.AFTER_HOURS_GRAY] ?: true
    }

    suspend fun saveAfterHoursGray(gray: Boolean) {
        context.dataStore.edit { it[PrefsKeys.AFTER_HOURS_GRAY] = gray }
    }

    // ── Margin check stats ──────────────────────────────────────────────────

    val marginCheckStats: Flow<MarginCheckStats?> = context.dataStore.data.map { prefs ->
        val isRunning = prefs[PrefsKeys.MARGIN_CHECK_IS_RUNNING] ?: false
        val runStartTime = prefs[PrefsKeys.MARGIN_CHECK_RUN_START] ?: 0L
        // Stale guard: if isRunning=true but start was > 15 minutes ago, treat as not running
        val staleRunning = isRunning && (System.currentTimeMillis() - runStartTime) > 15 * 60_000
        val runTime = prefs[PrefsKeys.LAST_MARGIN_CHECK_TIME] ?: return@map if (isRunning && !staleRunning) {
            MarginCheckStats(
                runTime = 0L,
                oldestDataTime = 0L,
                triggeredPortfolios = emptyList(),
                isRunning = true,
                runStartTime = runStartTime
            )
        } else null
        MarginCheckStats(
            runTime = runTime,
            oldestDataTime = prefs[PrefsKeys.LAST_MARGIN_CHECK_DATA_OLD] ?: 0L,
            triggeredPortfolios = prefs[PrefsKeys.LAST_MARGIN_CHECK_TRIGGERED]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            errorMessage = prefs[PrefsKeys.LAST_MARGIN_CHECK_ERROR],
            isRunning = isRunning && !staleRunning,
            runStartTime = runStartTime,
            currencySuggestionText = prefs[PrefsKeys.CURRENCY_SUGGESTION_TEXT]
        )
    }

    suspend fun updateMarginCheckStats(stats: MarginCheckStats) {
        context.dataStore.edit { prefs ->
            prefs[PrefsKeys.LAST_MARGIN_CHECK_TIME] = stats.runTime
            prefs[PrefsKeys.LAST_MARGIN_CHECK_DATA_OLD] = stats.oldestDataTime
            prefs[PrefsKeys.LAST_MARGIN_CHECK_TRIGGERED] = stats.triggeredPortfolios.joinToString(",")
            if (stats.errorMessage != null) {
                prefs[PrefsKeys.LAST_MARGIN_CHECK_ERROR] = stats.errorMessage
            } else {
                prefs.remove(PrefsKeys.LAST_MARGIN_CHECK_ERROR)
            }
            if (stats.currencySuggestionText != null) {
                prefs[PrefsKeys.CURRENCY_SUGGESTION_TEXT] = stats.currencySuggestionText
            } else {
                prefs.remove(PrefsKeys.CURRENCY_SUGGESTION_TEXT)
            }
            // Completing a run always clears running state
            prefs[PrefsKeys.MARGIN_CHECK_IS_RUNNING] = false
            prefs[PrefsKeys.MARGIN_CHECK_RUN_START] = 0L
        }
    }

    val currencySuggestionThresholdUsd: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.CURRENCY_SUGGESTION_THRESHOLD_USD] ?: 2.0
    }

    suspend fun saveCurrencySuggestionThresholdUsd(usd: Double) {
        context.dataStore.edit { it[PrefsKeys.CURRENCY_SUGGESTION_THRESHOLD_USD] = usd }
    }

    suspend fun setMarginCheckRunning(isRunning: Boolean, startTime: Long = 0L) {
        context.dataStore.edit { prefs ->
            prefs[PrefsKeys.MARGIN_CHECK_IS_RUNNING] = isRunning
            prefs[PrefsKeys.MARGIN_CHECK_RUN_START] = startTime
        }
    }
}

data class SyncServerInfo(val name: String, val host: String, val port: Int)
