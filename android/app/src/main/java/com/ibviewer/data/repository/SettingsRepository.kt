package com.ibviewer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ibviewer.data.model.AllocMode
import com.ibviewer.data.model.MarginAlertSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ibviewer_prefs")

object PrefsKeys {
    val MARGIN_ALERT_ENABLED    = booleanPreferencesKey("margin_alert_enabled")
    val MARGIN_ALERT_LOWER_PCT  = floatPreferencesKey("margin_alert_lower_pct")
    val MARGIN_ALERT_UPPER_PCT  = floatPreferencesKey("margin_alert_upper_pct")
    val MARGIN_ALERT_INTERVAL   = intPreferencesKey("margin_alert_interval_min")
    val REBAL_TARGET_USD        = floatPreferencesKey("rebal_target_usd")
    val ALLOC_ADD_MODE          = stringPreferencesKey("alloc_add_mode")
    val ALLOC_REDUCE_MODE       = stringPreferencesKey("alloc_reduce_mode")
    val FX_RATES_JSON           = stringPreferencesKey("fx_rates_json")   // "{CCY:rate,...}"
}

class SettingsRepository(private val context: Context) {

    val marginAlertSettings: Flow<MarginAlertSettings> = context.dataStore.data.map { prefs ->
        MarginAlertSettings(
            enabled               = prefs[PrefsKeys.MARGIN_ALERT_ENABLED] ?: false,
            lowerPct              = (prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] ?: 20f).toDouble(),
            upperPct              = (prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] ?: 50f).toDouble(),
            checkIntervalMinutes  = prefs[PrefsKeys.MARGIN_ALERT_INTERVAL] ?: 15
        )
    }

    val allocAddMode: Flow<AllocMode> = context.dataStore.data.map { prefs ->
        AllocMode.valueOf(prefs[PrefsKeys.ALLOC_ADD_MODE] ?: AllocMode.WATERFALL.name)
    }

    val allocReduceMode: Flow<AllocMode> = context.dataStore.data.map { prefs ->
        AllocMode.valueOf(prefs[PrefsKeys.ALLOC_REDUCE_MODE] ?: AllocMode.WATERFALL.name)
    }

    val fxRatesJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PrefsKeys.FX_RATES_JSON] ?: "{}"
    }

    suspend fun saveMarginAlertSettings(s: MarginAlertSettings) {
        context.dataStore.edit { prefs ->
            prefs[PrefsKeys.MARGIN_ALERT_ENABLED]   = s.enabled
            prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] = s.lowerPct.toFloat()
            prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] = s.upperPct.toFloat()
            prefs[PrefsKeys.MARGIN_ALERT_INTERVAL]  = s.checkIntervalMinutes
        }
    }

    suspend fun saveFxRates(json: String) {
        context.dataStore.edit { it[PrefsKeys.FX_RATES_JSON] = json }
    }

    suspend fun saveAllocModes(addMode: AllocMode, reduceMode: AllocMode) {
        context.dataStore.edit { prefs ->
            prefs[PrefsKeys.ALLOC_ADD_MODE]    = addMode.name
            prefs[PrefsKeys.ALLOC_REDUCE_MODE] = reduceMode.name
        }
    }
}
