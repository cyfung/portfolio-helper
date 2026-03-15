package com.portfoliohelper

import android.app.Application
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.GroupRow
import com.portfoliohelper.data.model.MarginAlertSettings
import com.portfoliohelper.data.model.Position
import com.portfoliohelper.data.repository.PortfolioCalculator
import com.portfoliohelper.data.repository.SyncRepository
import com.portfoliohelper.data.repository.SyncServerInfo
import com.portfoliohelper.data.repository.YahooMarketDataService
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.worker.MarginCheckWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
    data class NeedsPairing(val server: NsdServiceInfo) : SyncStatus()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as PortfolioHelperApp).database
    private val settings = (app as PortfolioHelperApp).settingsRepo
    private val syncRepo = (app as PortfolioHelperApp).syncRepo

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

    val syncServerInfo: StateFlow<SyncServerInfo?> = settings.syncServerInfo
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Market Data (In-memory) ───────────────────────────────────────────────

    private val _marketData = MutableStateFlow<Map<String, YahooQuote>>(emptyMap())
    val marketData: StateFlow<Map<String, YahooQuote>> = _marketData

    // ── Discovery ─────────────────────────────────────────────────────────────

    val discoveredServers: StateFlow<List<NsdServiceInfo>> = syncRepo.discoverServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Derived: FX rates ─────────────────────────────────────────────────────

    val fxRates: StateFlow<Map<String, Double>> = marketData.combine(cashEntries) { data, entries ->
        val rates = mutableMapOf<String, Double>()
        val currencies = entries.map { it.currency }.distinct().filter { it != "USD" }
        for (ccy in currencies) {
            val pair = "${ccy}USD=X"
            val quote = data[pair]
            val rate = quote?.regularMarketPrice ?: quote?.previousClose
            if (rate != null) {
                rates[ccy] = rate
            }
        }
        rates
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
            PortfolioCalculator.computeGroups(pos, prices, totals.stockGrossValue)
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
        refreshMarketData()
    }

    fun deleteCashEntry(entry: CashEntry) = viewModelScope.launch {
        db.cashDao().delete(entry)
        refreshMarketData()
    }

    fun saveMarginAlertSettings(s: MarginAlertSettings) = viewModelScope.launch {
        settings.saveMarginAlertSettings(s)
        MarginCheckWorker.schedule(getApplication(), s)
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus

    fun requestPairing(service: NsdServiceInfo) {
        _syncStatus.value = SyncStatus.NeedsPairing(service)
    }

    fun pairServer(service: NsdServiceInfo, pin: String) = viewModelScope.launch {
        Log.i("MainViewModel", "Attempting to pair with server: ${service.serviceName} using PIN $pin")
        _syncStatus.value = SyncStatus.Syncing
        try {
            val host = service.host?.hostAddress ?: ""
            val port = service.port
            syncRepo.pair(host, port, pin)
            
            settings.saveSyncServerInfo(SyncServerInfo(
                name = service.serviceName,
                host = host,
                port = port
            ))
            Log.i("MainViewModel", "Pairing successful")
            sync()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Pairing failed: ${e.message}")
            _syncStatus.value = SyncStatus.Error("Pairing failed: ${e.message}")
        }
    }

    fun unpairServer() = viewModelScope.launch {
        Log.i("MainViewModel", "Unpairing server")
        settings.saveSyncServerInfo(null)
        _syncStatus.value = SyncStatus.Idle
    }

    fun sync() = viewModelScope.launch {
        Log.i("MainViewModel", "Sync button clicked or auto-sync started")
        _syncStatus.value = SyncStatus.Syncing
        try {
            syncRepo.sync()
            Log.i("MainViewModel", "Sync repository call completed successfully")
            _syncStatus.value = SyncStatus.Success
            refreshMarketData()
        } catch (e: Exception) {
            if (e is SyncRepository.UnauthorizedException) {
                Log.w("MainViewModel", "Sync failed: Unauthorized. Re-pairing needed.")
                _syncStatus.value = SyncStatus.Error("Device not paired or pairing expired.")
            } else {
                Log.e("MainViewModel", "Sync failed with error: ${e.message}", e)
                _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = SyncStatus.Idle
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    init {
        YahooMarketDataService.setOnUpdateListener { symbol, quote ->
            _marketData.value += (symbol to quote)
        }
        viewModelScope.launch {
            combine(positions, cashEntries) { pos, cash ->
                val symbols = pos.filter { !it.isDeleted }.map { it.symbol }.toMutableList()
                val currencies = cash.map { it.currency }.distinct().filter { it != "USD" }
                currencies.forEach { symbols.add("${it}USD=X") }
                symbols
            }.collect { activeSymbols ->
                if (activeSymbols.isNotEmpty()) {
                    YahooMarketDataService.start(activeSymbols)
                }
            }
        }
        
        // Initial sync if paired
        viewModelScope.launch {
            if (settings.syncServerInfo.firstOrNull() != null) {
                sync()
            }
        }
    }

    private fun refreshMarketData() {
        val posSymbols = positions.value.filter { !it.isDeleted }.map { it.symbol }
        val fxSymbols = cashEntries.value.map { it.currency }.distinct().filter { it != "USD" }.map { "${it}USD=X" }
        val all = posSymbols + fxSymbols
        if (all.isNotEmpty()) {
            YahooMarketDataService.start(all)
        }
    }

    override fun onCleared() {
        super.onCleared()
        YahooMarketDataService.stop()
    }
}

data class CashTotals(val totalUsd: Double, val marginUsd: Double)
