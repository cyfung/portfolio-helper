package com.portfoliohelper.service

import java.util.Locale

object CurveNaming {
    const val NO_MARGIN = "No Margin"

    fun margin(index: Int, config: MarginConfig): String {
        val upper = HybridAllocStrategyRegistry.modeLabel(config.upperRebalanceMode)
        val lower = HybridAllocStrategyRegistry.modeLabel(config.lowerRebalanceMode)
        val modeSuffix =
            if (upper == lower) "($upper)"
            else "($upper\u2191/$lower\u2193)"
        return "Margin ${index + 1} ${percent(config.marginRatio)} $modeSuffix"
    }

    private fun percent(value: Double): String {
        val pct = value * 100.0
        val formatted =
            if (pct % 1.0 == 0.0) pct.toInt().toString()
            else String.format(Locale.US, "%.2f", pct).trimEnd('0').trimEnd('.')
        return "$formatted%"
    }
}
