package com.portfoliohelper.worker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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

class MarginCheckWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        // Override onReceive so goAsync() can be called before coroutine work starts.
        // goAsync() prevents the system from killing the process after onReceive() returns.
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                val pendingResult = goAsync()
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val newOptions = intent.getBundleExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS) ?: Bundle()
                val wm = AppWidgetManager.getInstance(context)
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        val app = context.applicationContext as PortfolioHelperApp
                        val stats = app.settingsRepo.marginCheckStats.firstOrNull()
                        if (appWidgetId != -1) {
                            wm.updateAppWidget(appWidgetId, buildRemoteViews(context, stats, newOptions))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "OPTIONS_CHANGED update failed", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
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
                val opts = wm.getAppWidgetOptions(id)
                wm.updateAppWidget(id, buildRemoteViews(context, stats, opts))
            }
        }

        /**
         * Builds a RemoteViews that automatically adapts to the widget's current size.
         * On API 31+: uses the responsive RemoteViews map — the launcher picks the right variant.
         * Pre-31: falls back to measuring current height via options bundle.
         */
        fun buildRemoteViews(context: Context, stats: MarginCheckStats?, options: Bundle): RemoteViews {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                buildResponsiveViews(context, stats)
            } else {
                // Portrait: current height ≈ MAX_HEIGHT, current width ≈ MIN_WIDTH
                val h = maxOf(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                )
                val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                buildViews(context, stats, isMedium = h >= 100, widthDp = w)
            }
        }

        /**
         * API 31+ only: provide all size variants; the launcher selects the best fit automatically.
         * Size breakpoints match the original Glance SizeMode.Responsive values.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        private fun buildResponsiveViews(context: Context, stats: MarginCheckStats?): RemoteViews {
            return RemoteViews(
                mapOf(
                    SizeF(130f, 70f)  to buildViews(context, stats, isMedium = false, widthDp = 130),
                    SizeF(210f, 70f)  to buildViews(context, stats, isMedium = false, widthDp = 210),
                    SizeF(130f, 145f) to buildViews(context, stats, isMedium = true,  widthDp = 130),
                    SizeF(210f, 145f) to buildViews(context, stats, isMedium = true,  widthDp = 210),
                )
            )
        }

        fun buildViews(context: Context, stats: MarginCheckStats?, isMedium: Boolean, widthDp: Int = 0): RemoteViews {
            val layoutId = if (isMedium) R.layout.widget_medium else R.layout.widget_small
            val rv = RemoteViews(context.packageName, layoutId)

            // Adaptive padding: wide widget (≥150dp) → 20dp horizontal, narrow → 10dp
            val density = context.resources.displayMetrics.density
            val hPad = ((if (widthDp >= 150) 20 else 10) * density).toInt()
            val vPad = ((if (isMedium) 12 else 8) * density).toInt()
            rv.setViewPadding(R.id.widget_root, hPad, vPad, hPad, vPad)

            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)

            val colorTextPrimary = ContextCompat.getColor(context, R.color.widget_text_primary)
            val colorWarning     = ContextCompat.getColor(context, R.color.widget_warning)
            val colorPositive    = ContextCompat.getColor(context, R.color.widget_positive)
            val colorNegative    = ContextCompat.getColor(context, R.color.widget_negative)

            if (stats == null) {
                rv.setViewVisibility(R.id.container_no_data, View.VISIBLE)
                rv.setViewVisibility(R.id.container_running, View.GONE)
                rv.setViewVisibility(R.id.container_normal, View.GONE)
                rv.setTextViewText(R.id.text_status_badge, "")
                return rv
            }

            if (stats.isRunning) {
                rv.setViewVisibility(R.id.container_no_data, View.GONE)
                rv.setViewVisibility(R.id.container_running, View.VISIBLE)
                rv.setViewVisibility(R.id.container_normal, View.GONE)
                rv.setTextViewText(R.id.text_status_badge, "● Running")
                rv.setTextColor(R.id.text_status_badge, colorWarning)

                val elapsed = System.currentTimeMillis() - stats.runStartTime
                rv.setChronometer(
                    R.id.chronometer_elapsed,
                    SystemClock.elapsedRealtime() - elapsed,
                    null,
                    true
                )

                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                rv.setTextViewText(R.id.text_started_time, "Started ${timeFmt.format(Date(stats.runStartTime))}")

                if (stats.runTime > 0L) {
                    rv.setViewVisibility(R.id.text_last_run, View.VISIBLE)
                    rv.setTextViewText(R.id.text_last_run, "Last: ${timeFmt.format(Date(stats.runTime))}")
                } else {
                    rv.setViewVisibility(R.id.text_last_run, View.GONE)
                }
                return rv
            }

            // Normal state
            rv.setViewVisibility(R.id.container_no_data, View.GONE)
            rv.setViewVisibility(R.id.container_running, View.GONE)
            rv.setViewVisibility(R.id.container_normal, View.VISIBLE)

            val isError = stats.errorMessage != null
            val isAlert = !isError && stats.triggeredPortfolios.isNotEmpty()

            val statusColor = if (isError || isAlert) colorNegative else colorPositive
            val statusLabel = when {
                isError -> "● Fail"
                isAlert -> "● Alert"
                else    -> "● OK"
            }
            rv.setTextViewText(R.id.text_status_badge, statusLabel)
            rv.setTextColor(R.id.text_status_badge, statusColor)

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeColor = if (isError || isAlert) colorNegative else colorTextPrimary
            rv.setTextViewText(R.id.text_run_time, timeFmt.format(Date(stats.runTime)))
            rv.setTextColor(R.id.text_run_time, timeColor)

            val dataAgeMinutes = if (!isError && stats.oldestDataTime > 0L)
                (System.currentTimeMillis() - stats.oldestDataTime) / 60_000
            else null
            val ageColor = when {
                dataAgeMinutes == null -> colorTextPrimary
                dataAgeMinutes < 15   -> colorPositive
                dataAgeMinutes < 60   -> colorWarning
                else                  -> colorNegative
            }

            when {
                isError -> {
                    rv.setViewVisibility(R.id.text_detail, View.VISIBLE)
                    rv.setTextViewText(R.id.text_detail, stats.errorMessage ?: "Error")
                    rv.setTextColor(R.id.text_detail, colorNegative)
                }
                isAlert -> {
                    rv.setViewVisibility(R.id.text_detail, View.VISIBLE)
                    rv.setTextViewText(R.id.text_detail, stats.triggeredPortfolios.joinToString(", "))
                    rv.setTextColor(R.id.text_detail, colorNegative)
                }
                else -> rv.setViewVisibility(R.id.text_detail, View.GONE)
            }

            if (!isMedium) {
                rv.setTextViewText(R.id.text_data_age, if (dataAgeMinutes != null) "${dataAgeMinutes}m old" else "–")
                rv.setTextColor(R.id.text_data_age, if (isError) colorNegative else ageColor)
            } else {
                rv.setTextViewText(R.id.text_data_age_value, if (dataAgeMinutes != null) "${dataAgeMinutes}m" else "–")
                rv.setTextColor(R.id.text_data_age_value, if (isError) colorNegative else ageColor)
                if (!isError && !isAlert) {
                    rv.setViewVisibility(R.id.row_portfolios, View.VISIBLE)
                    rv.setTextViewText(R.id.text_portfolios_count, "${stats.triggeredPortfolios.size} triggered")
                    rv.setTextColor(R.id.text_portfolios_count, colorPositive)
                } else {
                    rv.setViewVisibility(R.id.row_portfolios, View.GONE)
                }
            }

            return rv
        }
    }
}
