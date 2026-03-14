package com.portfoliohelper.service.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class BundledDbTest {

    @Test
    fun `bundled app_db contains at least one portfolio`() {
        val stream = javaClass.classLoader.getResourceAsStream("data/app.db")
            ?: error("data/app.db not found in resources")

        val tmp = Files.createTempFile("app", ".db").toFile()
        try {
            stream.use { Files.copy(it, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            Database.connect("jdbc:sqlite:${tmp.absolutePath}", driver = "org.sqlite.JDBC")
            val slugs = transaction {
                PortfoliosTable.selectAll().map { it[PortfoliosTable.slug] }
            }
            assertTrue(slugs.isNotEmpty(), "Expected at least one portfolio in bundled app.db, got none")
            println("Portfolios in bundled app.db: $slugs")
        } finally {
            tmp.delete()
        }
    }
}
