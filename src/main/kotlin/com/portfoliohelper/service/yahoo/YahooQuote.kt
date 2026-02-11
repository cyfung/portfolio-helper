package com.portfoliohelper.service.yahoo

data class YahooQuote(
    val symbol: String,
    val regularMarketPrice: Double?,
    val previousClose: Double?,
    val lastUpdateTime: Long = System.currentTimeMillis()
)

class YahooFinanceException(message: String, cause: Throwable? = null) : Exception(message, cause)
