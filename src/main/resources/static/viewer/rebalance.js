// ── rebalance.js — Rebalancing columns, allocation modes, margin target ───────
// Depends on: utils.js, ui-helpers.js

function getRebalTotal() {
    if (marginTargetPct !== null) return deriveRebalFromMarginPct(marginTargetPct);
    if (rebalTargetUsd !== null && rebalTargetUsd > 0) return rebalTargetUsd;
    return lastStockGrossVal + Math.max(lastMarginUsd, 0);
}

// getAllocRebalTotal uses same logic as getRebalTotal
const getAllocRebalTotal = getRebalTotal;

function deriveMarginPct(rebalTotal) {
    const ec = lastStockGrossVal + lastMarginUsd;
    if (ec <= 0) return 0;
    const marginPct = (lastMarginUsd - (rebalTotal - lastStockGrossVal)) / ec * 100;
    if (marginPct >= 0) return 0;
    return -marginPct;
}

function deriveRebalFromMarginPct(pct) {
    const ec = lastStockGrossVal + lastMarginUsd;
    return (pct / 100) * ec + lastStockGrossVal + lastMarginUsd;
}

function refreshRebalUI() {
    scheduleDisplayUpdate();
}

function updateRebalTargetPlaceholder() {
    const input = document.getElementById('rebal-target-input');
    if (!input) return;
    const marginInput = document.getElementById('margin-target-input');
    const baseUsd = (marginInput && marginInput.value.trim() !== '')
        ? getRebalTotal()
        : lastStockGrossVal + Math.max(lastMarginUsd, 0);
    const converted = toDisplayCurrency(baseUsd);
    input.placeholder = Math.abs(converted).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
    });
}

function updateMarginInputPlaceholder() {
    const input = document.getElementById('margin-target-input');
    if (!input || input.value.trim() !== '') return;
    let pct = deriveMarginPct(getRebalTotal());
    input.placeholder = pct.toFixed(1);
}

function updateMarginTargetDisplay() {
    const marginTargetRow = document.getElementById('margin-target-row');
    if (!marginTargetRow) return;

    const marginTargetEl = document.getElementById('margin-target-usd');
    const rebalTotal = getRebalTotal();
    const marginTargetUsd = lastMarginUsd - (rebalTotal - lastStockGrossVal);
    if (marginTargetEl) {
        marginTargetEl.textContent = marginTargetUsd < 0 ? formatDisplayCurrency(-marginTargetUsd) : '';
    }

    updateMarginInputPlaceholder();
}

function updateRebalancingColumns(_portfolioTotal) {
    // No-op: display worker handles rebal column updates
}

function updateAllocColumns(_rebalTotal) {
    // No-op: display worker handles alloc column updates
}

// ── Allocation computation strategies ────────────────────────────────────────

function applyProportionalSpillover(alloc, eligible, remaining, sign) {
    for (const s of eligible)
        alloc[s.symbol] = (alloc[s.symbol] ?? 0) + (s.targetWeight / 100) * remaining * sign;
}

function computeUndervalueFirst(eligible, totalStockValue, delta) {
    const alloc = {};
    for (const s of eligible) alloc[s.symbol] = 0;

    const finalTotal = totalStockValue + delta;
    const sign = delta >= 0 ? 1 : -1;

    const sorted = [...eligible].sort((a, b) =>
        sign * (
            (a.currentValue / finalTotal - a.targetWeight / 100) -
            (b.currentValue / finalTotal - b.targetWeight / 100)
        )
    );

    let remaining = Math.abs(delta);
    for (const s of sorted) {
        if (remaining <= 0) break;
        const target = finalTotal * (s.targetWeight / 100);
        const amount = Math.min(remaining, Math.max(0, (target - s.currentValue) * sign));
        alloc[s.symbol] = amount * sign;
        remaining -= amount;
    }

    if (remaining > 0) applyProportionalSpillover(alloc, eligible, remaining, sign);
    return alloc;
}

