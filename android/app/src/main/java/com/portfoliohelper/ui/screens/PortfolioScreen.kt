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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.Position
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.ui.components.*
import com.portfoliohelper.ui.theme.ext

// ── Column indices — single source of truth ───────────────────────────────────
private const val COL_MARK   = 0
private const val COL_PNL    = 1
private const val COL_WEIGHT = 2
private val COLUMN_LABELS = listOf("Mark", "P&L", "Weight")

@Composable
fun PortfolioScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val positions  by vm.positions.collectAsState()
    val marketData by vm.marketData.collectAsState()
    val totals     by vm.portfolioTotals.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editPosition  by remember { mutableStateOf<Position?>(null) }

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bgPrimary)
    ) {
        val screenWidth = maxWidth

        // ── Pick worst-case sample rows for measurement ───────────────────
        // We want the widest content that will actually appear in each column.

        // Frozen: longest symbol
        val sampleSymbol = positions.maxByOrNull { it.symbol.length }?.symbol ?: "WWWW"

        // Mark: longest formatted price + widest pill
        val sampleMark = positions
            .mapNotNull { pos ->
                val mark = marketData[pos.symbol]?.regularMarketPrice
                    ?: marketData[pos.symbol]?.previousClose
                    ?: return@mapNotNull null
                val close = marketData[pos.symbol]?.previousClose
                    ?: marketData[pos.symbol]?.regularMarketPrice
                    ?: return@mapNotNull null
                val dayPct = if (close != 0.0) (mark - close) / close * 100.0 else 0.0
                Triple(pos.symbol, mark, dayPct)
            }
            .maxByOrNull { (_, mark, dayPct) ->
                formatSmart(mark).length + formatSignedPct(dayPct).length
            }
        val sampleMarkPrice = sampleMark?.second ?: 8888888.88
        val sampleMarkPct   = sampleMark?.third  ?: -88.88

        // P&L: longest *formatted* string — not largest absolute value,
        // because formatSmart compresses large numbers (K/M/B) so e.g.
        // "-9,999.99" renders wider than "-10.00M".
        val samplePnl = positions
            .mapNotNull { pos ->
                val quote = marketData[pos.symbol] ?: return@mapNotNull null
                val mark  = quote.regularMarketPrice ?: quote.previousClose ?: return@mapNotNull null
                val close = quote.previousClose ?: quote.regularMarketPrice ?: return@mapNotNull null
                (mark - close) * pos.quantity
            }
            .maxByOrNull { formatSignedCurrency(it).length } ?: -99_999.99

        // Weight: longest current/target/diff combo
        data class WeightSample(val current: Double, val target: Double)
        val sampleWeight = positions
            .mapNotNull { pos ->
                val mark = marketData[pos.symbol]?.regularMarketPrice
                    ?: marketData[pos.symbol]?.previousClose
                    ?: return@mapNotNull null
                val currentVal    = mark * pos.quantity
                val currentWeight = if (totals.stockGrossValue > 0)
                    (currentVal / totals.stockGrossValue) * 100.0 else 0.0
                WeightSample(currentWeight, pos.targetWeight)
            }
            .maxByOrNull { s ->
                "%.1f".format(s.current).length +
                "%.1f".format(s.target).length +
                "%.1f".format(s.current - s.target).length
            } ?: WeightSample(88.88, 44.44)

        MeasureTableLayout(
            screenWidth = screenWidth,
            frozenContent = {
                // Mirror the exact padding used in the real frozen cell
                Text(
                    text       = sampleSymbol,
                    modifier   = Modifier.padding(start = 12.dp, end = 8.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize   = 15.sp,
                )
            },
            columnContents = listOf(
                // COL_MARK
                {
                    Row(
                        modifier           = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment  = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MonoText(
                            text       = formatSmart(sampleMarkPrice),
                            fontWeight = FontWeight.Normal,
                            fontSize   = 16.sp,
                        )
                        Spacer(Modifier.width(4.dp))
                        DayPctPill(sampleMarkPct)
                    }
                },
                // COL_PNL
                {
                    MonoText(
                        text       = formatSignedCurrency(samplePnl),
                        fontWeight = FontWeight.Light,
                        fontSize   = 15.sp,
                        modifier   = Modifier.padding(horizontal = 4.dp),
                    )
                },
                // COL_WEIGHT
                {
                    WeightBreakdown(
                        current = sampleWeight.current,
                        target  = sampleWeight.target,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
            ),
        ) { layout ->

            Scaffold(
                floatingActionButton = {
                    FloatingActionButton(
                        onClick        = { showAddDialog = true },
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val totalValue     = totals.stockGrossValue + cashTotals.totalUsd
                            val prevTotalValue = totalValue - totals.dayChangeDollars
                            val totalChangePct = if (prevTotalValue != 0.0)
                                (totals.dayChangeDollars / prevTotalValue) * 100.0 else 0.0
                            val changeColor = changeColor(totals.dayChangeDollars)

                            SummaryCard(
                                label    = "Portfolio Value",
                                value    = if (totals.isReady) formatCurrency(totalValue) else "N/A",
                                subValue = if (totals.isReady) {
                                    "${formatSignedCurrency(totals.dayChangeDollars)} (${
                                        formatSignedPct(totalChangePct)
                                    })"
                                } else null,
                                subValueColor = changeColor,
                                modifier = Modifier.weight(1f)
                            )
                            SummaryCard(
                                label    = "Gross Value",
                                value    = if (totals.isReady) formatCurrency(totals.stockGrossValue) else "N/A",
                                subValue = if (totals.isReady) {
                                    "${formatSignedCurrency(totals.dayChangeDollars)} (${
                                        formatSignedPct(totals.dayChangePct)
                                    })"
                                } else null,
                                subValueColor = changeColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // ── Table header ──────────────────────────────────────
                    item {
                        TableHeader(
                            firstColumn  = "Symbol" to layout.frozenWidth,
                            otherColumns = COLUMN_LABELS.zip(layout.columnWidths),
                            scrollState  = if (layout.isScrollable) scrollState else null,
                        )
                        Divider()
                    }

                    // ── Position rows ─────────────────────────────────────
                    items(positions, key = { it.symbol }) { pos ->
                        PositionRow(
                            pos        = pos,
                            quote      = marketData[pos.symbol],
                            grossValue = totals.stockGrossValue,
                            onEdit     = { editPosition = pos },
                            onDelete   = { vm.deletePosition(pos.symbol) },
                            scrollState = scrollState,
                            layout      = layout,
                        )
                        Divider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        PositionDialog(
            initial   = null,
            onDismiss = { showAddDialog = false },
            onSave    = { vm.upsertPosition(it); showAddDialog = false }
        )
    }

    editPosition?.let { pos ->
        PositionDialog(
            initial   = pos,
            onDismiss = { editPosition = null },
            onSave    = { vm.upsertPosition(it); editPosition = null }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PositionRow(
    pos:         Position,
    quote:       YahooQuote?,
    grossValue:  Double,
    onEdit:      () -> Unit,
    onDelete:    () -> Unit,
    scrollState: ScrollState,
    layout:      TableLayout,
) {
    val ext   = MaterialTheme.ext
    val mark  = quote?.regularMarketPrice ?: quote?.previousClose
    val close = quote?.previousClose ?: quote?.regularMarketPrice
    val dayPct = if (mark != null && close != null && close != 0.0)
        (mark - close) / close * 100.0 else 0.0
    val pnl = if (mark != null && close != null) (mark - close) * pos.quantity else 0.0

    val currentVal    = if (mark != null) mark * pos.quantity else 0.0
    val currentWeight = if (grossValue > 0) (currentVal / grossValue) * 100.0 else 0.0

    var showActions by remember { mutableStateOf(false) }

    val markW   = layout.columnWidths[COL_MARK]
    val pnlW    = layout.columnWidths[COL_PNL]
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
                modifier   = Modifier
                    .width(layout.frozenWidth)
                    .padding(start = 12.dp),
                fontWeight = FontWeight.Medium,
                fontSize   = 15.sp,
                color      = ext.textPrimary
            )

            // Scrollable columns
            Row(
                modifier          = scrollMod.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mark + day % pill
                Row(
                    modifier              = Modifier.width(markW),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    MonoText(
                        text       = if (mark != null) formatSmart(mark) else "—",
                        color      = ext.textPrimary,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 16.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    DayPctPill(dayPct)
                }

                // P&L
                MonoText(
                    text       = formatSignedCurrency(pnl),
                    color      = changeColor(pnl),
                    fontWeight = FontWeight.Light,
                    fontSize   = 15.sp,
                    modifier   = Modifier.width(pnlW),
                )

                // Weight breakdown
                WeightBreakdown(
                    current  = currentWeight,
                    target   = pos.targetWeight,
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
                verticalAlignment     = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onEdit(); showActions = false }) {
                    Icon(Icons.Default.Edit, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", fontSize = 12.sp)
                }
                TextButton(onClick = { onDelete(); showActions = false }) {
                    Icon(
                        Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint     = MaterialTheme.ext.negative
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
    initial:  Position?,
    onDismiss: () -> Unit,
    onSave:    (Position) -> Unit
) {
    var symbol   by remember { mutableStateOf(initial?.symbol ?: "") }
    var qty      by remember { mutableStateOf(initial?.quantity?.toString() ?: "") }
    var targetWt by remember { mutableStateOf(initial?.targetWeight?.toString() ?: "") }
    var groups   by remember { mutableStateOf(initial?.groups ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Position" else "Edit ${initial.symbol}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    symbol, { symbol = it.uppercase() },
                    label   = { Text("Symbol") },
                    enabled = initial == null,
                    singleLine = true,
                    modifier   = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    qty, { qty = it },
                    label      = { Text("Quantity") },
                    singleLine = true,
                    modifier   = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    targetWt, { targetWt = it },
                    label      = { Text("Target Weight %") },
                    singleLine = true,
                    modifier   = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    groups, { groups = it },
                    label    = { Text("Groups (e.g. '1.0 Tech; 0.5 Growth')") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pos = Position(
                    symbol       = symbol.trim().uppercase(),
                    quantity     = qty.toDoubleOrNull() ?: 0.0,
                    targetWeight = targetWt.toDoubleOrNull() ?: 0.0,
                    groups       = groups.trim()
                )
                if (pos.symbol.isNotEmpty()) onSave(pos)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
