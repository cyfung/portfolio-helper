// ── stats-formatters.js — Shared stat formatters for backtest & monte carlo ───

function pct(v)   { return (v * 100).toFixed(2) + '%'; }
function fmt2(v)  { return v.toFixed(2); }
function money(v) { return '$' + v.toFixed(0); }
// Format trading-day count as a human-readable duration (e.g. "2.5y", "8m", "0d")
function dur(tradingDays) {
    if (tradingDays <= 0) return '0d';
    if (tradingDays >= 252) return (tradingDays / 252).toFixed(1) + 'y';
    return Math.round(tradingDays / 21) + 'm';
}

// Wire curve-visibility toggle checkboxes in a stats table.
// selectedSet: module-level Set of "pi-ci" keys; headerId: id of the header checkbox.
// onRerender: called whenever selection changes.
function wireCurveToggles(statsContainer, allKeys, selectedSet, headerId, onRerender) {
    function updateHeaderState() {
        const headerCb = document.getElementById(headerId);
        if (!headerCb) return;
        const allChecked = allKeys.every(k => selectedSet.has(k));
        const noneChecked = selectedSet.size === 0;
        headerCb.checked = allChecked;
        headerCb.indeterminate = !allChecked && !noneChecked;
    }

    statsContainer.querySelectorAll('.curve-toggle[data-key]').forEach(cb => {
        cb.checked = selectedSet.has(cb.dataset.key);
        cb.addEventListener('change', () => {
            if (cb.checked) selectedSet.add(cb.dataset.key);
            else selectedSet.delete(cb.dataset.key);
            updateHeaderState();
            onRerender();
        });
    });

    const headerCb = document.getElementById(headerId);
    if (headerCb) {
        updateHeaderState();
        headerCb.addEventListener('change', () => {
            if (headerCb.checked) allKeys.forEach(k => selectedSet.add(k));
            else selectedSet.clear();
            statsContainer.querySelectorAll('.curve-toggle[data-key]').forEach(cb => {
                cb.checked = selectedSet.has(cb.dataset.key);
            });
            onRerender();
        });
    }
}
