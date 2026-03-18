package com.portfoliohelper.worker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
        val bg = Color(0xFF1C1B1F)
        val textPrimary = Color.White
        val textSecondary = Color(0xFFCAC4D0)
        val positive = Color(0xFF4CAF50)
        val negative = Color(0xFFF44336)
        val warning = Color(0xFFFFC107)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .padding(8.dp),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Margin Check",
                style = TextStyle(
                    color = ColorProvider(textPrimary),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            if (stats == null) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No data yet",
                        style = TextStyle(color = ColorProvider(textSecondary), fontSize = 10.sp)
                    )
                }
                return@Column
            }

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val isError = stats.errorMessage != null

            Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    text = timeFmt.format(Date(stats.runTime)),
                    style = TextStyle(color = ColorProvider(textSecondary), fontSize = 10.sp)
                )
                Spacer(GlanceModifier.defaultWeight())
                if (isError) {
                    Text(
                        text = "FAILED",
                        style = TextStyle(color = ColorProvider(negative), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    )
                } else if (stats.triggeredPortfolios.isNotEmpty()) {
                    Text(
                        text = "ALERT",
                        style = TextStyle(color = ColorProvider(negative), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    )
                } else {
                    Text(
                        text = "OK",
                        style = TextStyle(color = ColorProvider(positive), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }

            if (isError) {
                Text(
                    text = stats.errorMessage ?: "Unknown error",
                    style = TextStyle(color = ColorProvider(negative), fontSize = 9.sp),
                    maxLines = 2
                )
            } else {
                val dataAgeMinutes = (System.currentTimeMillis() - stats.oldestDataTime) / 60_000
                val ageColor = when {
                    dataAgeMinutes < 15 -> positive
                    dataAgeMinutes < 60 -> warning
                    else -> negative
                }
                
                Text(
                    text = "Data: ${dataAgeMinutes}m old",
                    style = TextStyle(color = ColorProvider(ageColor), fontSize = 9.sp)
                )

                if (stats.triggeredPortfolios.isNotEmpty()) {
                    Text(
                        text = stats.triggeredPortfolios.joinToString(", "),
                        style = TextStyle(color = ColorProvider(negative), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

class MarginCheckWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MarginCheckWidget()
}
