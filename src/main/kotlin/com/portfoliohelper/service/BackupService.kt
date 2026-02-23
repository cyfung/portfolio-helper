package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupService {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            performBackups()
            scheduleDaily(scope)
        }
    }

    private fun performBackups() {
        PortfolioRegistry.entries.forEach { backupPortfolio(it) }
    }

    fun backupNow(portfolio: ManagedPortfolio, prefix: String? = null, subfolder: String? = null) {
        backupPortfolio(portfolio, prefix, subfolder)
    }

    private fun backupPortfolio(portfolio: ManagedPortfolio, prefix: String? = null, subfolder: String? = null) {
        val dataDir = Paths.get(portfolio.csvPath).parent
        val backupDir = dataDir.resolve(".backup").let { if (subfolder != null) it.resolve(subfolder) else it }
        Files.createDirectories(backupDir)

        val csvFile  = Paths.get(portfolio.csvPath)
        val cashFile = Paths.get(portfolio.cashPath)
        val backupCsv  = backupDir.resolve("stocks.csv")
        val backupCash = backupDir.resolve("cash.txt")

        val csvChanged  = contentDiffers(csvFile, backupCsv)
        val cashChanged = contentDiffers(cashFile, backupCash)
        if (!csvChanged && !cashChanged) {
            logger.debug("No changes for '${portfolio.id}'${if (subfolder != null) " [$subfolder]" else ""}, backup skipped")
            return
        }

        val date = LocalDate.now().toString()  // yyyy-MM-dd
        val stem = if (prefix != null) "$prefix-$date" else date
        val zipPath = generateZipPath(backupDir, stem)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            if (Files.exists(csvFile))  zos.addFile("stocks.csv", csvFile)
            if (Files.exists(cashFile)) zos.addFile("cash.txt",  cashFile)
        }

        // Update change-detection snapshots in the backup dir (each subfolder has its own snapshots)
        if (Files.exists(csvFile))  Files.copy(csvFile,  backupCsv,  REPLACE_EXISTING)
        else                        Files.deleteIfExists(backupCsv)
        if (Files.exists(cashFile)) Files.copy(cashFile, backupCash, REPLACE_EXISTING)
        else                        Files.deleteIfExists(backupCash)

        logger.info("Backup created for '${portfolio.id}'${if (subfolder != null) " [$subfolder]" else ""}: ${zipPath.fileName}")
    }

    private fun generateZipPath(backupDir: Path, date: String): Path {
        val base = backupDir.resolve("$date.zip")
        if (!Files.exists(base)) return base
        var n = 1
        while (true) {
            val candidate = backupDir.resolve("${date}_$n.zip")
            if (!Files.exists(candidate)) return candidate
            n++
        }
    }

    private fun contentDiffers(current: Path, backup: Path): Boolean {
        val currExists   = Files.exists(current)
        val backupExists = Files.exists(backup)
        return when {
            !currExists && !backupExists -> false
            currExists != backupExists   -> true
            else -> !Files.readAllBytes(current).contentEquals(Files.readAllBytes(backup))
        }
    }

    private fun ZipOutputStream.addFile(entryName: String, source: Path) {
        putNextEntry(ZipEntry(entryName))
        Files.copy(source, this)
        closeEntry()
    }

    /** Returns all backups grouped by subfolder. Key "default" = root .backup/ dir. */
    fun listAllBackups(portfolio: ManagedPortfolio): Map<String, List<String>> {
        val baseDir = Paths.get(portfolio.csvPath).parent.resolve(".backup")
        if (!Files.exists(baseDir)) return emptyMap()
        val result = linkedMapOf<String, List<String>>()
        val rootZips = listZipsIn(baseDir)
        if (rootZips.isNotEmpty()) result["default"] = rootZips
        Files.list(baseDir)
            .filter { Files.isDirectory(it) }
            .sorted()
            .forEach { subDir ->
                val zips = listZipsIn(subDir)
                if (zips.isNotEmpty()) result[subDir.fileName.toString()] = zips
            }
        return result
    }

    private fun listZipsIn(dir: Path): List<String> =
        Files.list(dir)
            .filter { it.fileName.toString().endsWith(".zip") }
            .map { it.fileName.toString().removeSuffix(".zip") }
            .sorted().toList().reversed()

    fun restoreBackup(portfolio: ManagedPortfolio, date: String, subfolder: String? = null) {
        require(date.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid backup name: $date" }
        if (subfolder != null) require(subfolder.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid subfolder: $subfolder" }
        val baseDir = Paths.get(portfolio.csvPath).parent.resolve(".backup")
        val backupDir = if (subfolder != null) baseDir.resolve(subfolder) else baseDir
        val zipPath = backupDir.resolve("$date.zip")
        require(Files.exists(zipPath)) { "Backup not found: $date" }
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = when (entry.name) {
                    "stocks.csv" -> Paths.get(portfolio.csvPath)
                    "cash.txt"   -> Paths.get(portfolio.cashPath)
                    else         -> null
                }
                if (target != null) Files.copy(zis, target, REPLACE_EXISTING)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        logger.info("Restored '${portfolio.id}' from backup${if (subfolder != null) " [$subfolder]" else ""}: $date")
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
