package com.portfoliohelper.worker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.portfoliohelper.MainActivity
import com.portfoliohelper.PortfolioHelperApp
import com.portfoliohelper.data.repository.MarginCheckStats
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Color helpers ────────────────────────────────────────────────────────────
// ColorProvider(Color) is restricted to the Glance library group.
// The public API requires day/night variants. Since this widget is dark-only,
// we pass the same color for both via this helper.
private fun fixedColor(color: Color): ColorProvider =
    ColorProvider(day = color, night = color)

private val BgColor          = fixedColor(Color(0xFF1C1B1F))
private val TextPrimaryColor = fixedColor(Color(0xFFE6E1E5))
private val TextMutedColor   = fixedColor(Color(0xFF938F99))
private val PositiveColor    = fixedColor(Color(0xFF4CAF50))
private val WarningColor     = fixedColor(Color(0xFFFFC107))
private val NegativeColor    = fixedColor(Color(0xFFF44336))
private val DividerColor     = fixedColor(Color(0x1AFFFFFF))

class MarginCheckWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(130.dp, 70.dp),   // 5-col 2×1
            DpSize(165.dp, 70.dp),   // 4-col 2×1
            DpSize(210.dp, 70.dp),   // 3-col 2×1
            DpSize(130.dp, 145.dp),  // 5-col 2×2
            DpSize(165.dp, 145.dp),  // 4-col 2×2
            DpSize(210.dp, 145.dp),  // 3-col 2×2
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as PortfolioHelperApp
        val stats = app.settingsRepo.marginCheckStats.firstOrNull()

        provideContent {
            GlanceTheme {
                WidgetContent(stats)
            }
        }
    }

    @Composable
    private fun WidgetContent(stats: MarginCheckStats?) {
        val size = LocalSize.current
        val hPad = if (size.width >= 150.dp) 20.dp else 10.dp
        val isMedium = size.height >= 120.dp

        if (isMedium) {
            MediumLayout(stats, hPad)
        } else {
            SmallLayout(stats, hPad)
        }
    }

    @Composable
    private fun SmallLayout(
        stats: MarginCheckStats?,
        hPad: androidx.compose.ui.unit.Dp
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(BgColor)
                .padding(horizontal = hPad, vertical = 8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start
        ) {
            if (stats == null) {
                // ── No data yet ──────────────────────────────────────────
                Text(
                    text = "MARGIN CHECK",
                    style = TextStyle(
                        color = TextMutedColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No data yet",
                        style = TextStyle(color = TextMutedColor, fontSize = 10.sp)
                    )
                }
                return@Column
            }

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val isError = stats.errorMessage != null
            val isAlert = !isError && stats.triggeredPortfolios.isNotEmpty()

            val statusColor = if (isError || isAlert) NegativeColor else PositiveColor
            val statusLabel = when {
                isError -> "● Fail"
                isAlert -> "● Alert"
                else    -> "● OK"
            }
            val timeColor = if (isError || isAlert) NegativeColor else TextPrimaryColor

            // ── Header: label + status badge ─────────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MARGIN CHECK",
                    style = TextStyle(
                        color = TextMutedColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = statusLabel,
                    style = TextStyle(
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            // ── Row 2: time + data age ────────────────────────────────────
            val dataAgeMinutes = (System.currentTimeMillis() - stats.oldestDataTime) / 60_000
            val ageColor = when {
                dataAgeMinutes < 15 -> PositiveColor
                dataAgeMinutes < 60 -> WarningColor
                else                -> NegativeColor
            }

            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFmt.format(Date(stats.runTime)),
                    style = TextStyle(
                        color = timeColor,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = "${dataAgeMinutes}m old",
                    style = TextStyle(
                        color = if (isError) NegativeColor else ageColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            // ── Row 3: detail line ────────────────────────────────────────
            Spacer(GlanceModifier.height(2.dp))
            when {
                isError -> Text(
                    text = stats.errorMessage,
                    style = TextStyle(color = NegativeColor, fontSize = 9.sp),
                    maxLines = 1
                )
                isAlert -> Text(
                    text = stats.triggeredPortfolios.joinToString(", "),
                    style = TextStyle(
                        color = NegativeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                else -> { /* OK — no third line needed */ }
            }
        }
    }

    @Composable
    private fun MediumLayout(
        stats: MarginCheckStats?,
        hPad: androidx.compose.ui.unit.Dp
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(BgColor)
                .padding(horizontal = hPad, vertical = 12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start
        ) {
            if (stats == null) {
                Text(
                    text = "MARGIN CHECK",
                    style = TextStyle(color = TextMutedColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                )
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data yet", style = TextStyle(color = TextMutedColor, fontSize = 10.sp))
                }
                return@Column
            }

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val isError = stats.errorMessage != null
            val isAlert = !isError && stats.triggeredPortfolios.isNotEmpty()
            val statusColor = if (isError || isAlert) NegativeColor else PositiveColor
            val statusLabel = when { isError -> "● Failed"; isAlert -> "● Alert"; else -> "● OK" }
            val timeColor = if (isError || isAlert) NegativeColor else TextPrimaryColor
            val dataAgeMinutes = (System.currentTimeMillis() - stats.oldestDataTime) / 60_000
            val ageColor = when {
                dataAgeMinutes < 15 -> PositiveColor
                dataAgeMinutes < 60 -> WarningColor
                else                -> NegativeColor
            }

            // ── Header ───────────────────────────────────────────────────
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("MARGIN CHECK", style = TextStyle(color = TextMutedColor, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.defaultWeight())
                Text(statusLabel, style = TextStyle(color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Medium))
            }

            // ── Hero time ────────────────────────────────────────────────
            Text(
                text = timeFmt.format(Date(stats.runTime)),
                style = TextStyle(color = timeColor, fontSize = 28.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.padding(top = 6.dp, bottom = 6.dp)
            )

            // ── Divider ──────────────────────────────────────────────────
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(DividerColor)) {}

            Spacer(GlanceModifier.height(10.dp))

            // ── Metrics rows ─────────────────────────────────────────────
            when {
                isError -> {
                    Text(
                        text = stats.errorMessage,
                        style = TextStyle(color = NegativeColor, fontSize = 10.sp),
                        maxLines = 2,
                        modifier = GlanceModifier.padding(bottom = 8.dp)
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Data age", style = TextStyle(color = TextMutedColor, fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${dataAgeMinutes}m", style = TextStyle(color = NegativeColor, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
                isAlert -> {
                    Text(
                        text = stats.triggeredPortfolios.joinToString(", "),
                        style = TextStyle(color = NegativeColor, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2,
                        modifier = GlanceModifier.padding(bottom = 8.dp)
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Data age", style = TextStyle(color = TextMutedColor, fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${dataAgeMinutes}m", style = TextStyle(color = ageColor, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
                else -> {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Data age", style = TextStyle(color = TextMutedColor, fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${dataAgeMinutes}m", style = TextStyle(color = ageColor, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Portfolios", style = TextStyle(color = TextMutedColor, fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${stats.triggeredPortfolios.size} triggered", style = TextStyle(color = PositiveColor, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}

class MarginCheckWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MarginCheckWidget()
}
