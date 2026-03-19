package com.portfoliohelper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val TABLE_MAX_WIDTH = 600.dp

data class TableLayout(
    val frozenWidth: Dp,
    val columnWidths: List<Dp>,
    val isScrollable: Boolean,
    val horizontalOffset: Dp,
)

private fun resolveTableLayout(
    screenWidth: Dp,
    frozenMinWidth: Dp,
    columnMinWidths: List<Dp>,
): TableLayout {
    val totalColMin = columnMinWidths.fold(0.dp) { acc, w -> acc + w }
    val totalMin = frozenMinWidth + totalColMin

    if (totalMin > screenWidth) {
        return TableLayout(
            frozenWidth = frozenMinWidth,
            columnWidths = columnMinWidths,
            isScrollable = true,
            horizontalOffset = 0.dp,
        )
    }

    val tableWidth = minOf(screenWidth, TABLE_MAX_WIDTH)
    val horizontalOffset = if (screenWidth > TABLE_MAX_WIDTH)
        (screenWidth - TABLE_MAX_WIDTH) / 2 else 0.dp
    val scrollableBudget = tableWidth - frozenMinWidth

    val columnWidths = columnMinWidths.map { colMin ->
        (scrollableBudget * (colMin.value / totalColMin.value))
    }

    return TableLayout(
        frozenWidth = frozenMinWidth,
        columnWidths = columnWidths,
        isScrollable = false,
        horizontalOffset = horizontalOffset,
    )
}

/**
 * Measures [frozenContent] and each item in [columnContents] at unconstrained
 * width to discover natural min widths, then resolves a [TableLayout] and
 * passes it to [content] for the real render pass.
 */
@Composable
fun MeasureTableLayout(
    screenWidth: Dp,
    frozenContent: @Composable () -> Unit,
    columnContents: List<@Composable () -> Unit>,
    content: @Composable (TableLayout) -> Unit,
) {
    val density = LocalDensity.current

    SubcomposeLayout { constraints ->
        val frozenPx = subcompose("frozen", frozenContent)
            .map { it.measure(Constraints()) }
            .maxOfOrNull { it.width } ?: 0

        val colMinPxList = columnContents.mapIndexed { i, cell ->
            subcompose("col_$i", cell)
                .map { it.measure(Constraints()) }
                .maxOfOrNull { it.width } ?: 0
        }

        val frozenMinDp = with(density) { frozenPx.toDp() }
        val colMinDps = colMinPxList.map { with(density) { it.toDp() } }
        val layout = resolveTableLayout(screenWidth, frozenMinDp, colMinDps)

        val contentPlaceables = subcompose("content") { content(layout) }
            .map { it.measure(constraints) }

        val totalH = contentPlaceables.sumOf { it.height }
        layout(constraints.maxWidth, totalH) {
            var y = 0
            contentPlaceables.forEach { p ->
                p.placeRelative(0, y)
                y += p.height
            }
        }
    }
}
