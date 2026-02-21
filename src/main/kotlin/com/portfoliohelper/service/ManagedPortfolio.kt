package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Stock
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents a single portfolio with its own CSV and cash files.
 * Thread-safe via AtomicReference.
 */
class ManagedPortfolio(
    val name: String,     // Display name: "Main", "Retirement", etc.
    val id: String,       // URL slug: lowercase, e.g. "main", "retirement"
    val csvPath: String,  // e.g. "data/stocks.csv" or "data/retirement/stocks.csv"
    val cashPath: String, // e.g. "data/cash.txt" or "data/retirement/cash.txt"
) {
    private val _stocks = AtomicReference<List<Stock>>(emptyList())
    private val _cash   = AtomicReference<List<CashEntry>>(emptyList())

    fun getStocks(): List<Stock> = _stocks.get()
    fun updateStocks(s: List<Stock>) = _stocks.set(s)
    fun getCash(): List<CashEntry> = _cash.get()
    fun updateCash(e: List<CashEntry>) = _cash.set(e)
}
