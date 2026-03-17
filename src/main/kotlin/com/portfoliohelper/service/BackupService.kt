package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.service.db.CashTable
import com.portfoliohelper.service.db.PortfolioBackupsTable
import com.portfoliohelper.service.db.PortfoliosTable
import com.portfoliohelper.service.db.PositionsTable
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.util.appJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.*
import java.util.zip.*

@Serializable
data class AllSyncResponse(val portfolios: Map<Int, BackupRoot>)

@Serializable
data class BackupRoot(
    val version: Int = 1,
    val portfolioSlug: String,
    val stocks: List<BackupStock>,
    val cash: List<BackupCash>
)

@Serializable
data class BackupStock(
    val symbol: String,
    val amount: Double,
    val targetWeight: Double = 0.0,
    val letf: String = "",
    val groups: String = ""
)

@Serializable
data class BackupCash(
    val key: String,
    val label: String,
    val currency: String,
    val marginFlag: Boolean,
    val amount: Double,
    val portfolioRef: String? = null,
    val snapshotUsd: Double? = null   // P entries only; ignored on restore/import
)

data class DbBackupEntry(val id: Int, val createdAt: Long, val label: String)

data class ImportResult(
    val stocks: List<Map<String, Any>>?,      // null = not present in file
    val cashKeys: List<Map<String, String>>?, // [{key, value}, ...]; null = not present
    val error: String?
)

