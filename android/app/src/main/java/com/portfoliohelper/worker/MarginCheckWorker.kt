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
import com.portfoliohelper.data.model.MarginAlertSettings
import com.portfoliohelper.data.model.MarketPrice
import com.portfoliohelper.data.repository.PortfolioCalculator
import com.portfoliohelper.data.repository.PrefsKeys
import com.portfoliohelper.data.repository.YahooFinanceClient
import com.portfoliohelper.data.repository.YahooMarketDataService
import com.portfoliohelper.data.repository.dataStore
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
        const val NOTIF_ID_ERROR = 1003
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
        val app = context.applicationContext as PortfolioHelperApp
        val prefs = context.dataStore.data.first()

        // Read alert settings
        val enabled = prefs[PrefsKeys.MARGIN_ALERT_ENABLED] ?: false
        if (!enabled) return Result.success()

        // 1. Background Sync: Attempt to get latest positions from the server
        try {
            Log.d(TAG, "Attempting background sync with portfolio server...")
            app.syncRepo.sync()
            Log.i(TAG, "Background sync successful")
        } catch (e: Exception) {
            Log.w(TAG, "Background sync failed: ${e.message}. Falling back to cached database data.")
        }

        val lowerPct = (prefs[PrefsKeys.MARGIN_ALERT_LOWER_PCT] ?: 20f).toDouble()
        val upperPct = (prefs[PrefsKeys.MARGIN_ALERT_UPPER_PCT] ?: 50f).toDouble()

        // 2. Read portfolio data from DB
        val positions = app.database.positionDao().getAll()
        val cashEntries = app.database.cashDao().getAll()
        
        if (positions.isEmpty() && cashEntries.isEmpty()) {
            Log.d(TAG, "No data found in database. Success.")
            return Result.success()
        }

        // 3. Fetch/Persist Market Prices
        val symbols = positions.map { it.symbol }.distinct()
        val prices = symbols.associateWith { symbol ->
            try {
                val quote = YahooFinanceClient.fetchQuote(symbol)
                // Persist to database for long-term fallback
                app.database.marketPriceDao().upsert(
                    MarketPrice(symbol, quote.regularMarketPrice ?: 0.0, quote.previousClose)
                )
                YahooMarketDataService.updateCache(symbol, quote)
                quote
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch quote for $symbol", e)
                // Try database fallback
                app.database.marketPriceDao().get(symbol)?.let { dbCached ->
                    com.portfoliohelper.data.repository.YahooQuote(symbol, dbCached.price, dbCached.previousClose)
                }
            }
        }.filterValues { it != null }.mapValues { it.value!! }

        // 4. Fetch/Persist FX rates
        val fxRates = mutableMapOf<String, Double>()
        val currencies = cashEntries.map { it.currency }.distinct().filter { it != "USD" }
        for (ccy in currencies) {
            val pair = "${ccy}USD=X"
            try {
                val quote = YahooFinanceClient.fetchQuote(pair)
                val rate = quote.regularMarketPrice ?: quote.previousClose
                if (rate != null) {
                    app.database.marketPriceDao().upsert(MarketPrice(pair, rate, null))
                    YahooMarketDataService.updateCache(pair, quote)
                    fxRates[ccy] = rate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch FX rate for $ccy", e)
                app.database.marketPriceDao().get(pair)?.let { dbCached ->
                    fxRates[ccy] = dbCached.price
                }
            }
        }

        // 5. Data Readiness Check
        val missingSymbols = symbols.filter { it !in prices }
        val missingCurrencies = currencies.filter { it !in fxRates }
        
        if (missingSymbols.isNotEmpty() || missingCurrencies.isNotEmpty()) {
            val missing = (missingSymbols + missingCurrencies).joinToString(", ")
            Log.w(TAG, "Data missing for: $missing. Showing error alert.")
            ensureChannel()
            notify(
                NOTIF_ID_ERROR,
                "⚠️ Margin Check Error",
                "Missing data for: $missing. Check internet or server sync."
            )
            return Result.success()
        }

        // Clear error if data is now ready
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_ERROR)

        // 6. Compute margin USD
        var marginUsd = 0.0
        for (e in cashEntries) {
            if (e.isMargin) {
                val rate = if (e.currency == "USD") 1.0 else fxRates[e.currency]!!
                marginUsd += e.amount * rate
            }
        }

        val totals = PortfolioCalculator.computeTotals(positions, prices, marginUsd)
        val marginPct = totals.marginPct
        Log.i(TAG, "Computed Margin %: $marginPct")

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
