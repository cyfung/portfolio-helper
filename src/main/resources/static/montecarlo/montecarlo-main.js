// ── montecarlo-main.js — Bootstrap ────────────────────────────────────────────
// Load order for <script> tags:
//   theme.js → backtest-blocks.js → backtest-saved.js →
//   montecarlo-chart.js → montecarlo-run.js → montecarlo-main.js

(async () => {
    initThemeToggle();

    // Initialise all 3 portfolio blocks (reuse backtest-blocks.js)
    [0, 1, 2].forEach(i => initBlock(i));

    // Load saved portfolios bar (reuse backtest-saved.js)
    refreshSavedPortfolios();

    // Wire up run button, date selectors, percentile tabs, import/export
    initMcRunButton();
    initMcDateQuickSelectors();
    initMcDateClearBtns();
    initPercentileTabs();
    initMcImportExport();

    // Restore saved MC settings
    try {
        const res = await fetch('/api/montecarlo/settings');
        if (!res.ok) { console.warn('[mc-main] settings fetch failed:', res.status); return; }
        const req = await res.json();
        if (!req || !Object.keys(req).length) return;
        if (req.fromDate) document.getElementById('mc-from-date').value = req.fromDate;
        if (req.toDate)   document.getElementById('mc-to-date').value   = req.toDate;
        updateDateClearBtns();
        if (req.minChunkYears  != null) document.getElementById('mc-min-chunk').value   = req.minChunkYears;
        if (req.maxChunkYears  != null) document.getElementById('mc-max-chunk').value   = req.maxChunkYears;
        if (req.simulatedYears != null) document.getElementById('mc-sim-years').value   = req.simulatedYears;
        if (req.numSimulations != null) document.getElementById('mc-num-sims').value    = req.numSimulations;
        if (req.portfolios) req.portfolios.forEach((p, i) => {
            if (i < 3) loadPortfolioIntoBlock(i, p, p.label || '');
        });
    } catch (e) { console.error('[mc-main] settings restore failed:', e); }
})();
