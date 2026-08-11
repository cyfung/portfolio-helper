package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V13__ExpandExchangeSuffixes : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val currentValue = context.connection.prepareStatement(
            "SELECT value FROM global_settings WHERE key = ?"
        ).use { statement ->
            statement.setString(1, KEY)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString("value") else null
            }
        } ?: return

        val existingKeys = currentValue.split(',').mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator < 0) null else entry.substring(0, separator).trim()
        }.toSet()
        val missingMappings = DEFAULT_MAPPINGS.filter { mapping ->
            mapping.substringBefore('=') !in existingKeys
        }
        if (missingMappings.isEmpty()) return

        val separator = if (currentValue.isEmpty() || currentValue.endsWith(',')) "" else ","
        val nextValue = currentValue + separator + missingMappings.joinToString(",")
        context.connection.prepareStatement(
            "UPDATE global_settings SET value = ? WHERE key = ?"
        ).use { statement ->
            statement.setString(1, nextValue)
            statement.setString(2, KEY)
            statement.executeUpdate()
        }
    }

    companion object {
        private const val KEY = "exchangeSuffixes"
        private val DEFAULT_MAPPINGS = listOf(
            "LSE=.L",
            "LSEETF=.L",
            "SBF=.PA",
            "AEB=.AS",
            "ENEXT.BE=.BR",
            "BVME=.MI",
            "BVME.ETF=.MI",
            "IBIS=.DE",
            "IBIS2=.DE",
            "FWB=.F",
            "FWB2=.F",
            "EBS=.SW",
            "BME=.MC",
            "SEHK=.HK",
            "TSEJ=.T",
            "ASX=.AX",
            "TSE=.TO",
            "VENTURE=.V",
            "SGX=.SI",
            "OSE=.OL",
            "SFB=.ST",
            "CPH=.CO",
            "HEX=.HE",
            "TWSE=.TW",
            "TPEX=.TWO",
            "TRWBUKETF=.L",
        )
    }
}
