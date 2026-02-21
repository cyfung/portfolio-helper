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

        val knownFlags = setOf("M", "E")
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

                val allParts = key.split(".")
                val mutableParts = allParts.toMutableList()
                val flags = mutableSetOf<String>()
                while (mutableParts.isNotEmpty() && mutableParts.last().uppercase() in knownFlags) {
                    flags.add(mutableParts.removeLast().uppercase())
                }

                if (mutableParts.size < 2) {
                    logger.warn("Skipping malformed cash key (no currency after stripping flags): $key")
                    continue
                }

                val currency = mutableParts.last().uppercase()
                val label = mutableParts.dropLast(1).joinToString(".")
                val marginFlag = "M" in flags
                val equityFlag = "E" in flags

                if (currency == "P") {
                    val trimmedVal = valueStr.trim()
                    val sign = if (trimmedVal.startsWith("-")) -1.0 else 1.0
                    val portfolioId = trimmedVal.trimStart('+', '-').lowercase()
                    if (portfolioId.isEmpty()) {
                        logger.warn("Skipping P entry with empty portfolio reference: $key")
                        continue
                    }
                    entries.add(CashEntry(label, "P", marginFlag, equityFlag, amount = sign, portfolioRef = portfolioId))
                } else {
                    val amount = valueStr.toDoubleOrNull()
                    if (amount == null) {
                        logger.warn("Skipping cash entry with non-numeric amount: $valueStr")
                        continue
                    }
                    entries.add(CashEntry(label, currency, marginFlag, equityFlag, amount))
                }
            }
        }

        logger.info("Loaded ${entries.size} cash entries from $path")
        return entries
    }
}
