package com.portfoliohelper.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.portfoliohelper.data.repository.MarginCheckRunner
import com.portfoliohelper.data.repository.MarginCheckStats
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
        const val NOTIF_ID_FOREGROUND = 30000
        private const val INTERVAL_MINUTES = 15L
        private const val TAG = "MarginPriceWorker"

        fun schedule(
            context: Context,
            shouldRun: Boolean,
            policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        ) {
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
                policy,
                request
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        MarginCheckRunner.ensureChannels(context)
        val notification = NotificationCompat.Builder(context, MarginCheckRunner.CHANNEL_SYSTEM_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Updating Market Data")
            .setContentText("Refreshing prices and checking margins...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIF_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIF_ID_FOREGROUND, notification)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker execution started: Price Sync + Margin Check")
        val app = context.applicationContext as PortfolioHelperApp

        // Signal running state immediately so widget shows ● Running + Chronometer
        val startTime = System.currentTimeMillis()
        app.settingsRepo.setMarginCheckRunning(true, startTime)
        MarginCheckWidgetReceiver.updateAll(context)

        try {
            try {
                setForeground(getForegroundInfo())
            } catch (e: Exception) {
                Log.w(TAG, "Could not run in foreground — worker may be killed early", e)
            }

            val result = kotlinx.coroutines.withTimeout(45_000) {
                MarginCheckRunner.run(context, app)
                Result.success()
            }
            MarginCheckWidgetReceiver.updateAll(context)
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in worker", e)
            app.settingsRepo.updateMarginCheckStats(
                MarginCheckStats(
                    runTime = System.currentTimeMillis(),
                    oldestDataTime = 0L,
                    triggeredPortfolios = emptyList(),
                    errorMessage = e.message ?: "Unknown error"
                )
            )
            MarginCheckWidgetReceiver.updateAll(context)
            return Result.retry()
        } finally {
            // Always clear running state — even if worker is interrupted
            app.settingsRepo.setMarginCheckRunning(false)
            // Repaint widget to last known state (DataStore preserves previous run stats)
            MarginCheckWidgetReceiver.updateAll(context)
        }
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
