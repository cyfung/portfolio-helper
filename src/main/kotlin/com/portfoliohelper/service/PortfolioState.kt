package com.portfoliohelper.service

import com.portfoliohelper.model.Stock
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the current portfolio base stocks.
 * Updated when CSV file changes.
 */
object PortfolioState {
    private val currentStocks = AtomicReference<List<Stock>>(emptyList())

    /**
     * Get the current base stocks.
     */
    fun getStocks(): List<Stock> = currentStocks.get()

    /**
     * Update the base stocks (called when CSV is reloaded).
     */
    fun updateStocks(stocks: List<Stock>) {
        currentStocks.set(stocks)
    }
}
