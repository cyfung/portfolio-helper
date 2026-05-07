package com.portfoliohelper.service

import com.portfoliohelper.service.db.PortfoliosTable
import com.portfoliohelper.service.db.PositionsTable
import com.portfoliohelper.service.db.StockTickersTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedPortfolioTest {
    private fun withDb(block: (ManagedPortfolio, ManagedPortfolio) -> Unit) {
        val dbFile = Files.createTempFile("managed-portfolio-test", ".db").toFile()
        try {
            Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
            val portfolios = transaction {
                SchemaUtils.create(PortfoliosTable, PositionsTable, StockTickersTable)
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
}
