// ── main.js — Application bootstrap (DOMContentLoaded only) ──────────────────
// Load order for <script> tags:
//   utils.js → ui-helpers.js → letf.js → cash.js → rebalance.js →
//   display-worker.js → portfolio.js → rebalance-ga.js → groups.js →
//   edit-mode.js → controls.js → backup.js → sse.js → main.js

document.addEventListener('DOMContentLoaded', () => {
    initSseConnection();
    initMoreInfoToggle();
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

    // Compute display values (including cash totals) from server-rendered DOM data.
    // The worker reads cash entries from DOM and sets lastMarginUsd/lastCashTotalUsd globals.
    scheduleDisplayUpdate();

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

    updateTargetWeightTotal();
});
