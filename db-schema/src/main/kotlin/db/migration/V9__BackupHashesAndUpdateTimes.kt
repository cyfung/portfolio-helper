package db.migration

import com.portfoliohelper.service.db.BackupContent
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V9__BackupHashesAndUpdateTimes : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val conn = context.connection
        addColumnIfMissing(conn, "portfolio_backups", "updated_at", "BIGINT NOT NULL DEFAULT 0")
        addColumnIfMissing(conn, "portfolio_backups", "content_hash", "VARCHAR(64) NOT NULL DEFAULT ''")

        val rows = loadRows(conn).map {
            val canonical = BackupContent.canonicalJson(it.data)
            it.copy(
                updatedAt = if (it.updatedAt > 0L) it.updatedAt else it.createdAt,
                contentHash = BackupContent.contentHash(it.data),
                canonicalData = canonical
            )
        }

        conn.prepareStatement(
            "UPDATE portfolio_backups SET updated_at = ?, content_hash = ? WHERE id = ?"
        ).use { ps ->
            for (row in rows) {
                ps.setLong(1, row.updatedAt)
                ps.setString(2, row.contentHash)
                ps.setInt(3, row.id)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        val idsToDelete = mutableSetOf<Int>()
        for (portfolioRows in rows.groupBy { it.portfolioId }.values) {
            val keptRows = mutableListOf<BackupRow>()
            for (hashRows in portfolioRows.groupBy { it.contentHash }.values) {
                for (sameRows in hashRows.groupBy { it.canonicalData }.values) {
                    val sorted = sameRows.sortedWith(compareBy<BackupRow> { it.createdAt }.thenBy { it.id })
                    val keep = sorted.first()
                    val createAt = sameRows.minOf { it.createdAt }
                    val updateAt = sameRows.maxOf { it.createdAt }
                    updateKeptRow(conn, keep.id, createAt, updateAt, keep.contentHash)
                    idsToDelete += sorted.drop(1).map { it.id }
                    keptRows += keep.copy(createdAt = createAt, updatedAt = updateAt)
                }
            }

            val keepIds = keptRows
                .sortedWith(compareByDescending<BackupRow> { it.updatedAt }.thenByDescending { it.id })
                .take(20)
                .map { it.id }
                .toSet()
            idsToDelete += keptRows.asSequence()
                .map { it.id }
                .filter { it !in keepIds }
        }

        deleteRows(conn, idsToDelete)
    }

    private fun addColumnIfMissing(conn: Connection, table: String, column: String, definition: String) {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) return
                }
            }
            stmt.execute("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun loadRows(conn: Connection): List<BackupRow> {
        val rows = mutableListOf<BackupRow>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT id, portfolio_id, created_at, updated_at, label, content_hash, data FROM portfolio_backups"
            ).use { rs ->
                while (rs.next()) {
                    rows += BackupRow(
                        id = rs.getInt("id"),
                        portfolioId = rs.getInt("portfolio_id"),
                        createdAt = rs.getLong("created_at"),
                        updatedAt = rs.getLong("updated_at"),
                        label = rs.getString("label") ?: "",
                        contentHash = rs.getString("content_hash") ?: "",
                        data = rs.getString("data") ?: "",
                        canonicalData = ""
                    )
                }
            }
        }
        return rows
    }

    private fun updateKeptRow(conn: Connection, id: Int, createdAt: Long, updatedAt: Long, contentHash: String) {
        conn.prepareStatement(
            "UPDATE portfolio_backups SET created_at = ?, updated_at = ?, content_hash = ? WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, createdAt)
            ps.setLong(2, updatedAt)
            ps.setString(3, contentHash)
            ps.setInt(4, id)
            ps.executeUpdate()
        }
    }

    private fun deleteRows(conn: Connection, ids: Set<Int>) {
        if (ids.isEmpty()) return
        conn.prepareStatement("DELETE FROM portfolio_backups WHERE id = ?").use { ps ->
            for (id in ids) {
                ps.setInt(1, id)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private data class BackupRow(
        val id: Int,
        val portfolioId: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val label: String,
        val contentHash: String,
        val data: String,
        val canonicalData: String
    )
}
