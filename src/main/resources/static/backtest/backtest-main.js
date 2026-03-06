// ── backtest-main.js — Bootstrap ──────────────────────────────────────────────
// Load order for <script> tags:
//   theme.js → backtest-blocks.js → backtest-saved.js →
//   backtest-chart.js → backtest-run.js → backtest-main.js

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();

    // Initialise all 3 portfolio blocks
    [0, 1, 2].forEach(i => initBlock(i));

    // Load saved portfolios into the bar
    refreshSavedPortfolios();

    // Restore last used settings from server
    (async function restoreSettings() {
        try {
            const res = await fetch('/api/backtest/settings');
            if (!res.ok) return;
            const req = await res.json();
            if (!req.portfolios) return;

            if (req.fromDate) document.getElementById('from-date').value = req.fromDate;
            if (req.toDate)   document.getElementById('to-date').value   = req.toDate;

            req.portfolios.forEach((p, i) => {
                if (i >= 3) return;
                loadPortfolioIntoBlock(i, p, p.label || '');
            });
        } catch (_) { /* silently ignore */ }
    })();

    // Wire up run button, date selectors, import/export
    initRunButton();
    initDateQuickSelectors();
    initImportExport();
});
