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
import androidx.glance.appwidget.updateAll
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
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.PortfolioMarginAlert
import com.portfoliohelper.data.model.Position
import com.portfoliohelper.data.repository.MarginCheckStats
import com.portfoliohelper.data.repository.PortfolioCalculator
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches latest market data from Yahoo Finance and performs margin checks.
 * This worker serves two critical purposes:
 * 1. Price Update: Refreshes cached stock/FX prices so the UI and Home Screen widgets are up-to-date.
 * 2. Margin Check: Evaluates margin thresholds and sends notifications if thresholds are breached.
 *
 * It runs every 15 minutes as long as at least one portfolio exists.
 */
class MarginCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "margin_check_and_price_sync"
        const val CHANNEL_ID = "margin_alerts"
        const val CHANNEL_SYSTEM_ID = "system_tasks"
        const val NOTIF_ID_ALERT_BASE = 10000
        const val NOTIF_ID_ERROR_BASE = 20000
        const val NOTIF_ID_FOREGROUND = 30000
        private const val INTERVAL_MINUTES = 15L
        private const val TAG = "MarginPriceWorker"

        fun schedule(context: Context, shouldRun: Boolean) {
            Log.d(TAG, "Scheduling worker (Price Sync + Margin Check). ShouldRun: $shouldRun")
            val wm = WorkManager.getInstance(context)

            if (!shouldRun) {
                Log.d(TAG, "Worker disabled. Cancelling periodic work.")
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
            Log.d(TAG, "Enqueuing immediate run for price refresh")
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
            .setContentTitle("Updating Market Data")
            .setContentText("Refreshing prices and checking margins...")
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
        Log.d(TAG, "Worker execution started: Price Sync + Margin Check")
        val app = context.applicationContext as PortfolioHelperApp
        
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not run in foreground: ${e.message}")
        }

        try {
            val result = runMarginCheck(app)
            // Update widget to show new prices and margin status
            MarginCheckWidget().updateAll(context)
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in worker: ${e.message}", e)
            app.settingsRepo.updateMarginCheckStats(
                MarginCheckStats(
                    runTime = System.currentTimeMillis(),
                    oldestDataTime = 0L,
                    triggeredPortfolios = emptyList(),
                    errorMessage = e.message ?: "Unknown error"
                )
            )
            MarginCheckWidget().updateAll(context)
            return Result.success()
        }
    }

    private suspend fun runMarginCheck(app: PortfolioHelperApp): Result {
        kotlinx.coroutines.delay(1000)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationsEnabled = app.settingsRepo.marginCheckNotificationsEnabled.first()

        val allAlerts = app.database.portfolioMarginAlertDao().getAll()
        val portfolios = app.database.portfolioDao().getAll().associateBy { it.serialId }
        
        if (allAlerts.isEmpty()) {
            Log.d(TAG, "No alerts or portfolios found to sync.")
            return Result.success()
        }

        try {
            Log.d(TAG, "Attempting background sync with data server...")
            app.syncRepo.sync()
        } catch (e: Exception) {
            Log.w(TAG, "Background sync failed: ${e.message}")
        }

        ensureChannels()

        val allPositions = mutableListOf<Position>()
        val allCashEntries = mutableListOf<CashEntry>()
        val portfolioBundles = allAlerts.map { alert ->
            val pid = alert.portfolioId
            val pos = app.database.positionDao().getAll(pid)
            val cash = app.database.cashDao().getAll(pid)
            allPositions.addAll(pos)
            allCashEntries.addAll(cash)
            Triple(alert, pos, cash)
        }

        if (allPositions.isEmpty() && allCashEntries.isEmpty()) {
            Log.d(TAG, "No positions/cash to update.")
            return Result.success()
        }

        Log.d(TAG, "Fetching latest market prices from Yahoo Finance...")
        val prices = PortfolioCalculator.fetchAndCacheMarketData(app.database, allPositions, allCashEntries)
        Log.i(TAG, "Successfully updated prices for ${prices.size} symbols.")

        val triggeredPortfolioNames = mutableListOf<String>()
        var oldestDataTime = Long.MAX_VALUE

        portfolioBundles.forEach { (alert, positions, cashEntries) ->
            val pid = alert.portfolioId
            val name = portfolios[pid]?.displayName ?: "Portfolio $pid"
            val alertNotifId = NOTIF_ID_ALERT_BASE + pid
            val errorNotifId = NOTIF_ID_ERROR_BASE + pid

            if (positions.isEmpty() && cashEntries.isEmpty()) {
                nm.cancel(alertNotifId)
                nm.cancel(errorNotifId)
                return@forEach
            }

            val totals = PortfolioCalculator.computeTotals(positions, cashEntries, prices)

            positions.forEach { pos ->
                prices[pos.symbol]?.timestamp?.let { if (it < oldestDataTime) oldestDataTime = it }
            }
            cashEntries.map { "${it.currency}USD=X" }.forEach { sym ->
                prices[sym]?.timestamp?.let { if (it < oldestDataTime) oldestDataTime = it }
            }

            if (!totals.isReady) {
                val hasThresholds = alert.lowerPct > 0 || alert.upperPct > 0
                if (hasThresholds && notificationsEnabled) {
                    val symbols = positions.map { it.symbol }.distinct()
                    val currencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
                    val missing = (symbols.filter { it !in prices } + currencies.filter { "${it}USD=X" !in prices }).joinToString(", ")
                    notify(errorNotifId, "⚠️ Margin Check Error ($name)", "Missing data: $missing")
                }
                return@forEach
            }

            nm.cancel(errorNotifId)

            val marginPct = totals.marginPct
            val isLower = alert.lowerPct > 0 && marginPct < alert.lowerPct
            val isUpper = alert.upperPct > 0 && marginPct > alert.upperPct

            if (isLower || isUpper) {
                triggeredPortfolioNames.add(name)
                if (notificationsEnabled) {
                    val title = if (isLower) "⚠️ Margin Low — $name" else "⚠️ Margin High — $name"
                    val body = if (isLower) {
                        "Margin is %.1f%% — below lower threshold of %.1f%%".format(marginPct, alert.lowerPct)
                    } else {
                        "Margin is %.1f%% — above upper threshold of %.1f%%".format(marginPct, alert.upperPct)
                    }
                    notify(alertNotifId, title, body)
                } else {
                    nm.cancel(alertNotifId)
                }
            } else {
                nm.cancel(alertNotifId)
            }
        }

        app.settingsRepo.updateMarginCheckStats(
            MarginCheckStats(
                runTime = System.currentTimeMillis(),
                oldestDataTime = if (oldestDataTime == Long.MAX_VALUE) 0L else oldestDataTime,
                triggeredPortfolios = triggeredPortfolioNames,
                errorMessage = null
            )
        )

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
        val portfolios = app.database.portfolioDao().getAll()
        MarginCheckWorker.schedule(applicationContext, portfolios.isNotEmpty())
        return Result.success()
    }
}
