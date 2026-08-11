package com.portfoliohelper

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun `exchange suffixes default covers supported TWS listing exchanges`() {
        val database = Files.createTempFile("app-config-defaults", ".db")
        val jdbcUrl = "jdbc:sqlite:${database.toAbsolutePath()}"
        try {
            Flyway.configure()
                .dataSource(jdbcUrl, "", "")
                .locations("classpath:db/migration")
                .load()
                .migrate()
            Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")

            assertEquals(EXPECTED_EXCHANGE_SUFFIXES, AppConfig.exchangeSuffixes)
        } finally {
            Files.deleteIfExists(database)
        }
    }

    companion object {
        private val EXPECTED_EXCHANGE_SUFFIXES = mapOf(
            "LSE" to ".L",
            "LSEETF" to ".L",
            "SBF" to ".PA",
            "AEB" to ".AS",
            "ENEXT.BE" to ".BR",
            "BVME" to ".MI",
            "BVME.ETF" to ".MI",
            "IBIS" to ".DE",
            "IBIS2" to ".DE",
            "FWB" to ".F",
            "FWB2" to ".F",
            "EBS" to ".SW",
            "BME" to ".MC",
            "SEHK" to ".HK",
            "TSEJ" to ".T",
            "ASX" to ".AX",
            "TSE" to ".TO",
            "VENTURE" to ".V",
            "SGX" to ".SI",
            "OSE" to ".OL",
            "SFB" to ".ST",
            "CPH" to ".CO",
            "HEX" to ".HE",
            "TWSE" to ".TW",
            "TPEX" to ".TWO",
            "TRWBUKETF" to ".L",
        )
    }
}
