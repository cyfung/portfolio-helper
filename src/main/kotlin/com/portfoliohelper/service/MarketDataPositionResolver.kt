package com.portfoliohelper.service

import com.portfoliohelper.tws.StockPosition

data class MarketDataPosition(val symbol: String, val quantity: Double)

object MarketDataPositionResolver {
    fun resolve(
        positions: List<StockPosition>,
        exchangeSuffixes: Map<String, String>,
        exchangeSymbolMinWidths: Map<String, Int>,
    ): List<MarketDataPosition> {
        val quantitiesBySymbol = linkedMapOf<String, Double>()
        positions.forEach { position ->
            val symbol = MarketDataSymbolResolver.resolve(
                symbol = position.symbol,
                exchange = position.exchange,
                exchangeSuffixes = exchangeSuffixes,
                exchangeSymbolMinWidths = exchangeSymbolMinWidths,
            )
            quantitiesBySymbol[symbol] = (quantitiesBySymbol[symbol] ?: 0.0) + position.qty
        }
        return quantitiesBySymbol.map { (symbol, quantity) -> MarketDataPosition(symbol, quantity) }
    }
}
