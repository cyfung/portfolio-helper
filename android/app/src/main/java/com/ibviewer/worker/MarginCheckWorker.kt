package com.ibviewer.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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

        fun schedule(context: Context, settings: MarginAlertSettings) {
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
        }
    }

    override suspend fun doWork(): Result {
        val prefs = context.dataStore.data.first()

        // Read alert settings
        val enabled = prefs[PrefsKeys.MARGIN_ALERT_ENABLED] ?: false
        if (!enabled) return Result.success()
        val lowerPct = (prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] ?: 20f).toDouble()
        val upperPct = (prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] ?: 50f).toDouble()

        // Read portfolio data from DB
        val app = context.applicationContext as IbViewerApp
        val positions = app.database.positionDao().getAll()
        val cashEntries = app.database.cashDao().getAll()

        // Fetch current prices
        val prices = positions.map { it.symbol }.distinct().associateWith { symbol ->
            try {
                YahooFinanceClient.fetchQuote(symbol)
            } catch (e: Exception) {
                null
            }
        }.filterValues { it != null }.mapValues { it.value!! }

        // Fetch FX rates
        val fxRates = mutableMapOf<String, Double>()
        val currencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
        for (ccy in currencies) {
            try {
                val quote = YahooFinanceClient.fetchQuote("${ccy}USD=X")
                val rate = quote.regularMarketPrice ?: quote.previousClose
                if (rate != null) {
                    fxRates[ccy] = rate
                }
            } catch (e: Exception) {
                // Skip if fetch fails
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

        ensureChannel()

        if (marginPct < lowerPct) {
            notify(
                NOTIF_ID_LOWER,
                "⚠️ Margin Low",
                "Margin is %.1f%% — below lower threshold of %.1f%%".format(marginPct, lowerPct)
            )
        } else if (marginPct > upperPct) {
            notify(
                NOTIF_ID_UPPER,
                "⚠️ Margin High",
                "Margin is %.1f%% — above upper threshold of %.1f%%".format(marginPct, upperPct)
            )
        }

        return Result.success()
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
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
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }
}

// Re-schedule worker after device reboot
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // DataStore isn't accessible synchronously in BroadcastReceiver;
        // schedule a one-time worker to re-enqueue the periodic one
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
