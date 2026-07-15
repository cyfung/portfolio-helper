package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import com.portfoliohelper.service.db.BackupContent
import com.portfoliohelper.service.db.CashTable
import com.portfoliohelper.service.db.PortfolioCfgTable
import com.portfoliohelper.service.db.PortfolioBackupsTable
import com.portfoliohelper.service.db.PortfoliosTable
import com.portfoliohelper.service.db.PositionsTable
import com.portfoliohelper.service.db.StockTickersTable
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.*

@Serializable
data class PortfolioSyncEntry(
    val serialId: Int,
    val name: String,
    val slug: String,
    val stocks: List<BackupStock>,
    val cash: List<BackupCash>
)

@Serializable
data class AllSyncResponse(
    val portfolios: List<PortfolioSyncEntry>,
    val checksum: String,
    val tickerSettings: List<BackupTickerSettings>? = null
)

fun computeSyncChecksum(entries: List<PortfolioSyncEntry>): String {
    val lines = entries
        .flatMap { p -> p.stocks.map { "${p.slug}:${it.symbol}:${it.amount}" } }
        .sorted()
        .joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(lines.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable
data class BackupRoot(
    val version: Int = 1,
    val portfolioSlug: String,
    val stocks: List<BackupStock>,
    val cash: List<BackupCash>,
    val dividendStartDate: String? = null,
    val tickerSettings: List<BackupTickerSettings>? = null
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

@Serializable
data class BackupTickerSettings(
    val symbol: String,
    val letf: String = "",
    val groups: String = ""
)

@Serializable
data class DbBackupEntry(val id: Int, val createdAt: Long, val updatedAt: Long, val label: String)

@Serializable
data class ImportedStock(
    val symbol: String,
    val amount: Double,
    val targetWeight: Double,
    val letf: String,
    val groups: String
)

@Serializable
data class ImportedCash(val key: String, val value: String)

@Serializable
data class ImportResult(
    val stocks: List<ImportedStock>?,  // null = not present in file
    val cash: List<ImportedCash>?,     // null = not present in file
    val error: String?,
    val dividendStartDate: String? = null
)

object BackupService {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)
    private const val MAX_BACKUPS_PER_PORTFOLIO = 20

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
        resolveUsd: Boolean = false,
        force: Boolean = false
    ) {
        // Hash is a fast prefilter only. Canonical JSON is compared before deciding two backups are identical.
        val hashJson = serializeToJson(portfolio, resolveUsd = false)
        val canonicalJson = BackupContent.canonicalJson(hashJson)
        val hash = BackupContent.contentHash(hashJson)
        val storeJson = if (resolveUsd) serializeToJson(portfolio, true) else hashJson
        val nowMillis = System.currentTimeMillis()
        var updatedExisting = false
        transaction {
            val identicalRow = PortfolioBackupsTable.selectAll()
                .where {
                    (PortfolioBackupsTable.portfolioId eq portfolio.serialId) and
                            (PortfolioBackupsTable.contentHash eq hash)
                }
                .orderBy(PortfolioBackupsTable.createdAt, SortOrder.ASC)
                .firstOrNull { row ->
                    BackupContent.canonicalJson(row[PortfolioBackupsTable.data]) == canonicalJson
                }

            if (identicalRow != null) {
                PortfolioBackupsTable.update({ PortfolioBackupsTable.id eq identicalRow[PortfolioBackupsTable.id] }) {
                    it[updatedAt] = nowMillis
                }
                updatedExisting = true
            } else {
                PortfolioBackupsTable.insert {
                    it[portfolioId] = portfolio.serialId
                    it[createdAt] = nowMillis
                    it[updatedAt] = nowMillis
                    it[PortfolioBackupsTable.label] = label
                    it[contentHash] = hash
                    it[data] = storeJson
                }
            }
            pruneOldBackups(portfolio.serialId)
        }
        if (updatedExisting) {
            logger.debug("DB backup timestamp updated for '${portfolio.slug}'${if (label.isNotEmpty()) " [$label]" else ""}")
        } else {
            logger.info("DB backup saved for '${portfolio.slug}'${if (label.isNotEmpty()) " [$label]" else ""}")
        }
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
        logger.info("Deleted all DB backups for '${portfolio.slug}'")
    }

    fun listDbBackups(portfolio: ManagedPortfolio): List<DbBackupEntry> = transaction {
        PortfolioBackupsTable.selectAll()
            .where { PortfolioBackupsTable.portfolioId eq portfolio.serialId }
            .orderBy(PortfolioBackupsTable.updatedAt, SortOrder.DESC)
            .limit(MAX_BACKUPS_PER_PORTFOLIO)
            .map { row ->
                DbBackupEntry(
                    id = row[PortfolioBackupsTable.id],
                    createdAt = row[PortfolioBackupsTable.createdAt],
                    updatedAt = row[PortfolioBackupsTable.updatedAt],
                    label = row[PortfolioBackupsTable.label]
                )
            }
    }

    fun restoreFromDb(portfolio: ManagedPortfolio, backupId: Int) {
        val root = loadDbBackupRoot(portfolio, backupId)
        val currentStocks = root(portfolio, resolveUsd = false, includeTickerMetadata = true).stocks
        val restoredStocks = appendMissingCurrentStocksWithZeroQty(root.stocks, currentStocks)
        val cashEntries = root.cash.map { c -> CashEntry(c.label, c.currency, c.marginFlag, c.amount, c.portfolioRef) }
        transaction {
            portfolio.replacePositions(restoredStocks)
            portfolio.replaceCash(cashEntries)
            root.dividendStartDate?.let { restoreDividendStartDate(portfolio.serialId, it) }
        }
        logger.info("Restored '${portfolio.slug}' from DB backup $backupId")
    }

    fun previewRestoreFromDb(portfolio: ManagedPortfolio, backupId: Int): ImportResult =
        importResultFromRoot(loadDbBackupRoot(portfolio, backupId))

    fun exportJson(portfolio: ManagedPortfolio): String =
        serializeToJson(portfolio, resolveUsd = true, includeTickerMetadata = true)

    fun exportRoot(portfolio: ManagedPortfolio): BackupRoot =
        root(portfolio, resolveUsd = true, includeTickerMetadata = true)

    fun exportSyncEntry(portfolio: ManagedPortfolio): PortfolioSyncEntry {
        val r = root(portfolio, resolveUsd = true, includeTickerMetadata = true)
        return PortfolioSyncEntry(
            serialId = portfolio.serialId,
            name = portfolio.name,
            slug = portfolio.slug,
            stocks = r.stocks,
            cash = r.cash
        )
    }

    fun exportTickerSettings(): List<BackupTickerSettings> = transaction {
        StockTickersTable.selectAll()
            .orderBy(StockTickersTable.symbol to SortOrder.ASC)
            .map {
                BackupTickerSettings(
                    symbol = it[StockTickersTable.symbol],
                    letf = it[StockTickersTable.letf],
                    groups = it[StockTickersTable.groups]
                )
            }
            .filter { it.letf.isNotBlank() || it.groups.isNotBlank() }
    }

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

    private fun serializeToJson(
        portfolio: ManagedPortfolio,
        resolveUsd: Boolean = false,
        includeTickerMetadata: Boolean = true
    ): String {
        val root = root(portfolio, resolveUsd, includeTickerMetadata)
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
        resolveUsd: Boolean = false,
        includeTickerMetadata: Boolean = false
    ): BackupRoot {
        val pid = portfolio.serialId
        val stocks = transaction {
            PositionsTable.leftJoin(StockTickersTable, { PositionsTable.symbol }, { StockTickersTable.symbol })
                .selectAll()
                .where { PositionsTable.portfolioId eq pid }
                .map { row ->
                    BackupStock(
                        symbol = row[PositionsTable.symbol],
                        amount = row[PositionsTable.amount],
                        targetWeight = row[PositionsTable.targetWeight],
                        letf = if (includeTickerMetadata) row.getOrNull(StockTickersTable.letf) ?: "" else "",
                        groups = if (includeTickerMetadata) row.getOrNull(StockTickersTable.groups) ?: "" else ""
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
            cash = cashBackup,
            dividendStartDate = portfolio.getConfig("dividendStartDate") ?: "",
            tickerSettings = if (includeTickerMetadata) exportTickerSettings() else null
        )
        return root
    }

    private fun restoreDividendStartDate(portfolioId: Int, date: String) {
        if (date.isBlank()) {
            PortfolioCfgTable.deleteWhere {
                (PortfolioCfgTable.portfolioId eq portfolioId) and
                        (PortfolioCfgTable.cfgKey eq "dividendStartDate")
            }
        } else {
            PortfolioCfgTable.upsert {
                it[PortfolioCfgTable.portfolioId] = portfolioId
                it[PortfolioCfgTable.cfgKey] = "dividendStartDate"
                it[PortfolioCfgTable.cfgValue] = date
            }
        }
    }

    private fun appendMissingCurrentStocksWithZeroQty(
        restoredStocks: List<BackupStock>,
        currentStocks: List<BackupStock>
    ): List<BackupStock> {
        val currentBySymbol = currentStocks.associateBy { it.symbol.trim().uppercase() }
        val restoredSymbols = restoredStocks.map { it.symbol.trim().uppercase() }.toSet()
        val restoredWithoutBackupMetadata = restoredStocks.map { restored ->
            val current = currentBySymbol[restored.symbol.trim().uppercase()]
            restored.copy(
                letf = restored.letf.takeIf { it.isNotBlank() } ?: current?.letf ?: "",
                groups = restored.groups.takeIf { it.isNotBlank() } ?: current?.groups ?: ""
            )
        }
        val missingCurrentStocks = currentStocks
            .filter { it.symbol.trim().uppercase() !in restoredSymbols }
            .map { it.copy(amount = 0.0, targetWeight = 0.0) }
        return restoredWithoutBackupMetadata + missingCurrentStocks
    }

    private fun parseJsonImport(bytes: ByteArray): ImportResult {
        return try {
            val root = appJson.decodeFromString<BackupRoot>(String(bytes))
            importResultFromRoot(root)
        } catch (e: Exception) {
            ImportResult(null, null, "Invalid JSON: ${e.message}")
        }
    }

    private fun loadDbBackupRoot(portfolio: ManagedPortfolio, backupId: Int): BackupRoot {
        val json = transaction {
            PortfolioBackupsTable.selectAll()
                .where {
                    (PortfolioBackupsTable.id eq backupId) and
                            (PortfolioBackupsTable.portfolioId eq portfolio.serialId)
                }
                .firstOrNull()?.get(PortfolioBackupsTable.data)
        }
            ?: throw IllegalArgumentException("Backup $backupId not found for portfolio '${portfolio.slug}'")

        return appJson.decodeFromString<BackupRoot>(json)
    }

    private fun importResultFromRoot(root: BackupRoot): ImportResult {
        val stocks = root.stocks.map { s ->
            ImportedStock(s.symbol, s.amount, s.targetWeight, s.letf, s.groups)
        }
        val cash = root.cash.map { c ->
            val value = if (c.currency == "P") {
                val ref = c.portfolioRef ?: ""
                if (kotlin.math.abs(c.amount) == 1.0) {
                    (if (c.amount < 0) "-" else "") + ref
                } else {
                    "${c.amount} $ref"
                }
            } else c.amount.toString()
            ImportedCash(c.key, value)
        }
        return if (stocks.isEmpty() && cash.isEmpty()) {
            ImportResult(null, null, "JSON backup has no stocks or cash entries")
        } else {
            ImportResult(stocks, cash, null, root.dividendStartDate)
        }
    }

    private fun parseCsvImport(bytes: ByteArray): ImportResult {
        return try {
            val reader = InputStreamReader(ByteArrayInputStream(bytes))
            val csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
            val rows = mutableListOf<ImportedStock>()
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
                        rows.add(ImportedStock(symbol, amount, targetWeight, "", ""))
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
            val entries = mutableListOf<ImportedCash>()
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
                    entries.add(ImportedCash(key, value))
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
            val cash = txtResult?.cash
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

    private fun pruneOldBackups(portfolioSerialId: Int) {
        val keepIds = PortfolioBackupsTable.selectAll()
            .where { PortfolioBackupsTable.portfolioId eq portfolioSerialId }
            .orderBy(
                PortfolioBackupsTable.updatedAt to SortOrder.DESC,
                PortfolioBackupsTable.id to SortOrder.DESC
            )
            .limit(MAX_BACKUPS_PER_PORTFOLIO)
            .map { it[PortfolioBackupsTable.id] }
        if (keepIds.size < MAX_BACKUPS_PER_PORTFOLIO) return
        PortfolioBackupsTable.deleteWhere {
            (PortfolioBackupsTable.portfolioId eq portfolioSerialId) and
                    (PortfolioBackupsTable.id notInList keepIds)
        }
    }
}
