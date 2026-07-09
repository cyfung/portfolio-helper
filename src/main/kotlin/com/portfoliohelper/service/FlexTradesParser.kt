package com.portfoliohelper.service

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

data class IbkrTradeEntry(
    val tradeKey: String,
    val tradeDate: String,
    val tradeTime: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val currency: String,
    val exchange: String,
    val assetCategory: String,
    val commission: Double?,
    val commissionCurrency: String?,
    val realizedPnl: Double?
)

@Serializable
@XmlSerialName("FlexQueryResponse", "", "")
private data class XTradesFlexQueryResponse(
    @XmlElement(true) val FlexStatements: XTradesFlexStatements? = null
)

@Serializable
@XmlSerialName("FlexStatements", "", "")
private data class XTradesFlexStatements(
    @XmlElement(true) val statements: List<XTradesFlexStatement> = emptyList()
)

@Serializable
@XmlSerialName("FlexStatement", "", "")
private data class XTradesFlexStatement(
    @XmlElement(true) val Trades: XTrades? = null
)

@Serializable
@XmlSerialName("Trades", "", "")
private data class XTrades(
    @XmlElement(true) val trades: List<XTrade> = emptyList()
)

@Serializable
@XmlSerialName("Trade", "", "")
private data class XTrade(
    val tradeID: String = "",
    val tradeId: String = "",
    val transactionID: String = "",
    val transactionId: String = "",
    val ibExecID: String = "",
    val ibExecId: String = "",
    val execID: String = "",
    val execId: String = "",
    val orderID: String = "",
    val orderId: String = "",
    val reportDate: String = "",
    val tradeDate: String = "",
    val tradeTime: String = "",
    val dateTime: String = "",
    val symbol: String = "",
    val underlyingSymbol: String = "",
    val assetCategory: String = "",
    val assetClass: String = "",
    val secType: String = "",
    val securityType: String = "",
    val buySell: String = "",
    val side: String = "",
    val quantity: Double = 0.0,
    val tradePrice: Double = 0.0,
    val price: Double = 0.0,
    val currency: String = "",
    val exchange: String = "",
    val listingExchange: String = "",
    val ibCommission: Double? = null,
    val commission: Double? = null,
    val ibCommissionCurrency: String = "",
    val commissionCurrency: String = "",
    val fifoPnlRealized: Double? = null,
    val realizedPnl: Double? = null,
)

object FlexTradesParser {
    private val xmlFormat = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
    }

    fun parse(rawXml: String): List<IbkrTradeEntry> {
        val response = try {
            xmlFormat.decodeFromString(XTradesFlexQueryResponse.serializer(), rawXml)
        } catch (e: Exception) {
            throw FlexParseException("Failed to parse Flex trades XML: ${e.message}", e)
        }
        val statements = response.FlexStatements?.statements
            ?: throw FlexParseException("FlexStatements element missing from trades response")

        val trades = statements.flatMap { it.Trades?.trades ?: emptyList() }
            .mapNotNull(::toTradeEntry)
        if (trades.isEmpty()) {
            throw FlexParseException("No Trade rows found. Enable the Trades section in the Flex Query.")
        }
        return trades.sortedWith(compareBy<IbkrTradeEntry> { it.tradeDate }.thenBy { it.tradeTime }.thenBy { it.tradeKey })
    }

    private fun toTradeEntry(t: XTrade): IbkrTradeEntry? {
        val symbol = t.symbol.ifBlank { t.underlyingSymbol }.trim().uppercase()
        if (symbol.isBlank()) return null
        val dateTime = t.dateTime.trim()
        val date = normalizeDate(t.tradeDate.ifBlank { t.reportDate }.ifBlank { dateTime.take(8) }) ?: return null
        val time = t.tradeTime.ifBlank { normalizeDateTimeTime(dateTime) }
        val side = normalizeSide(t.buySell.ifBlank { t.side })
        val tradeKey = listOf(t.tradeID, t.tradeId, t.transactionID, t.transactionId, t.ibExecID, t.ibExecId, t.execID, t.execId)
            .firstOrNull { it.isNotBlank() }
            ?: listOf(date, time, symbol, side, t.quantity.toString(), tradePrice(t).toString(), t.orderID.ifBlank { t.orderId })
                .joinToString(":")

        return IbkrTradeEntry(
            tradeKey = tradeKey,
            tradeDate = date,
            tradeTime = time,
            symbol = symbol,
            side = side,
            quantity = t.quantity,
            price = tradePrice(t),
            currency = t.currency.trim().uppercase(),
            exchange = t.exchange.ifBlank { t.listingExchange }.trim(),
            assetCategory = listOf(t.assetCategory, t.assetClass, t.secType, t.securityType)
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.uppercase()
                ?: "",
            commission = optionalDouble(t.ibCommission ?: t.commission),
            commissionCurrency = t.ibCommissionCurrency.ifBlank { t.commissionCurrency }.trim().uppercase().ifBlank { null },
            realizedPnl = optionalDouble(t.fifoPnlRealized ?: t.realizedPnl),
        )
    }

    private fun tradePrice(t: XTrade): Double = if (t.tradePrice != 0.0) t.tradePrice else t.price

    private fun normalizeDate(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        if (digits.length < 8) return null
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }

    private fun normalizeDateTimeTime(raw: String): String {
        val trimmed = raw.trim()
        val match = Regex("""^\d{8}[; T](\d{2}:\d{2}:\d{2})""").find(trimmed)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun normalizeSide(raw: String): String {
        return when (raw.trim().uppercase()) {
            "BUY", "BOT", "B" -> "BUY"
            "SELL", "SLD", "S" -> "SELL"
            else -> raw.trim().uppercase()
        }
    }

    private fun optionalDouble(value: Double?): Double? =
        value?.takeUnless { it >= Double.MAX_VALUE / 2 }
}
