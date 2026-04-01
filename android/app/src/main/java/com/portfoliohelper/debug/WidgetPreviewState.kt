package com.portfoliohelper.debug

import com.portfoliohelper.data.repository.MarginCheckStats

enum class WidgetPreviewState {
    NONE,
    NO_DATA,
    RUNNING,
    FAILED,
    ALERT,
    FX_SUGGESTION,
    OK;

    val label: String get() = when (this) {
        NONE         -> "None (live)"
        NO_DATA      -> "No Data"
        RUNNING      -> "Running"
        FAILED       -> "Failed"
        ALERT        -> "Alert"
        FX_SUGGESTION -> "FX Suggestion"
        OK           -> "OK"
    }
}

object WidgetPreviewMocks {
    fun buildStats(state: WidgetPreviewState): MarginCheckStats? = when (state) {
        WidgetPreviewState.NONE,
        WidgetPreviewState.NO_DATA    -> null
        WidgetPreviewState.RUNNING    -> MarginCheckStats(
            runTime = 0L,
            oldestDataTime = 0L,
            triggeredPortfolios = emptyList(),
            isRunning = true,
            runStartTime = System.currentTimeMillis() - 45_000L
        )
        WidgetPreviewState.FAILED     -> MarginCheckStats(
            runTime = System.currentTimeMillis(),
            oldestDataTime = 0L,
            triggeredPortfolios = emptyList(),
            errorMessage = "[Preview] Connection refused"
        )
        WidgetPreviewState.ALERT      -> MarginCheckStats(
            runTime = System.currentTimeMillis(),
            oldestDataTime = System.currentTimeMillis() - 5 * 60_000L,
            triggeredPortfolios = listOf("Main", "Retirement")
        )
        WidgetPreviewState.FX_SUGGESTION -> MarginCheckStats(
            runTime = System.currentTimeMillis(),
            oldestDataTime = System.currentTimeMillis() - 10 * 60_000L,
            triggeredPortfolios = emptyList(),
            currencySuggestionText = "Convert loan balance to EUR"
        )
        WidgetPreviewState.OK         -> MarginCheckStats(
            runTime = System.currentTimeMillis(),
            oldestDataTime = System.currentTimeMillis() - 8 * 60_000L,
            triggeredPortfolios = emptyList()
        )
    }
}
