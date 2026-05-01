package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.db.CashTable
import com.portfoliohelper.service.db.PortfolioBackupsTable
import com.portfoliohelper.service.db.PortfolioCfgTable
import com.portfoliohelper.service.db.PortfoliosTable
import com.portfoliohelper.service.db.PositionsTable
import com.portfoliohelper.service.db.StockTickersTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Represents a single portfolio. Data is persisted in SQLite (app.db) via Exposed.
 *
 * `serialId` is the stable integer PK from `portfolios` table, used as FK in all data tables.
 * `slug`     is the URL-facing identifier (e.g. "main", "retirement").
 * `name`     is derived from `slug` for display purposes.
 *
 * Instances are lightweight and created on demand from DB queries — there is no in-memory registry.
 */
class ManagedPortfolio(
    val serialId: Int,
    val slug: String,
    val name: String,
    val seqOrder: Double = 0.0,
) {

    fun getStocks(): List<Stock> {
        val pid = serialId
        val stocks = transaction {
            PositionsTable.leftJoin(StockTickersTable, { PositionsTable.symbol }, { StockTickersTable.symbol })
                .selectAll()
                .where { PositionsTable.portfolioId eq pid }
                .map { row ->
                    val letf = row.getOrNull(StockTickersTable.letf) ?: ""
                    val rawGroups = row.getOrNull(StockTickersTable.groups) ?: ""
                    val letfComponents = parseLetf(letf)
                    val groups = parseGroups(rawGroups)
                    Stock(
                        label          = row[PositionsTable.symbol],
                        amount         = row[PositionsTable.amount],
                        targetWeight   = row[PositionsTable.targetWeight].takeIf { it != 0.0 },
                        letfComponents = letfComponents,
                        groups         = groups
                    )
                }
        }
        return stocks
    }

    fun getCash(): List<CashEntry> {
        val pid = serialId
        return transaction {
            CashTable.leftJoin(PortfoliosTable, { CashTable.portfolioRefId }, { PortfoliosTable.id })
                .selectAll().where { CashTable.portfolioId eq pid }
                .map { row ->
                    CashEntry(
                        label        = row[CashTable.label],
                        currency     = row[CashTable.currency],
                        marginFlag   = row[CashTable.marginFlag],
                        amount       = row[CashTable.amount],
                        portfolioRef = row.getOrNull(PortfoliosTable.slug)
                    )
                }
        }
    }

    // --- Config accessors (PortfolioCfgTable) ---

    fun getConfig(key: String): String? {
        val pid = serialId
        return transaction {
            PortfolioCfgTable.selectAll()
                .where { (PortfolioCfgTable.portfolioId eq pid) and (PortfolioCfgTable.cfgKey eq key) }
                .singleOrNull()
                ?.get(PortfolioCfgTable.cfgValue)
                ?.takeIf { it.isNotBlank() }
        }
    }

    fun getAllConfig(): Map<String, String> {
        val pid = serialId
        return transaction {
            PortfolioCfgTable.selectAll()
                .where { PortfolioCfgTable.portfolioId eq pid }
                .associate { it[PortfolioCfgTable.cfgKey] to it[PortfolioCfgTable.cfgValue] }
        }
    }

    fun saveConfig(key: String, value: String) {
        val pid = serialId
        transaction {
            if (value.isBlank()) {
                PortfolioCfgTable.deleteWhere {
                    (PortfolioCfgTable.portfolioId eq pid) and (PortfolioCfgTable.cfgKey eq key)
                }
            } else {
                PortfolioCfgTable.upsert {
                    it[PortfolioCfgTable.portfolioId] = pid
                    it[cfgKey]                        = key
                    it[cfgValue]                      = value
                }
            }
        }
    }

    fun replacePositions(stocks: List<BackupStock>) {
        val pid = serialId
        PositionsTable.deleteWhere { PositionsTable.portfolioId eq pid }
        PositionsTable.batchInsert(stocks) { s ->
            this[PositionsTable.portfolioId] = pid
            this[PositionsTable.symbol] = s.symbol
            this[PositionsTable.amount] = s.amount
            this[PositionsTable.targetWeight] = s.targetWeight
        }
        stocks.forEach { s ->
            StockTickersTable.upsert {
                it[symbol] = s.symbol
                it[letf] = s.letf
                it[groups] = s.groups
            }
        }
    }

    fun replaceCash(entries: List<CashEntry>) {
        val pid = serialId
        val slugToId = PortfoliosTable.selectAll().associate { it[PortfoliosTable.slug] to it[PortfoliosTable.id] }
        CashTable.deleteWhere { CashTable.portfolioId eq pid }
        CashTable.batchInsert(entries) { entry ->
            this[CashTable.portfolioId] = pid
            this[CashTable.label] = entry.label
            this[CashTable.currency] = entry.currency
            this[CashTable.marginFlag] = entry.marginFlag
            this[CashTable.amount] = entry.amount
            this[CashTable.portfolioRefId] = entry.portfolioRef?.let { slugToId[it] }
        }
    }

    // TWS Account — now stored inside ibkrConfig JSON (migrated from standalone key by V5)
    fun getTwsAccount(): String? {
        val raw = getConfig("ibkrConfig") ?: return null
        return try {
            Json.parseToJsonElement(raw).jsonObject["twsAccount"]
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    companion object {
        /** All portfolios ordered by seq_order (user-defined), then id as tiebreaker. */
        fun getAll(): List<ManagedPortfolio> = transaction {
            PortfoliosTable.selectAll()
                .orderBy(PortfoliosTable.seqOrder to SortOrder.ASC, PortfoliosTable.id to SortOrder.ASC)
                .map { ManagedPortfolio(it[PortfoliosTable.id], it[PortfoliosTable.slug], it[PortfoliosTable.name], it[PortfoliosTable.seqOrder]) }
        }

        /** Look up a portfolio by its URL slug. Returns null if not found. */
        fun getBySlug(slug: String): ManagedPortfolio? = transaction {
            PortfoliosTable.selectAll()
                .where { PortfoliosTable.slug eq slug }
                .singleOrNull()
                ?.let { ManagedPortfolio(it[PortfoliosTable.id], it[PortfoliosTable.slug], it[PortfoliosTable.name], it[PortfoliosTable.seqOrder]) }
        }

        /** Insert a new portfolio row. Returns the new instance. Caller must ensure slug is unique. */
        fun create(slug: String, name: String): ManagedPortfolio = transaction {
            val maxSeqOrder = PortfoliosTable.select(PortfoliosTable.seqOrder)
                .maxOfOrNull { it[PortfoliosTable.seqOrder] } ?: 0.0
            val newSeqOrder = maxSeqOrder + 1.0
            val newId = PortfoliosTable.insert {
                it[PortfoliosTable.slug] = slug
                it[PortfoliosTable.name] = name
                it[PortfoliosTable.seqOrder] = newSeqOrder
            } get PortfoliosTable.id
            ManagedPortfolio(newId, slug, name, newSeqOrder)
        }

        /** Returns the first (default) portfolio — the one with the lowest seq_order. */
        fun getDefault(): ManagedPortfolio = transaction {
            PortfoliosTable.selectAll()
                .orderBy(PortfoliosTable.seqOrder to SortOrder.ASC, PortfoliosTable.id to SortOrder.ASC)
                .limit(1)
                .single()
                .let { ManagedPortfolio(it[PortfoliosTable.id], it[PortfoliosTable.slug], it[PortfoliosTable.name], it[PortfoliosTable.seqOrder]) }
        }

        /** Update seq_order for a single portfolio (drag-and-drop midpoint insertion). */
        fun moveTab(slug: String, newSeqOrder: Double) = transaction {
            PortfoliosTable.update({ PortfoliosTable.slug eq slug }) {
                it[seqOrder] = newSeqOrder
            }
        }

        /** Returns the serial id of the first (default) portfolio, used to guard against deletion. */
        fun firstSerialId(): Int = getDefault().serialId

        /**
         * Resolves a portfolio from an optional slug query parameter.
         * - slug provided → look up by slug (null if not found)
         * - slug absent   → return the default (first) portfolio
         */
        fun resolve(slug: String?): ManagedPortfolio? =
            if (slug != null) getBySlug(slug) else getDefault()
    }

    /** Rename this portfolio to [newSlug]/[newName]. Caller must ensure slug uniqueness. */
    fun rename(newSlug: String, newName: String) {
        val pid = serialId
        transaction {
            PortfoliosTable.update({ PortfoliosTable.id eq pid }) {
                it[slug] = newSlug
                it[name] = newName
            }
        }
    }

    /** Delete this portfolio and all its associated data rows. */
    fun delete() {
        val pid = serialId
        transaction {
            PortfolioBackupsTable.deleteWhere { portfolioId eq pid }
            PortfolioCfgTable.deleteWhere { portfolioId eq pid }
            CashTable.deleteWhere { portfolioId eq pid }
            PositionsTable.deleteWhere { portfolioId eq pid }
            PortfoliosTable.deleteWhere { id eq pid }
        }
    }
}

// ---------------------------------------------------------------------------
// Private parsing helpers
// ---------------------------------------------------------------------------

private fun parseLetf(raw: String): List<Pair<Double, String>>? {
    val trimmed = raw.trim().takeIf { it.isNotBlank() } ?: return null
    val tokens = trimmed.split("\\s+".toRegex())
    val components = mutableListOf<Pair<Double, String>>()
    var i = 0
    while (i + 1 < tokens.size) {
        val mult = tokens[i].toDoubleOrNull() ?: break
        components.add(mult to tokens[i + 1])
        i += 2
    }
    return components.takeIf { it.isNotEmpty() }
}

private fun parseGroups(raw: String): List<Pair<Double, String>> {
    val trimmed = raw.trim().takeIf { it.isNotBlank() } ?: return emptyList()
    return trimmed.split(";").mapNotNull { entry ->
        val t = entry.trim()
        val spaceIdx = t.indexOf(' ')
        if (spaceIdx < 0) null
        else {
            val mult = t.substring(0, spaceIdx).toDoubleOrNull() ?: return@mapNotNull null
            val name = t.substring(spaceIdx + 1).trim()
            if (name.isEmpty()) null else mult to name
        }
    }
}
