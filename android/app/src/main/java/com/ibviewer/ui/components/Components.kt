package com.ibviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibviewer.ui.theme.ext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ── Number formatting ─────────────────────────────────────────────────────────

private val currFmt = NumberFormat.getCurrencyInstance(Locale.US)

fun formatCurrency(v: Double): String = currFmt.format(v)

fun formatSignedCurrency(v: Double): String =
    if (v >= 0) "+${currFmt.format(v)}" else "-${currFmt.format(abs(v))}"

fun formatPct(v: Double, decimals: Int = 2): String =
    "%.${decimals}f%%".format(v)

fun formatSignedPct(v: Double, decimals: Int = 2): String =
    "%+.${decimals}f%%".format(v)

// ── Color helpers ─────────────────────────────────────────────────────────────

@Composable
fun changeColor(value: Double, threshold: Double = 0.01): Color {
    val ext = MaterialTheme.ext
    return when {
        value >  threshold -> ext.positive
        value < -threshold -> ext.negative
        else               -> ext.textTertiary
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
fun SummaryCard(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.ext.textPrimary,
    modifier: Modifier = Modifier
) {
    val ext = MaterialTheme.ext
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(8.dp),
        color    = ext.bgElevated,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = ext.textTertiary,
                fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            Text(value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily  = FontFamily.Monospace,
                    fontWeight  = FontWeight.SemiBold,
                    fontSize    = 13.sp
                ),
                color = valueColor)
        }
    }
}

@Composable
fun TableHeader(columns: List<Pair<String, Float>>) {
    val ext = MaterialTheme.ext
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.headerBg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        columns.forEach { (label, weight) ->
            Text(
                text      = label,
                modifier  = Modifier.weight(weight),
                style     = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 11.sp
                ),
                color     = ext.headerText,
                textAlign = if (label == columns.first().first) TextAlign.Start else TextAlign.End
            )
        }
    }
}

@Composable
fun Divider() {
    HorizontalDivider(color = MaterialTheme.ext.borderMedium, thickness = 0.5.dp)
}

@Composable
fun MonoText(
    text: String,
    color: Color = MaterialTheme.ext.textSecondary,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.End,
    modifier: Modifier = Modifier
) {
    Text(
        text     = text,
        color    = color,
        style    = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight,
            fontSize   = 12.sp
        ),
        textAlign = textAlign,
        modifier = modifier
    )
}

@Composable
fun DayPctPill(pct: Double, isStale: Boolean = false) {
    val ext    = MaterialTheme.ext
    val isZero = abs(pct) < 0.1
    val color  = when {
        isZero   -> ext.textTertiary
        pct > 0  -> ext.positive
        else     -> ext.negative
    }
    val alpha  = if (isStale) 0.55f else 1f
    val text   = if (isZero) "—" else formatSignedPct(pct)
    Box(
        modifier = Modifier
            .background(
                color  = color.copy(alpha = 0.12f * alpha),
                shape  = RoundedCornerShape(3.dp)
            )
            .border(0.5.dp, color.copy(alpha = 0.25f * alpha), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text  = text,
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 11.sp
            )
        )
    }
}
