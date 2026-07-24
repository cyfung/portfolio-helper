package db.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PortfolioRowsMigrationTest {
    @Test
    fun `flyway migrates saved and autosaved portfolio configurations to tagged rows`() {
        val database = Files.createTempFile("portfolio-rows-migration", ".db")
        val jdbcUrl = "jdbc:sqlite:${database.toAbsolutePath()}"
        try {
            flyway(jdbcUrl, "10").migrate()
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO saved_backtest_portfolios(name, config, created_at) VALUES (?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, "Nested")
                    statement.setString(2, SAVED_CONFIGURATION)
                    statement.setLong(3, 1L)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO global_settings(key, value) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, "backtest.portfolios")
                    statement.setString(2, AUTOSAVED_CONFIGURATIONS)
                    statement.executeUpdate()
                }
            }

            flyway(jdbcUrl).migrate()

            DriverManager.getConnection(jdbcUrl).use { connection ->
                val saved = connection.prepareStatement(
                    "SELECT config FROM saved_backtest_portfolios WHERE name = ?"
                ).use { statement ->
                    statement.setString(1, "Nested")
                    statement.executeQuery().use { result ->
                        result.next()
                        JSON.parseToJsonElement(result.getString(1)).jsonObject
                    }
                }
                val autosaved = connection.prepareStatement(
                    "SELECT value FROM global_settings WHERE key = ?"
                ).use { statement ->
                    statement.setString(1, "backtest.portfolios")
                    statement.executeQuery().use { result ->
                        result.next()
                        JSON.parseToJsonElement(result.getString(1)).jsonArray
                    }
                }

                assertFalse(saved.containsKey("tickers"))
                assertEquals("YEARLY", saved["rebalanceStrategy"]?.jsonPrimitive?.content)
                assertRows(saved["rows"]?.jsonArray)

                val nested = saved["portfolios"]?.jsonArray?.single()?.jsonObject
                assertNotNull(nested)
                assertFalse(nested.containsKey("tickers"))
                assertEquals("DUMMY", nested["rows"]?.jsonArray?.single()?.jsonObject?.get("instrument")?.jsonPrimitive?.content)

                assertEquals(1, autosaved.size)
                assertFalse(autosaved.single().jsonObject.containsKey("tickers"))
                assertRows(autosaved.single().jsonObject["rows"]?.jsonArray)
            }
        } finally {
            Files.deleteIfExists(database)
        }
    }

    private fun flyway(jdbcUrl: String, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, "", "")
            .locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun assertRows(rows: JsonArray?) {
        assertNotNull(rows)
        assertEquals(
            listOf("HOLDING", "PORTFOLIO_REFERENCE", "SWAP", "SWAP", "SWAP"),
            rows.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
        assertEquals("SPY R=Q", rows[0].jsonObject["instrument"]?.jsonPrimitive?.content)
        assertEquals("NET_100", rows[1].jsonObject["normalizationMode"]?.jsonPrimitive?.content)
        assertEquals("AMOUNT", rows[2].jsonObject["transfer"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)
        assertEquals("TLT", rows[2].jsonObject["legs"]?.jsonArray?.single()?.jsonObject?.get("instrument")?.jsonPrimitive?.content)
        assertEquals("ALL_REMAINING", rows[3].jsonObject["transfer"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)
        assertEquals(2.0, rows[3].jsonObject["legs"]?.jsonArray?.single()?.jsonObject?.get("multiplier")?.jsonPrimitive?.doubleOrNull)
        assertEquals(
            "(0.5 SPY 0.5 TLT)",
            rows[4].jsonObject["source"]?.jsonPrimitive?.content,
        )
        assertEquals(
            listOf(-1.5, 2.0),
            rows[4].jsonObject["legs"]?.jsonArray?.map {
                it.jsonObject["multiplier"]?.jsonPrimitive?.doubleOrNull
            },
        )
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val LEGACY_ROWS =
            """"tickers":[{"id":"holding","ticker":" spy   R=Q ","weight":60},{"id":"reference","ticker":"Child","weight":40,"isPortfolioRef":true},{"id":"numeric-swap","ticker":"SWAP(SPY, TLT)","weight":10},{"id":"remaining-swap","ticker":"SPY > TLT #2","weight":"*"},{"id":"complex-swap","ticker":"(0.5 spy 0.5 tlt) > -1.5 (0.5 gld 0.5 ief) + (0.5 qqq 0.5 spy) #2","weight":5}]"""
        private const val SAVED_CONFIGURATION =
            """{"rebalanceStrategy":"YEARLY",$LEGACY_ROWS,"portfolios":[{"tickers":[{"id":"dummy","ticker":"DUMMY","weight":25}]}]}"""
        private const val AUTOSAVED_CONFIGURATIONS =
            """[{"label":"Autosaved",$LEGACY_ROWS}]"""
    }
}
