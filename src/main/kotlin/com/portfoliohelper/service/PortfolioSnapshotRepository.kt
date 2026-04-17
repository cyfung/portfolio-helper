package com.portfoliohelper.service

import com.portfoliohelper.service.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest

object PortfolioSnapshotRepository {

    data class SnapshotRow(
        val id: Int,
        val portfolioId: Int,
        val date: String,
        val netLiqValue: Double,
        val cashBase: Double,
        val stockBase: Double,
        val interestAccrualsBase: Double,
        val contentHash: String
    )

    data class FullSnapshot(
        val header: SnapshotRow,
        val positions: List<PositionEntry>,
        val cashBalances: List<CashBalanceEntry>,
        val interestAccruals: List<InterestAccrualEntry>,
        val cashFlows: List<CashFlowEntry>
    )

    /**
     * Ingests parsed daily snapshots for [portfolioId].
     * Skips days whose content hash matches the preceding day (handles weekends/holidays).
     * Returns the number of rows written.
     */
    fun ingest(portfolioId: Int, snapshots: List<DaySnapshot>): Int {
        var written = 0
        transaction {
            val sorted = snapshots.sortedBy { it.date }
            var prevHash: String? = sorted.firstOrNull()?.date?.let { lastHashBefore(portfolioId, it) }

            for (snap in sorted) {
                val hash = contentHash(snap)
                if (hash == prevHash) { prevHash = hash; continue }

                val snapshotId = upsertHeader(portfolioId, snap, hash)
                replaceChildren(snapshotId, snap)
                prevHash = hash
                written++
            }
        }
        return written
    }

    /**
     * Returns snapshots within [from]..[to] inclusive, plus the single snapshot before [from].
     */
    fun getSnapshots(portfolioId: Int, from: String, to: String): List<FullSnapshot> = transaction {
        val allUpToTo = PortfolioSnapshotsTable
            .selectAll()
            .where { (PortfolioSnapshotsTable.portfolioId eq portfolioId) and (PortfolioSnapshotsTable.snapshotDate lessEq to) }
            .orderBy(PortfolioSnapshotsTable.snapshotDate, SortOrder.ASC)
            .map { toRow(it) }

        val preRange = allUpToTo.lastOrNull { it.date < from }
        val inRange  = allUpToTo.filter { it.date >= from }
        (listOfNotNull(preRange) + inRange).map { header ->
            val (pos, cash, interest, flows) = loadChildren(header.id)
            FullSnapshot(header, pos, cash, interest, flows)
        }
    }

    /** All snapshot dates for [portfolioId], ascending. */
    fun getDates(portfolioId: Int): List<String> = transaction {
        PortfolioSnapshotsTable
            .select(PortfolioSnapshotsTable.snapshotDate)
            .where { PortfolioSnapshotsTable.portfolioId eq portfolioId }
            .orderBy(PortfolioSnapshotsTable.snapshotDate, SortOrder.ASC)
            .map { it[PortfolioSnapshotsTable.snapshotDate] }
    }

    // -------------------------------------------------------------------------

    private fun upsertHeader(portfolioId: Int, snap: DaySnapshot, hash: String): Int {
        val T = PortfolioSnapshotsTable
        val existing = T.select(T.id)
            .where { (T.portfolioId eq portfolioId) and (T.snapshotDate eq snap.date) }
            .firstOrNull()?.get(T.id)

        return if (existing != null) {
            T.update({ (T.portfolioId eq portfolioId) and (T.snapshotDate eq snap.date) }) {
                it[netLiqValue]          = snap.netLiq
                it[cashBase]             = snap.cashBase
                it[stockBase]            = snap.stockBase
                it[interestAccrualsBase] = snap.interestAccrualsBase
                it[contentHash]          = hash
                it[createdAt]            = System.currentTimeMillis()
            }
            existing
        } else {
            T.insert {
                it[T.portfolioId]         = portfolioId
                it[snapshotDate]          = snap.date
                it[netLiqValue]           = snap.netLiq
                it[cashBase]              = snap.cashBase
                it[stockBase]             = snap.stockBase
                it[interestAccrualsBase]  = snap.interestAccrualsBase
                it[contentHash]           = hash
                it[createdAt]             = System.currentTimeMillis()
            }[T.id]
        }
    }

    private fun lastHashBefore(portfolioId: Int, date: String): String? {
        val T = PortfolioSnapshotsTable
        return T.select(T.contentHash)
            .where { (T.portfolioId eq portfolioId) and (T.snapshotDate less date) }
            .orderBy(T.snapshotDate, SortOrder.DESC)
            .firstOrNull()
            ?.get(T.contentHash)
    }

