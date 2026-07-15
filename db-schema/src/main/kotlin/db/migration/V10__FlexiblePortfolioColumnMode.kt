package db.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V10__FlexiblePortfolioColumnMode : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val conn = context.connection
        val currentValue = conn.prepareStatement("SELECT value FROM global_settings WHERE key = ?").use { ps ->
            ps.setString(1, KEY)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
        }

        val nextValue = if (currentValue.isNullOrBlank()) {
            NEW_DEFAULT
        } else {
            appendFlexibleMode(currentValue)
        }

        conn.prepareStatement(
            """
            INSERT INTO global_settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, KEY)
            ps.setString(2, nextValue)
            ps.executeUpdate()
        }
    }

    private fun appendFlexibleMode(currentValue: String): String {
        val modes = runCatching { JSON.parseToJsonElement(currentValue).jsonArray }
            .getOrNull()
            ?: return NEW_DEFAULT
        if (modes.any { it.jsonObject["id"]?.jsonPrimitive?.content == "mode-4" }) {
            return currentValue
        }
        return JsonArray(modes + JSON.parseToJsonElement(FLEXIBLE_MODE)).toString()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val KEY = "portfolioColumnModes"
        private const val FLEXIBLE_MODE =
            """{"id":"mode-4","name":"Flexible","columns":["symbol","est","mark","change","pnl","weight","flexWeight","flexRebalDollars","allocDollars","ccy"]}"""
        private const val NEW_DEFAULT =
            """[{"id":"mode-1","name":"Compact","columns":["symbol","est","mark","change","pnl","weight","allocDollars","ccy"]},{"id":"mode-2","name":"Rebalance","columns":["symbol","est","mark","change","pnl","weight","rebalDollars","allocDollars","ccy"]},{"id":"mode-3","name":"Full","columns":["symbol","qty","lastNav","est","last","mark","change","pnl","mktVal","weight","rebalQty","rebalDollars","allocQty","allocDollars","ccy"]},{"id":"mode-4","name":"Flexible","columns":["symbol","est","mark","change","pnl","weight","flexWeight","flexRebalDollars","allocDollars","ccy"]}]"""
    }
}
