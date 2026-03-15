package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import org.slf4j.LoggerFactory

/**
 * Centralises symbol-list computation and market-data re-initialisation so that
 * any component (Application startup, route handlers after a save, etc.) can
 * trigger a refresh without duplicating logic.
 */
object MarketDataCoordinator {
    private val logger = LoggerFactory.getLogger(MarketDataCoordinator::class.java)

    /** Populated once at startup from the environment / config. */
    var updateIntervalSeconds: Long = 60L

    fun allSymbols(): List<String> {
        val symbols = mutableListOf<String>()
        for (entry in ManagedPortfolio.getAll()) {
            val stocks = entry.getStocks()
            symbols += stocks.map { it.label }
            symbols += stocks.flatMap { it.letfComponents?.map { c -> c.second } ?: emptyList() }
            symbols += entry.getCash()
                .map { it.currency }.distinct()
                .filter { it != "USD" && it != "P" }
                .map { "${it}USD=X" }
        }
        // Also subscribe to FX pairs for non-USD currencies found in cached stock quotes.
        // Sub-unit currencies (two uppercase + one lowercase, e.g. GBp, ILa, ZAc) map to
        // their parent by uppercasing (GBp → GBP, ILa → ILS, ZAc → ZAR).
        val cachedStockFxPairs = symbols
            .filterNot { it.endsWith("USD=X") }
            .mapNotNull { sym ->
                val ccy = YahooMarketDataService.getQuote(sym)?.currency ?: return@mapNotNull null
                val baseCcy = if (ccy.length == 3 && ccy[0].isUpperCase() && ccy[1].isUpperCase() && ccy[2].isLowerCase())
                    ccy.uppercase() else ccy
                if (baseCcy == "USD") null else "${baseCcy}USD=X"
            }
        symbols += cachedStockFxPairs
        return symbols.distinct()
    }

    /**
     * Registers a one-time post-batch callback that adds FX pairs for any non-USD
     * stock currencies discovered in the first fetch (e.g. GBPUSD=X for GBp stocks).
     * Safe to call multiple times — deduplication is handled by [allSymbols].
     */
    fun setupAutoFxDiscovery() {
        YahooMarketDataService.onBatchComplete {
            val needed = allSymbols()
            val current = YahooMarketDataService.cachedSymbols()
            val missing = needed.filterNot { it in current }
            if (missing.isNotEmpty()) {
                logger.info("Auto-discovered ${missing.size} new FX pair(s): $missing — adding to polling")
                YahooMarketDataService.requestMarketDataForSymbols(needed, updateIntervalSeconds)
            }
        }
    }

    /** Re-computes the symbol list and restarts Yahoo + NAV polling. */
    fun refresh() {
        val symbols = allSymbols()
        logger.info("Refreshing market data for ${symbols.size} symbols...")
        YahooMarketDataService.requestMarketDataForSymbols(symbols, updateIntervalSeconds)

        val navSymbols = ManagedPortfolio.getAll()
            .flatMap { it.getStocks().map { s -> s.label } }.distinct()
        val fixedNavInterval = AppConfig.navUpdateInterval
        if (fixedNavInterval != null) NavService.requestNavForSymbols(navSymbols, fixedNavInterval)
        else NavService.requestNavForSymbols(navSymbols)
    }
}
