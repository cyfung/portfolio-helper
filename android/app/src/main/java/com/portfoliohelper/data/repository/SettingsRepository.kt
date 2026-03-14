package com.portfoliohelper.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.portfoliohelper.data.model.MarginAlertSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "portfoliohelper_prefs")

object PrefsKeys {
    val MARGIN_ALERT_ENABLED    = booleanPreferencesKey("margin_alert_enabled")
    val MARGIN_ALERT_LOWER_PCT  = floatPreferencesKey("margin_alert_lower_pct")
    val MARGIN_ALERT_UPPER_PCT  = floatPreferencesKey("margin_alert_upper_pct")
    val MARGIN_ALERT_INTERVAL   = intPreferencesKey("margin_alert_interval_min")
    val ALLOC_ADD_MODE          = stringPreferencesKey("alloc_add_mode")
    val ALLOC_REDUCE_MODE       = stringPreferencesKey("alloc_reduce_mode")
    val SYNC_SERVER_HOST        = stringPreferencesKey("sync_server_host")
    val SYNC_SERVER_PORT        = intPreferencesKey("sync_server_port")
    val SYNC_SERVER_NAME        = stringPreferencesKey("sync_server_name")
    val DEVICE_ID               = stringPreferencesKey("device_id")
    val SERVER_ASSIGNED_ID      = stringPreferencesKey("server_assigned_id")
    val AES_KEY                 = stringPreferencesKey("aes_key")
    val TLS_FINGERPRINT         = stringPreferencesKey("tls_fingerprint")
}

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
            context.dataStore.edit { it[PrefsKeys.DEVICE_ID] = id!! }
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

    val marginAlertSettings: Flow<MarginAlertSettings> = context.dataStore.data.map { prefs ->
        MarginAlertSettings(
            enabled               = prefs[PrefsKeys.MARGIN_ALERT_ENABLED] ?: false,
            lowerPct              = (prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] ?: 20f).toDouble(),
            upperPct              = (prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] ?: 50f).toDouble(),
            checkIntervalMinutes  = prefs[PrefsKeys.MARGIN_ALERT_INTERVAL] ?: 15
        )
    }

    suspend fun saveMarginAlertSettings(s: MarginAlertSettings) {
        context.dataStore.edit { prefs ->
            prefs[PrefsKeys.MARGIN_ALERT_ENABLED]   = s.enabled
            prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] = s.lowerPct.toFloat()
            prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] = s.upperPct.toFloat()
            prefs[PrefsKeys.MARGIN_ALERT_INTERVAL]  = s.checkIntervalMinutes
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

}

data class SyncServerInfo(val name: String, val host: String, val port: Int)
