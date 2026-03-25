package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V2__AddPortfolioName : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val conn = context.connection
        conn.createStatement().use {
            it.execute("ALTER TABLE portfolios ADD COLUMN name VARCHAR(256) NOT NULL DEFAULT ''")
        }
        val slugs = mutableListOf<Pair<Int, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT id, slug FROM portfolios").use { rs ->
                while (rs.next()) slugs.add(rs.getInt(1) to rs.getString(2))
            }
        }
        conn.prepareStatement("UPDATE portfolios SET name = ? WHERE id = ?").use { ps ->
            for ((id, slug) in slugs) {
                val name = slug.split(Regex("[-_]"))
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                ps.setString(1, name)
                ps.setInt(2, id)
                ps.executeUpdate()
            }
        }
    }
}
