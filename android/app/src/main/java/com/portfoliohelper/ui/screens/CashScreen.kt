package com.portfoliohelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.repository.IbkrInterestResult
import com.portfoliohelper.ui.components.Divider
import com.portfoliohelper.ui.components.MonoText
import com.portfoliohelper.ui.components.SummaryCard
import com.portfoliohelper.ui.components.formatCurrency
import com.portfoliohelper.ui.components.formatPct
import com.portfoliohelper.ui.components.formatSmart
import com.portfoliohelper.ui.theme.ExtendedColors
import com.portfoliohelper.ui.theme.ext
import java.util.Locale
import kotlin.math.abs

@Composable
fun CashScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val cashEntries by vm.cashEntries.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()
    val fxRates by vm.fxRates.collectAsState()
    val stockValues by vm.allPortfolioStockValuesUsd.collectAsState()
    val displayCurrency by vm.displayCurrency.collectAsState()
    val scalingPercent by vm.scalingPercent.collectAsState()
    val ibkrInterest by vm.ibkrInterest.collectAsState()

    LaunchedEffect(Unit) { vm.refreshIbkrRates() }

    var showAddDialog by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<CashEntry?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = ext.actionPositive
            ) {
                Icon(
                    Icons.Default.Add, contentDescription = "Add cash entry",
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (cashTotals.isReady) {
                        val cashTotal = cashTotals.cashTotal
                        SummaryCard(
                            "Cash Total",
                            formatCurrency(cashTotal),
                            valueColor = if (cashTotal < 0) ext.negative else ext.textPrimary,
                            subValue = "",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        SummaryCard("Cash Total", "N/A", subValue = "", modifier = Modifier.weight(1f))
                    }

                    if (totals.isReady) {
                        val margin = cashTotals.margin
                        val marginPct = totals.marginPct
                        if (margin >= 0) {
                            SummaryCard("Margin", "-", valueColor = ext.textPrimary, subValue = "", modifier = Modifier.weight(1f))
                        } else {
                            SummaryCard(
                                "Margin",
                                formatSmart(abs(margin)),
                                valueColor = ext.warning,
                                subValue = formatPct(marginPct, 1),
                                subValueColor = ext.warning,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        SummaryCard("Margin", "N/A", valueColor = ext.textPrimary, subValue = "", modifier = Modifier.weight(1f))
                    }
                }
            }

            item { Divider() }

            ibkrInterest?.let { snap ->
                item {
                    IbkrRatesSection(snap, displayCurrency, fxRates)
                    Divider()
                }
            }

            itemsIndexed(cashEntries, key = { _, it -> it.id }) { index, entry ->
                val showLabel = index == 0 || entry.label != cashEntries[index - 1].label
                CashEntryRow(
                    entry = entry,
                    fxRates = fxRates,
                    stockValues = stockValues,
                    displayCurrency = displayCurrency,
                    showLabel = showLabel,
                    scaling = scalingPercent,
                    onEdit = { editEntry = entry },
                    onDelete = { vm.deleteCashEntry(entry) }
                )
                Divider()
            }
        }
    }

    if (showAddDialog) {
        CashEntryDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { vm.upsertCashEntry(it); showAddDialog = false }
        )
    }

    editEntry?.let { entry ->
        CashEntryDialog(
            initial = entry,
            onDismiss = { editEntry = null },
            onSave = { vm.upsertCashEntry(it); editEntry = null }
        )
    }
}

@Composable
fun IbkrRatesSection(
    result: IbkrInterestResult,
    displayCurrency: String,
    fxRates: Map<String, Double>
) {
    val ext = MaterialTheme.ext
    val fxToDisplay = if (displayCurrency == "USD") 1.0 else 1.0 / (fxRates[displayCurrency] ?: 1.0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "IBKR Pro Rates",
            fontSize = 11.sp,
            color = ext.textTertiary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(2.dp))

        for (ci in result.perCurrency) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Col 1: Currency code
                MonoText(
                    ci.currency,
                    color = ext.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1.5f)
                )
                // Col 2: empty (icons placeholder)
                Box(Modifier.weight(0.4f))
                // Col 3: rate text, right-aligned
                Row(modifier = Modifier.weight(1.5f), horizontalArrangement = Arrangement.End) {
                    MonoText(ci.displayRateText, color = ext.textSecondary, fontSize = 13.sp)
                }
                // Col 4: daily interest in display currency, right-aligned
                val dailyDisplay = ci.dailyInterestUsd * fxToDisplay
                Row(
                    modifier = Modifier.weight(1.3f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        if (dailyDisplay > 0) formatCurrency(dailyDisplay) else "—",
                        color = ext.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )
        Spacer(Modifier.height(4.dp))

        val currentDisplay = result.currentDailyUsd * fxToDisplay
        IbkrSummaryRow(
            "Current Daily Interest",
            if (currentDisplay > 0) formatCurrency(currentDisplay) else "—",
            ext
        )

        val cheapestLabel = if (result.cheapestCcy != null) "Cheapest (${result.cheapestCcy})" else "Cheapest"
        val cheapestDisplay = result.cheapestDailyUsd * fxToDisplay
        IbkrSummaryRow(
            cheapestLabel,
            if (result.cheapestCcy != null) formatCurrency(cheapestDisplay) else "—",
            ext
        )

        val savingsDisplay = result.savingsUsd * fxToDisplay
        val showSavings = savingsDisplay >= 0.005
        IbkrSummaryRow(
            result.label,
            if (showSavings) formatCurrency(savingsDisplay) else "—",
            ext,
            highlight = showSavings
        )
    }
}

