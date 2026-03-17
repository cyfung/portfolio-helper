package com.portfoliohelper.worker

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
import com.portfoliohelper.PortfolioHelperApp
import com.portfoliohelper.data.repository.PortfolioCalculator
import java.util.concurrent.TimeUnit

class MarginCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "margin_check"
        const val CHANNEL_ID = "margin_alerts"
        const val NOTIF_ID_ALERT_BASE = 1001
        const val NOTIF_ID_ERROR_BASE = 2001
        private const val INTERVAL_MINUTES = 15L
        private const val TAG = "MarginCheckWorker"

        fun schedule(context: Context, isAnyEnabled: Boolean) {
            Log.d(TAG, "Scheduling worker. AnyEnabled: $isAnyEnabled")
            val wm = WorkManager.getInstance(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!isAnyEnabled) {
                wm.cancelUniqueWork(WORK_NAME)
                nm.cancel(NOTIF_ID_ALERT_BASE)
                nm.cancel(NOTIF_ID_ERROR_BASE)
                return
            }

            val request = PeriodicWorkRequestBuilder<MarginCheckWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
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

            // Trigger a one-time run immediately for testing
            Log.d(TAG, "Enqueuing one-time test run")
            wm.enqueue(OneTimeWorkRequestBuilder<MarginCheckWorker>().build())
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker execution started")
        val app = context.applicationContext as PortfolioHelperApp

        // Read all portfolio margin alerts from DB
        val allAlerts = app.database.portfolioMarginAlertDao().getAll()
        // An alert is active if at least one threshold is set (> 0)
        val enabledAlerts = allAlerts.filter { it.lowerPct > 0 || it.upperPct > 0 }
        if (enabledAlerts.isEmpty()) return Result.success()

        // Background sync: attempt to get latest data from server
        try {
            Log.d(TAG, "Attempting background sync with portfolio server...")
            app.syncRepo.sync()
            Log.i(TAG, "Background sync successful")
        } catch (e: Exception) {
            Log.w(TAG, "Background sync failed: ${e.message}. Using cached database data.")
        }

        ensureChannel()

        enabledAlerts.forEachIndexed { index, alert ->
            val portfolioId = alert.portfolioId
            val positions = app.database.positionDao().getAll(portfolioId)
            val cashEntries = app.database.cashDao().getAll(portfolioId)

            if (positions.isEmpty() && cashEntries.isEmpty()) {
                Log.d(TAG, "No data for portfolio $portfolioId. Skipping.")
                return@forEachIndexed
            }

            val prices = PortfolioCalculator.fetchAndCacheMarketData(app.database, positions, cashEntries)
            val totals = PortfolioCalculator.computeTotals(positions, cashEntries, prices)

            val alertNotifId = NOTIF_ID_ALERT_BASE + index
            val errorNotifId = NOTIF_ID_ERROR_BASE + index
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!totals.isReady) {
                val symbols = positions.map { it.symbol }.distinct()
                val currencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
                val missingSymbols = symbols.filter { it !in prices }
                val missingCurrencies = currencies.filter { "${it}USD=X" !in prices }
                val missing = (missingSymbols + missingCurrencies).joinToString(", ")
                Log.w(TAG, "Data missing for $portfolioId: $missing")
                notify(errorNotifId, "⚠️ Margin Check Error ($portfolioId)", "Missing data: $missing")
                return@forEachIndexed
            }

            nm.cancel(errorNotifId)

            val marginPct = totals.marginPct
            Log.i(TAG, "Portfolio $portfolioId margin: $marginPct%")

            val lowerActive = alert.lowerPct > 0
            val upperActive = alert.upperPct > 0
            when {
                lowerActive && marginPct < alert.lowerPct -> notify(
                    alertNotifId,
                    "⚠️ Margin Low — $portfolioId",
                    "Margin is %.1f%% — below lower threshold of %.1f%%".format(marginPct, alert.lowerPct)
                )
                upperActive && marginPct > alert.upperPct -> notify(
                    alertNotifId,
                    "⚠️ Margin High — $portfolioId",
                    "Margin is %.1f%% — above upper threshold of %.1f%%".format(marginPct, alert.upperPct)
                )
                else -> nm.cancel(alertNotifId)
            }
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
                ).apply { description = "Alerts when margin % crosses configured thresholds" }
            )
        }
    }

    private fun notify(id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
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
    }
}

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
        val app = applicationContext as PortfolioHelperApp
        val anyEnabled = app.database.portfolioMarginAlertDao().getAll().any { it.lowerPct > 0 || it.upperPct > 0 }
        MarginCheckWorker.schedule(applicationContext, anyEnabled)
        return Result.success()
    }
}
