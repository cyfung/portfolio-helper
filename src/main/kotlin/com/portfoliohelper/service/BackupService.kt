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

    private fun backupPortfolio(portfolio: ManagedPortfolio) {
        val dataDir = Paths.get(portfolio.csvPath).parent
        val backupDir = dataDir.resolve(".backup")
        Files.createDirectories(backupDir)

        val csvFile  = Paths.get(portfolio.csvPath)
        val cashFile = Paths.get(portfolio.cashPath)
        val backupCsv  = backupDir.resolve("stocks.csv")
        val backupCash = backupDir.resolve("cash.txt")

        val csvChanged  = contentDiffers(csvFile,  backupCsv)
        val cashChanged = contentDiffers(cashFile, backupCash)

        if (!csvChanged && !cashChanged) {
            logger.debug("No changes for '${portfolio.id}', backup skipped")
            return
        }

        val date = LocalDate.now().toString()  // yyyy-MM-dd
        val zipPath = backupDir.resolve("$date.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            if (Files.exists(csvFile))  zos.addFile("stocks.csv", csvFile)
            if (Files.exists(cashFile)) zos.addFile("cash.txt",  cashFile)
        }

        if (Files.exists(csvFile))  Files.copy(csvFile,  backupCsv,  REPLACE_EXISTING)
        else                        Files.deleteIfExists(backupCsv)
        if (Files.exists(cashFile)) Files.copy(cashFile, backupCash, REPLACE_EXISTING)
        else                        Files.deleteIfExists(backupCash)

        logger.info("Backup created for '${portfolio.id}': ${zipPath.fileName}")
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
