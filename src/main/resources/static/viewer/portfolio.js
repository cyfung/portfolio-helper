// ── portfolio.js — Price ingestion: writes globals, delegates display to worker ─
// Depends on: utils.js, ui-helpers.js, rebalance.js, cash.js, display-worker.js

function updateNavInUI(symbol, nav) {
    navValues[symbol] = nav;
    const navCell = document.getElementById('nav-' + symbol);
    if (navCell) {
        navCell.textContent = nav !== null ? nav.toFixed(2) : '—';
        if (nav !== null) navCell.classList.add('loaded');
    }
    scheduleDisplayUpdate();
}

function updatePriceInUI(symbol, markPrice, lastClosePrice, isMarketClosed, tradingPeriodEnd) {
    // Update per-symbol market state globals
    symbolMarketClosed[symbol] = isMarketClosed;
    if (tradingPeriodEnd !== null && tradingPeriodEnd !== undefined) {
        const endMs = tradingPeriodEnd * 1000;
        if (endMs <= Date.now()) {
            symbolTradingPeriodEndMs[symbol] = endMs;
        }
    }

    // Store raw prices for high-precision calculations
    if (markPrice !== null) rawMarkPrices[symbol] = markPrice;
    if (lastClosePrice !== null) rawClosePrices[symbol] = lastClosePrice;

    // Store Day % for LETF Est Val calculations
    if (markPrice != null && lastClosePrice != null && lastClosePrice !== 0) {
        componentDayPercents[symbol] = ((markPrice - lastClosePrice) / lastClosePrice) * 100;
    }

    scheduleDisplayUpdate();
}
