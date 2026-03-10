// ── main.js — Application bootstrap (DOMContentLoaded only) ──────────────────
// Load order for <script> tags:
//   utils.js → ui-helpers.js → letf.js → cash.js → rebalance.js →
//   portfolio.js → edit-mode.js → controls.js → backup.js → sse.js → main.js

document.addEventListener('DOMContentLoaded', () => {
    initSseConnection();
    initColumnVisibility();
    initGroupViewToggle();
    initEditMode();
    initPasteHandler();
    initCurrencyControls();
    initRebalanceControls();
    initBackupPanel();
    initTwsSync();
    initSaveToBacktest();
    initThemeToggle();

    // Initialize cash totals on page load (USD entries are pre-filled server-side).
    // Must run before restoring targets so lastMarginUsd is correct.
    updateCashTotals();

    // Restore saved rebalance/margin targets — margin % takes priority
    const marginTargetInput = document.getElementById('margin-target-input');
    const rebalTargetInput = document.getElementById('rebal-target-input');
    if (savedMarginTargetPct > 0 && marginTargetInput) {
        marginTargetPct = savedMarginTargetPct;
        marginTargetInput.value = savedMarginTargetPct.toLocaleString('en-US', {
            minimumFractionDigits: 1, maximumFractionDigits: 4
        });
        refreshRebalUI();
    } else if (savedRebalTargetUsd > 0 && rebalTargetInput) {
        rebalTargetUsd = savedRebalTargetUsd;
        const rate = fxRates[currentDisplayCurrency];
        const displayVal = (rate && rate !== 0) ? savedRebalTargetUsd / rate : savedRebalTargetUsd;
        rebalTargetInput.value = displayVal.toLocaleString('en-US', {
            minimumFractionDigits: 2, maximumFractionDigits: 2
        });
        refreshRebalUI();
    }

    // Refresh display currency for any server-rendered values
    if (currentDisplayCurrency !== 'USD') {
        refreshDisplayCurrency();
    }

    updateRebalTargetPlaceholder();
    updateMarginTargetDisplay();
    updateTargetWeightTotal();
});
