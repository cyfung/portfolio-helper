// ── cash.js — Cash table structure rebuild and portfolio-ref updates ──────────
// Depends on: utils.js, ui-helpers.js, rebalance.js
// Note: cash totals, margin display, and IBKR interest are computed inside
// display-worker.js and applied via _applyDisplayResult().

function updatePortfolioRefValues(portfolioId, newPortfolioValue) {
    let updated = false;
    document.querySelectorAll(`[data-portfolio-ref="${portfolioId}"]`).forEach(row => {
        const mult = parseFloat(row.dataset.portfolioMultiplier || '1');
        const newAmount = mult * newPortfolioValue;
        row.dataset.amount = newAmount.toString();
        const rawCol = row.querySelector('.cash-raw-col');
        if (rawCol) rawCol.textContent = formatCurrency(Math.abs(newAmount)) + ' USD';
        updated = true;
    });
    if (updated) { scheduleDisplayUpdate(); }
}
