package com.portfoliohelper.service

import java.util.Properties

object CurrencyConventions {

    private val daysInYearMap: Map<String, Int>

    init {
        val props = Properties()
        CurrencyConventions::class.java.classLoader
            .getResourceAsStream("currency-conventions.properties")
            ?.use { props.load(it) }
        daysInYearMap = props.entries.associate { (k, v) ->
            k.toString().uppercase() to (v.toString().toIntOrNull() ?: 360)
        }
    }

    /** Returns the money-market day-count convention for the given currency (default 360). */
    fun getDaysInYear(currency: String): Int = daysInYearMap[currency.uppercase()] ?: 360
}
