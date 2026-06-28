package com.portfoliohelper.service.yahoo

import java.time.LocalDate

data class YahooQuote(
    val symbol: String,
    val regularMarketPrice: Double?,
    val previousClose: Double?,              // Yesterday's official close from Yahoo Finance
    val lastUpdateTime: Long = System.currentTimeMillis(),

    // Market hours data for determining if market is open
    val tradingPeriodStart: Long? = null,    // Regular session start (Unix seconds)
    val tradingPeriodEnd: Long? = null,      // Regular session end (Unix seconds)
    val gmtoffset: Int? = null,              // UTC offset in seconds for the exchange's local timezone
    val isMarketClosed: Boolean = false,     // Whether market is currently closed
    val currency: String? = null,            // Trading currency (e.g. "USD", "HKD")
    val markPriceDate: LocalDate? = null     // Local date of regularMarketPrice, from Yahoo regularMarketTime
)

class YahooFinanceException(message: String, cause: Throwable? = null) : Exception(message, cause)
