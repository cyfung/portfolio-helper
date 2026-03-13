package com.ibviewer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// ── Portfolio position ────────────────────────────────────────────────────────

@Entity(tableName = "positions")
@Serializable
data class Position(
    @PrimaryKey val symbol: String,
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
    val label: String,
    val currency: String,       // ISO code e.g. "USD", "HKD"
    val amount: Double,         // negative = margin/loan
    val isMargin: Boolean = false
)

// ── FX rate (in-memory only, entered manually) ────────────────────────────────

data class FxRate(
    val currency: String,
    val rateToUsd: Double       // 1 unit of currency = X USD
)

// ── Margin alert settings ─────────────────────────────────────────────────────

data class MarginAlertSettings(
    val enabled: Boolean = false,
    val lowerPct: Double = 20.0,   // alert when margin% drops below this
    val upperPct: Double = 50.0,   // alert when margin% rises above this
    val checkIntervalMinutes: Int = 15
)

// ── Allocation modes ──────────────────────────────────────────────────────────

enum class AllocMode { PROPORTIONAL, CURRENT_WEIGHT, UNDERVALUED_PRIORITY, WATERFALL }
