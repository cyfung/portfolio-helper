package db.migration

import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class ExchangeSuffixesMigrationTest {
    @Test
    fun `migration leaves exchange suffixes absent when configuration uses application defaults`() {
        withDatabase { jdbcUrl ->
            flyway(jdbcUrl, "12").migrate()

            flyway(jdbcUrl).migrate()

            assertEquals(null, settingValue(jdbcUrl, "exchangeSuffixes"))
        }
    }

    @Test
    fun `migration appends missing exchange suffixes while preserving existing configuration`() {
        withDatabase { jdbcUrl ->
            flyway(jdbcUrl, "12").migrate()
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO global_settings(key, value) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, "exchangeSuffixes")
                    statement.setString(2, "LSE=.CUSTOM,broken-entry,lse=.lower,SBF=.PA,SBF=.duplicate")
                    statement.executeUpdate()
                }
            }

            flyway(jdbcUrl).migrate()

            assertEquals(
                "LSE=.CUSTOM,broken-entry,lse=.lower,SBF=.PA,SBF=.duplicate," + EXPECTED_MISSING_SUFFIXES,
                settingValue(jdbcUrl, "exchangeSuffixes"),
            )
        }
    }

    private fun withDatabase(block: (String) -> Unit) {
        val database = Files.createTempFile("exchange-suffixes-migration", ".db")
        try {
            block("jdbc:sqlite:${database.toAbsolutePath()}")
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

    private fun settingValue(jdbcUrl: String, key: String): String? =
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement("SELECT value FROM global_settings WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString("value") else null
                }
            }
        }

    companion object {
        private const val EXPECTED_MISSING_SUFFIXES =
            "LSEETF=.L,AEB=.AS,ENEXT.BE=.BR,BVME=.MI,BVME.ETF=.MI,IBIS=.DE,IBIS2=.DE," +
                "FWB=.F,FWB2=.F,EBS=.SW,BME=.MC,SEHK=.HK,TSEJ=.T,ASX=.AX,TSE=.TO," +
                "VENTURE=.V,SGX=.SI,OSE=.OL,SFB=.ST,CPH=.CO,HEX=.HE,TWSE=.TW,TPEX=.TWO," +
                "TRWBUKETF=.L"
    }
}
