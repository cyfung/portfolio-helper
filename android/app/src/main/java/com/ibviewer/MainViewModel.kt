package com.ibviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibviewer.data.model.CashEntry
import com.ibviewer.data.model.GroupRow
import com.ibviewer.data.model.MarginAlertSettings
import com.ibviewer.data.model.Position
import com.ibviewer.data.repository.PortfolioCalculator
import com.ibviewer.data.repository.YahooMarketDataService
import com.ibviewer.data.repository.YahooQuote
import com.ibviewer.worker.MarginCheckWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // ── Market Data (In-memory) ───────────────────────────────────────────────

    private val _marketData = MutableStateFlow<Map<String, YahooQuote>>(emptyMap())
    val marketData: StateFlow<Map<String, YahooQuote>> = _marketData

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
        combine(positions, marketData, cashTotals) { pos, prices, cash ->
            PortfolioCalculator.computeTotals(pos, prices, cash.marginUsd)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            PortfolioCalculator.PortfolioTotals(0.0, 0.0, 0.0, 0.0, 0.0)
        )

    // ── Derived: groups ───────────────────────────────────────────────────────

    val groupRows: StateFlow<List<GroupRow>> =
        combine(positions, marketData, portfolioTotals) { pos, prices, totals ->
            PortfolioCalculator.computeGroups(pos, prices, totals.totalMktVal)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Write operations ──────────────────────────────────────────────────────

    fun upsertPosition(position: Position) = viewModelScope.launch {
        db.positionDao().upsert(position)
        refreshMarketData()
    }

    fun deletePosition(symbol: String) = viewModelScope.launch {
        db.positionDao().softDelete(symbol)
        refreshMarketData()
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

    // ── Market Data ───────────────────────────────────────────────────────────

    init {
        YahooMarketDataService.setOnUpdateListener { symbol, quote ->
            _marketData.value += (symbol to quote)
        }
        viewModelScope.launch {
            positions.collect { posList ->
                val activeSymbols = posList.filter { !it.isDeleted }.map { it.symbol }
                if (activeSymbols.isNotEmpty()) {
                    YahooMarketDataService.start(activeSymbols)
                }
            }
        }
    }

    private fun refreshMarketData() {
        val activeSymbols = positions.value.filter { !it.isDeleted }.map { it.symbol }
        if (activeSymbols.isNotEmpty()) {
            YahooMarketDataService.start(activeSymbols)
        }
    }

    override fun onCleared() {
        super.onCleared()
        YahooMarketDataService.stop()
    }
}

data class CashTotals(val totalUsd: Double, val marginUsd: Double)
