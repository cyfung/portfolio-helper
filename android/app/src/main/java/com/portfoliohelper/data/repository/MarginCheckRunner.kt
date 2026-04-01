package com.portfoliohelper.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.portfoliohelper.PortfolioHelperApp
import kotlinx.coroutines.flow.first

object MarginCheckRunner {

    const val CHANNEL_ID = "margin_alerts"
    const val CHANNEL_SYSTEM_ID = "system_tasks"
    const val NOTIF_ID_ALERT_BASE = 10000
    const val NOTIF_ID_ERROR_BASE = 20000
    const val NOTIF_ID_CURRENCY_SUGGESTION = 30000

    private const val TAG = "MarginCheckRunner"

    suspend fun run(context: Context, app: PortfolioHelperApp) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationsEnabled = app.settingsRepo.marginCheckNotificationsEnabled.                           first()

        val allAlerts = app.database.portfolioMarginAlertDao().getAll()
        val portfolios = app.database.portfolioDao().getAll().associateBy { it.serialId }
        val allPortfoliosList = app.database.portfolioDao().getAll()

        if (allAlerts.isEmpty()) {
            Log.d(TAG, "No alerts configured — updating stats timestamp.")
            app.settingsRepo.updateMarginCheckStats(
                MarginCheckStats(
                    runTime = System.currentTimeMillis(),
                    oldestDataTime = System.currentTimeMillis(),
                    triggeredPortfolios = emptyList(),
                    errorMessage = null
                )
            )
            return
        }

        // sync() connects to the local desktop server, which is expected to be offline most of the
        // time (e.g. server not running, or unreachable from the device). Failures are intentionally
        // swallowed — the margin check continues with locally cached DB data.
        try {
            app.syncRepo.sync()
        } catch (e: Exception) {
            Log.w(TAG, "Background sync skipped (local server unavailable): ${e.message}")
        }

        ensureChannels(context)

        val allPositions = app.database.positionDao().getAllPositions()
        val allCashEntries = app.database.cashDao().getAllEntries()

        Log.d(TAG, "Fetching latest market prices from Yahoo Finance...")
        val fetchedPrices =
            PortfolioCalculator.fetchAndCacheMarketData(app.database, allPositions, allCashEntries)
        Log.i(TAG, "Successfully updated prices for ${fetchedPrices.size} symbols.")

        // Fill in any symbols Yahoo failed to return with last-known DB cached prices.
        // This avoids false "Missing data" error notifications on transient network failures.
        val cachedPrices = PortfolioCalculator.loadCachedMarketData(app.database)
        val prices = fetchedPrices.toMutableMap().apply {
            cachedPrices.forEach { (sym, quote) -> putIfAbsent(sym, quote) }
        }

        // Pre-calculate all portfolio stock values for reference entries
        val stockValuesUsd = allPortfoliosList.associate { p ->
            val pPositions = allPositions.filter { it.portfolioId == p.serialId }
            p.slug to PortfolioCalculator.computeStockGrossValue(pPositions, prices)
        }

        val triggeredPortfolioNames = mutableListOf<String>()
        var oldestDataTime = Long.MAX_VALUE

        allAlerts.forEach { alert ->
            val pid = alert.portfolioId
            val name = portfolios[pid]?.displayName ?: "Portfolio $pid"
            val alertNotifId = NOTIF_ID_ALERT_BASE + pid
            val errorNotifId = NOTIF_ID_ERROR_BASE + pid

            val positions = allPositions.filter { it.portfolioId == pid }
            val cashEntries = allCashEntries.filter { it.portfolioId == pid }

            if (positions.isEmpty() && cashEntries.isEmpty()) {
                nm.cancel(alertNotifId)
                nm.cancel(errorNotifId)
                return@forEach
            }

            val totals = PortfolioCalculator.computeTotals(
                positions,
                cashEntries,
                prices,
                "USD",
                stockValuesUsd
            )

            positions.forEach { pos ->
                prices[pos.symbol]?.timestamp?.let { if (it < oldestDataTime) oldestDataTime = it }
            }
            cashEntries.filter { it.currency != "P" }.map { "${it.currency}USD=X" }.forEach { sym ->
                prices[sym]?.timestamp?.let { if (it < oldestDataTime) oldestDataTime = it }
            }

            if (!totals.isReady) {
                val hasThresholds = alert.lowerPct > 0 || alert.upperPct > 0
                if (hasThresholds && notificationsEnabled) {
                    val symbols = positions.map { it.symbol }.distinct()
                    val currencies = cashEntries.map { it.currency }.distinct()
                        .filter { it != "USD" && it != "P" }
                    val missing =
                        (symbols.filter { it !in prices } + currencies.filter { "${it}USD=X" !in prices }).joinToString(
                            ", "
                        )
                    notify(context, errorNotifId, "⚠️ Margin Check Error ($name)", "Missing data: $missing")
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
                        "Margin is %.1f%% — below lower threshold of %.1f%%".format(
                            marginPct,
                            alert.lowerPct
                        )
                    } else {
                        "Margin is %.1f%% — above upper threshold of %.1f%%".format(
                            marginPct,
                            alert.upperPct
                        )
                    }
                    notify(context, alertNotifId, title, body)
                } else {
                    nm.cancel(alertNotifId)
                }
            } else {
                nm.cancel(alertNotifId)
            }
        }

        // ── Currency conversion suggestion ────────────────────────────────────
        var currencySuggestionText: String? = null
        try {
            val fxRates = prices
                .filter { (sym, _) -> sym.endsWith("USD=X") }
                .mapKeys { (sym, _) -> sym.removeSuffix("USD=X") }
                .mapNotNull { (ccy, q) -> q.regularMarketPrice?.let { ccy to it } }
                .toMap()
            val ratesSnap = IbkrRateFetcher.fetch()
            if (ratesSnap != null) {
                val threshold = app.settingsRepo.currencySuggestionThresholdUsd.first()
                val result = IbkrInterestCalculator.compute(allCashEntries, fxRates, ratesSnap)
                if (result != null && result.savingsUsd >= threshold) {
                    currencySuggestionText = "Convert all loan balance to ${result.cheapestCcy}"
                    if (notificationsEnabled) {
                        notify(
                            context,
                            NOTIF_ID_CURRENCY_SUGGESTION,
                            "💡 Currency Conversion",
                            "${result.cheapestCcy?.let { "Convert all loan balance to $it" } ?: ""} · saves \$${"%.2f".format(result.savingsUsd)}/day"
                        )
                    }
                } else {
                    nm.cancel(NOTIF_ID_CURRENCY_SUGGESTION)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Currency suggestion check skipped: ${e.message}")
        }

        app.settingsRepo.updateMarginCheckStats(
            MarginCheckStats(
                runTime = System.currentTimeMillis(),
                oldestDataTime = if (oldestDataTime == Long.MAX_VALUE) System.currentTimeMillis() else oldestDataTime,
                triggeredPortfolios = triggeredPortfolioNames,
                errorMessage = null,
                currencySuggestionText = currencySuggestionText
            )
        )
    }

    fun ensureChannels(context: Context) {
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

    private fun notify(context: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
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