object BackupService {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)
    private val lastSavedHash = ConcurrentHashMap<Int, String>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            performBackups()
            scheduleDaily(scope)
        }
    }

    private fun performBackups() {
        ManagedPortfolio.getAll().forEach { saveToDb(it, label = "") }
    }

    fun saveToDb(
        portfolio: ManagedPortfolio,
        label: String = "",
        resolveUsd: Boolean = false
    ) {
        // Hash computed without snapshotUsd so P-entry price fluctuations don't trigger spurious backups
        val hashJson = serializeToJson(portfolio, resolveUsd = false)
        val hash = sha256(hashJson)
        // On first call after startup, seed the cache from the most recent DB backup so we don't
        // create a duplicate row just because the in-memory map was empty.
        val lastHash =
            lastSavedHash.getOrPut(portfolio.serialId) { loadLastHashFromDb(portfolio.serialId) }
        if (lastHash == hash) {
            logger.debug("No changes for '${portfolio.slug}'${if (label.isNotEmpty()) " [$label]" else ""}, backup skipped")
            return
        }
        val storeJson = if (resolveUsd) serializeToJson(portfolio, true) else hashJson
        val nowMillis = System.currentTimeMillis()
        transaction {
            PortfolioBackupsTable.insert {
                it[portfolioId] = portfolio.serialId
                it[createdAt] = nowMillis
                it[PortfolioBackupsTable.label] = label
                it[data] = storeJson
            }
        }
        lastSavedHash[portfolio.serialId] = hash
        logger.info("DB backup saved for '${portfolio.slug}'${if (label.isNotEmpty()) " [$label]" else ""}")
    }

    fun deleteFromDb(portfolio: ManagedPortfolio, backupId: Int) {
        transaction {
            PortfolioBackupsTable.deleteWhere {
                (PortfolioBackupsTable.id eq backupId) and
                        (PortfolioBackupsTable.portfolioId eq portfolio.serialId)
            }
        }
        logger.info("Deleted DB backup $backupId for '${portfolio.slug}'")
    }

    fun deleteAllFromDb(portfolio: ManagedPortfolio) {
        transaction {
            PortfolioBackupsTable.deleteWhere { PortfolioBackupsTable.portfolioId eq portfolio.serialId }
        }
        lastSavedHash.remove(portfolio.serialId)
        logger.info("Deleted all DB backups for '${portfolio.slug}'")
    }

    fun listDbBackups(portfolio: ManagedPortfolio): List<DbBackupEntry> = transaction {
        PortfolioBackupsTable.selectAll()
            .where { PortfolioBackupsTable.portfolioId eq portfolio.serialId }
            .orderBy(PortfolioBackupsTable.createdAt, SortOrder.DESC)
            .limit(50)
            .map { row ->
                DbBackupEntry(
                    id = row[PortfolioBackupsTable.id],
                    createdAt = row[PortfolioBackupsTable.createdAt],
                    label = row[PortfolioBackupsTable.label]
                )
            }
    }

    fun restoreFromDb(portfolio: ManagedPortfolio, backupId: Int) {
        val json = transaction {
            PortfolioBackupsTable.selectAll()
                .where {
                    (PortfolioBackupsTable.id eq backupId) and
                            (PortfolioBackupsTable.portfolioId eq portfolio.serialId)
                }
                .firstOrNull()?.get(PortfolioBackupsTable.data)
        }
            ?: throw IllegalArgumentException("Backup $backupId not found for portfolio '${portfolio.slug}'")

        val root = appJson.decodeFromString<BackupRoot>(json)
        val cashEntries = root.cash.map { c -> CashEntry(c.label, c.currency, c.marginFlag, c.amount, c.portfolioRef) }
        transaction {
            portfolio.replacePositions(root.stocks)
            portfolio.replaceCash(cashEntries)
        }
        logger.info("Restored '${portfolio.slug}' from DB backup $backupId")
    }

    fun exportJson(portfolio: ManagedPortfolio): String =
        serializeToJson(portfolio, true)

    fun exportRoot(portfolio: ManagedPortfolio): BackupRoot =
        root(portfolio, true)

    fun parseImportFile(bytes: ByteArray, filename: String): ImportResult {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".json") -> parseJsonImport(bytes)
            lower.endsWith(".csv") -> parseCsvImport(bytes)
            lower.endsWith(".txt") -> parseTxtImport(bytes)
            lower.endsWith(".zip") -> parseZipImport(bytes)
            else -> ImportResult(null, null, "Unsupported file type: $filename")
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /** Loads the most recent backup from DB and returns a hash of its content without snapshotUsd.
     *  Returns an empty string if no backup exists yet (guaranteeing a first-ever backup is saved). */
    private fun loadLastHashFromDb(portfolioSerialId: Int): String {
        val data = transaction {
            PortfolioBackupsTable.selectAll()
                .where { PortfolioBackupsTable.portfolioId eq portfolioSerialId }
                .orderBy(PortfolioBackupsTable.createdAt, SortOrder.DESC)
                .limit(1)
                .firstOrNull()?.get(PortfolioBackupsTable.data)
        } ?: return ""
        return try {
            val root = appJson.decodeFromString<BackupRoot>(data)
            // snapshotUsd is already excluded from hash since serializeToJson(resolveUsd=null) doesn't set it
            val stripped = root.copy(cash = root.cash.map { it.copy(snapshotUsd = null) })
            sha256(appJson.encodeToString(stripped))
        } catch (_: Exception) {
            "" // unparseable old backup → treat as no prior backup
        }
    }

    private fun serializeToJson(
        portfolio: ManagedPortfolio,
        resolveUsd: Boolean = false
    ): String {
        val root = root(portfolio, resolveUsd)
        return appJson.encodeToString(root)
    }

    private fun resolveUsd(e: CashEntry): Double? {
        return when (e.currency) {
            "USD" -> e.amount
            "P" -> {
                val ref = ManagedPortfolio.getBySlug(e.portfolioRef ?: return null)
                    ?: return null
                e.amount * YahooMarketDataService.getCurrentPortfolio(ref.getStocks()).stockGrossValue
            }

            else -> YahooMarketDataService.getQuote("${e.currency}USD=X")
                ?.let { q ->
                    (q.regularMarketPrice ?: q.previousClose
                    ?: return null) * e.amount
                }
        }
    }

    private fun root(
        portfolio: ManagedPortfolio,
        resolveUsd: Boolean = false
    ): BackupRoot {
        val pid = portfolio.serialId
        val stocks = transaction {
            PositionsTable.selectAll()
                .where { PositionsTable.portfolioId eq pid }
                .map { row ->
                    BackupStock(
                        symbol = row[PositionsTable.symbol],
                        amount = row[PositionsTable.amount],
                        targetWeight = row[PositionsTable.targetWeight],
                        letf = row[PositionsTable.letf],
                        groups = row[PositionsTable.groups]
                    )
                }
        }
        val cashEntries = transaction {
            CashTable.leftJoin(PortfoliosTable, { CashTable.portfolioRefId }, { PortfoliosTable.id })
                .selectAll().where { CashTable.portfolioId eq pid }
                .map { row ->
                    CashEntry(
                        label = row[CashTable.label],
                        currency = row[CashTable.currency],
                        marginFlag = row[CashTable.marginFlag],
                        amount = row[CashTable.amount],
                        portfolioRef = row.getOrNull(PortfoliosTable.slug)
                    )
                }
        }
        val cashBackup = cashEntries.map { e ->
            val snapshotUsd = if (e.currency == "P" && resolveUsd) resolveUsd(e) else null
            BackupCash(
                key = e.key,
                label = e.label,
                currency = e.currency,
                marginFlag = e.marginFlag,
                amount = e.amount,
                portfolioRef = e.portfolioRef,
                snapshotUsd = snapshotUsd
            )
        }
        val root = BackupRoot(
            portfolioSlug = portfolio.slug,
            stocks = stocks,
            cash = cashBackup
        )
        return root
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun parseJsonImport(bytes: ByteArray): ImportResult {
        return try {
            val root = appJson.decodeFromString<BackupRoot>(String(bytes))
            val stocks = root.stocks.map { s ->
                mapOf<String, Any>(
                    "symbol" to s.symbol, "amount" to s.amount,
                    "targetWeight" to s.targetWeight, "letf" to s.letf, "groups" to s.groups
                )
            }
            val cash = root.cash.map { c ->
                val value = if (c.currency == "P") {
                    (if (c.amount < 0) "-" else "") + (c.portfolioRef ?: "")
                } else c.amount.toString()
                mapOf("key" to c.key, "value" to value)
            }
            if (stocks.isEmpty() && cash.isEmpty())
                ImportResult(null, null, "JSON backup has no stocks or cash entries")
            else
                ImportResult(stocks, cash, null)
        } catch (e: Exception) {
            ImportResult(null, null, "Invalid JSON: ${e.message}")
        }
    }

    private fun parseCsvImport(bytes: ByteArray): ImportResult {
        return try {
            val reader = InputStreamReader(ByteArrayInputStream(bytes))
            val csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
            val rows = mutableListOf<Map<String, Any>>()
            CSVParser(reader, csvFormat).use { parser ->
                for (record in parser) {
                    try {
                        val symbol = record.get("stock_label").trim()
                        val amount = record.get("amount").toDouble()
                        if (symbol.isEmpty()) continue
                        val targetWeight = try {
                            record.get("target_weight")?.toDoubleOrNull() ?: 0.0
                        } catch (_: Exception) {
                            0.0
                        }
                        val letf = try {
                            record.get("letf")?.trim() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        val groups = try {
                            record.get("groups")?.trim() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        rows.add(
                            mapOf(
                                "symbol" to symbol as Any, "amount" to amount,
                                "targetWeight" to targetWeight, "letf" to letf, "groups" to groups
                            )
                        )
                    } catch (_: Exception) { /* skip bad rows */
                    }
                }
            }
            if (rows.isEmpty())
                ImportResult(
                    null,
                    null,
                    "CSV has no valid rows (need 'stock_label' and 'amount' columns)"
                )
            else
                ImportResult(rows, null, null)
        } catch (e: Exception) {
            ImportResult(null, null, "Invalid CSV: ${e.message}")
        }
    }

    private fun parseTxtImport(bytes: ByteArray): ImportResult {
        return try {
            val entries = mutableListOf<Map<String, String>>()
            String(bytes).lines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eqIdx = line.indexOf('=')
                if (eqIdx < 0) return@forEach
                val key = line.substring(0, eqIdx).trim()
                val value = line.substring(eqIdx + 1).trim()
                if (key.split(".").size < 2) return@forEach
                // Accept numeric values or non-empty portfolio refs
                if (value.toDoubleOrNull() != null || value.trimStart('+', '-').isNotEmpty()) {
                    entries.add(mapOf("key" to key, "value" to value))
                }
            }
            if (entries.isEmpty())
                ImportResult(null, null, "TXT file has no valid cash entries")
            else
                ImportResult(null, entries, null)
        } catch (e: Exception) {
            ImportResult(null, null, "Invalid TXT: ${e.message}")
        }
    }

    private fun parseZipImport(bytes: ByteArray): ImportResult {
        return try {
            var csvBytes: ByteArray? = null
            var txtBytes: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "stocks.csv" -> csvBytes = zis.readBytes()
                        "cash.txt" -> txtBytes = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val csvResult = csvBytes?.let { parseCsvImport(it) }
            val txtResult = txtBytes?.let { parseTxtImport(it) }
            if (csvResult?.error != null) return ImportResult(
                null,
                null,
                "stocks.csv in ZIP: ${csvResult.error}"
            )
            if (txtResult?.error != null) return ImportResult(
                null,
                null,
                "cash.txt in ZIP: ${txtResult.error}"
            )
            val stocks = csvResult?.stocks
            val cash = txtResult?.cashKeys
            if (stocks == null && cash == null)
                ImportResult(null, null, "ZIP contains no stocks.csv or cash.txt")
            else
                ImportResult(stocks, cash, null)
        } catch (e: Exception) {
            ImportResult(null, null, "Invalid ZIP: ${e.message}")
        }
    }

    private fun scheduleDaily(scope: CoroutineScope) {
        val now = LocalDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val delayMs = ChronoUnit.MILLIS.between(now, nextMidnight)
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            performBackups()
            scheduleDaily(scope)
        }
    }
}
