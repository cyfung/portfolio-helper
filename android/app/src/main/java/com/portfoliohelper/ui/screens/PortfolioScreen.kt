package com.portfoliohelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun PortfolioScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val positions by vm.positions.collectAsState()
    val marketData by vm.marketData.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editPosition by remember { mutableStateOf<Position?>(null) }

    Scaffold(
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
                .background(ext.bgPrimary)
                .padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ── Summary cards ────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(
                        label = "Portfolio Value",
                        value = formatCurrency(totals.totalMktVal),
                        modifier = Modifier.weight(1f)
                    )
                    val changeColor = changeColor(totals.dayChangeDollars)
                    SummaryCard(
                        label = "Day Change",
                        value = "${formatSignedCurrency(totals.dayChangeDollars)} (${
                            formatSignedPct(
                                totals.dayChangePct
                            )
                        })",
                        valueColor = changeColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Table header ─────────────────────────────────────────────────
            item {
                TableHeader(
                    listOf(
                        "Symbol" to 1.2f,
                        "Mark" to 2.5f,
                        "P&L" to 1.3f
                    )
                )
                Divider()
            }

            // ── Position rows ─────────────────────────────────────────────────
            items(positions, key = { it.symbol }) { pos ->
                val quote = marketData[pos.symbol]
                PositionRow(
                    pos = pos,
                    quote = quote,
                    onEdit = { editPosition = pos },
                    onDelete = { vm.deletePosition(pos.symbol) }
                )
                Divider()
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

@Composable
fun PositionRow(
    pos: Position,
    quote: YahooQuote?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = MaterialTheme.ext
    val mark = quote?.regularMarketPrice ?: quote?.previousClose
    val close = quote?.previousClose ?: quote?.regularMarketPrice
    val dayPct = if (mark != null && close != null && close != 0.0)
        (mark - close) / close * 100.0 else 0.0
    val pnl = if (mark != null && close != null) (mark - close) * pos.quantity else 0.0

    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.bgPrimary)
            .clickable { showActions = !showActions }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Symbol
        Text(
            pos.symbol,
            modifier = Modifier.weight(1.2f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = ext.textPrimary
        )

        // Mark Price + Day % Pill
        Row(
            modifier = Modifier.weight(2.5f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoText(
                text = if (mark != null) formatSmart(mark) else "—",
                color = ext.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(Modifier.width(8.dp))
            DayPctPill(dayPct)
        }

        // P&L
        val pnlColor = changeColor(pnl)
        MonoText(
            text = formatSignedCurrency(pnl),
            color = pnlColor,
            fontSize = 15.sp,
            modifier = Modifier.weight(1.3f),
        )
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
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit", fontSize = 12.sp)
            }
            TextButton(onClick = { onDelete(); showActions = false }) {
                Icon(
                    Icons.Default.Delete, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.ext.negative
                )
                Spacer(Modifier.width(4.dp))
                Text("Delete", fontSize = 12.sp, color = MaterialTheme.ext.negative)
            }
        }
    }
}

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
                    symbol, { symbol = it.uppercase() }, label = { Text("Symbol") },
                    enabled = initial == null, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    qty, { qty = it }, label = { Text("Quantity") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    targetWt, { targetWt = it }, label = { Text("Target Weight %") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    groups,
                    { groups = it },
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

private fun formatQty(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else "%.2f".format(q)