function computeWaterfall(eligible, totalStockValue, delta) {
    const alloc = {};
    for (const s of eligible) alloc[s.symbol] = 0;

    const finalTotal = totalStockValue + delta;
    const sign = delta >= 0 ? 1 : -1;

    const currentDev = {};
    for (const s of eligible) {
        currentDev[s.symbol] = (s.currentValue / finalTotal) - (s.targetWeight / 100);
    }

    const sorted = [...eligible].sort((a, b) => sign * (currentDev[a.symbol] - currentDev[b.symbol]));
    let remaining = Math.abs(delta);

    for (let i = 0; i < sorted.length && remaining > 0; i++) {
        const groupDev = currentDev[sorted[0].symbol];
        const nextDev = i + 1 < sorted.length ? currentDev[sorted[i + 1].symbol] : sign * Infinity;
        const groupSize = i + 1;
        const costToLevel = (nextDev - groupDev) * sign * finalTotal * groupSize;

        if (remaining >= costToLevel) {
            for (let j = 0; j <= i; j++) {
                alloc[sorted[j].symbol] += (nextDev - groupDev) * finalTotal;
                currentDev[sorted[j].symbol] = nextDev;
            }
            remaining -= costToLevel;
        } else {
            const perStock = remaining / groupSize;
            for (let j = 0; j <= i; j++) {
                alloc[sorted[j].symbol] += perStock * sign;
                currentDev[sorted[j].symbol] += (perStock / finalTotal) * sign;
            }
            remaining = 0;
        }
    }

    if (remaining > 0) applyProportionalSpillover(alloc, eligible, remaining, sign);
    return alloc;
}

function computeAllocations(delta, stocks, totalStockValue, mode) {
    const result = {};
    if (mode === 'PROPORTIONAL') {
        for (const s of stocks)
            result[s.symbol] = s.targetWeight !== null ? (s.targetWeight / 100) * delta : null;

    } else if (mode === 'CURRENT_WEIGHT') {
        for (const s of stocks) {
            const w = totalStockValue > 0 ? s.currentValue / totalStockValue : 0;
            result[s.symbol] = w * delta;
        }

    } else if (mode === 'UNDERVALUED_PRIORITY' || mode === 'WATERFALL') {
        const eligible = stocks.filter(s => s.targetWeight !== null);
        const alloc = mode === 'WATERFALL'
            ? computeWaterfall(eligible, totalStockValue, delta)
            : computeUndervalueFirst(eligible, totalStockValue, delta);
        for (const s of eligible) result[s.symbol] = alloc[s.symbol] ?? 0;
    }
    return result;
}

function updateTargetWeightTotal() {
    let editTotal = 0;
    (document.querySelectorAll('#stock-edit-table tbody tr:not([data-deleted])') || []).forEach(tr => {
        const input = tr.querySelector('.edit-weight') || tr.querySelector('input[data-column="weight"]');
        if (input) editTotal += parseFloat(input.value) || 0;
    });
    const totalCell = document.getElementById('target-weight-total');
    if (totalCell) {
        totalCell.textContent = editTotal.toFixed(1) + '%';
        totalCell.classList.toggle('weight-total-error', Math.abs(editTotal - 100) > 0.05);
    }

    let rebalTotal = 0;
    document.querySelectorAll('#stock-view-table tbody tr').forEach(tr => {
        rebalTotal += parseFloat(tr.dataset.weight) || 0;
    });
    const warningEl = document.getElementById('rebal-weight-warning');
    if (warningEl) {
        const isError = Math.abs(rebalTotal - 100) > 0.05;
        const rebalVisible = document.body.classList.contains('rebalancing-visible');
        const isEditing = document.body.classList.contains('editing-active');
        if (rebalVisible && !isEditing && isError) {
            warningEl.textContent = '\u26a0 Target weights sum to ' + rebalTotal.toFixed(1) + '% (must be 100%)';
            warningEl.style.display = 'block';
        } else {
            warningEl.style.display = 'none';
        }
    }
}
