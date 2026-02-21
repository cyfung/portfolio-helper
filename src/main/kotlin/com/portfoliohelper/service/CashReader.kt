package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import org.slf4j.LoggerFactory
import java.io.File

object CashReader {
    private val logger = LoggerFactory.getLogger(CashReader::class.java)

    fun readCash(path: String): List<CashEntry> {
        val file = File(path)
        if (!file.exists()) {
            logger.warn("Cash file not found at $path, returning empty list")
            return emptyList()
        }

        val entries = mutableListOf<CashEntry>()
        file.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val eqIdx = trimmed.indexOf('=')
                if (eqIdx < 0) {
                    logger.warn("Skipping malformed cash line (no '='): $trimmed")
                    continue
                }

                val key = trimmed.substring(0, eqIdx).trim()
                val valueStr = trimmed.substring(eqIdx + 1).trim()

                val parts = key.split(".")
                if (parts.size < 2) {
                    logger.warn("Skipping malformed cash key (no '.'): $key")
                    continue
                }

                val marginFlag = parts.size >= 3 && parts.last().uppercase() == "M"
                val (label, currency) = if (marginFlag) {
                    parts.dropLast(2).joinToString(".") to parts[parts.size - 2].uppercase()
                } else {
                    parts.dropLast(1).joinToString(".") to parts.last().uppercase()
                }

                val amount = valueStr.toDoubleOrNull()
                if (amount == null) {
                    logger.warn("Skipping cash entry with non-numeric amount: $valueStr")
                    continue
                }

                entries.add(CashEntry(label, currency, marginFlag, amount))
            }
        }

        logger.info("Loaded ${entries.size} cash entries from $path")
        return entries
    }
}
