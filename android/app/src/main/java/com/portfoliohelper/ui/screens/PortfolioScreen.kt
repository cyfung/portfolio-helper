package com.portfoliohelper.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.Position
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.ui.components.*
import com.portfoliohelper.ui.theme.ext

// ── Column indices — single source of truth ───────────────────────────────────
private const val COL_MARK = 0
private const val COL_PNL = 1
private const val COL_WEIGHT = 2
private val COLUMN_LABELS = listOf("Mark", "P&L", "Weight")

// ── Pre-computed display data for a single position ───────────────────────────
private data class StockDisplayData(
    val symbol: String,
    val dayPct: Double,
    val currentWeight: Double,
    val targetWeight: Double,
    // Pre-formatted strings
    val fmtMark: String,
    val fmtPnl: String,
    val pnlColor: Color,
)

@Composable
private fun buildStockDisplayData(
    pos: Position,
    quote: YahooQuote?,
    prices: Map<String, YahooQuote>,
    grossValue: Double,
    pnlDisplayMode: String,
    displayCurrency: String
): StockDisplayData {
    val rawMark = quote?.regularMarketPrice ?: quote?.previousClose
    val rawClose = quote?.previousClose ?: quote?.regularMarketPrice
    
    val currency = quote?.currency ?: "USD"
    val isPence = currency.length == 3 && currency[2].isLowerCase()
    val normalizedCcy = if (isPence) currency.uppercase() else currency

    val rateToUsd = if (normalizedCcy == "USD") 1.0 else {
        val pair = "${normalizedCcy}USD=X"
        prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
    }

    val multiplierToUsd = if (isPence) rateToUsd / 100.0 else rateToUsd

    val markUsd = rawMark?.let { it * multiplierToUsd }
    val closeUsd = rawClose?.let { it * multiplierToUsd }

    val dayPct = if (markUsd != null && closeUsd != null && closeUsd != 0.0)
        (markUsd - closeUsd) / closeUsd * 100.0 else 0.0

    // USD to Display Rate
    val usdToDisplayRate = if (displayCurrency == "USD") 1.0 else {
        val pair = "${displayCurrency}USD=X"
        val rateToUsdVal = prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: 1.0
        if (rateToUsdVal != 0.0) 1.0 / rateToUsdVal else 1.0
    }

    // P&L calculation based on mode
    val pnl = if (pnlDisplayMode == "NATIVE") {
        if (rawMark != null && rawClose != null) (rawMark - rawClose) * pos.quantity else 0.0
    } else {
        if (markUsd != null && closeUsd != null) (markUsd - closeUsd) * pos.quantity * usdToDisplayRate else 0.0
    }

    val currentValUsd = if (markUsd != null) markUsd * pos.quantity else 0.0
    val currentWeight = if (grossValue > 0) (currentValUsd * usdToDisplayRate / grossValue) * 100.0 else 0.0

    return StockDisplayData(
        symbol = pos.symbol,
        dayPct = dayPct,
        currentWeight = currentWeight,
        targetWeight = pos.targetWeight,
        fmtMark = if (rawMark != null) formatSmart(rawMark) else "—",
        fmtPnl = if (pnlDisplayMode == "NATIVE") {
            formatSigned(pnl)
        } else {
            // Note: formatSignedCurrency usually prepends $, we might need a generic formatSigned if displayCurrency is not USD
            if (displayCurrency == "USD") formatSignedCurrency(pnl) else formatSigned(pnl)
        },
        pnlColor = changeColor(pnl),
    )
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PortfolioScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val positions by vm.positions.collectAsState()
    val marketData by vm.marketData.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()
    val pnlMode by vm.pnlDisplayMode.collectAsState()
    val displayCcy by vm.displayCurrency.collectAsState()
    val cashEntries by vm.cashEntries.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editPosition by remember { mutableStateOf<Position?>(null) }

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bgPrimary)
    ) {
        val screenWidth = maxWidth

        // ── Build display data once — reused for measurement and row rendering ─
        val stockData = positions.map { pos ->
            buildStockDisplayData(pos, marketData[pos.symbol], marketData, totals.stockGrossValue, pnlMode, displayCcy)
        }

        val widthMeasureData = stockData + StockDisplayData(
            symbol = "WWWW.PA",
            dayPct = -11.11,
            currentWeight = 22.2,
            targetWeight = 99.9,
            fmtMark = "888.88",
            fmtPnl = "-888.88",
            pnlColor = Color.Green,
        )

        val sampleSymbol = widthMeasureData.maxBy { it.symbol.length }.symbol
        val sampleMark = widthMeasureData.maxBy {
            it.fmtMark.length + formatSignedPct(it.dayPct).length
        }
        val samplePnl = widthMeasureData.maxBy { it.fmtPnl.length }.fmtPnl
        val sampleWeight = widthMeasureData.maxBy {
            val diffWeight = it.currentWeight - it.targetWeight
            val fmtCurWeight = "%.1f".format(it.currentWeight)
            val fmtTgtWeight = "%.1f".format(it.targetWeight)
            val fmtDiffWeight = "%.1f".format(diffWeight)
            fmtCurWeight.length + fmtTgtWeight.length + fmtDiffWeight.length
        }
        MeasureTableLayout(
            screenWidth = screenWidth,
            frozenContent = {
                // Mirror the exact padding used in the real frozen cell
                Text(
                    text = sampleSymbol,
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            },
            columnContents = listOf(
                // COL_MARK
                {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MonoText(
                            text = sampleMark.fmtMark,
                            fontWeight = FontWeight.Normal,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.width(4.dp))
                        DayPctPill(sampleMark.dayPct)
                    }
                },
                // COL_PNL
                {
                    MonoText(
                        text = samplePnl,
                        fontWeight = FontWeight.Light,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
                // COL_WEIGHT
                {
                    WeightBreakdown(
                        current = sampleWeight.currentWeight,
                        target = sampleWeight.targetWeight,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
            ),
        ) { layout ->

            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0), // handled by Scaffold in MainActivity
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { showAddDialog = true },
                        containerColor = ext.actionPositive
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add position",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(start = layout.horizontalOffset),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // ── Summary cards ─────────────────────────────────────
                    item {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val totalValue = totals.stockGrossValue + cashTotals.totalUsd
                                val prevTotalValue = totalValue - totals.dayChange
                                val totalChangePct = if (prevTotalValue != 0.0)
                                    (totals.dayChange / prevTotalValue) * 100.0 else 0.0
                                val changeColor = changeColor(totals.dayChange)

                                SummaryCard(
                                    label = "Portfolio Value",
                                    value = if (totals.isReady) (if (displayCcy == "USD") formatCurrency(totalValue) else formatSmart(totalValue))else "N/A",
                                    subValue = if (totals.isReady) {
                                        "${if (displayCcy == "USD") formatSignedCurrency(totals.dayChange) else formatSigned(totals.dayChange)} (${
                                            formatSignedPct(totalChangePct)
                                        })"
                                    } else null,
                                    subValueColor = changeColor,
                                    modifier = Modifier.weight(1f)
                                )
                                SummaryCard(
                                    label = "Gross Value",
                                    value = if (totals.isReady) (if (displayCcy == "USD") formatCurrency(totals.stockGrossValue) else formatSmart(totals.stockGrossValue)) else "N/A",
                                    subValue = if (totals.isReady) {
                                        "${if (displayCcy == "USD") formatSignedCurrency(totals.dayChange) else formatSigned(totals.dayChange)} (${
                                            formatSignedPct(totals.dayChangePct)
                                        })"
                                    } else null,
                                    subValueColor = changeColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // ── Table header ──────────────────────────────────────
                    item {
                        TableHeader(
                            firstColumn = "Symbol" to layout.frozenWidth,
                            otherColumns = COLUMN_LABELS.zip(layout.columnWidths),
                            scrollState = if (layout.isScrollable) scrollState else null,
                        )
                        Divider()
                    }

                    // ── Position rows ─────────────────────────────────────
                    items(positions, key = { it.symbol }) { pos ->
                        val display = stockData.firstOrNull { it.symbol == pos.symbol } ?: return@items
                        PositionRow(
                            pos = pos,
                            display = display,
                            onEdit = { editPosition = pos },
                            onDelete = { vm.deletePosition(pos.symbol) },
                            scrollState = scrollState,
                            layout = layout,
                        )
                        Divider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        PositionDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { vm.upsertPosition(it); showAddDialog = false }
        )
    }

    editPosition?.let { pos ->
        PositionDialog(
            initial = pos,
            onDismiss = { editPosition = null },
            onSave = { vm.upsertPosition(it); editPosition = null }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PositionRow(
    pos: Position,
    display: StockDisplayData,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    scrollState: ScrollState,
    layout: TableLayout,
) {
    val ext = MaterialTheme.ext

    var showActions by remember { mutableStateOf(false) }

    val markW = layout.columnWidths[COL_MARK]
    val pnlW = layout.columnWidths[COL_PNL]
    val weightW = layout.columnWidths[COL_WEIGHT]

    val scrollMod = if (layout.isScrollable)
        Modifier.horizontalScroll(scrollState) else Modifier

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.bgPrimary)
                .clickable { showActions = !showActions }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frozen column
            Text(
                pos.symbol,
                modifier = Modifier
                    .width(layout.frozenWidth)
                    .padding(start = 12.dp),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = ext.textPrimary
            )

            // Scrollable columns
            Row(
                modifier = scrollMod.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mark + day % pill
                Row(
                    modifier = Modifier.width(markW),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = display.fmtMark,
                        color = ext.textPrimary,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    DayPctPill(display.dayPct)
                }

                // P&L
                MonoText(
                    text = display.fmtPnl,
                    color = display.pnlColor,
                    fontWeight = FontWeight.Light,
                    fontSize = 15.sp,
                    modifier = Modifier.width(pnlW),
                )

                // Weight breakdown
                WeightBreakdown(
                    current = display.currentWeight,
                    target = display.targetWeight,
                    modifier = Modifier.width(weightW),
                )
            }
        }

        // Inline action row
        if (showActions) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ext.bgSecondary)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onEdit(); showActions = false }) {
                    Icon(
                        Icons.Default.Edit, contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", fontSize = 12.sp)
                }
                TextButton(onClick = { onDelete(); showActions = false }) {
                    Icon(
                        Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.ext.negative
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp, color = MaterialTheme.ext.negative)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PositionDialog(
    initial: Position?,
    onDismiss: () -> Unit,
    onSave: (Position) -> Unit
) {
    var symbol by remember { mutableStateOf(initial?.symbol ?: "") }
    var qty by remember { mutableStateOf(initial?.quantity?.toString() ?: "") }
    var targetWt by remember { mutableStateOf(initial?.targetWeight?.toString() ?: "") }
    var groups by remember { mutableStateOf(initial?.groups ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Position" else "Edit ${initial.symbol}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    symbol, { symbol = it.uppercase() },
                    label = { Text("Symbol") },
                    enabled = initial == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    qty, { qty = it },
                    label = { Text("Quantity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    targetWt, { targetWt = it },
                    label = { Text("Target Weight %") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    groups, { groups = it },
                    label = { Text("Groups (e.g. '1.0 Tech; 0.5 Growth')") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pos = Position(
                    symbol = symbol.trim().uppercase(),
                    quantity = qty.toDoubleOrNull() ?: 0.0,
                    targetWeight = targetWt.toDoubleOrNull() ?: 0.0,
                    groups = groups.trim()
                )
                if (pos.symbol.isNotEmpty()) onSave(pos)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
