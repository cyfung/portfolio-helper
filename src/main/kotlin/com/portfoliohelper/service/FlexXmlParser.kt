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
    val stockBase: Double,
    val interestAccrualsBase: Double,
    val positions: List<PositionEntry>,
    val cashBalances: List<CashBalanceEntry>,
    val interestAccruals: List<InterestAccrualEntry>,
    val cashFlows: List<CashFlowEntry>
)

data class PositionEntry(
    val symbol: String,
    val currency: String,
    val position: Double,
    val markPrice: Double,
    val positionValue: Double
)

data class CashBalanceEntry(val currency: String, val amount: Double)
data class InterestAccrualEntry(val currency: String, val endingAccrualBalance: Double)
data class CashFlowEntry(val currency: String, val fxRateToBase: Double, val amount: Double, val type: String)

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
    @XmlElement(true) val CashReport: XCashReport? = null,
    @XmlElement(true) val OpenPositions: XOpenPositions? = null,
    @XmlElement(true) val CashTransactions: XCashTransactions? = null,
    @XmlElement(true) val InterestAccruals: XInterestAccruals? = null
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
    val stock: Double = 0.0,
    val interestAccruals: Double = 0.0,
    val total: Double = 0.0
)

@Serializable
@XmlSerialName("CashReport", "", "")
private data class XCashReport(
    @XmlElement(true) val rows: List<XCashReportCurrency> = emptyList()
)

@Serializable
@XmlSerialName("CashReportCurrency", "", "")
private data class XCashReportCurrency(
    val currency: String = "",
    val endingCash: Double = 0.0
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
    val currency: String = "",
    val position: Double = 0.0,
    val markPrice: Double = 0.0,
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
    val currency: String = "",
    val fxRateToBase: Double = 1.0,
    val amount: Double = 0.0,
    val type: String = ""
)

@Serializable
@XmlSerialName("InterestAccruals", "", "")
private data class XInterestAccruals(
    @XmlElement(true) val rows: List<XInterestAccrualsCurrency> = emptyList()
)

@Serializable
@XmlSerialName("InterestAccrualsCurrency", "", "")
private data class XInterestAccrualsCurrency(
    val currency: String = "",
    val endingAccrualBalance: Double = 0.0
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

        val cashBalances = stmt.CashReport?.rows
            ?.filter { it.currency.isNotBlank() && it.currency != "BASE_SUMMARY" }
            ?.map { CashBalanceEntry(it.currency, it.endingCash) }
            ?: emptyList()

        val positions = stmt.OpenPositions?.positions
            ?.filter { it.symbol.isNotBlank() }
            ?.map { PositionEntry(it.symbol, it.currency, it.position, it.markPrice, it.positionValue) }
            ?: emptyList()

        val cashFlows = stmt.CashTransactions?.transactions
            ?.filter { it.currency.isNotBlank() && it.amount != 0.0 }
            ?.map { CashFlowEntry(it.currency, it.fxRateToBase, it.amount, it.type) }
            ?: emptyList()

        val interestAccruals = stmt.InterestAccruals?.rows
            ?.filter { it.endingAccrualBalance != 0.0 }
            ?.map { InterestAccrualEntry(it.currency.ifBlank { "BASE" }, it.endingAccrualBalance) }
            ?: emptyList()

        return DaySnapshot(
            date                 = date,
            netLiq               = eod.total,
            cashBase             = eod.cash,
            stockBase            = eod.stock,
            interestAccrualsBase = eod.interestAccruals,
            positions            = positions,
            cashBalances         = cashBalances,
            interestAccruals     = interestAccruals,
            cashFlows            = cashFlows
        )
    }
}
