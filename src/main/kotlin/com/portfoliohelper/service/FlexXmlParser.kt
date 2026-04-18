package com.portfoliohelper.service

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

// ---------------------------------------------------------------------------
// Domain model — one end-of-day snapshot parsed from a single FlexStatement
// ---------------------------------------------------------------------------

data class DaySnapshot(
    val date: String,
    val netLiq: Double,
    val cashBase: Double,
    val positions: List<PositionEntry>,
    val cashFlows: List<CashFlowEntry>
)

data class PositionEntry(val symbol: String, val positionValue: Double)

data class CashFlowEntry(val fxRateToBase: Double, val amount: Double, val type: String)

// ---------------------------------------------------------------------------
// xmlutil-mapped classes (private — only used by FlexXmlParser)
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("FlexQueryResponse", "", "")
private data class XFlexQueryResponse(
    @XmlElement(true) val FlexStatements: XFlexStatements? = null
)

@Serializable
@XmlSerialName("FlexStatements", "", "")
private data class XFlexStatements(
    @XmlElement(true) val statements: List<XFlexStatement> = emptyList()
)

@Serializable
@XmlSerialName("FlexStatement", "", "")
private data class XFlexStatement(
    val fromDate: String = "",  // YYYYMMDD — the canonical date for this statement
    @XmlElement(true) val EquitySummaryInBase: XEquitySummaryInBase? = null,
    @XmlElement(true) val OpenPositions: XOpenPositions? = null,
    @XmlElement(true) val CashTransactions: XCashTransactions? = null,
)

@Serializable
@XmlSerialName("EquitySummaryInBase", "", "")
private data class XEquitySummaryInBase(
    @XmlElement(true) val rows: List<XEquitySummaryRow> = emptyList()
)

@Serializable
@XmlSerialName("EquitySummaryByReportDateInBase", "", "")
private data class XEquitySummaryRow(
    val cash: Double = 0.0,
    val total: Double = 0.0
)

@Serializable
@XmlSerialName("OpenPositions", "", "")
private data class XOpenPositions(
    @XmlElement(true) val positions: List<XOpenPosition> = emptyList()
)

@Serializable
@XmlSerialName("OpenPosition", "", "")
private data class XOpenPosition(
    val symbol: String = "",
    val positionValue: Double = 0.0
)

@Serializable
@XmlSerialName("CashTransactions", "", "")
private data class XCashTransactions(
    @XmlElement(true) val transactions: List<XCashTransaction> = emptyList()
)

@Serializable
@XmlSerialName("CashTransaction", "", "")
private data class XCashTransaction(
    val fxRateToBase: Double = 1.0,
    val amount: Double = 0.0,
    val type: String = ""
)

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

object FlexXmlParser {

    private val xmlFormat = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
    }

    /**
     * Parses a FlexQueryResponse XML string into a list of daily snapshots.
     * Throws [FlexParseException] if the structure is missing required sections.
     */
    fun parse(rawXml: String): List<DaySnapshot> {
        val response = try {
            xmlFormat.decodeFromString(XFlexQueryResponse.serializer(), rawXml)
        } catch (e: Exception) {
            throw FlexParseException("Failed to parse Flex XML: ${e.message}", e)
        }

        val statements = response.FlexStatements?.statements
            ?: throw FlexParseException("FlexStatements element missing from response")

        return statements.mapIndexedNotNull { idx, stmt ->
            parseStatement(idx, stmt)
        }
    }

    private fun parseStatement(idx: Int, stmt: XFlexStatement): DaySnapshot? {
        // Date comes from the FlexStatement's fromDate attribute (YYYYMMDD → YYYY-MM-DD)
        val rawDate = stmt.fromDate
        if (rawDate.length != 8) return null
        val date = "${rawDate.substring(0, 4)}-${rawDate.substring(4, 6)}-${rawDate.substring(6, 8)}"

        val equityRows = stmt.EquitySummaryInBase?.rows ?: return null
        // Second row is end-of-day; first row is start-of-day (carry-over from prior day)
        val eod = equityRows.getOrNull(1) ?: equityRows.firstOrNull() ?: return null

        val positions = stmt.OpenPositions?.positions
            ?.filter { it.symbol.isNotBlank() }
            ?.map { PositionEntry(it.symbol, it.positionValue) }
            ?: emptyList()

        val cashFlows = stmt.CashTransactions?.transactions
            ?.filter { it.amount != 0.0 }
            ?.map { CashFlowEntry(it.fxRateToBase, it.amount, it.type) }
            ?: emptyList()

        return DaySnapshot(
            date      = date,
            netLiq    = eod.total,
            cashBase  = eod.cash,
            positions = positions,
            cashFlows = cashFlows
        )
    }
}
