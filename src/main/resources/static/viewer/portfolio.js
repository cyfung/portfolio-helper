// ── portfolio.js — Price ingestion: writes globals for legacy compatibility ────
// Depends on: utils.js, ui-helpers.js, rebalance.js, cash.js, display-worker.js
// Note: per-stock display cells are now updated by applyStockDisplay() in ui-helpers.js
// when the server sends a 'stock-display' SSE event.

function updateNavInUI(symbol, nav) {
    navValues[symbol] = nav;
    const navCell = document.getElementById('nav-' + symbol);
    if (navCell) {
        navCell.textContent = nav !== null ? nav.toFixed(2) : '—';
        if (nav !== null) navCell.classList.add('loaded');
    }
    // No scheduleDisplayUpdate() — est val is now computed server-side
}

function updatePriceInUI(symbol, markPrice, lastClosePrice, isMarketClosed, tradingPeriodEnd) {
    // Keep globals for FX/display-currency compatibility; stock cells are updated via applyStockDisplay
    symbolMarketClosed[symbol] = isMarketClosed;
    if (tradingPeriodEnd !== null && tradingPeriodEnd !== undefined) {
        const endMs = tradingPeriodEnd * 1000;
        if (endMs <= Date.now()) {
            symbolTradingPeriodEndMs[symbol] = endMs;
        }
    }
    if (markPrice !== null) rawMarkPrices[symbol] = markPrice;
    if (lastClosePrice !== null) rawClosePrices[symbol] = lastClosePrice;
    // No scheduleDisplayUpdate() — stock cells are updated via applyStockDisplay SSE event
}
