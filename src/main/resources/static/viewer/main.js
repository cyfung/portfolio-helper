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
    document.body.classList.toggle('after-hours-gray', afterHoursGray);

    // Compute initial weight/rebal/alloc columns. Per-stock prices and totals
    // are populated once the first stock-display/portfolio-totals SSE events arrive.
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

    initTabDragAndDrop();
});

function initTabDragAndDrop() {
    const container = document.querySelector('.portfolio-tabs');
    const tabs = Array.from(container.querySelectorAll('.tab-link'));

    if (tabs.length < 2) return;

    let dragged = null;
    let dragModeOn = false;
    let didDrag = false;

    // Single indicator line — repositioned in the flex container during drag
    const indicator = document.createElement('div');
    indicator.className = 'tab-drop-indicator';
    indicator.style.display = 'none';
    container.appendChild(indicator);

    function setDragMode(on) {
        dragModeOn = on;
        container.classList.toggle('drag-mode', on);
        tabs.forEach(tab => tab.setAttribute('draggable', on ? 'true' : 'false'));
        if (!on) indicator.style.display = 'none';
    }

    // Sync drag mode with edit mode
    new MutationObserver(() => {
        setDragMode(document.body.classList.contains('editing-active'));
    }).observe(document.body, { attributeFilter: ['class'] });
    setDragMode(document.body.classList.contains('editing-active'));

    function postMove(slug, newSeqOrder) {
        fetch('/api/portfolios/move-tab', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ slug, seqOrder: newSeqOrder })
        }).then(r => {
            if (r.ok) {
                if (document.body.classList.contains('editing-active'))
                    sessionStorage.setItem('restoreEditMode', '1');
                location.reload();
            }
        });
    }

    function computeSeqOrder(reordered, newIdx) {
        const prevOrder = newIdx > 0
            ? parseFloat(reordered[newIdx - 1].dataset.seqOrder)
            : parseFloat(reordered[newIdx + 1].dataset.seqOrder) - 2;
        const nextOrder = newIdx < reordered.length - 1
            ? parseFloat(reordered[newIdx + 1].dataset.seqOrder)
            : parseFloat(reordered[newIdx - 1].dataset.seqOrder) + 2;
        return (prevOrder + nextOrder) / 2;
    }

    function buildReordered(fromIdx, targetPos) {
        const reordered = tabs.slice();
        reordered.splice(fromIdx, 1);
        const insertAt = fromIdx < targetPos ? targetPos - 1 : targetPos;
        reordered.splice(insertAt, 0, dragged);
        return reordered;
    }

    tabs.forEach(tab => {
        tab.addEventListener('click', e => {
            if (didDrag) { didDrag = false; e.preventDefault(); return; }
            if (document.body.classList.contains('editing-active'))
                sessionStorage.setItem('restoreEditMode', '1');
        });

        tab.addEventListener('dragstart', e => {
            if (!dragModeOn) { e.preventDefault(); return; }
            didDrag = true;
            dragged = tab;
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setDragImage(tab, 20, tab.offsetHeight / 2);
            requestAnimationFrame(() => tab.classList.add('dragging'));
        });

        tab.addEventListener('dragend', () => {
            tab.classList.remove('dragging');
            indicator.style.display = 'none';
            dragged = null;
        });

        tab.addEventListener('dragover', e => {
            if (!dragged || tab === dragged) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            const rect = tab.getBoundingClientRect();
            const isLeft = e.clientX < rect.left + rect.width / 2;
            indicator.style.display = '';
            container.insertBefore(indicator, isLeft ? tab : tab.nextSibling);
        });

        tab.addEventListener('drop', e => {
            e.preventDefault();
            indicator.style.display = 'none';
            if (!dragged || dragged === tab) return;

            const slug = dragged.dataset.slug;
            const rect = tab.getBoundingClientRect();
            const isLeft = e.clientX < rect.left + rect.width / 2;
            const fromIdx = tabs.indexOf(dragged);
            const toIdx   = tabs.indexOf(tab);
            const targetPos = isLeft ? toIdx : toIdx + 1;
            const reordered = buildReordered(fromIdx, targetPos);
            postMove(slug, computeSeqOrder(reordered, reordered.indexOf(dragged)));
        });
    });

    // Hide indicator when cursor leaves the tab bar
    container.addEventListener('dragleave', e => {
        if (!container.contains(e.relatedTarget)) {
            indicator.style.display = 'none';
        }
    });
}
