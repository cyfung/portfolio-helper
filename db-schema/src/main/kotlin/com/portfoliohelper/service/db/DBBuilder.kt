package com.portfoliohelper.service.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("DBBuilder")

fun main(args: Array<String>) {
    val outPath = args.getOrNull(0) ?: "./app_new.db"
    val outFile = File(outPath)
    if (outFile.exists()) outFile.delete()
    newDB(outPath)
}

fun newDB(outPath: String) {
    Database.connect("jdbc:sqlite:$outPath", driver = "org.sqlite.JDBC")

    transaction {
        SchemaUtils.create(*AppDatabase.allTables)

        val mainId = PortfoliosTable.insert { it[slug] = "main" } get PortfoliosTable.id

        PositionsTable.insert {
            it[portfolioId]  = mainId
            it[symbol]       = "VTI"
            it[amount]       = 100.0
            it[targetWeight] = 60.0
        }
        PositionsTable.insert {
            it[portfolioId]  = mainId
            it[symbol]       = "VXUS"
            it[amount]       = 260.0
            it[targetWeight] = 40.0
        }

        CashTable.insert {
            it[portfolioId] = mainId
            it[label]       = "Cash"
            it[currency]    = "HKD"
            it[marginFlag]  = true
            it[amount]      = 1234.0
        }
        CashTable.insert {
            it[portfolioId] = mainId
            it[label]       = "Cash"
            it[currency]    = "USD"
            it[marginFlag]  = true
            it[amount]      = 123.0
        }
    }

    logger.info("Created $outPath")
}
