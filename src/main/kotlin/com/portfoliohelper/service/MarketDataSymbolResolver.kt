package com.portfoliohelper.service

object MarketDataSymbolResolver {
    fun resolve(
        symbol: String,
        exchange: String,
        exchangeSuffixes: Map<String, String>,
        exchangeSymbolMinWidths: Map<String, Int>,
    ): String {
        val normalizedExchange = exchange.trim().uppercase()
        val normalizedSymbol = symbol.trim().uppercase()
        val minimumWidth = exchangeSymbolMinWidths[normalizedExchange]
        val symbolBody = if (minimumWidth != null && normalizedSymbol.all(Char::isDigit)) {
            normalizedSymbol.padStart(minimumWidth, '0')
        } else {
            normalizedSymbol
        }
        return symbolBody + (exchangeSuffixes[normalizedExchange] ?: "")
    }
}
