package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.db.CashTable
import com.portfoliohelper.service.db.PortfolioBackupsTable
import com.portfoliohelper.service.db.PortfolioCfgTable
import com.portfoliohelper.service.db.PortfoliosTable
import com.portfoliohelper.service.db.PositionsTable
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
) {
    val name: String get() = slug.replaceFirstChar { it.uppercase() }

    fun getStocks(): List<Stock> {
        val pid = serialId
        val stocks = transaction {
            PositionsTable.selectAll()
                .where { PositionsTable.portfolioId eq pid }
                .map { row ->
                    val letfComponents = parseLetf(row[PositionsTable.letf])
                    val groups = parseGroups(row[PositionsTable.groups])
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
            CashTable.selectAll()
                .where { CashTable.portfolioId eq pid }
                .map { row ->
                    CashEntry(
                        label        = row[CashTable.label],
                        currency     = row[CashTable.currency],
                        marginFlag   = row[CashTable.marginFlag],
                        amount       = row[CashTable.amount],
                        portfolioRef = row[CashTable.portfolioRef]
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

    // TWS Account shortcut
    fun getTwsAccount(): String? = getConfig("twsAccount")
    fun saveTwsAccount(account: String) = saveConfig("twsAccount", account)

    companion object {
        /** All portfolios ordered by serial id (default/oldest first). */
        fun getAll(): List<ManagedPortfolio> = transaction {
            PortfoliosTable.selectAll()
                .orderBy(PortfoliosTable.id to SortOrder.ASC)
                .map { ManagedPortfolio(it[PortfoliosTable.id], it[PortfoliosTable.slug]) }
        }

        /** The portfolio with the smallest serial id — the "default" portfolio. */
        fun getDefault(): ManagedPortfolio = transaction {
            PortfoliosTable.selectAll()
                .orderBy(PortfoliosTable.id to SortOrder.ASC)
                .limit(1)
                .single()
                .let { ManagedPortfolio(it[PortfoliosTable.id], it[PortfoliosTable.slug]) }
        }

        /** Look up a portfolio by its URL slug. Returns null if not found. */
        fun getBySlug(slug: String): ManagedPortfolio? = transaction {
            val s = slug
            PortfoliosTable.selectAll()
                .where { PortfoliosTable.slug eq s }
                .singleOrNull()
                ?.let { ManagedPortfolio(it[PortfoliosTable.id], it[PortfoliosTable.slug]) }
        }

        fun hasMultiple(): Boolean = transaction {
            PortfoliosTable.selectAll().count() > 1
        }

        /** Insert a new portfolio row. Returns the new instance. Caller must ensure slug is unique. */
        fun create(slug: String): ManagedPortfolio = transaction {
            val newId = PortfoliosTable.insert { it[PortfoliosTable.slug] = slug } get PortfoliosTable.id
            ManagedPortfolio(newId, slug)
        }

        /** Returns the serial id of the first (default) portfolio, used to guard against deletion. */
        fun firstSerialId(): Int = transaction {
            PortfoliosTable.selectAll()
                .orderBy(PortfoliosTable.id to SortOrder.ASC)
                .limit(1)
                .single()[PortfoliosTable.id]
        }
    }

    /** Rename this portfolio to a new slug derived from [newName]. Caller must ensure uniqueness. */
    fun rename(newSlug: String) {
        val pid = serialId
        transaction {
            PortfoliosTable.update({ PortfoliosTable.id eq pid }) {
                it[slug] = newSlug
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
// Private parsing helpers (mirror CsvStockReader parsing logic)
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
