package com.portfoliohelper.ui.components

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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.ui.theme.ext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ── Number formatting ─────────────────────────────────────────────────────────

fun formatCurrency(v: Double): String = formatSmart(v, showCurrency = true)

fun formatSignedCurrency(v: Double): String = formatSmart(v, showCurrency = true, showSign = true)

fun formatPct(v: Double, decimals: Int = 2): String =
    "%.${decimals}f%%".format(v)

fun formatSignedPct(v: Double, decimals: Int = 2): String =
    "%+.${decimals}f%%".format(v)

/**
 * Smart formatting:
 * - Max 5 significant figures
 * - Up to 2 decimal places
 * - Suffixes: K, M, B
 * - Optional USD symbol ($)
 * - Optional leading sign (+/-)
 */
fun formatSmart(
    value: Double,
    showCurrency: Boolean = false,
    showSign: Boolean = false
): String {
    if (value == 0.0) {
        val base = if (showCurrency) "$0.00" else "0.00"
        return if (showSign) "+$base" else base
    }

    val absVal = abs(value)
    val (scaledValue, suffix) = when {
        absVal >= 1_000_000_000 -> (absVal / 1_000_000_000.0) to "B"
        absVal >= 1_000_000 -> (absVal / 1_000_000.0) to "M"
        absVal >= 10_000 -> (absVal / 1_000.0) to "K"
        else -> absVal to ""
    }

    val formattedNum = if (suffix == "") {
        val nf = NumberFormat.getNumberInstance(Locale.US)
        if (absVal >= 1000) {
            nf.minimumFractionDigits = 0
            nf.maximumFractionDigits = 0
        } else {
            nf.minimumFractionDigits = 2
            nf.maximumFractionDigits = 2
        }
        nf.format(absVal)
    } else {
        "%.2f%s".format(Locale.US, scaledValue, suffix)
    }

    val signStr = if (value < 0) "-" else if (showSign) "+" else ""
    val currencyStr = if (showCurrency) "$" else ""

    return "$signStr$currencyStr$formattedNum"
}

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
    subValue: String? = null,
    subValueColor: Color = MaterialTheme.ext.textSecondary,
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
                    fontSize    = 14.sp
                ),
                color = valueColor)
            if (subValue != null) {
                Text(
                    text = subValue,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = subValueColor
                )
            }
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
                    fontSize   = 12.sp
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
    fontSize: TextUnit = 14.sp,
    textAlign: TextAlign = TextAlign.End,
    modifier: Modifier = Modifier
) {
    Text(
        text     = text,
        color    = color,
        style    = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight,
            fontSize   = fontSize
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
    val text   = formatSignedPct(pct)
    Box(
        modifier = Modifier
            .background(
                color  = color.copy(alpha = 0.12f * alpha),
                shape  = RoundedCornerShape(3.dp)
            )
            .border(0.5.dp, color.copy(alpha = 0.25f * alpha), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text  = text,
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 12.sp
            )
        )
    }
}

@Composable
fun WeightDiffPill(diff: Double) {
    val ext = MaterialTheme.ext
    val color = when {
        abs(diff) > 1.0 -> if (diff > 0) ext.negative else ext.positive
        abs(diff) > 0.2 -> ext.warning
        else -> ext.textTertiary
    }
    
    val text = "${if (diff >= 0) "+" else ""}${"%.1f".format(diff)}%"
    
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .border(0.5.dp, color.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        )
    }
}

@Composable
fun WeightBreakdown(
    current: Double,
    target: Double,
    modifier: Modifier = Modifier
) {
    val ext = MaterialTheme.ext
    val diff = current - target
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${"%.1f".format(current)}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = ext.textPrimary
            )
            Text(
                text = "/",
                fontSize = 11.sp,
                color = ext.textTertiary,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = "${"%.1f".format(target)}%",
                fontSize = 12.sp,
                color = ext.textSecondary
            )
        }
        Spacer(Modifier.height(2.dp))
        WeightDiffPill(diff)
    }
}

@Composable
fun CashTypeBadge(type: String) {
    val ext = MaterialTheme.ext
    val (color, text) = when (type) {
        "M" -> ext.warning to "M"
        else -> ext.actionPositive to type
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .border(0.5.dp, color.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
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
