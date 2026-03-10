package com.portfoliohelper.service

import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.model.Stock
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.BufferedReader
import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Paths

object CsvStockReader {

    fun readPortfolio(filePath: String): Portfolio {
        val path = Paths.get(filePath)

        if (!Files.exists(path)) {
            throw IllegalArgumentException("CSV file not found: ${path.toAbsolutePath()}")
        }
        val reader = BufferedReader(FileReader(path.toFile()))
        val stocks = mutableListOf<Stock>()

        reader.use { bufferedReader ->
            val csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()

            CSVParser(bufferedReader, csvFormat).use { parser ->
            for (record in parser) {
                try {
                    val label = record.get("stock_label")
                    val amount = record.get("amount").toDouble()

                    // Read target_weight if column exists (backward compatible)
                    val targetWeight = try {
                        record.get("target_weight")?.toDoubleOrNull()?:0.0
                    } catch (e: IllegalArgumentException) {
                        null  // Column doesn't exist in CSV
                    }

                    // Read LETF column if exists (backward compatible)
                    // Format: "1 CTA 1 IVV" → listOf(1.0 to "CTA", 1.0 to "IVV")
                    val letfComponents = try {
                        val letfValue = record.get("letf")?.trim()
                        if (!letfValue.isNullOrBlank()) {
                            val tokens = letfValue.split("\\s+".toRegex())
                            val components = mutableListOf<Pair<Double, String>>()
                            var i = 0
                            while (i + 1 < tokens.size) {
                                val multiplier = tokens[i].toDouble()
                                val symbol = tokens[i + 1]
                                components.add(multiplier to symbol)
                                i += 2
                            }
                            components.takeIf { it.isNotEmpty() }
                        } else null
                    } catch (e: IllegalArgumentException) {
                        null  // Column doesn't exist in CSV
                    } catch (e: NumberFormatException) {
                        null  // Invalid format
                    }

                    // Read groups column if exists (backward compatible)
                    // Format: "1 Managed Futures;1 US Stock" → listOf(1.0 to "Managed Futures", 1.0 to "US Stock")
                    val groups = try {
                        val groupsValue = record.get("groups")?.trim()
                        if (!groupsValue.isNullOrBlank()) {
                            groupsValue.split(";").mapNotNull { entry ->
                                val trimmed = entry.trim()
                                val spaceIdx = trimmed.indexOf(' ')
                                if (spaceIdx < 0) null
                                else {
                                    val mult = trimmed.substring(0, spaceIdx).toDoubleOrNull() ?: return@mapNotNull null
                                    val name = trimmed.substring(spaceIdx + 1).trim()
                                    if (name.isEmpty()) null else mult to name
                                }
                            }
                        } else emptyList()
                    } catch (e: IllegalArgumentException) {
                        emptyList()  // Column doesn't exist in CSV
                    }

                    // Create stock with null prices - will be populated by IB API
                    stocks.add(Stock(
                        label = label,
                        amount = amount,
                        markPrice = null,
                        lastClosePrice = null,
                        targetWeight = targetWeight,
                        letfComponents = letfComponents,
                        groups = groups
                    ))
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Invalid number format in CSV at record ${record.recordNumber}: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Missing column in CSV at record ${record.recordNumber}: ${e.message}")
                }
            }
            }
        }

        return Portfolio(stocks)
    }
}
