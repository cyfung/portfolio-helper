package com.portfoliohelper.service.nav

import com.portfoliohelper.service.PollingService
import com.portfoliohelper.service.TradingDaySchedule

object NavService : PollingService<NavData>("NAV") {

    private val providers: Map<String, NavProvider> = listOf(
        CtapNavProvider,
        CtaNavProvider
    ).associateBy { it.symbol }

    fun requestNavForSymbols(symbols: List<String>) {
        val supportedSymbols = symbols.filter { it in providers }

        if (supportedSymbols.isEmpty()) {
            logger.info("No symbols with NAV providers found in portfolio")
            return
        }

        logger.info("Starting NAV polling for ${supportedSymbols.size} symbols: $supportedSymbols (next: ${TradingDaySchedule.describeNextFetch()})")
        startPollingWithSchedule(supportedSymbols, TradingDaySchedule::nextNavFetchDelayMs)
    }

    override suspend fun fetchItem(symbol: String): NavData? {
        val provider = providers[symbol] ?: return null
        val navData = provider.fetchNav() ?: return null
        logger.debug("Updated NAV for $symbol: ${navData.nav}")
        return navData
    }

    fun getNav(symbol: String): Double? = get(symbol)?.nav

    fun onNavUpdate(callback: (String, NavData) -> Unit) = onUpdate(callback)

    override fun shutdown() {
        super.shutdown()
        SimplifyEtfNavProvider.shutdown()
    }
}
