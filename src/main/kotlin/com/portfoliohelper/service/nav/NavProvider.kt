package com.portfoliohelper.service.nav

interface NavProvider {
    val symbol: String
    suspend fun fetchNav(): NavData?
}
