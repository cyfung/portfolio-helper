package com.portfoliohelper.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// ── Portfolio ─────────────────────────────────────────────────────────────────

@Entity(tableName = "portfolios")
data class Portfolio(
    @PrimaryKey(autoGenerate = true) val serialId: Int = 0,
    val displayName: String,
    val slug: String = ""
)

// ── Portfolio position ────────────────────────────────────────────────────────

@Entity(tableName = "positions", primaryKeys = ["portfolioId", "symbol"])
@Serializable
data class Position(
    val portfolioId: Int = 0,
    val symbol: String,
    val quantity: Double,
    val targetWeight: Double,     // % 0–100
    val groups: String = "",      // semicolon-separated "multiplier name" entries
    val isDeleted: Boolean = false
)

// ── Group aggregation (computed, not stored) ──────────────────────────────────

data class GroupRow(
    val name: String,
    val mktVal: Double,
    val prevMktVal: Double,
    val targetWeight: Double,
    val members: List<String>
)

// ── Cash entry ────────────────────────────────────────────────────────────────

@Entity(tableName = "cash_entries")
@Serializable
data class CashEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val portfolioId: Int = 0,
    val label: String,
    val currency: String,       // ISO code e.g. "USD", "HKD"
    val amount: Double,         // value if regular; multiplier if portfolioRef is set
    val isMargin: Boolean = false,
    val portfolioRef: String? = null // slug of the referenced portfolio; if set, this is a portfolio reference
)

// ── Market Price (cached for offline fallback) ───────────────────────────────

@Entity(tableName = "market_prices")
data class MarketPrice(
    @PrimaryKey val symbol: String,
    val price: Double,
    val previousClose: Double?,
    val isMarketClosed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val currency: String? = null,
    val localDate: String? = null   // local trading date "YYYY-MM-DD"
)

// ── Per-portfolio margin alert settings ───────────────────────────────────────

@Entity(tableName = "portfolio_margin_alerts")
data class PortfolioMarginAlert(
    @PrimaryKey val portfolioId: Int,
    val lowerPct: Double = -1.0,   // alert when margin% drops below this
    val upperPct: Double = -1.0    // alert when margin% rises above this
)

// ── Allocation modes ──────────────────────────────────────────────────────────

enum class AllocMode { PROPORTIONAL, CURRENT_WEIGHT, UNDERVALUED_PRIORITY, WATERFALL }
