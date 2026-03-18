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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import com.portfoliohelper.PortfolioHelperApp
import com.portfoliohelper.data.repository.MarginCheckStats
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val bg          = Color(0xFF1C1B1F)
        val textPrimary = Color(0xFFE6E1E5)
        val textMuted   = Color(0xFF938F99)
        val positive    = Color(0xFF4CAF50)
        val warning     = Color(0xFFFFC107)
        val negative    = Color(0xFFF44336)

        val size = LocalSize.current
        val hPad = if (size.width >= 150.dp) 20.dp else 10.dp
        val isMedium = size.height >= 120.dp

        if (isMedium) {
            MediumLayout(stats, bg, textPrimary, textMuted, positive, warning, negative, hPad)
        } else {
            SmallLayout(stats, bg, textPrimary, textMuted, positive, warning, negative, hPad)
        }
    }

    @Composable
    private fun SmallLayout(
        stats: MarginCheckStats?,
        bg: Color, textPrimary: Color, textMuted: Color,
        positive: Color, warning: Color, negative: Color,
        hPad: androidx.compose.ui.unit.Dp
    ) {
        Column(modifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = hPad, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start
        ) {
            if (stats == null) {
                // ── No data yet ──────────────────────────────────────────
                Text(
                    text = "MARGIN CHECK",
                    style = TextStyle(
                        color = ColorProvider(textMuted),
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
                        style = TextStyle(color = ColorProvider(textMuted), fontSize = 10.sp)
                    )
                }
                return@Column
            }

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val isError = stats.errorMessage != null
            val isAlert = !isError && stats.triggeredPortfolios.isNotEmpty()

            val statusColor = when {
                isError -> negative
                isAlert -> negative
                else    -> positive
            }
            val statusLabel = when {
                isError -> "● Failed"
                isAlert -> "● Alert"
                else    -> "● OK"
            }
            val timeColor = if (isError || isAlert) negative else textPrimary

            // ── Header: label + status badge ─────────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MARGIN CHECK",
                    style = TextStyle(
                        color = ColorProvider(textMuted),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = statusLabel,
                    style = TextStyle(
                        color = ColorProvider(statusColor),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            // ── Row 2: time + data age (or error snippet) ────────────────
            val dataAgeMinutes = (System.currentTimeMillis() - stats.oldestDataTime) / 60_000
            val ageColor = when {
                dataAgeMinutes < 15 -> positive
                dataAgeMinutes < 60 -> warning
                else                -> negative
            }

            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFmt.format(Date(stats.runTime)),
                    style = TextStyle(
                        color = ColorProvider(timeColor),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                // Right side: data age (ok/alert) or stale indicator (error)
                Text(
                    text = "${dataAgeMinutes}m old",
                    style = TextStyle(
                        color = ColorProvider(if (isError) negative else ageColor),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            // ── Row 3: detail line ────────────────────────────────────────
            Spacer(GlanceModifier.height(2.dp))
            when {
                isError -> Text(
                    text = stats.errorMessage ?: "Unknown error",
                    style = TextStyle(color = ColorProvider(negative), fontSize = 9.sp),
                    maxLines = 1
                )
                isAlert -> Text(
                    text = stats.triggeredPortfolios.joinToString(", "),
                    style = TextStyle(
                        color = ColorProvider(negative),
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
        bg: Color, textPrimary: Color, textMuted: Color,
        positive: Color, warning: Color, negative: Color,
        hPad: androidx.compose.ui.unit.Dp
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .padding(horizontal = hPad, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start
        ) {
            if (stats == null) {
                Text(
                    text = "MARGIN CHECK",
                    style = TextStyle(color = ColorProvider(textMuted), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                )
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data yet", style = TextStyle(color = ColorProvider(textMuted), fontSize = 10.sp))
                }
                return@Column
            }

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val isError = stats.errorMessage != null
            val isAlert = !isError && stats.triggeredPortfolios.isNotEmpty()
            val statusColor = when { isError -> negative; isAlert -> negative; else -> positive }
            val statusLabel = when { isError -> "● Failed"; isAlert -> "● Alert"; else -> "● OK" }
            val timeColor = if (isError || isAlert) negative else textPrimary
            val dataAgeMinutes = (System.currentTimeMillis() - stats.oldestDataTime) / 60_000
            val ageColor = when { dataAgeMinutes < 15 -> positive; dataAgeMinutes < 60 -> warning; else -> negative }

            // ── Header ───────────────────────────────────────────────────
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("MARGIN CHECK", style = TextStyle(color = ColorProvider(textMuted), fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.defaultWeight())
                Text(statusLabel, style = TextStyle(color = ColorProvider(statusColor), fontSize = 10.sp, fontWeight = FontWeight.Medium))
            }

            // ── Hero time ────────────────────────────────────────────────
            Text(
                text = timeFmt.format(Date(stats.runTime)),
                style = TextStyle(color = ColorProvider(timeColor), fontSize = 28.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.padding(top = 6.dp, bottom = 6.dp)
            )

            // ── Divider ──────────────────────────────────────────────────
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF))) {}

            Spacer(GlanceModifier.height(10.dp))

            // ── Metrics rows ─────────────────────────────────────────────
            when {
                isError -> {
                    Text(
                        text = stats.errorMessage ?: "Unknown error",
                        style = TextStyle(color = ColorProvider(negative), fontSize = 10.sp),
                        maxLines = 2,
                        modifier = GlanceModifier.padding(bottom = 8.dp)
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Data age", style = TextStyle(color = ColorProvider(textMuted), fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${dataAgeMinutes}m", style = TextStyle(color = ColorProvider(negative), fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
                isAlert -> {
                    Text(
                        text = stats.triggeredPortfolios.joinToString(", "),
                        style = TextStyle(color = ColorProvider(negative), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2,
                        modifier = GlanceModifier.padding(bottom = 8.dp)
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Data age", style = TextStyle(color = ColorProvider(textMuted), fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${dataAgeMinutes}m", style = TextStyle(color = ColorProvider(ageColor), fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
                else -> {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Data age", style = TextStyle(color = ColorProvider(textMuted), fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${dataAgeMinutes}m", style = TextStyle(color = ColorProvider(ageColor), fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Portfolios", style = TextStyle(color = ColorProvider(textMuted), fontSize = 10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("${stats.triggeredPortfolios.size} triggered", style = TextStyle(color = ColorProvider(positive), fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}

class MarginCheckWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MarginCheckWidget()
}