@Composable
private fun IbkrSummaryRow(
    label: String,
    value: String,
    ext: ExtendedColors,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = ext.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(
                value,
                fontSize = 12.sp,
                color = if (highlight) ext.warning else ext.textSecondary,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun CashEntryRow(
    entry: CashEntry,
    fxRates: Map<String, Double>,
    stockValues: Map<String, Pair<Double, Boolean>>,
    displayCurrency: String,
    showLabel: Boolean,
    scaling: Int? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = MaterialTheme.ext

    val scaledAmount = if (scaling != null && entry.portfolioRef == null)
        kotlin.math.round(entry.amount * scaling) / 100.0
    else
        entry.amount

    // 1. Calculate USD Value — null means broken/unknown ref (portfolio deleted or not yet synced)
    val valueUsd: Double? = if (entry.portfolioRef != null) {
        val refData = stockValues[entry.portfolioRef]
        if (refData == null) null else refData.first * entry.amount
    } else {
        val rateToUsd = if (entry.currency == "USD") 1.0 else fxRates[entry.currency] ?: 1.0
        scaledAmount * rateToUsd
    }

    // 2. Calculate Display Value
    val rateDisplayToUsd = if (displayCurrency == "USD") 1.0 else fxRates[displayCurrency] ?: 1.0
    val valueDisplay: Double? = valueUsd?.let { it / rateDisplayToUsd }

    // 3. Original Amount & Currency for Col 3
    val (actualAmount, actualCcy) = if (entry.portfolioRef != null) {
        valueUsd to "USD"
    } else {
        scaledAmount to entry.currency
    }

    var showActions by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.bgPrimary)
                .clickable { showActions = !showActions }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Col 1: Label
            Text(
                text = if (showLabel) entry.label else "",
                modifier = Modifier.weight(1.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ext.textSecondary
            )

            // Col 2: Icons (Margin + Ref)
            Row(
                modifier = Modifier.weight(0.4f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry.isMargin) {
                    SquareBadge("M", ext.warning)
                }
                if (entry.portfolioRef != null) {
                    SquareIconBadge(Icons.Default.NorthEast, Color(0xFF42A5F5))
                }
            }

            // Col 3: Actual Amount + Currency
            Row(
                modifier = Modifier.weight(1.5f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText(
                    text = if (actualAmount == null) "---" else formatCurrency(actualAmount),
                    color = ext.textTertiary,
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp
                )
                if (actualAmount != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = actualCcy,
                        fontSize = 11.sp,
                        color = ext.textTertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Col 4: Display Amount
            MonoText(
                text = if (valueDisplay == null) "---" else formatCurrency(valueDisplay),
                color = ext.textSecondary,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.weight(1.3f)
            )
        }

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
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = ext.negative)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp, color = ext.negative)
                }
            }
        }
    }
}

@Composable
fun SquareBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        )
    }
}

@Composable
fun SquareIconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = color
        )
    }
}

@Composable
fun CashEntryDialog(initial: CashEntry?, onDismiss: () -> Unit, onSave: (CashEntry) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var currency by remember { mutableStateOf(if (initial?.portfolioRef != null) "P" else (initial?.currency ?: "USD")) }
    
    val formattedInitialAmount = remember(initial) {
        initial?.amount?.let { 
            if (initial.portfolioRef != null) "%.2f".format(it) else "%,.2f".format(Locale.US, it)
        } ?: if (currency == "P") "1.00" else ""
    }
    var amount by remember { mutableStateOf(formattedInitialAmount) }
    var isMargin by remember { mutableStateOf(initial?.isMargin ?: false) }
    var portfolioRef by remember { mutableStateOf(initial?.portfolioRef ?: "") }

    val isValidAmount = remember(amount) {
        amount.isEmpty() || amount == "-" || amount.replace(",", "").toDoubleOrNull() != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Cash Entry" else "Edit Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    label = { Text("Currency (ISO or 'P')") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (currency == "P") {
                    OutlinedTextField(
                        value = portfolioRef,
                        onValueChange = { portfolioRef = it.lowercase() },
                        label = { Text("Portfolio Reference (Slug)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { input -> 
                        if (input.all { it.isDigit() || it == '.' || it == ',' || it == '-' }) {
                            amount = input
                        }
                    },
                    label = { Text(if (currency == "P") "Multiplier" else "Amount (negative = loan)") },
                    placeholder = { Text(if (currency == "P") "1.00" else "0.00") },
                    singleLine = true,
                    isError = !isValidAmount,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isMargin, { isMargin = it })
                    Text("Margin / Loan", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedAmount = amount.replace(",", "").toDoubleOrNull() ?: (if (currency == "P") 1.0 else 0.0)
                    val isP = currency.trim().uppercase() == "P"
                    val entry = CashEntry(
                        id = initial?.id ?: 0,
                        label = label.trim(),
                        currency = if (isP) "USD" else currency.trim().uppercase(),
                        amount = parsedAmount,
                        isMargin = isMargin,
                        portfolioRef = if (isP) portfolioRef.trim() else null
                    )
                    if (entry.label.isNotEmpty() && isValidAmount) onSave(entry)
                },
                enabled = label.isNotBlank() && isValidAmount && (currency != "P" || portfolioRef.isNotBlank())
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
