package com.portfoliohelper.service.yahoo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YahooChartResponse(val chart: YahooChart)

@Serializable
data class YahooChart(val result: List<YahooChartResult>? = null)

@Serializable
data class YahooChartResult(
    val meta: YahooMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: YahooIndicators? = null,
    val events: YahooEvents? = null
)

@Serializable
data class YahooMeta(
    val currency: String? = null,
    val regularMarketPrice: Double? = null,
    val chartPreviousClose: Double? = null,
    val currentTradingPeriod: YahooTradingPeriods? = null
)

@Serializable
data class YahooTradingPeriods(val regular: YahooTradingPeriod? = null)

@Serializable
data class YahooTradingPeriod(
    val start: Long? = null,
    val end: Long? = null
)

@Serializable
data class YahooIndicators(@SerialName("adjclose") val adjClose: List<YahooAdjClose>? = null)

@Serializable
data class YahooAdjClose(@SerialName("adjclose") val adjClose: List<Double?>? = null)

@Serializable
data class YahooEvents(val dividends: Map<String, YahooDividend>? = null)

@Serializable
data class YahooDividend(val amount: Double, val date: Long)
