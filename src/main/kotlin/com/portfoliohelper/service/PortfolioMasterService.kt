package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PortfolioServices(val portfolio: ManagedPortfolio, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job())

    private val _stocks = MutableStateFlow(portfolio.getStocks())
    private val _cashEntries = MutableStateFlow(portfolio.getCash())
    private val _config = MutableStateFlow(portfolio.getAllConfig())

    val stockDisplay = StockDisplayService(portfolio.slug, _stocks, AppConfig.privacyScalePctFlow)
    val stockGross = StockGrossService(stockDisplay, scope)
    val cashDisplay = CashDisplayService(portfolio.slug, _cashEntries, AppConfig.privacyScalePctFlow)
    val totals = PortfolioTotalsService(portfolio.slug, stockDisplay, cashDisplay, scope)
    val interest = IbkrInterestService(portfolio.slug, _cashEntries, cashDisplay, scope)
    val dividend = DividendService(portfolio, _stocks, _config, scope)

    fun initialize() {
        stockDisplay.initialize(scope)
        cashDisplay.initialize(scope)
    }

    fun refreshStocks() {
        _stocks.value = portfolio.getStocks()
    }

    fun refreshCashEntries() {
        _cashEntries.value = portfolio.getCash()
    }

    fun refreshConfig() {
        _config.value = portfolio.getAllConfig()
    }

    fun shutdown() = scope.cancel()
}

@OptIn(ExperimentalCoroutinesApi::class)
object PortfolioMasterService {
    private val mutex = Mutex()

    private val _services =
        MutableStateFlow<LinkedHashMap<String, PortfolioServices>>(LinkedHashMap())
    private lateinit var _scope: CoroutineScope

    fun initialize(scope: CoroutineScope) {
        _scope = scope
        val initial = LinkedHashMap<String, PortfolioServices>()
        ManagedPortfolio.getAll().forEach { p ->
            initial[p.slug] = PortfolioServices(p, scope).also { it.initialize() }
        }
        _services.value = initial
    }

    // --- CRUD (suspend, Mutex-guarded) ---

    /** Throws IllegalArgumentException if slug already exists. */
    suspend fun create(slug: String): ManagedPortfolio = mutex.withLock {
        require(ManagedPortfolio.getBySlug(slug) == null) { "A portfolio named '$slug' already exists." }
        val portfolio = ManagedPortfolio.create(slug)
        val svc = PortfolioServices(portfolio, _scope).also { it.initialize() }
        _services.value = LinkedHashMap(_services.value).also { it[slug] = svc }
        PortfolioUpdateBroadcaster.broadcastReload()
        portfolio
    }

    /** Throws IllegalArgumentException on slug conflict. */
    suspend fun rename(portfolio: ManagedPortfolio, newSlug: String): ManagedPortfolio =
        mutex.withLock {
            require(newSlug == portfolio.slug || ManagedPortfolio.getBySlug(newSlug) == null) {
                "A portfolio named '$newSlug' already exists."
            }
            val oldSlug = portfolio.slug
            val oldSvc = _services.value[oldSlug]
            portfolio.rename(newSlug)
            val newSvc = PortfolioServices(portfolio, _scope).also { it.initialize() }
            _services.value = LinkedHashMap<String, PortfolioServices>().also { copy ->
                _services.value.forEach { (k, v) ->
                    copy[if (k == oldSlug) newSlug else k] = if (k == oldSlug) newSvc else v
                }
            }
            oldSvc?.shutdown()
            PortfolioUpdateBroadcaster.broadcastReload()
            portfolio
        }

    /** Throws IllegalStateException if trying to delete the default portfolio. */
    suspend fun delete(portfolio: ManagedPortfolio) = mutex.withLock {
        check(portfolio.serialId != ManagedPortfolio.firstSerialId()) {
            "The default portfolio cannot be removed."
        }
        val slug = portfolio.slug
        val svc = _services.value[slug]
        portfolio.delete()
        val newServices = LinkedHashMap(_services.value).also { it.remove(slug) }
        _services.value = newServices
        svc?.shutdown()
        // Refresh cash entries on all remaining portfolios so P-entries that referenced
        // the deleted portfolio recompute immediately (portfolioRef resolves to null via DB join)
        newServices.values.forEach { it.refreshCashEntries() }
        PortfolioUpdateBroadcaster.broadcastReload()
        MarketDataCoordinator.refresh()
    }

    // --- Read access ---

    fun get(slug: String): PortfolioServices? = _services.value[slug]

    // flatMapLatest re-merges whenever the portfolio set changes, so long-lived
    // collectors (e.g. SSE) automatically track portfolios being added/removed.
    val stockFlow: Flow<StockDisplaySnapshot> = childFlow { it.stockDisplay.updates }
    val stockGrossFlow: Flow<StockGrossSnapshot> = childFlow { it.stockGross.updates.filterNotNull() }
    val cashFlow: Flow<CashDisplaySnapshot> = childFlow { it.cashDisplay.updates }
    val totalsFlow: Flow<PortfolioTotalsSnapshot> = childFlow { it.totals.updates.filterNotNull() }
    val interestFlow: Flow<IbkrInterestSnapshot> = childFlow { it.interest.updates.filterNotNull() }
    val dividendFlow: Flow<DividendSnapshot> = childFlow { it.dividend.updates.filterNotNull() }

    private fun <T> childFlow(f: (PortfolioServices) -> Flow<T>): Flow<T> =
        _services.flatMapLatest { service ->
            service.values.map(f).merge()
        }
}
