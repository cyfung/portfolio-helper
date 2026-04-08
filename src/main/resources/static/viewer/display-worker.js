// ── display-worker.js — Background worker for rebalance/allocation computation ──
// Per-stock prices, cash totals, and IBKR interest are now computed server-side and
// applied via applyStockDisplay / applyCashDisplay / applyPortfolioTotals / ibkr-display SSE.
// This worker only handles weight, rebal, and alloc columns (needs user UI state).
//
// Depends on: globals.js, utils.js (parsePrice available on main thread)
// Load order: after rebalance.js, before portfolio.js

// ── Section 1: Worker source (pure JS, no DOM) ────────────────────────────────

const _DISPLAY_WORKER_SRC = `
'use strict';

function formatCurrency(val) {
    const sign = val < 0 ? '-' : '';
    return sign + Math.abs(val).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatSignedCurrency(val) {
    return (val >= 0 ? '+' : '') + formatCurrency(val);
}

function toDisplayCurrency(usdVal, fxRates, cdc) {
    const rate = fxRates[cdc];
    return (rate && rate !== 0) ? usdVal / rate : usdVal;
}

function getStockFxRate(stockCcy, fxRates) {
    if (stockCcy === 'USD') return 1.0;
    if (/^[A-Z]{2}[a-z]$/.test(stockCcy)) {
        const parent = stockCcy.toUpperCase();
        const rate = fxRates[parent];
        return rate != null ? rate / 100 : null;
    }
    const rate = fxRates[stockCcy];
    return rate != null ? rate : null;
}

function deriveRebalFromMarginPct(pct, stockVal, marginUsd) {
    const ec = stockVal + marginUsd;
    return (pct / 100) * ec + stockVal + marginUsd;
}

function getRebalTotal(stockVal, marginUsd, rebalTargetUsd, marginTargetPct) {
    if (marginTargetPct !== null) return deriveRebalFromMarginPct(marginTargetPct, stockVal, marginUsd);
    if (rebalTargetUsd !== null && rebalTargetUsd > 0) return rebalTargetUsd;
    return stockVal + Math.max(marginUsd, 0);
}

function deriveMarginPct(rebalTotal, stockVal, marginUsd) {
    const ec = stockVal + marginUsd;
    if (ec <= 0) return 0;
    const marginPct = (marginUsd - (rebalTotal - stockVal)) / ec * 100;
    if (marginPct >= 0) return 0;
    return -marginPct;
}

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

function compute(snap) {
    const {
        stocks, stockGrossUsd, stockGrossKnown, marginUsd,
        fxRates, currentDisplayCurrency: cdc,
        rebalTargetUsd, marginTargetPct, allocAddMode, allocReduceMode
    } = snap;

    const rebalTotal = getRebalTotal(stockGrossUsd, marginUsd, rebalTargetUsd, marginTargetPct);
    const allocStocks = [];
    const perStockData = {};

    for (const stock of stocks) {
        const { symbol, qty, targetWeight, markPrice, closePrice, positionValueUsd, currency } = stock;
        const d = {};

        // Weight
        if (!stockGrossKnown) {
            d.weightHTML = 'N/A'; d.weightLoaded = false;
        } else if (stockGrossUsd > 0 && positionValueUsd !== null) {
            const currentWeight = (positionValueUsd / stockGrossUsd) * 100;
            if (targetWeight !== null && !isNaN(targetWeight)) {
                const diff = currentWeight - targetWeight;
                const diffClass = Math.abs(diff) > 1.0 ? (diff > 0 ? 'alert-over' : 'alert-under')
                    : Math.abs(diff) > 0.2 ? 'warning' : 'good';
                const diffSign = diff >= 0 ? '+' : '';
                d.weightHTML = '<span class="weight-cur">' + currentWeight.toFixed(1) + '%</span>' +
                    '<span class="weight-sep">/</span>' +
                    '<span class="weight-tgt">' + targetWeight.toFixed(1) + '%</span>' +
                    '<span class="weight-diff ' + diffClass + '">' + diffSign + diff.toFixed(1) + '%</span>';
            } else {
                d.weightHTML = currentWeight.toFixed(1) + '%';
            }
            d.weightLoaded = true;
        } else {
            d.weightHTML = ''; d.weightLoaded = false;
        }

        // Rebal
        const effectivePrice = markPrice !== null ? markPrice : closePrice;
        const rebalDollarsClass0 = 'action-neutral rebal-column';
        const rebalQtyClass0 = 'action-neutral rebal-column col-moreinfo';
        if (!stockGrossKnown) {
            d.rebalDollarsText = 'N/A'; d.rebalDollarsClass = rebalDollarsClass0;
            d.rebalQtyText = 'N/A'; d.rebalQtyClass = rebalQtyClass0;
        } else if (rebalTotal > 0 && targetWeight !== null && !isNaN(targetWeight) && effectivePrice !== null) {
            const nativeValue = effectivePrice * qty;
            const targetValue = (targetWeight / 100) * rebalTotal;
            const rebalDollars = targetValue - nativeValue;
            const direction = Math.abs(rebalDollars) > 0.50
                ? (rebalDollars > 0 ? 'action-positive' : 'action-negative') : 'action-neutral';
            d.rebalDollarsText = formatSignedCurrency(rebalDollars);
            d.rebalDollarsClass = 'action-neutral loaded rebal-column ' + direction;
            if (markPrice !== null && markPrice > 0) {
                const rebalShares = rebalDollars / markPrice;
                d.rebalQtyText = (rebalShares >= 0 ? '+' : '-') + Math.abs(rebalShares).toFixed(2);
                d.rebalQtyClass = 'action-neutral loaded rebal-column col-moreinfo ' + direction;
            } else {
                d.rebalQtyText = ''; d.rebalQtyClass = rebalQtyClass0;
            }
        } else {
            d.rebalDollarsText = ''; d.rebalDollarsClass = rebalDollarsClass0;
            d.rebalQtyText = ''; d.rebalQtyClass = rebalQtyClass0;
        }

        const nativeValue = effectivePrice !== null ? effectivePrice * qty : 0;
        allocStocks.push({ symbol, markPrice, targetWeight, currentValue: nativeValue, currency });
        perStockData[symbol] = d;
    }

    // Alloc
    const totalStockValueForAlloc = allocStocks.reduce((s, x) => s + x.currentValue, 0);
    const allocDelta = rebalTotal - stockGrossUsd;
    const allocMode = allocDelta >= 0 ? allocAddMode : allocReduceMode;
    const allocations = stockGrossKnown
        ? computeAllocations(allocDelta, allocStocks, totalStockValueForAlloc, allocMode)
        : {};

    for (const stock of stocks) {
        const { symbol } = stock;
        const d = perStockData[symbol];
        const allocDollarsClass0 = 'action-neutral alloc-column';
        const allocQtyClass0 = 'action-neutral alloc-column col-moreinfo';
        if (!stockGrossKnown) {
            d.allocDollarsText = 'N/A'; d.allocDollarsClass = allocDollarsClass0;
            d.allocQtyText = 'N/A'; d.allocQtyClass = allocQtyClass0;
        } else {
            const amt = allocations[symbol];
            if (amt != null) {
                const s = allocStocks.find(x => x.symbol === symbol);
                const fxRate = getStockFxRate(s?.currency ?? 'USD', fxRates);
                const amtNative = (fxRate !== null && fxRate > 0) ? amt / fxRate : amt;
                const dir = amtNative > 0.50 ? 'action-positive' : amtNative < -0.50 ? 'action-negative' : 'action-neutral';
                d.allocDollarsText = formatSignedCurrency(amtNative);
                d.allocDollarsClass = 'action-neutral loaded alloc-column ' + dir;
                if (s && s.markPrice > 0) {
                    const qty = amtNative / s.markPrice;
                    d.allocQtyText = (qty >= 0 ? '+' : '-') + Math.abs(qty).toFixed(2);
                    d.allocQtyClass = 'action-neutral loaded alloc-column col-moreinfo ' + dir;
                } else {
                    d.allocQtyText = ''; d.allocQtyClass = allocQtyClass0;
                }
            } else {
                d.allocDollarsText = ''; d.allocDollarsClass = allocDollarsClass0;
                d.allocQtyText = ''; d.allocQtyClass = allocQtyClass0;
            }
        }
    }

    // Placeholders
    const marginTargetUsd = marginUsd - (rebalTotal - stockGrossUsd);
    const marginTargetText = marginTargetUsd < 0 ? formatCurrency(-marginTargetUsd) : '';
    const marginInputPlaceholder = deriveMarginPct(rebalTotal, stockGrossUsd, marginUsd).toFixed(1);
    const baseUsd = marginTargetPct !== null ? rebalTotal : stockGrossUsd + Math.max(marginUsd, 0);
    const rebalTargetPlaceholder = Math.abs(toDisplayCurrency(baseUsd, fxRates, cdc)).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
    });

    const perStock = {};
    for (const stock of stocks) {
        const { symbol } = stock;
        const d = perStockData[symbol];
        perStock[symbol] = {
            weightHTML: d.weightHTML, weightLoaded: d.weightLoaded,
            rebalDollarsText: d.rebalDollarsText, rebalDollarsClass: d.rebalDollarsClass,
            rebalQtyText: d.rebalQtyText, rebalQtyClass: d.rebalQtyClass,
            allocDollarsText: d.allocDollarsText, allocDollarsClass: d.allocDollarsClass,
            allocQtyText: d.allocQtyText, allocQtyClass: d.allocQtyClass
        };
    }

    return { perStock, marginTargetText, marginInputPlaceholder, rebalTargetPlaceholder };
}

self.onmessage = function(e) {
    try {
        self.postMessage(compute(e.data));
    } catch (err) {
        console.error('[display-worker] compute error:', err);
        self.postMessage({ error: err.message });
    }
};
`;

