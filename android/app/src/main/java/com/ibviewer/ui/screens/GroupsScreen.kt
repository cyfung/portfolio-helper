package com.ibviewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibviewer.MainViewModel
import com.ibviewer.data.model.GroupRow
import com.ibviewer.ui.components.*
import com.ibviewer.ui.theme.ext
import kotlin.math.abs

@Composable
fun GroupsScreen(vm: MainViewModel) {
    val ext    = MaterialTheme.ext
    val groups by vm.groupRows.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()

    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize().background(ext.bgPrimary), contentAlignment = Alignment.Center) {
            Text("No groups defined.\nAdd group tags to positions to see them here.",
                color = ext.textTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ext.bgPrimary),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            TableHeader(listOf(
                "Group"   to 1.5f,
                "Day %"   to 0.9f,
                "Mkt Val" to 1.2f,
                "Cur/Tgt" to 1.1f,
                "Diff %"  to 1.1f
            ))
            Divider()
        }

        items(groups, key = { it.name }) { group ->
            GroupRow(
                group        = group,
                portfolioVal = totals.totalMktVal
            )
            Divider()
        }

        item {
            Text(
                "⚠ Group values should be interpreted cautiously — their meaning depends on how groups are defined.",
                modifier = Modifier.padding(12.dp),
                style    = MaterialTheme.typography.bodySmall,
                color    = ext.textTertiary
            )
        }
    }
}

@Composable
fun GroupRow(
    group: GroupRow,
    portfolioVal: Double
) {
    val ext        = MaterialTheme.ext
    val mktValChg  = group.mktVal - group.prevMktVal
    val dayPct     = if (group.prevMktVal > 0) mktValChg / group.prevMktVal * 100.0 else null
    val weightPct  = if (portfolioVal > 0) group.mktVal / portfolioVal * 100.0 else 0.0
    val weightDiff = weightPct - group.targetWeight

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.bgPrimary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group name
            Text(
                text       = group.name,
                modifier   = Modifier.weight(1.5f),
                fontWeight = FontWeight.Medium,
                fontSize   = 13.sp,
                color      = ext.textPrimary
            )

            // Day %
            Box(modifier = Modifier.weight(0.9f), contentAlignment = Alignment.CenterEnd) {
                if (dayPct != null) DayPctPill(dayPct)
                else MonoText("—", color = ext.textTertiary)
            }

            // Mkt Val
            MonoText(
                text     = formatCurrency(group.mktVal),
                color    = ext.textSecondary,
                modifier = Modifier.weight(1.2f)
            )

            // Cur / Tgt weight
            Column(modifier = Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                Text("${weightPct.toFixed(1)}% / ${group.targetWeight.toFixed(1)}%",
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color      = ext.textTertiary)
            }

            // Diff %
            val diffColor = when {
                abs(weightDiff) > 1.0 -> if (weightDiff > 0) ext.negative else ext.actionPositive
                abs(weightDiff) > 0.2 -> ext.warning
                else                  -> ext.positive
            }
            Text(
                text       = "${if (weightDiff >= 0) "+" else ""}${weightDiff.toFixed(1)}%",
                modifier   = Modifier.weight(1.1f),
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color      = diffColor,
                textAlign  = androidx.compose.ui.text.style.TextAlign.End
            )
        }

        // Member chips on tap
        if (group.members.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ext.bgSecondary)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.members.forEach { symbol ->
                    SuggestionChip(
                        onClick = {},
                        label   = { Text(symbol, fontSize = 10.sp) }
                    )
                }
            }
        }
    }
}

private fun Double.toFixed(n: Int) = "%.${n}f".format(this)
