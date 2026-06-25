package com.portfoliohelper.service.nav

import java.time.LocalDate

data class NavData(
    val symbol: String,
    val nav: Double,
    val asOfDate: LocalDate?,
    val lastFetchTime: Long
)
