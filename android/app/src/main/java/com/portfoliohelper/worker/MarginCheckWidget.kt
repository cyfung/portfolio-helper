package com.portfoliohelper.worker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.portfoliohelper.MainActivity
import com.portfoliohelper.PortfolioHelperApp
import com.portfoliohelper.R
import com.portfoliohelper.data.repository.MarginCheckStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MarginCheckWidget"
private const val STALE_RUN_MS  = 30 * 60_000L
private const val STALE_DATA_MS = 60 * 60_000L

class MarginCheckWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        updateAll(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "onUpdate failed", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<MarginCheckWorker>().build()
                )
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {

        suspend fun updateAll(context: Context) {
            val app = context.applicationContext as PortfolioHelperApp
            val stats = app.settingsRepo.marginCheckStats.firstOrNull()
            val wm = AppWidgetManager.getInstance(context)
            val ids = wm.getAppWidgetIds(ComponentName(context, MarginCheckWidgetReceiver::class.java))
            ids.forEach { id ->
                wm.updateAppWidget(id, buildViews(context, stats))
            }
        }

        fun buildViews(context: Context, stats: MarginCheckStats?): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_small)

            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)

            val now = System.currentTimeMillis()
            when {
                stats == null        -> applyNoData(context, rv)
                stats.isRunning      -> applyRunning(context, rv, stats)
                stats.errorMessage != null -> applyFailed(context, rv, stats)
                stats.triggeredPortfolios.isNotEmpty() -> applyAlert(context, rv, stats)
                stats.currencySuggestionText != null -> applyFxSuggestion(context, rv, stats)
                isStale(stats, now)  -> applyOk(context, rv, stats, stale = true, now)
                else                 -> applyOk(context, rv, stats, stale = false, now)
            }

            return rv
        }

        // ── No data ──────────────────────────────────────────────────────────────

        private fun applyNoData(context: Context, rv: RemoteViews) {
            rv.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, R.color.widget_state_checking))
            rv.setImageViewResource(R.id.icon_state, R.drawable.ic_widget_sync)
            rv.setViewVisibility(R.id.container_text, View.VISIBLE)
            rv.setViewVisibility(R.id.text_title, View.VISIBLE)
            rv.setTextViewText(R.id.text_title, "No data yet")
            rv.setTextViewText(R.id.text_subtitle, "Waiting for first run")
            rv.setViewVisibility(R.id.text_subtitle, View.VISIBLE)
            rv.setViewVisibility(R.id.chronometer_elapsed, View.GONE)
            setTextColors(context, rv, R.color.widget_text_on_colored, R.color.widget_text_on_colored_muted)

        }

        // ── Running (Checking...) ─────────────────────────────────────────────────

        private fun applyRunning(context: Context, rv: RemoteViews, stats: MarginCheckStats) {
            rv.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, R.color.widget_state_checking))
            rv.setImageViewResource(R.id.icon_state, R.drawable.ic_widget_sync)
            rv.setViewVisibility(R.id.container_text, View.VISIBLE)
            rv.setViewVisibility(R.id.text_title, View.VISIBLE)
            rv.setTextViewText(R.id.text_title, "Checking…")
            rv.setViewVisibility(R.id.text_subtitle, View.GONE)
            rv.setViewVisibility(R.id.chronometer_elapsed, View.VISIBLE)
            setTextColors(context, rv, R.color.widget_text_on_colored, R.color.widget_text_on_colored_muted)
            rv.setTextColor(R.id.chronometer_elapsed,
                ContextCompat.getColor(context, R.color.widget_text_on_colored_muted))

            val elapsed = System.currentTimeMillis() - stats.runStartTime
            rv.setChronometer(
                R.id.chronometer_elapsed,
                SystemClock.elapsedRealtime() - elapsed,
                null,
                true
            )

        }

        // ── Failed ────────────────────────────────────────────────────────────────

        private fun applyFailed(context: Context, rv: RemoteViews, stats: MarginCheckStats) {
            rv.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, R.color.widget_state_failed))
            rv.setImageViewResource(R.id.icon_state, R.drawable.ic_widget_error)
            rv.setViewVisibility(R.id.container_text, View.VISIBLE)
            rv.setViewVisibility(R.id.text_title, View.GONE)
            rv.setTextViewText(R.id.text_subtitle, stats.errorMessage ?: "Unknown error")
            rv.setViewVisibility(R.id.text_subtitle, View.VISIBLE)
            rv.setViewVisibility(R.id.chronometer_elapsed, View.GONE)
            setTextColors(context, rv, R.color.widget_text_on_failed, R.color.widget_text_on_failed_muted)

        }

        // ── Alert (portfolios triggered) ──────────────────────────────────────────

        private fun applyAlert(context: Context, rv: RemoteViews, stats: MarginCheckStats) {
            rv.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, R.color.widget_state_alert))
            rv.setImageViewResource(R.id.icon_state, R.drawable.ic_widget_warning)
            rv.setViewVisibility(R.id.container_text, View.VISIBLE)
            rv.setViewVisibility(R.id.text_title, View.VISIBLE)
            rv.setTextViewText(R.id.text_title, "Margin Alert")
            rv.setTextViewText(R.id.text_subtitle, stats.triggeredPortfolios.joinToString("\n"))
            rv.setViewVisibility(R.id.text_subtitle, View.VISIBLE)
            rv.setViewVisibility(R.id.chronometer_elapsed, View.GONE)
            setTextColors(context, rv, R.color.widget_text_on_alert, R.color.widget_text_on_alert_muted)

        }

        // ── Currency Suggestion ───────────────────────────────────────────────────

        private fun applyFxSuggestion(context: Context, rv: RemoteViews, stats: MarginCheckStats) {
            rv.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, R.color.widget_state_suggestion))
            rv.setImageViewResource(R.id.icon_state, R.drawable.ic_widget_lightbulb)
            rv.setViewVisibility(R.id.container_text, View.VISIBLE)
            rv.setViewVisibility(R.id.text_title, View.VISIBLE)
            rv.setTextViewText(R.id.text_title, "FX Save")
            rv.setTextViewText(R.id.text_subtitle, stats.currencySuggestionText ?: "")
            rv.setViewVisibility(R.id.text_subtitle, View.VISIBLE)
            rv.setViewVisibility(R.id.chronometer_elapsed, View.GONE)
            setTextColors(context, rv, R.color.widget_text_on_suggestion, R.color.widget_text_on_suggestion_muted)

        }

        // ── OK / Healthy (fresh or stale) ────────────────────────────────────────

        private fun isStale(stats: MarginCheckStats, now: Long): Boolean {
            val staleRun  = stats.runTime > 0 && (now - stats.runTime) > STALE_RUN_MS
            val staleData = stats.oldestDataTime > 0 && (now - stats.oldestDataTime) > STALE_DATA_MS
            return staleRun || staleData
        }

        private fun applyOk(context: Context, rv: RemoteViews, stats: MarginCheckStats, stale: Boolean, now: Long) {
            rv.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, if (stale) R.color.widget_state_stale else R.color.widget_state_ok))
            rv.setImageViewResource(R.id.icon_state, if (stale) R.drawable.ic_widget_stale else R.drawable.ic_widget_check)
            rv.setViewVisibility(R.id.container_text, View.VISIBLE)
            rv.setViewVisibility(R.id.text_title, View.VISIBLE)

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            rv.setTextViewText(R.id.text_title, "Last: ${timeFmt.format(Date(stats.runTime))}")

            val dataAgeMinutes = if (stats.oldestDataTime > 0L) (now - stats.oldestDataTime) / 60_000 else null
            rv.setTextViewText(R.id.text_subtitle, if (dataAgeMinutes != null) "Data: ${dataAgeMinutes}m ago" else "–")
            rv.setViewVisibility(R.id.text_subtitle, View.VISIBLE)
            rv.setViewVisibility(R.id.chronometer_elapsed, View.GONE)

            if (stale) {
                setTextColors(context, rv, R.color.widget_text_on_stale, R.color.widget_text_on_stale_muted)
            } else {
                setTextColors(context, rv, R.color.widget_text_on_ok, R.color.widget_text_on_ok_muted)
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────────────

        private fun setTextColors(
            context: Context,
            rv: RemoteViews,
            @androidx.annotation.ColorRes titleRes: Int,
            @androidx.annotation.ColorRes subtitleRes: Int
        ) {
            rv.setTextColor(R.id.text_title,    ContextCompat.getColor(context, titleRes))
            rv.setTextColor(R.id.text_subtitle, ContextCompat.getColor(context, subtitleRes))
        }
    }
}
