package com.ibviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibviewer.data.model.CashEntry
import com.ibviewer.data.model.GroupRow
import com.ibviewer.data.model.MarginAlertSettings
import com.ibviewer.data.model.Position
import com.ibviewer.data.repository.PortfolioCalculator
import com.ibviewer.worker.MarginCheckWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as IbViewerApp).database
    private val settings = (app as IbViewerApp).settingsRepo

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ── Raw data flows ────────────────────────────────────────────────────────

    val positions: StateFlow<List<Position>> = db.positionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cashEntries: StateFlow<List<CashEntry>> = db.cashDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val marginAlertSettings: StateFlow<MarginAlertSettings> = settings.marginAlertSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, MarginAlertSettings())

    val fxRates: StateFlow<Map<String, Double>> = settings.fxRatesJson.map { jsonStr ->
        if (jsonStr.isBlank() || jsonStr == "{}") emptyMap()
        else {
            runCatching { json.decodeFromString<Map<String, Double>>(jsonStr) }
                .getOrDefault(emptyMap())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Rebal target (UI state) ───────────────────────────────────────────────

    private val _rebalTargetUsd = MutableStateFlow<Double?>(null)
    val rebalTargetUsd: StateFlow<Double?> = _rebalTargetUsd

    fun setRebalTarget(usd: Double?) {
        _rebalTargetUsd.value = usd
    }

    // ── Derived: cash totals ──────────────────────────────────────────────────

    val cashTotals: StateFlow<CashTotals> = combine(cashEntries, fxRates) { entries, rates ->
        var totalUsd = 0.0
        var marginUsd = 0.0
        for (e in entries) {
            val rate = if (e.currency == "USD") 1.0 else rates[e.currency] ?: continue
            val usd = e.amount * rate
            totalUsd += usd
            if (e.isMargin) marginUsd += usd
        }
        CashTotals(totalUsd, marginUsd)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CashTotals(0.0, 0.0))

    // ── Derived: portfolio totals ─────────────────────────────────────────────

    val portfolioTotals: StateFlow<PortfolioCalculator.PortfolioTotals> =
        combine(positions, cashTotals) { pos, cash ->
            PortfolioCalculator.computeTotals(pos, cash.marginUsd)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            PortfolioCalculator.PortfolioTotals(0.0, 0.0, 0.0, 0.0, 0.0)
        )

    // ── Derived: rebal rows ───────────────────────────────────────────────────

    val rebalRows: StateFlow<List<PortfolioCalculator.RebalRow>> =
        combine(positions, portfolioTotals, rebalTargetUsd) { pos, totals, target ->
            val rebalTotal = target ?: (totals.totalMktVal + maxOf(0.0, cashTotals.value.marginUsd))
            PortfolioCalculator.computeRebal(pos, rebalTotal)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Derived: groups ───────────────────────────────────────────────────────

    val groupRows: StateFlow<List<GroupRow>> =
        combine(positions, portfolioTotals) { pos, totals ->
            PortfolioCalculator.computeGroups(pos, totals.totalMktVal)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Write operations ──────────────────────────────────────────────────────

    fun upsertPosition(position: Position) = viewModelScope.launch {
        db.positionDao().upsert(position)
    }

    fun deletePosition(symbol: String) = viewModelScope.launch {
        db.positionDao().softDelete(symbol)
    }

    fun upsertCashEntry(entry: CashEntry) = viewModelScope.launch {
        db.cashDao().upsert(entry)
    }

    fun deleteCashEntry(entry: CashEntry) = viewModelScope.launch {
        db.cashDao().delete(entry)
    }

    fun saveFxRates(rates: Map<String, Double>) = viewModelScope.launch {
        settings.saveFxRates(json.encodeToString(rates))
    }

    fun saveMarginAlertSettings(s: MarginAlertSettings) = viewModelScope.launch {
        settings.saveMarginAlertSettings(s)
        MarginCheckWorker.schedule(getApplication(), s)
    }

    fun updatePrice(symbol: String, mark: Double?, close: Double?) = viewModelScope.launch {
        val pos = db.positionDao().get(symbol) ?: return@launch
        db.positionDao().upsert(pos.copy(markPrice = mark, closePrice = close))
    }
}

data class CashTotals(val totalUsd: Double, val marginUsd: Double)
