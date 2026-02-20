package com.portfoliohelper.service.nav

data class NavData(
    val symbol: String,
    val nav: Double,
    val asOfDate: String?,
    val lastFetchTime: Long
)
