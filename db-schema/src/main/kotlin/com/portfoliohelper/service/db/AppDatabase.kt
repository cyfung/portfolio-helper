package com.portfoliohelper.service.db

import org.jetbrains.exposed.sql.Table

// ---------------------------------------------------------------------------
// Table definitions
// ---------------------------------------------------------------------------

/** One row per portfolio. `id` is the stable serial PK; `slug` is the URL-facing identifier. */
object PortfoliosTable : Table("portfolios") {
    val id = integer("id").autoIncrement()
    val slug = varchar("slug", 64).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object PositionsTable : Table("positions") {
    val portfolioId = integer("portfolio_id")
    val symbol = varchar("symbol", 32)
    val amount = double("amount")
    val targetWeight = double("target_weight").default(0.0)
    val letf = text("letf").default("")
    val groups = text("groups").default("")
    override val primaryKey = PrimaryKey(portfolioId, symbol)
}

object CashTable : Table("cash") {
    val id = integer("id").autoIncrement()
    val portfolioId = integer("portfolio_id")
    val label = varchar("label", 128)
    val currency = varchar("currency", 16)
    val marginFlag = bool("margin_flag").default(false)
    val amount = double("amount")
    val portfolioRef = varchar("portfolio_ref", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Key-value config per portfolio (rebalTarget, marginTarget, allocAddMode, etc.) */
object PortfolioCfgTable : Table("portfolio_cfg") {
    val portfolioId = integer("portfolio_id")
    val cfgKey = varchar("key", 64)
    val cfgValue = text("value")
    override val primaryKey = PrimaryKey(portfolioId, cfgKey)
}

/**
 * Paired Android devices. Schema is set up now for Phase 2 (AES-256-GCM auth).
 * Currently only clientId / displayName / pairedAt / lastIp are used by PairingService.
 */
object PairedDevicesTable : Table("paired_devices") {
    val serverAssignedId = varchar("server_assigned_id", 64)
    val clientId = varchar("client_id", 128)
    val displayName = varchar("display_name", 256)
    val pairedAt = long("paired_at")
    val lastIp = varchar("last_ip", 64)
    val aesKey = varchar("aes_key", 512).default("")
    val useCount = integer("use_count").default(0)
    override val primaryKey = PrimaryKey(serverAssignedId)
}

/** Persistent browser sessions for the admin UI. */
object AdminSessionsTable : Table("admin_sessions") {
    val token = varchar("token", 64)
    val createdAt = long("created_at")
    val ip = varchar("ip", 64).default("")
    val userAgent = varchar("user_agent", 512).default("")
    override val primaryKey = PrimaryKey(token)
}

/** Generic global key-value blob store for app settings, backtest/MC settings, loan history, etc. */
object GlobalSettingsTable : Table("global_settings") {
    val key = varchar("key", 128)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

/** One row per saved backtest portfolio — enables clean per-row CRUD. */
object SavedBacktestPortfoliosTable : Table("saved_backtest_portfolios") {
    val name = varchar("name", 256)
    val config = text("config")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(name)
}

/** One row per portfolio backup snapshot stored as JSON. */
object PortfolioBackupsTable : Table("portfolio_backups") {
    val id = integer("id").autoIncrement()
    val portfolioId = integer("portfolio_id")
    val createdAt = long("created_at")         // Unix millis
    val label = varchar("label", 128).default("")   // "" = daily; "rebalance" etc = labelled tab
    val data = text("data")               // JSON blob
    override val primaryKey = PrimaryKey(id)
}

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------

object AppDatabase {
    internal val allTables = arrayOf(
        PortfoliosTable,
        PositionsTable,
        CashTable,
        PortfolioCfgTable,
        PairedDevicesTable,
        AdminSessionsTable,
        GlobalSettingsTable,
        SavedBacktestPortfoliosTable,
        PortfolioBackupsTable
    )
}