    private fun contentHash(snap: DaySnapshot): String {
        val input = buildString {
            snap.positions.sortedBy { it.symbol }.forEach { append("${it.symbol}:${it.position}:${it.markPrice};") }
            snap.cashBalances.sortedBy { it.currency }.forEach { append("${it.currency}:${it.amount};") }
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun toRow(r: ResultRow) = SnapshotRow(
        id                   = r[PortfolioSnapshotsTable.id],
        portfolioId          = r[PortfolioSnapshotsTable.portfolioId],
        date                 = r[PortfolioSnapshotsTable.snapshotDate],
        netLiqValue          = r[PortfolioSnapshotsTable.netLiqValue],
        cashBase             = r[PortfolioSnapshotsTable.cashBase],
        stockBase            = r[PortfolioSnapshotsTable.stockBase],
        interestAccrualsBase = r[PortfolioSnapshotsTable.interestAccrualsBase],
        contentHash          = r[PortfolioSnapshotsTable.contentHash]
    )

    private data class Children(
        val positions: List<PositionEntry>,
        val cashBalances: List<CashBalanceEntry>,
        val interestAccruals: List<InterestAccrualEntry>,
        val cashFlows: List<CashFlowEntry>
    )

    private fun loadChildren(snapshotId: Int): Children {
        val positions = SnapshotPositionsTable
            .selectAll().where { SnapshotPositionsTable.snapshotId eq snapshotId }
            .map { PositionEntry(it[SnapshotPositionsTable.symbol], it[SnapshotPositionsTable.currency], it[SnapshotPositionsTable.position], it[SnapshotPositionsTable.markPrice], it[SnapshotPositionsTable.positionValue]) }

        val cash = SnapshotCashBalancesTable
            .selectAll().where { SnapshotCashBalancesTable.snapshotId eq snapshotId }
            .map { CashBalanceEntry(it[SnapshotCashBalancesTable.currency], it[SnapshotCashBalancesTable.amount]) }

        val interest = SnapshotInterestAccrualsTable
            .selectAll().where { SnapshotInterestAccrualsTable.snapshotId eq snapshotId }
            .map { InterestAccrualEntry(it[SnapshotInterestAccrualsTable.currency], it[SnapshotInterestAccrualsTable.endingAccrualBalance]) }

        val flows = SnapshotCashFlowsTable
            .selectAll().where { SnapshotCashFlowsTable.snapshotId eq snapshotId }
            .map { CashFlowEntry(it[SnapshotCashFlowsTable.currency], it[SnapshotCashFlowsTable.fxRateToBase], it[SnapshotCashFlowsTable.amount], it[SnapshotCashFlowsTable.type]) }

        return Children(positions, cash, interest, flows)
    }

    private fun replaceChildren(snapshotId: Int, snap: DaySnapshot) {
        val sid = snapshotId
        SnapshotPositionsTable.deleteWhere        { SnapshotPositionsTable.snapshotId eq sid }
        SnapshotCashBalancesTable.deleteWhere     { SnapshotCashBalancesTable.snapshotId eq sid }
        SnapshotInterestAccrualsTable.deleteWhere { SnapshotInterestAccrualsTable.snapshotId eq sid }
        SnapshotCashFlowsTable.deleteWhere        { SnapshotCashFlowsTable.snapshotId eq sid }

        for (p in snap.positions) SnapshotPositionsTable.insert {
            it[SnapshotPositionsTable.snapshotId]    = sid
            it[SnapshotPositionsTable.symbol]        = p.symbol
            it[SnapshotPositionsTable.currency]      = p.currency
            it[SnapshotPositionsTable.position]      = p.position
            it[SnapshotPositionsTable.markPrice]     = p.markPrice
            it[SnapshotPositionsTable.positionValue] = p.positionValue
        }
        for (c in snap.cashBalances) SnapshotCashBalancesTable.insert {
            it[SnapshotCashBalancesTable.snapshotId] = sid
            it[SnapshotCashBalancesTable.currency]   = c.currency
            it[SnapshotCashBalancesTable.amount]     = c.amount
        }
        for (ia in snap.interestAccruals) SnapshotInterestAccrualsTable.insert {
            it[SnapshotInterestAccrualsTable.snapshotId]           = sid
            it[SnapshotInterestAccrualsTable.currency]             = ia.currency
            it[SnapshotInterestAccrualsTable.endingAccrualBalance] = ia.endingAccrualBalance
        }
        for (cf in snap.cashFlows) SnapshotCashFlowsTable.insert {
            it[SnapshotCashFlowsTable.snapshotId]   = sid
            it[SnapshotCashFlowsTable.currency]     = cf.currency
            it[SnapshotCashFlowsTable.fxRateToBase] = cf.fxRateToBase
            it[SnapshotCashFlowsTable.amount]       = cf.amount
            it[SnapshotCashFlowsTable.type]         = cf.type
        }
    }
}
