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
        return symbols.distinct()
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
