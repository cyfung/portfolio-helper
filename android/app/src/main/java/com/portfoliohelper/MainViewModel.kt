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
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.portfoliohelper.data.repository.IbkrInterestCalculator
import com.portfoliohelper.data.repository.IbkrInterestResult
import com.portfoliohelper.data.repository.IbkrRateFetcher
import com.portfoliohelper.data.repository.IbkrRatesSnapshot
import com.portfoliohelper.data.repository.MarginCheckRunner
import com.portfoliohelper.data.repository.MarginCheckStats
import com.portfoliohelper.data.repository.PortfolioCalculator
import com.portfoliohelper.data.repository.SyncServerInfo
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.debug.WidgetPreviewMocks
import com.portfoliohelper.debug.WidgetPreviewState
import com.portfoliohelper.worker.MarginCheckWidgetReceiver
import com.portfoliohelper.worker.MarginCheckWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val TAG = "MainViewModel"
    }

    init {
        startInAppPolling()
    }

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

    val scalingPercent: StateFlow<Int?> = settings.scalingPercent
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val afterHoursGray: StateFlow<Boolean> = settings.afterHoursGray
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val currencySuggestionThresholdUsd: StateFlow<Double> = settings.currencySuggestionThresholdUsd
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2.0)

    // ── Cash screen navigation (triggered by notification tap) ───────────────

    private val _pendingCashNav = MutableStateFlow(false)
    val pendingCashNav: StateFlow<Boolean> = _pendingCashNav.asStateFlow()

    fun requestCashNavigation() { _pendingCashNav.value = true }
    fun onCashNavConsumed() { _pendingCashNav.value = false }

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
                    it.timestamp,
                    tradingPeriodStart = it.tradingPeriodStart
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

        val cashCurrencies = cash.filter { it.portfolioRef == null }.map { it.currency }.distinct().filter { it != "USD" }
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
        val currencies = entries.filter { it.portfolioRef == null }.map { it.currency }.distinct().filter { it != "USD" }
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
        combine(allPositions, marketData, portfolios, scalingPercent) { allPos, prices, allPortfolios, scaling ->
            allPortfolios.associate { p ->
                val pPositions = allPos.filter { it.portfolioId == p.serialId }
                p.slug to PortfolioCalculator.computeStockGrossValue(pPositions, prices, scaling = scaling)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Derived: cash totals ──────────────────────────────────────────────────

    val cashTotals: StateFlow<CashTotals> = combine(cashEntries, marketData, displayCurrency, allPortfolioStockValuesUsd, scalingPercent) { entries, prices, displayCcy, stockValues, scaling ->
        val totals = PortfolioCalculator.computeTotals(emptyList(), entries, prices, displayCcy, stockValues, scaling)
        CashTotals(totals.cashTotal, totals.margin, totals.isReady)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CashTotals(0.0, 0.0, false))

    // ── IBKR margin rates ─────────────────────────────────────────────────────

    private val _ibkrRates = MutableStateFlow(IbkrRatesSnapshot(emptyMap(), 0L))

    val ibkrInterest: StateFlow<IbkrInterestResult?> = combine(_ibkrRates, cashEntries, fxRates) { rates, entries, fx ->
        if (rates.rates.isEmpty()) return@combine null
        if (entries.none { it.isMargin && it.amount < 0 }) return@combine null
        IbkrInterestCalculator.compute(entries, fx, rates)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Derived: portfolio totals ─────────────────────────────────────────────

    val portfolioTotals: StateFlow<PortfolioCalculator.PortfolioTotals> =
        combine(positions, cashEntries, marketData, displayCurrency, allPortfolioStockValuesUsd, scalingPercent) { arr ->
            @Suppress("UNCHECKED_CAST")
            val pos = arr[0] as List<Position>
            @Suppress("UNCHECKED_CAST")
            val cash = arr[1] as List<CashEntry>
            @Suppress("UNCHECKED_CAST")
            val prices = arr[2] as Map<String, YahooQuote>
            val displayCcy = arr[3] as String
            @Suppress("UNCHECKED_CAST")
            val stockValues = arr[4] as Map<String, Pair<Double, Boolean>>
            val scaling = arr[5] as? Int
            PortfolioCalculator.computeTotals(pos, cash, prices, displayCcy, stockValues, scaling)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            PortfolioCalculator.PortfolioTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false)
        )

    // ── Derived: groups ───────────────────────────────────────────────────────

    val groupRows: StateFlow<List<GroupRow>> =
        combine(positions, marketData, portfolioTotals, scalingPercent) { pos, prices, totals, scaling ->
            PortfolioCalculator.computeGroups(pos, prices, totals.stockGrossValue, scaling)
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

    fun saveScalingPercent(percent: Int?) = viewModelScope.launch {
        settings.saveScalingPercent(percent)
    }

    fun saveAfterHoursGray(gray: Boolean) = viewModelScope.launch {
        settings.saveAfterHoursGray(gray)
    }

    fun saveCurrencySuggestionThresholdUsd(usd: Double) = viewModelScope.launch {
        settings.saveCurrencySuggestionThresholdUsd(usd)
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
            performSync()
            _syncStatus.value = SyncStatus.Success
            refreshMarketData()
            refreshIbkrRates()
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
        }
    }

    fun unpairServer() = viewModelScope.launch {
        settings.saveSyncServerInfo(null)
        _syncStatus.value = SyncStatus.Idle
    }

    private suspend fun performSync() {
        syncRepo.sync()
        // Portfolios are fully replaced on sync — revalidate selection.
        // The `portfolios` StateFlow updates automatically via observeAll(),
        // so single↔multi selector display in the UI toggles without extra code.
        val currentPortfolios = db.portfolioDao().getAll()
        if (currentPortfolios.none { it.serialId == selectedPortfolioId.value }) {
            val lowest = currentPortfolios.minByOrNull { it.serialId }
            settings.saveSelectedPortfolioId(lowest?.serialId ?: 0)
        }
    }

    fun sync() = viewModelScope.launch {
        val server = syncServerInfo.value ?: return@launch
        _syncStatus.value = SyncStatus.Syncing
        try {
            performSync()
            _syncStatus.value = SyncStatus.Success
            refreshMarketData()
            refreshIbkrRates()
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Error(e.message ?: "Sync failed")
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = SyncStatus.Idle
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    fun refreshIbkrRates() = viewModelScope.launch {
        val last = _ibkrRates.value.lastFetch
        if (last > 0 && System.currentTimeMillis() - last < 60 * 60_000L) return@launch
        val snapshot = IbkrRateFetcher.fetch() ?: return@launch
        _ibkrRates.value = snapshot
    }

    fun refreshMarketData() = viewModelScope.launch {
        val symbols = activeSymbols.value
        val cash = allCashEntries.value
        val pos = allPositions.value
        if (symbols.isNotEmpty()) {
            PortfolioCalculator.fetchAndCacheMarketData(db, pos, cash)
        }
    }

    // ── Dev: widget state preview (debug only) ───────────────────────────────
    // BuildConfig.DEBUG is false in release; R8 folds the guard and removes dead branches.

    fun pushWidgetPreview(state: WidgetPreviewState) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            val context = getApplication<PortfolioHelperApp>()
            val statsToApply: MarginCheckStats? = when (state) {
                WidgetPreviewState.NONE -> settings.marginCheckStats.firstOrNull()
                else -> WidgetPreviewMocks.buildStats(state)
            }
            val wm = AppWidgetManager.getInstance(context)
            val ids = wm.getAppWidgetIds(ComponentName(context, MarginCheckWidgetReceiver::class.java))
            ids.forEach { id ->
                wm.updateAppWidget(id, MarginCheckWidgetReceiver.buildViews(context, statsToApply))
            }
        }
    }

    // ── In-app polling ────────────────────────────────────────────────────────

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling

    private fun startInAppPolling() {
        viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                _isPolling.value = true
                try {
                    MarginCheckRunner.run(getApplication(), getApplication() as PortfolioHelperApp)
                    MarginCheckWidgetReceiver.updateAll(getApplication())
                } catch (e: Exception) {
                    Log.w(TAG, "In-app poll error: ${e.message}")
                } finally {
                    _isPolling.value = false
                }
            }
        }
    }
}

data class CashTotals(val cashTotal: Double, val margin: Double, val isReady: Boolean)
