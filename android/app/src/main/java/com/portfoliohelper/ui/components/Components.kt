package com.portfoliohelper.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.ui.theme.ext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ── Typography Helpers ────────────────────────────────────────────────────────

val DataFont = FontFamily.SansSerif

/**
 * Applies tighter spacing specifically to symbols like . , - + 
 * while keeping digits at a readable width.
 */
fun thinData(text: String): AnnotatedString = buildAnnotatedString {
    text.forEach { char ->
        if (char in ".,-+%") {
            // Very tight spacing for symbols
            withStyle(SpanStyle(letterSpacing = (-1).sp)) {
                append(char)
            }
        } else {
            append(char)
        }
    }
}

// ── Number formatting ─────────────────────────────────────────────────────────

fun formatCurrency(v: Double): String = formatSmart(v, showCurrency = false)

fun formatSignedCurrency(v: Double): String = formatSmart(v, showCurrency = false, showSign = true)

fun formatPct(v: Double, decimals: Int = 2): String =
    "%.${decimals}f%%".format(v)

fun formatSignedPct(v: Double, decimals: Int = 2): String =
    "%+.${decimals}f%%".format(v)

fun formatSmart(
    value: Double,
    showCurrency: Boolean = false,
    showSign: Boolean = false
): String {
    if (value == 0.0) {
        val base = "0.00"
        return if (showSign) "+$base" else base
    }

    val absVal = abs(value)

    // Round at each candidate scale first, then re-check thresholds so that
    // e.g. 999_999.999 rounds to 1000.00K → bumped up to 1.00M, not left as
    // the malformed "1000.00K".
    fun roundedScale(v: Double, divisor: Double): Double =
        Math.round(v / divisor * 100.0) / 100.0

    val (scaledValue, suffix) = when {
        absVal >= 1_000_000_000 -> roundedScale(absVal, 1_000_000_000.0) to "B"
        absVal >= 1_000_000     -> {
            val s = roundedScale(absVal, 1_000_000.0)
            if (s >= 1_000.0) roundedScale(absVal, 1_000_000_000.0) to "B" else s to "M"
        }
        absVal >= 10_000        -> {
            val s = roundedScale(absVal, 1_000.0)
            if (s >= 1_000.0) roundedScale(absVal, 1_000_000.0) to "M" else s to "K"
        }
        else -> absVal to ""
    }

    val formattedNum = if (suffix == "") {
        val nf = NumberFormat.getNumberInstance(Locale.US)
        val rounded = Math.round(absVal * 100.0) / 100.0
        if (rounded >= 1000.0) {
            nf.minimumFractionDigits = 0
            nf.maximumFractionDigits = 0
        } else {
            nf.minimumFractionDigits = 2
            nf.maximumFractionDigits = 2
        }
        nf.format(rounded)
    } else {
        "%.2f%s".format(Locale.US, scaledValue, suffix)
    }

    val signStr = if (value < 0) "-" else if (showSign) "+" else ""

    return "$signStr$formattedNum"
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
            Text(
                text = thinData(value),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily  = DataFont,
                    fontWeight  = FontWeight.SemiBold,
                    fontSize    = 19.sp,
                    letterSpacing = (-0.2).sp
                ),
                color = valueColor)
            if (subValue != null) {
                Text(
                    text = thinData(subValue),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = DataFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        letterSpacing = (-0.2).sp
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
fun TableHeader(
    firstColumn: Pair<String, Dp>,
    otherColumns: List<Pair<String, Dp>>,
    scrollState: ScrollState? = null
) {
    val ext = MaterialTheme.ext
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.headerBg)
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = firstColumn.first,
            modifier = Modifier
                .width(firstColumn.second)
                .padding(start = 12.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            ),
            color = ext.headerText,
            textAlign = TextAlign.Start
        )
        val scrollModifier = if (scrollState != null) Modifier.horizontalScroll(scrollState) else Modifier
        Row(
            modifier = scrollModifier.padding(end = 12.dp)
        ) {
            otherColumns.forEach { (label, width) ->
                Text(
                    text = label,
                    modifier = Modifier.width(width),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = ext.headerText,
                    textAlign = TextAlign.End
                )
            }
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
        text     = thinData(text),
        color    = color,
        style    = MaterialTheme.typography.bodySmall.copy(
            fontFamily = DataFont,
            fontWeight = fontWeight,
            fontSize   = fontSize,
            letterSpacing = (-0.2).sp
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
            text  = thinData(text),
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = DataFont,
                fontWeight = FontWeight.Normal,
                fontSize   = 13.sp
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
            text = thinData(text),
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = DataFont,
                fontWeight = FontWeight.Normal,
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
                text = thinData("${"%.1f".format(current)}%"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = DataFont,
                color = ext.textPrimary
            )
            Text(
                text = "/",
                fontSize = 11.sp,
                color = ext.textTertiary,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = thinData("${"%.1f".format(target)}%"),
                fontSize = 12.sp,
                fontFamily = DataFont,
                color = ext.textSecondary,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Spacer(Modifier.width(2.dp))
            WeightDiffPill(diff)
        }
        Spacer(Modifier.height(2.dp))
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
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp
            )
        )
    }
}
