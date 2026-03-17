package com.portfoliohelper.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
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
        const val CHANNEL_SYSTEM_ID = "system_tasks"
        const val NOTIF_ID_ALERT_BASE = 10000
        const val NOTIF_ID_ERROR_BASE = 20000
        const val NOTIF_ID_FOREGROUND = 30000
        private const val INTERVAL_MINUTES = 15L
        private const val TAG = "MarginCheckWorker"

        fun schedule(context: Context, isAnyEnabled: Boolean) {
            Log.d(TAG, "Scheduling worker. AnyEnabled: $isAnyEnabled")
            val wm = WorkManager.getInstance(context)

            if (!isAnyEnabled) {
                Log.d(TAG, "No alerts enabled. Cancelling periodic work.")
                wm.cancelUniqueWork(WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MarginCheckWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(constraints)
                .build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            // Trigger a one-time run immediately for testing
            Log.d(TAG, "Enqueuing one-time test run with NetworkType.CONNECTED")
            wm.enqueue(
                OneTimeWorkRequestBuilder<MarginCheckWorker>()
                    .setConstraints(constraints)
                    .build()
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_SYSTEM_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Checking Margins")
            .setContentText("Syncing latest market data...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID_FOREGROUND, notification)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker execution started")
        
        // Promote to foreground to ensure network reliability in background/Doze mode
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not run in foreground: ${e.message}")
        }

        // Small delay to let DNS/Network stabilize
        kotlinx.coroutines.delay(1000)

        val app = context.applicationContext as PortfolioHelperApp
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Read all portfolio margin alerts and portfolio names from DB
        val allAlerts = app.database.portfolioMarginAlertDao().getAll()
        val portfolios = app.database.portfolioDao().getAll().associateBy { it.serialId }
        
        Log.d(TAG, "Found ${allAlerts.size} alert configurations in database")
        
        if (allAlerts.isEmpty()) return Result.success()

        // Background sync: attempt to get latest data from server
        try {
            Log.d(TAG, "Attempting background sync with portfolio server...")
            app.syncRepo.sync()
            Log.i(TAG, "Background sync successful")
        } catch (e: Exception) {
            Log.w(TAG, "Background sync failed: ${e.message}. Using cached database data.")
        }

        ensureChannels()

        allAlerts.forEach { alert ->
            val portfolioId = alert.portfolioId
            val portfolioName = portfolios[portfolioId]?.displayName ?: "Portfolio $portfolioId"
            val alertNotifId = NOTIF_ID_ALERT_BASE + portfolioId
            val errorNotifId = NOTIF_ID_ERROR_BASE + portfolioId

            val isEnabled = alert.lowerPct > 0 || alert.upperPct > 0
            if (!isEnabled) {
                Log.d(TAG, "Alert disabled for $portfolioName. Clearing notifications.")
                nm.cancel(alertNotifId)
                nm.cancel(errorNotifId)
                return@forEach
            }

            val positions = app.database.positionDao().getAll(portfolioId)
            val cashEntries = app.database.cashDao().getAll(portfolioId)

            if (positions.isEmpty() && cashEntries.isEmpty()) {
                Log.d(TAG, "No data for $portfolioName. Skipping.")
                return@forEach
            }

            Log.d(TAG, "[$portfolioName] Fetching market data for ${positions.size} positions and ${cashEntries.size} cash entries...")
            val prices = try {
                val p = PortfolioCalculator.fetchAndCacheMarketData(app.database, positions, cashEntries)
                Log.i(TAG, "[$portfolioName] Market data fetch completed. Found ${p.size} prices.")
                p
            } catch (e: Exception) {
                Log.e(TAG, "[$portfolioName] Fatal error during market data fetch: ${e.message}", e)
                return@forEach
            }

            val totals = PortfolioCalculator.computeTotals(positions, cashEntries, prices)

            if (!totals.isReady) {
                val symbols = positions.map { it.symbol }.distinct()
                val currencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
                val missingSymbols = symbols.filter { it !in prices }
                val missingCurrencies = currencies.filter { "${it}USD=X" !in prices }
                val missing = (missingSymbols + missingCurrencies).joinToString(", ")
                Log.w(TAG, "[$portfolioName] Data missing: $missing")
                notify(errorNotifId, "⚠️ Margin Check Error ($portfolioName)", "Missing data: $missing")
                return@forEach
            }

            // If we reached here, calculation was successful, so clear any previous error notif
            nm.cancel(errorNotifId)

            val marginPct = totals.marginPct
            Log.i(TAG, "[$portfolioName] Calculated margin: %.2f%% (Lower: %.1f%%, Upper: %.1f%%)".format(marginPct, alert.lowerPct, alert.upperPct))

            val isLowerTriggered = alert.lowerPct > 0 && marginPct < alert.lowerPct
            val isUpperTriggered = alert.upperPct > 0 && marginPct > alert.upperPct

            when {
                isLowerTriggered -> {
                    Log.i(TAG, "[$portfolioName] TRIGGER: Margin low (%.2f%% < %.1f%%)".format(marginPct, alert.lowerPct))
                    notify(
                        alertNotifId,
                        "⚠️ Margin Low — $portfolioName",
                        "Margin is %.1f%% — below lower threshold of %.1f%%".format(marginPct, alert.lowerPct)
                    )
                }
                isUpperTriggered -> {
                    Log.i(TAG, "[$portfolioName] TRIGGER: Margin high (%.2f%% > %.1f%%)".format(marginPct, alert.upperPct))
                    notify(
                        alertNotifId,
                        "⚠️ Margin High — $portfolioName",
                        "Margin is %.1f%% — above upper threshold of %.1f%%".format(marginPct, alert.upperPct)
                    )
                }
                else -> {
                    Log.d(TAG, "[$portfolioName] Margin safe. Cancelling any existing alert notification.")
                    nm.cancel(alertNotifId)
                }
            }
        }

        return Result.success()
    }

    private fun ensureChannels() {
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
        if (nm.getNotificationChannel(CHANNEL_SYSTEM_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SYSTEM_ID,
                    "System Tasks",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Background update status" }
            )
        }
    }

    private fun notify(id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted. Cannot notify.")
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
