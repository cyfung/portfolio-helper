package com.portfoliohelper.service

import kotlinx.serialization.Serializable

@Serializable
enum class WarningCategory {
    NULL_DATA,
    FILLED_DATA,
    SPLIT_REPAIR,
    TICKER_MAPPING,
    OTHER,
}

@Serializable
data class AnalysisWarning(
    val category: WarningCategory,
    val message: String,
    val occurrences: Int = 1,
) {
    init {
        require(occurrences > 0) { "Warning occurrences must be positive." }
    }
}
