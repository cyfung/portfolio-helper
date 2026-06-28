package com.portfoliohelper.data.repository

import java.time.LocalDate

object LetfEstPriceCalculator {
    suspend fun compute(
        components: List<Pair<Double, String>>,
        quote: YahooQuote?,
        nav: NavSnapshot?,
        quoteProvider: (String) -> YahooQuote?,
        historicalPriceProvider: suspend (String, LocalDate, LocalDate) -> Map<LocalDate, Double>
    ): Double? {
        if (components.isEmpty()) return null
        quote ?: return null
        val stockDate = quote.localDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        if (nav?.nav != null && nav.asOfDate != null && stockDate != null) {
            when {
                nav.asOfDate == stockDate -> return nav.nav
                nav.asOfDate < stockDate -> {
                    computeFromReferenceCloses(components, nav.nav, quoteProvider) { sym ->
                        historicalPriceProvider(sym, nav.asOfDate, nav.asOfDate)[nav.asOfDate]
                    }?.let { return it }

                    if (isPreviousTradingDate(quote.symbol, stockDate, nav.asOfDate, historicalPriceProvider)) {
                        return computeFromReferenceCloses(components, nav.nav, quoteProvider) { sym ->
                            quoteProvider(sym)?.previousClose
                        }
                    }

                    return null
                }
            }
        }

        if (nav == null && stockDate != null) {
            val referenceDate = latestTradingDateBefore(quote.symbol, stockDate, historicalPriceProvider)
            if (referenceDate != null) {
                val basePrice = historicalPriceProvider(quote.symbol, referenceDate, referenceDate)[referenceDate]
                if (basePrice != null) {
                    computeFromReferenceCloses(components, basePrice, quoteProvider) { sym ->
                        historicalPriceProvider(sym, referenceDate, referenceDate)[referenceDate]
                    }?.let { return it }
                }
            }
        }

        val closePrice = quote.previousClose ?: return null
        val basePrice = nav?.nav ?: closePrice
        return computeFromReferenceCloses(components, basePrice, quoteProvider) { sym ->
            quoteProvider(sym)?.previousClose
        }
    }

    private suspend fun computeFromReferenceCloses(
        components: List<Pair<Double, String>>,
        basePrice: Double,
        quoteProvider: (String) -> YahooQuote?,
        referenceClose: suspend (String) -> Double?
    ): Double? {
        var sumComponent = 0.0
        for ((mult, sym) in components) {
            val compQuote = quoteProvider(sym) ?: return null
            val compMark = compQuote.regularMarketPrice ?: return null
            val compClose = referenceClose(sym) ?: return null
            if (compClose == 0.0) return null
            sumComponent += mult * (compMark - compClose) / compClose
        }
        return (1.0 + sumComponent) * basePrice
    }

    private suspend fun isPreviousTradingDate(
        symbol: String,
        stockDate: LocalDate,
        candidateDate: LocalDate,
        historicalPriceProvider: suspend (String, LocalDate, LocalDate) -> Map<LocalDate, Double>
    ): Boolean {
        val endDate = stockDate.minusDays(1)
        if (candidateDate > endDate) return false
        return historicalPriceProvider(symbol, candidateDate, endDate).keys.maxOrNull() == candidateDate
    }

    private suspend fun latestTradingDateBefore(
        symbol: String,
        stockDate: LocalDate,
        historicalPriceProvider: suspend (String, LocalDate, LocalDate) -> Map<LocalDate, Double>
    ): LocalDate? =
        historicalPriceProvider(symbol, stockDate.minusDays(10), stockDate.minusDays(1)).keys.maxOrNull()
}

fun parseLetfDefinition(raw: String): List<Pair<Double, String>> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    val tokens = if (trimmed.contains(",")) {
        trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    }
    val components = mutableListOf<Pair<Double, String>>()
    var i = 0
    while (i + 1 < tokens.size) {
        val mult = tokens[i].toDoubleOrNull() ?: break
        components += mult to tokens[i + 1].uppercase()
        i += 2
    }
    return components
}
