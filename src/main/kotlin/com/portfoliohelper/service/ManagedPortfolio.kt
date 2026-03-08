package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Stock
import java.io.File
import java.nio.file.Paths
import java.util.Properties
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
    // Derived: same dir as stocks.csv, named portfolio.conf
    val portfolioConfigPath: String = Paths.get(csvPath).resolveSibling("portfolio.conf").toString()

    private val _stocks = AtomicReference<List<Stock>>(emptyList())
    private val _cash   = AtomicReference<List<CashEntry>>(emptyList())

    fun getStocks(): List<Stock> = _stocks.get()
    fun updateStocks(s: List<Stock>) = _stocks.set(s)
    fun getCash(): List<CashEntry> = _cash.get()
    fun updateCash(e: List<CashEntry>) = _cash.set(e)

    // TWS Account (per-portfolio config)
    fun getTwsAccount(): String? {
        val f = File(portfolioConfigPath)
        if (!f.exists()) return null
        return Properties().also { p -> f.inputStream().use { p.load(it) } }
            .getProperty("twsAccount")?.takeIf { it.isNotBlank() }
    }

    fun saveTwsAccount(account: String) {
        val f = File(portfolioConfigPath)
        f.parentFile?.mkdirs()
        val props = Properties().also { if (f.exists()) f.inputStream().use { s -> it.load(s) } }
        if (account.isBlank()) props.remove("twsAccount") else props["twsAccount"] = account
        f.outputStream().use { props.store(it, null) }
    }
}