// ── Section 2: Worker lifecycle ───────────────────────────────────────────────

var _displayWorker = null;
var _workerRunning = false;
var _pendingUpdate = false;

function _getDisplayWorker() {
    if (!_displayWorker) {
        const blob = new Blob([_DISPLAY_WORKER_SRC], { type: 'application/javascript' });
        const url = URL.createObjectURL(blob);
        _displayWorker = new Worker(url);
    }
    return _displayWorker;
}

function scheduleDisplayUpdate() {
    if (_workerRunning) { _pendingUpdate = true; return; }
    _dispatchDisplayUpdate();
}

function _dispatchDisplayUpdate() {
    _workerRunning = true;
    _pendingUpdate = false;
    const snapshot = _buildSnapshot();
    const w = _getDisplayWorker();
    w.onmessage = function(e) {
        _workerRunning = false;
        if (e.data && !e.data.error) _applyDisplayResult(e.data);
        if (_pendingUpdate) { _pendingUpdate = false; _dispatchDisplayUpdate(); }
    };
    w.onerror = function(err) {
        console.error('[display-worker]', err);
        _workerRunning = false;
        if (_pendingUpdate) { _pendingUpdate = false; _dispatchDisplayUpdate(); }
    };
    w.postMessage(snapshot);
}

// ── Section 3: Snapshot builder ───────────────────────────────────────────────

