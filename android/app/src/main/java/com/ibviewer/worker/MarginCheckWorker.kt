package com.ibviewer.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ibviewer.IbViewerApp
import com.ibviewer.data.model.MarginAlertSettings
import com.ibviewer.data.repository.PortfolioCalculator
import com.ibviewer.data.repository.PrefsKeys
import com.ibviewer.data.repository.YahooFinanceClient
import com.ibviewer.data.repository.dataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class MarginCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "margin_check"
        const val CHANNEL_ID = "margin_alerts"
        const val NOTIF_ID_LOWER = 1001
        const val NOTIF_ID_UPPER = 1002
        private const val TAG = "MarginCheckWorker"

        fun schedule(context: Context, settings: MarginAlertSettings) {
            Log.d(TAG, "Scheduling worker. Enabled: ${settings.enabled}, Interval: ${settings.checkIntervalMinutes}")
            val wm = WorkManager.getInstance(context)
            if (!settings.enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<MarginCheckWorker>(
                settings.checkIntervalMinutes.toLong(), TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            // For testing: trigger a one-time run immediately
            Log.d(TAG, "Enqueuing one-time test run")
            wm.enqueue(OneTimeWorkRequestBuilder<MarginCheckWorker>().build())
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker execution started")
        val prefs = context.dataStore.data.first()

        // Read alert settings
        val enabled = prefs[PrefsKeys.MARGIN_ALERT_ENABLED] ?: false
        Log.d(TAG, "Alerts enabled: $enabled")
        if (!enabled) return Result.success()

        val lowerPct = (prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] ?: 20f).toDouble()
        val upperPct = (prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] ?: 50f).toDouble()
        Log.d(TAG, "Thresholds: lower=$lowerPct, upper=$upperPct")

        // Read portfolio data from DB
        val app = context.applicationContext as IbViewerApp
        val positions = app.database.positionDao().getAll()
        val cashEntries = app.database.cashDao().getAll()
        
        if (positions.isEmpty() && cashEntries.isEmpty()) {
            Log.d(TAG, "No data to check. Success.")
            return Result.success()
        }
        
        Log.d(TAG, "Positions count: ${positions.size}, Cash entries count: ${cashEntries.size}")

        // Fetch current prices
        val symbols = positions.map { it.symbol }.distinct()
        val prices = symbols.associateWith { symbol ->
            try {
                YahooFinanceClient.fetchQuote(symbol)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch quote for $symbol", e)
                null
            }
        }.filterValues { it != null }.mapValues { it.value!! }
        
        // Safety check: if we have positions but couldn't fetch ANY prices, abort to avoid 100% margin false alerts
        if (symbols.isNotEmpty() && prices.isEmpty()) {
            Log.w(TAG, "Could not fetch any prices. Aborting to avoid false alert.")
            return Result.retry() // Retry later when network might be better
        }

        Log.d(TAG, "Prices fetched for: ${prices.keys}")

        // Fetch FX rates
        val fxRates = mutableMapOf<String, Double>()
        val currencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
        for (ccy in currencies) {
            try {
                val quote = YahooFinanceClient.fetchQuote("${ccy}USD=X")
                val rate = quote.regularMarketPrice ?: quote.previousClose
                if (rate != null) {
                    fxRates[ccy] = rate
                    Log.d(TAG, "FX Rate for $ccy: $rate")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch FX rate for $ccy", e)
            }
        }

        // Compute margin USD
        var marginUsd = 0.0
        for (e in cashEntries) {
            val rate = if (e.currency == "USD") 1.0 else fxRates[e.currency] ?: continue
            marginUsd += e.amount * rate
        }

        val totals = PortfolioCalculator.computeTotals(positions, prices, marginUsd)
        val marginPct = totals.marginPct
        Log.i(TAG, "Computed Margin %: $marginPct")

        ensureChannel()

        if (marginPct < lowerPct) {
            Log.i(TAG, "Triggering LOW margin alert: $marginPct < $lowerPct")
            notify(
                NOTIF_ID_LOWER,
                "⚠️ Margin Low",
                "Margin is %.1f%% — below lower threshold of %.1f%%".format(marginPct, lowerPct)
            )
        } else if (marginPct > upperPct) {
            Log.i(TAG, "Triggering HIGH margin alert: $marginPct > $upperPct")
            notify(
                NOTIF_ID_UPPER,
                "⚠️ Margin High",
                "Margin is %.1f%% — above upper threshold of %.1f%%".format(marginPct, upperPct)
            )
        } else {
            Log.d(TAG, "Margin within healthy range: $marginPct")
        }

        return Result.success()
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            Log.d(TAG, "Creating notification channel")
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Margin Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                    .apply { description = "Alerts when margin % crosses configured thresholds" }
            )
        }
    }

    private fun notify(id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show notification.")
                return
            }
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
        Log.d(TAG, "Notification posted: $title")
    }
}

// Re-schedule worker after device reboot
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ReScheduleWorker>().build()
        )
    }
}

class ReScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.dataStore.data.first()
        val enabled = prefs[PrefsKeys.MARGIN_ALERT_ENABLED] ?: false
        val interval = prefs[PrefsKeys.MARGIN_ALERT_INTERVAL] ?: 15
        val lower = (prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] ?: 20f).toDouble()
        val upper = (prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] ?: 50f).toDouble()
        MarginCheckWorker.schedule(
            applicationContext,
            MarginAlertSettings(enabled, lower, upper, interval)
        )
        return Result.success()
    }
}
