package com.portfoliohelper.service

import com.portfoliohelper.service.db.PortfoliosTable
import com.portfoliohelper.service.db.PositionsTable
import com.portfoliohelper.service.db.CashTable
import com.portfoliohelper.service.db.BackupContent
import com.portfoliohelper.service.db.PortfolioBackupsTable
import com.portfoliohelper.service.db.PortfolioCfgTable
import com.portfoliohelper.service.db.StockTickersTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagedPortfolioTest {
    private fun withDb(block: (ManagedPortfolio, ManagedPortfolio) -> Unit) {
        val dbFile = Files.createTempFile("managed-portfolio-test", ".db").toFile()
        try {
            Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
            val portfolios = transaction {
                SchemaUtils.create(PortfoliosTable, PositionsTable, StockTickersTable, CashTable, PortfolioCfgTable, PortfolioBackupsTable)
                val firstId = PortfoliosTable.insert {
                    it[slug] = "first"
                    it[name] = "First"
                } get PortfoliosTable.id
                val secondId = PortfoliosTable.insert {
                    it[slug] = "second"
                    it[name] = "Second"
                } get PortfoliosTable.id
                ManagedPortfolio(firstId, "first", "First") to
                    ManagedPortfolio(secondId, "second", "Second")
            }
            block(portfolios.first, portfolios.second)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `adding existing ticker to another portfolio does not clear ticker metadata`() = withDb { first, second ->
        transaction {
            first.replacePositions(listOf(BackupStock("SSO", 10.0, 50.0, "2 SPY", "1 Equity")))
            second.replacePositions(listOf(BackupStock("SSO", 1.0, 0.0)))

            val row = StockTickersTable.selectAll()
                .where { StockTickersTable.symbol eq "SSO" }
                .single()
            assertEquals("2 SPY", row[StockTickersTable.letf])
            assertEquals("1 Equity", row[StockTickersTable.groups])
        }
    }

    @Test
    fun `blank metadata still clears an existing portfolio ticker`() = withDb { first, _ ->
        transaction {
            first.replacePositions(listOf(BackupStock("SSO", 10.0, 50.0, "2 SPY", "1 Equity")))
            first.replacePositions(listOf(BackupStock("SSO", 10.0, 50.0)))

            val row = StockTickersTable.selectAll()
                .where { StockTickersTable.symbol eq "SSO" }
                .single()
            assertEquals("", row[StockTickersTable.letf])
            assertEquals("", row[StockTickersTable.groups])
        }
    }

    @Test
    fun `duplicate rows for the same ticker prefer non-blank metadata`() = withDb { first, _ ->
        transaction {
            first.replacePositions(
                listOf(
                    BackupStock("SSO", 10.0, 50.0, "2 SPY", "1 Equity"),
                    BackupStock("SSO", 5.0, 10.0),
                )
            )

            val ticker = StockTickersTable.selectAll()
                .where { StockTickersTable.symbol eq "SSO" }
                .single()
            val position = PositionsTable.selectAll()
                .where { (PositionsTable.portfolioId eq first.serialId) and (PositionsTable.symbol eq "SSO") }
                .single()
            assertEquals("2 SPY", ticker[StockTickersTable.letf])
            assertEquals("1 Equity", ticker[StockTickersTable.groups])
            assertEquals(15.0, position[PositionsTable.amount])
            assertEquals(60.0, position[PositionsTable.targetWeight])
        }
    }

    @Test
    fun `restoring db backup keeps missing current ticker with zero amount and zero target weight`() = withDb { first, _ ->
        transaction {
            first.replacePositions(listOf(BackupStock("SSO", 10.0, 100.0, "2 SPY", "1 Equity")))
        }
        first.saveConfig("dividendStartDate", "2026-01-15")
        BackupService.saveToDb(first, force = true)
        val backupId = BackupService.listDbBackups(first).single().id
        val exportedJson = BackupService.exportJson(first)
        assertTrue("\"letf\":\"2 SPY\"" in exportedJson)
        assertTrue("\"groups\":\"1 Equity\"" in exportedJson)

        transaction {
            val oldBackupJson = exportedJson
                .replace("\"letf\":\"2 SPY\"", "\"letf\":\"9 BAD\"")
                .replace("\"groups\":\"1 Equity\"", "\"groups\":\"9 Bad\"")
            assertTrue(oldBackupJson != exportedJson)
            PortfolioBackupsTable.update({ PortfolioBackupsTable.id eq backupId }) {
                it[data] = oldBackupJson
            }
        }

        first.saveConfig("dividendStartDate", "2026-02-20")
        transaction {
            first.replacePositions(
                listOf(
                    BackupStock("SSO", 20.0, 0.0, "2 SPY", "1 Equity"),
                    BackupStock("UPRO", 3.0, 100.0, "3 SPY", "1 Equity"),
                )
            )
        }

        BackupService.restoreFromDb(first, backupId)

        transaction {
            val restored = PositionsTable.selectAll()
                .where { PositionsTable.portfolioId eq first.serialId }
                .associateBy { it[PositionsTable.symbol] }
            assertEquals(10.0, restored.getValue("SSO")[PositionsTable.amount])
            assertEquals(100.0, restored.getValue("SSO")[PositionsTable.targetWeight])
            assertEquals(0.0, restored.getValue("UPRO")[PositionsTable.amount])
            assertEquals(0.0, restored.getValue("UPRO")[PositionsTable.targetWeight])
            assertEquals(100.0, restored.values.sumOf { it[PositionsTable.targetWeight] })
            val dividendStartDate = PortfolioCfgTable.selectAll()
                .where { (PortfolioCfgTable.portfolioId eq first.serialId) and (PortfolioCfgTable.cfgKey eq "dividendStartDate") }
                .single()
                .get(PortfolioCfgTable.cfgValue)
            assertEquals("2026-01-15", dividendStartDate)

            val ssoTicker = StockTickersTable.selectAll()
                .where { StockTickersTable.symbol eq "SSO" }
                .single()
            assertEquals("9 BAD", ssoTicker[StockTickersTable.letf])
            assertEquals("9 Bad", ssoTicker[StockTickersTable.groups])

            val uproTicker = StockTickersTable.selectAll()
                .where { StockTickersTable.symbol eq "UPRO" }
                .single()
            assertEquals("3 SPY", uproTicker[StockTickersTable.letf])
            assertEquals("1 Equity", uproTicker[StockTickersTable.groups])
        }
    }

    @Test
    fun `saving identical db backup updates timestamp instead of inserting row`() = withDb { first, _ ->
        transaction {
            first.replacePositions(listOf(BackupStock("SSO", 10.0, 50.0, "2 SPY", "1 Equity")))
        }
        BackupService.saveToDb(first, force = true)
        transaction {
            PortfolioBackupsTable.update({ PortfolioBackupsTable.portfolioId eq first.serialId }) {
                it[createdAt] = 100L
                it[updatedAt] = 100L
            }
        }

        BackupService.saveToDb(first, force = true)

        val entries = BackupService.listDbBackups(first)
        assertEquals(1, entries.size)
        assertEquals(100L, entries.single().createdAt)
        assertTrue(entries.single().updatedAt > 100L)
    }

    @Test
    fun `matching hash still requires full backup payload compare`() = withDb { first, _ ->
        transaction {
            first.replacePositions(listOf(BackupStock("SSO", 10.0, 50.0, "2 SPY", "1 Equity")))
        }
        val currentJson = BackupService.exportJson(first)
        val differentJson = currentJson.replace("\"amount\":10.0", "\"amount\":99.0")

        transaction {
            PortfolioBackupsTable.insert {
                it[portfolioId] = first.serialId
                it[createdAt] = 100L
                it[updatedAt] = 100L
                it[label] = ""
                it[contentHash] = BackupContent.contentHash(currentJson)
                it[data] = differentJson
            }
        }

        BackupService.saveToDb(first, force = true)

        transaction {
            assertEquals(
                2,
                PortfolioBackupsTable.selectAll()
                    .where { PortfolioBackupsTable.portfolioId eq first.serialId }
                    .count()
                    .toInt()
            )
        }
    }

    @Test
    fun `db backups keep only twenty most recently updated records`() = withDb { first, _ ->
        transaction { PortfolioBackupsTable.deleteAll() }

        repeat(22) { idx ->
            transaction {
                first.replacePositions(listOf(BackupStock("SSO", idx.toDouble(), 50.0, "2 SPY", "1 Equity")))
            }
            BackupService.saveToDb(first, force = true)
        }

        assertEquals(20, BackupService.listDbBackups(first).size)
        transaction {
            assertEquals(
                20,
                PortfolioBackupsTable.selectAll()
                    .where { PortfolioBackupsTable.portfolioId eq first.serialId }
                    .count()
                    .toInt()
            )
        }
    }
}
