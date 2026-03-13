package com.ibviewer.ui.screens

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
import com.ibviewer.MainViewModel
import com.ibviewer.data.model.CashEntry
import com.ibviewer.ui.components.*
import com.ibviewer.ui.theme.ext
import kotlin.math.abs

@Composable
fun CashScreen(vm: MainViewModel) {
    val ext         = MaterialTheme.ext
    val cashEntries by vm.cashEntries.collectAsState()
    val cashTotals  by vm.cashTotals.collectAsState()
    val totals      by vm.portfolioTotals.collectAsState()
    val fxRates     by vm.fxRates.collectAsState()

    var showAddDialog  by remember { mutableStateOf(false) }
    var editEntry      by remember { mutableStateOf<CashEntry?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick        = { showAddDialog = true },
                containerColor = ext.actionPositive
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add cash entry",
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().background(ext.bgPrimary).padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ── Summary cards ────────────────────────────────────────────────
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard("Cash Total",
                        formatCurrency(cashTotals.totalUsd), modifier = Modifier.weight(1f))

                    val marginUsd = cashTotals.marginUsd
                    val equity    = totals.totalMktVal + marginUsd
                    val marginPct = if (equity != 0.0) abs(marginUsd / equity) * 100.0 else 0.0
                    val marginColor = when {
                        marginPct > 40.0 -> ext.negative
                        marginPct > 20.0 -> ext.warning
                        else             -> ext.textPrimary
                    }
                    SummaryCard("Margin",
                        "${formatCurrency(abs(marginUsd))} (${formatPct(marginPct, 1)})",
                        valueColor = if (marginUsd < 0) marginColor else ext.textTertiary,
                        modifier   = Modifier.weight(1f))
                }
            }

            // ── Table header ──────────────────────────────────────────────────
            item {
                TableHeader(listOf("Label" to 1.5f, "" to 0.4f, "Amount" to 1.5f, "USD" to 1.3f))
                Divider()
            }

            // ── Cash rows ─────────────────────────────────────────────────────
            items(cashEntries, key = { it.id }) { entry ->
                CashEntryRow(
                    entry    = entry,
                    fxRates  = fxRates,
                    onEdit   = { editEntry = entry },
                    onDelete = { vm.deleteCashEntry(entry) }
                )
                Divider()
            }
        }
    }

    if (showAddDialog) {
        CashEntryDialog(
            initial   = null,
            onDismiss = { showAddDialog = false },
            onSave    = { vm.upsertCashEntry(it); showAddDialog = false }
        )
    }

    editEntry?.let { entry ->
        CashEntryDialog(
            initial   = entry,
            onDismiss = { editEntry = null },
            onSave    = { vm.upsertCashEntry(it); editEntry = null }
        )
    }
}

@Composable
fun CashEntryRow(
    entry: CashEntry,
    fxRates: Map<String, Double>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val ext  = MaterialTheme.ext
    val rate = if (entry.currency == "USD") 1.0 else fxRates[entry.currency]
    val usd  = rate?.let { entry.amount * it }

    var showActions by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.bgPrimary)
                .clickable { showActions = !showActions }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label
            Text(
                text = entry.label,
                modifier = Modifier.weight(1.5f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ext.textPrimary
            )

            // Margin Badge
            Box(modifier = Modifier.weight(0.4f), contentAlignment = Alignment.CenterStart) {
                if (entry.isMargin) CashTypeBadge("M")
            }

            // Raw Amount + Currency on same line
            Row(
                modifier = Modifier.weight(1.5f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText(
                    text = "%,.2f".format(entry.amount),
                    color = if (entry.amount < 0) ext.negative else ext.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = entry.currency,
                    fontSize = 11.sp,
                    color = ext.textTertiary,
                    fontWeight = FontWeight.Medium
                )
            }

            // USD Converted
            MonoText(
                text = if (usd != null) formatCurrency(usd) else "—",
                color = if (usd != null && usd < 0) ext.negative else ext.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1.3f)
            )
        }

        if (showActions) {
            Row(
                modifier              = Modifier.fillMaxWidth().background(ext.bgSecondary)
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
                    Icon(Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = ext.negative)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp, color = ext.negative)
                }
            }
        }
    }
}

@Composable
fun CashEntryDialog(initial: CashEntry?, onDismiss: () -> Unit, onSave: (CashEntry) -> Unit) {
    var label    by remember { mutableStateOf(initial?.label ?: "") }
    var currency by remember { mutableStateOf(initial?.currency ?: "USD") }
    var amount   by remember { mutableStateOf(initial?.amount?.toString() ?: "") }
    var isMargin by remember { mutableStateOf(initial?.isMargin ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Cash Entry" else "Edit Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label,    { label    = it }, label = { Text("Label") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(currency, { currency = it.uppercase() }, label = { Text("Currency") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount,   { amount   = it }, label = { Text("Amount (negative = loan)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isMargin, { isMargin = it })
                    Text("Margin / Loan", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entry = CashEntry(
                    id       = initial?.id ?: 0,
                    label    = label.trim(),
                    currency = currency.trim().uppercase(),
                    amount   = amount.toDoubleOrNull() ?: 0.0,
                    isMargin = isMargin
                )
                if (entry.label.isNotEmpty()) onSave(entry)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
