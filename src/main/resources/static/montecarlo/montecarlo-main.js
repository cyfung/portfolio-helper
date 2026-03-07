// ── montecarlo-main.js — Bootstrap ────────────────────────────────────────────
// Load order for <script> tags:
//   theme.js → backtest-blocks.js → backtest-saved.js →
//   montecarlo-chart.js → montecarlo-run.js → montecarlo-main.js

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();

    // Initialise all 3 portfolio blocks (reuse backtest-blocks.js)
    [0, 1, 2].forEach(i => initBlock(i));

    // Load saved portfolios bar (reuse backtest-saved.js)
    refreshSavedPortfolios();

    // Wire up run button, date selectors, percentile tabs
    initMcRunButton();
    initMcDateQuickSelectors();
    initMcDateClearBtns();
    initPercentileTabs();
});