function _buildSnapshot() {
    const stocks = [];
    document.querySelectorAll('#stock-view-table tbody tr[data-symbol]').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        const qty = parseFloat(row.dataset.qty) || 0;
        const tw = parseFloat(row.dataset.weight);
        const targetWeight = isNaN(tw) ? null : tw;
        const srv = lastServerStocks[symbol] || {};
        stocks.push({
            symbol, qty, targetWeight,
            markPrice: srv.markPrice ?? null,
            closePrice: srv.closePrice ?? null,
            positionValueUsd: srv.positionValueUsd ?? null,
            currency: srv.currency ?? 'USD'
        });
    });

    return {
        stocks,
        stockGrossUsd: lastStockGrossVal,
        stockGrossKnown: stockGrossValueKnown,
        marginUsd: lastMarginUsd,
        fxRates: Object.assign({}, fxRates),
        currentDisplayCurrency,
        rebalTargetUsd,
        marginTargetPct,
        allocAddMode,
        allocReduceMode
    };
}

// ── Section 4: DOM applier ────────────────────────────────────────────────────

function _applyDisplayResult(result) {
    const { perStock, marginTargetText, marginInputPlaceholder, rebalTargetPlaceholder } = result;

    // Per-stock rebal/alloc/weight cells
    for (const [symbol, d] of Object.entries(perStock)) {
        // Weight
        const weightCell = document.getElementById('current-weight-' + symbol);
        if (weightCell) {
            weightCell.innerHTML = d.weightHTML;
            weightCell.classList.toggle('loaded', d.weightLoaded);
        }

        // Rebal
        const rebalDollarsCell = document.getElementById('rebal-dollars-' + symbol);
        if (rebalDollarsCell) {
            rebalDollarsCell.textContent = d.rebalDollarsText;
            rebalDollarsCell.className = d.rebalDollarsClass;
        }
        const rebalQtyCell = document.getElementById('rebal-qty-' + symbol);
        if (rebalQtyCell) {
            rebalQtyCell.textContent = d.rebalQtyText;
            rebalQtyCell.className = d.rebalQtyClass;
        }

        // Alloc
        const allocDollarsCell = document.getElementById('alloc-dollars-' + symbol);
        if (allocDollarsCell) {
            allocDollarsCell.textContent = d.allocDollarsText;
            allocDollarsCell.className = d.allocDollarsClass;
        }
        const allocQtyCell = document.getElementById('alloc-qty-' + symbol);
        if (allocQtyCell) {
            allocQtyCell.textContent = d.allocQtyText;
            allocQtyCell.className = d.allocQtyClass;
        }
    }

    // Margin target
    const marginTargetEl = document.getElementById('margin-target-usd');
    if (marginTargetEl) marginTargetEl.textContent = marginTargetText;

    // Margin input placeholder (only when user hasn't typed a value)
    const marginInput = document.getElementById('margin-target-input');
    if (marginInput && marginInput.value.trim() === '') {
        marginInput.placeholder = marginInputPlaceholder;
    }

    // Rebal input placeholder
    const rebalInput = document.getElementById('rebal-target-input');
    if (rebalInput) rebalInput.placeholder = rebalTargetPlaceholder;

    // Group table (runs on main thread, reads updated globals)
    if (groupViewActive && typeof updateGroupTable === 'function') {
        updateGroupTable();
    }
}
