package com.portfoliohelper

import android.app.Application
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.GroupRow
import com.portfoliohelper.data.model.Portfolio
import com.portfoliohelper.data.model.PortfolioMarginAlert
import com.portfoliohelper.data.model.Position
import com.portfoliohelper.data.repository.MarginCheckStats
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    // ── Portfolio list & selection ────────────────────────────────────────────

    val portfolios: StateFlow<List<Portfolio>> = db.portfolioDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedPortfolioId: StateFlow<Int> = settings.selectedPortfolioId
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val portfolioAlerts: StateFlow<List<PortfolioMarginAlert>> =
        db.portfolioMarginAlertDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isAnyAlertEnabled: StateFlow<Boolean> = portfolioAlerts.map { alerts ->
        alerts.any { it.lowerPct > 0 || it.upperPct > 0 }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Portfolio-scoped data flows ───────────────────────────────────────────

    val positions: StateFlow<List<Position>> = selectedPortfolioId
        .flatMapLatest { pid -> db.positionDao().observeAll(pid) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allPositions: StateFlow<List<Position>> = db.positionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cashEntries: StateFlow<List<CashEntry>> = selectedPortfolioId
        .flatMapLatest { pid -> db.cashDao().observeAll(pid) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allCashEntries: StateFlow<List<CashEntry>> = db.cashDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncServerInfo: StateFlow<SyncServerInfo?> = settings.syncServerInfo
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pnlDisplayMode: StateFlow<String> = settings.pnlDisplayMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "NATIVE")

    val displayCurrency: StateFlow<String> = settings.displayCurrency
        .stateIn(viewModelScope, SharingStarted.Eagerly, "USD")
    
    val marginCheckNotificationsEnabled: StateFlow<Boolean> = settings.marginCheckNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val marginCheckStats: StateFlow<MarginCheckStats?> = settings.marginCheckStats
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Market Data (Database Cache is the source of truth) ───────────────────

    val marketData: StateFlow<Map<String, YahooQuote>> = db.marketPriceDao().observeAll()
        .map { list ->
            list.associate {
                it.symbol to YahooQuote(
                    it.symbol,
                    it.price,
                    it.previousClose,
                    it.isMarketClosed,
                    it.currency,
                    it.timestamp
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Active Symbols ────────────────────────────────────────────────────────

    val activeSymbols: StateFlow<Set<String>> = combine(
        allPositions,
        allCashEntries,
        marketData,
        displayCurrency
    ) { allPos, cash, data, displayCcy ->
        val symbols = allPos.filter { !it.isDeleted }.map { it.symbol }.toMutableSet()

        val cashCurrencies = cash.map { it.currency }.distinct().filter { it != "USD" && it != "P" }
        cashCurrencies.forEach { symbols.add("${it}USD=X") }

        data.values.forEach { quote ->
            val ccy = quote.currency
            if (ccy != null && ccy != "USD" && !quote.symbol.endsWith("=X")) {
                val isPence = ccy.length == 3 && ccy[2].isLowerCase()
                val normalizedCcy = if (isPence) ccy.uppercase() else ccy
                symbols.add("${normalizedCcy}USD=X")
            }
        }

        if (displayCcy != "USD") {
            symbols.add("${displayCcy}USD=X")
        }
        symbols
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ── Discovery ─────────────────────────────────────────────────────────────

    val discoveredServers: StateFlow<List<NsdServiceInfo>> = syncRepo.discoverServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Derived: FX rates ─────────────────────────────────────────────────────

    val fxRates: StateFlow<Map<String, Double>> = marketData.combine(cashEntries) { data, entries ->
        val rates = mutableMapOf<String, Double>()
        val currencies = entries.map { it.currency }.distinct().filter { it != "USD" && it != "P" }
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

    // ── Derived: All Portfolio Stock Values (USD) ──────────────────────────────

    val allPortfolioStockValuesUsd: StateFlow<Map<String, Pair<Double, Boolean>>> =
        combine(allPositions, marketData, portfolios) { allPos, prices, allPortfolios ->
            allPortfolios.associate { p ->
                val pPositions = allPos.filter { it.portfolioId == p.serialId }
                p.slug to PortfolioCalculator.computeStockGrossValue(pPositions, prices)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Derived: cash totals ──────────────────────────────────────────────────

    val cashTotals: StateFlow<CashTotals> = combine(cashEntries, marketData, displayCurrency, allPortfolioStockValuesUsd) { entries, prices, displayCcy, stockValues ->
        val totals = PortfolioCalculator.computeTotals(emptyList(), entries, prices, displayCcy, stockValues)
        CashTotals(totals.cashTotal, totals.margin, totals.isReady)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CashTotals(0.0, 0.0, false))

    // ── Derived: portfolio totals ─────────────────────────────────────────────

    val portfolioTotals: StateFlow<PortfolioCalculator.PortfolioTotals> =
        combine(positions, cashEntries, marketData, displayCurrency, allPortfolioStockValuesUsd) { pos, cash, prices, displayCcy, stockValues ->
            PortfolioCalculator.computeTotals(pos, cash, prices, displayCcy, stockValues)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            PortfolioCalculator.PortfolioTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false)
        )

    // ── Derived: groups ───────────────────────────────────────────────────────

    val groupRows: StateFlow<List<GroupRow>> =
        combine(positions, marketData, portfolioTotals) { pos, prices, totals ->
            PortfolioCalculator.computeGroups(pos, prices, totals.stockGrossValue)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Portfolio selection ───────────────────────────────────────────────────

    fun selectPortfolio(id: Int) = viewModelScope.launch {
        settings.saveSelectedPortfolioId(id)
    }

    // ── Portfolio alerts ──────────────────────────────────────────────────────

    fun savePortfolioAlerts(alerts: List<PortfolioMarginAlert>) = viewModelScope.launch {
        db.portfolioMarginAlertDao().upsertAll(alerts)
        MarginCheckWorker.schedule(getApplication(), true) // Always schedule
    }

    // ── Write operations ──────────────────────────────────────────────────────

    fun upsertPosition(position: Position) = viewModelScope.launch {
        db.positionDao().upsert(position.copy(portfolioId = selectedPortfolioId.value))
        refreshMarketData()
    }

    fun deletePosition(symbol: String) = viewModelScope.launch {
        db.positionDao().softDelete(selectedPortfolioId.value, symbol)
        refreshMarketData()
    }

    fun upsertCashEntry(entry: CashEntry) = viewModelScope.launch {
        db.cashDao().upsert(entry.copy(portfolioId = selectedPortfolioId.value))
        refreshMarketData()
    }

    fun deleteCashEntry(entry: CashEntry) = viewModelScope.launch {
        db.cashDao().delete(entry)
        refreshMarketData()
    }

    fun savePnlDisplayMode(mode: String) = viewModelScope.launch {
        settings.savePnlDisplayMode(mode)
    }

    fun saveDisplayCurrency(ccy: String) = viewModelScope.launch {
        settings.saveDisplayCurrency(ccy)
        refreshMarketData()
    }

    fun saveMarginCheckNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.saveMarginCheckNotificationsEnabled(enabled)
    }

    // ── Portfolio CRUD (local only) ───────────────────────────────────────────

    fun createPortfolio(name: String) = viewModelScope.launch {
        val serialId = db.portfolioDao().insert(Portfolio(displayName = name)).toInt()
        db.portfolioMarginAlertDao().upsert(PortfolioMarginAlert(portfolioId = serialId))
        selectPortfolio(serialId)
        refreshMarketData()
    }

    fun renamePortfolio(serialId: Int, name: String) = viewModelScope.launch {
        db.portfolioDao().upsert(Portfolio(serialId = serialId, displayName = name))
        refreshMarketData()
    }

    fun deletePortfolio(serialId: Int) = viewModelScope.launch {
        if (selectedPortfolioId.value == serialId) {
            val remaining = portfolios.value.firstOrNull { it.serialId != serialId }
            settings.saveSelectedPortfolioId(remaining?.serialId ?: 0)
        }
        db.positionDao().hardDeleteAll(serialId)
        db.cashDao().deleteAll(serialId)
        db.portfolioMarginAlertDao().delete(serialId)
        db.portfolioDao().delete(serialId)
        refreshMarketData()
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

            // Auto-select the portfolio with the lowest serialId after sync
            db.portfolioDao().getAll().minByOrNull { it.serialId }?.let { lowest ->
                settings.saveSelectedPortfolioId(lowest.serialId)
            }

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
        YahooMarketDataService.setOnBatchUpdateListener { quotes ->
            viewModelScope.launch {
                val marketPrices = quotes.mapNotNull { quote ->
                    val price = quote.regularMarketPrice ?: quote.previousClose
                    if (price != null) {
                        com.portfoliohelper.data.model.MarketPrice(
                            quote.symbol,
                            price,
                            quote.previousClose,
                            quote.isMarketClosed,
                            timestamp = quote.timestamp,
                            currency = quote.currency
                        )
                    } else null
                }
                if (marketPrices.isNotEmpty()) {
                    db.marketPriceDao().upsertAll(marketPrices)
                }
            }
        }

        viewModelScope.launch {
            activeSymbols.collect { symbols ->
                if (symbols.isNotEmpty()) {
                    YahooMarketDataService.start(symbols.toList())
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
        val all = activeSymbols.value.toList()
        if (all.isNotEmpty()) {
            YahooMarketDataService.start(all)
        }
    }

    override fun onCleared() {
        super.onCleared()
        YahooMarketDataService.stop()
    }
}

data class CashTotals(val totalUsd: Double, val marginUsd: Double, val isReady: Boolean)
