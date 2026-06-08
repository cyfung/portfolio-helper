package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import com.portfoliohelper.util.appJson
import kotlinx.serialization.Serializable

@Serializable
data class HybridAllocStrategyConfig(
    val id: String,
    val label: String,
    val first: String,
    val second: String,
    val firstRatio: Double = 1.0,
    val secondRatio: Double = 1.0,
)

object HybridAllocStrategyRegistry {
    val defaultStrategies: List<HybridAllocStrategyConfig> = listOf(
        HybridAllocStrategyConfig(
            id = "HYBRID_WATERFALL_FULL_REBALANCE",
            label = "Hybrid Waterfall/Full",
            first = MarginRebalanceMode.WATERFALL.name,
            second = MarginRebalanceMode.FULL_REBALANCE.name,
            firstRatio = 1.0,
            secondRatio = 1.0,
        ),
    )

    private val customBaseModes = MarginRebalanceMode.entries
        .filterNot { it.name.startsWith("HYBRID_") }
        .associateBy { it.name }

    fun strategies(): List<HybridAllocStrategyConfig> {
        val raw = runCatching { AppConfig.get(AppConfig.KEY_HYBRID_ALLOC_STRATEGIES) }.getOrDefault("")
        val parsed = raw.takeIf { it.isNotBlank() }?.let {
            runCatching { appJson.decodeFromString<List<HybridAllocStrategyConfig>>(it) }.getOrNull()
        }.orEmpty()
        val cleaned = parsed.mapNotNull(::normalize)
        return cleaned.ifEmpty { defaultStrategies }
    }

    fun find(id: String?): HybridAllocStrategyConfig? {
        val normalizedId = normalizeId(id) ?: return null
        return strategies().firstOrNull { it.id == normalizedId }
    }

    fun baseMode(id: String?): MarginRebalanceMode? =
        normalizeId(id)?.let { customBaseModes[it] }

    fun modeLabel(id: String): String =
        find(id)?.label ?: when (normalizeId(id) ?: id) {
            MarginRebalanceMode.CURRENT_WEIGHT.name -> "Cur Wt"
            MarginRebalanceMode.PROPORTIONAL.name -> "Tgt Wt"
            MarginRebalanceMode.FULL_REBALANCE.name -> "Full"
            MarginRebalanceMode.UNDERVALUED_PRIORITY.name -> "UVal"
            MarginRebalanceMode.WATERFALL.name -> "WaterFall"
            MarginRebalanceMode.DAILY.name -> "Daily"
            else -> id
        }

    private fun normalizeId(id: String?): String? =
        id
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9_]+"), "_")

    private fun normalize(config: HybridAllocStrategyConfig): HybridAllocStrategyConfig? {
        val id = normalizeId(config.id) ?: return null
        if (id.isBlank()) return null
        val first = normalizeId(config.first) ?: return null
        val second = normalizeId(config.second) ?: return null
        if (first !in customBaseModes || second !in customBaseModes) return null
        return config.copy(
            id = id,
            label = config.label.trim().ifBlank { id },
            first = first,
            second = second,
            firstRatio = config.firstRatio.takeIf { it >= 0.0 } ?: 1.0,
            secondRatio = config.secondRatio.takeIf { it >= 0.0 } ?: 1.0,
        )
    }
}
