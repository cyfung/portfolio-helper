package com.portfoliohelper.service

import com.portfoliohelper.model.Portfolio
import com.portfoliohelper.model.Stock
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths

object CsvStockReader {

    fun readPortfolio(filePath: String): Portfolio {
        val path = Paths.get(filePath)

        // Try to read from file system first, then fall back to classpath
        val reader = if (Files.exists(path)) {
            BufferedReader(FileReader(path.toFile()))
        } else {
            val inputStream = this::class.java.classLoader.getResourceAsStream(filePath)
                ?: throw IllegalArgumentException("CSV file not found: $filePath (checked filesystem and classpath)")
            BufferedReader(InputStreamReader(inputStream))
        }
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
                    val amount = record.get("amount").toInt()

                    // Read target_weight if column exists (backward compatible)
                    val targetWeight = try {
                        record.get("target_weight")?.toDoubleOrNull()
                    } catch (e: IllegalArgumentException) {
                        null  // Column doesn't exist in CSV
                    }

                    // Create stock with null prices - will be populated by IB API
                    stocks.add(Stock(
                        label = label,
                        amount = amount,
                        markPrice = null,
                        lastClosePrice = null,
                        targetWeight = targetWeight
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
