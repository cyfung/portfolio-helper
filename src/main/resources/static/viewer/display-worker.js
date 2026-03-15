// ── display-worker.js — Background worker for portfolio display computation ───
// Depends on: globals.js, utils.js (parsePrice available on main thread)
// Load order: after rebalance.js, before portfolio.js

// ── Section 1: Worker source (pure JS, no DOM) ────────────────────────────────

const _DISPLAY_WORKER_SRC = `
'use strict';

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

function formatDisplayCurrency(usdVal, fxRates, cdc) {
    const converted = toDisplayCurrency(usdVal, fxRates, cdc);
    const sign = converted < 0 ? '-' : '';
    return sign + Math.abs(converted).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatSignedDisplayCurrency(usdVal, fxRates, cdc) {
    const converted = toDisplayCurrency(usdVal, fxRates, cdc);
    return (converted >= 0 ? '+' : '') + formatDisplayCurrency(usdVal, fxRates, cdc);
}

function toStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc) {
    const toUsd = getStockFxRate(stockCcy, fxRates);
    if (toUsd === null) return null;
    return toDisplayCurrency(nativeVal * toUsd, fxRates, cdc);
}

function formatStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc) {
    const converted = toStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc);
    if (converted === null) return '—';
    const sign = converted < 0 ? '-' : '';
    return sign + Math.abs(converted).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatSignedStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc) {
    const converted = toStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc);
    if (converted === null) return '—';
    return (converted >= 0 ? '+' : '') + formatStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc);
}

function buildDayChangeHTML(changeDollars, changePercent, changeClass, fxRates, cdc) {
    const sign = changeDollars >= 0 ? '+' : '-';
    return '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars, fxRates, cdc) + '</span> ' +
        '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
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
        stocks, previousValues, rawMarkPrices, rawClosePrices, fxRates, stockCurrencies,
        symbolMarketClosed, symbolTradingPeriodEndMs, componentDayPercents, navValues,
        currentDisplayCurrency: cdc, showStockDisplayCurrency,
        lastMarginUsd, lastCashTotalUsd, cashTotalKnown,
        rebalTargetUsd, marginTargetPct, allocAddMode, allocReduceMode,
        nowMs
    } = snap;

    let markTotal = 0;
    let prevTotal = 0;
    let stockGrossValueKnown = true;
    const perStockData = {};

    // Pass 1: per-stock prices, values, day change, est val
    for (const stock of stocks) {
        const { symbol, qty, targetWeight, letfComponents } = stock;
        const stockCcy = stockCurrencies[symbol] ?? 'USD';
        const fxRate = getStockFxRate(stockCcy, fxRates);
        const markPrice = rawMarkPrices[symbol] ?? null;
        const closePrice = rawClosePrices[symbol] ?? null;

        const markLoaded = markPrice !== null;
        const markText = markLoaded ? markPrice.toFixed(2) : '—';
        const markAfterHours = !!symbolMarketClosed[symbol];
        const closeLoaded = closePrice !== null;
        const closeText = closeLoaded ? closePrice.toFixed(2) : '—';

        let dayChangeDollars = null, dayChangeText = '', dayChangeClass = '';
        let dayPctText = '', dayPctClass = '';
        let positionChangeText = '—', positionChangeClass = '';

        if (markPrice !== null && closePrice !== null) {
            dayChangeDollars = markPrice - closePrice;
            const isZeroChange = Math.abs(dayChangeDollars) < 0.001;
            dayChangeText = (dayChangeDollars >= 0 ? '+' : '-') + Math.abs(dayChangeDollars).toFixed(2);
            const dir = isZeroChange ? 'neutral' : dayChangeDollars > 0 ? 'positive' : 'negative';
            dayChangeClass = 'loaded ' + dir + (markAfterHours ? ' after-hours' : '');

            const changePercent = (dayChangeDollars / closePrice) * 100;
            const sign = changePercent >= 0 ? '+' : '\\u2212';
            dayPctText = sign + Math.abs(changePercent).toFixed(2) + '%';
            const isNeutralPct = Math.abs(changePercent) < 0.1;
            const colorClass = isNeutralPct ? 'neutral' : changePercent > 0 ? 'positive' : 'negative';
            dayPctClass = 'mark-day-pct ' + colorClass + (markAfterHours ? ' after-hours' : '');

            if (fxRate !== null) {
                const positionChange = dayChangeDollars * qty;
                const posDir = isZeroChange ? 'neutral' : positionChange > 0 ? 'positive' : 'negative';
                if (showStockDisplayCurrency) {
                    positionChangeText = formatSignedStockDisplayCurrency(positionChange, stockCcy, fxRates, cdc);
                    if (positionChangeText === null) positionChangeText = '—';
                } else {
                    positionChangeText = formatSignedCurrency(positionChange);
                }
                positionChangeClass = 'loaded ' + posDir + (markAfterHours ? ' after-hours' : '');
            }
        }

        // Value (in native currency units, matching original updatePriceInUI behavior)
        const price = markPrice !== null ? markPrice : closePrice;
        let valueText = '—', valueLoaded = false, valueUsd = null;
        if (price !== null && fxRate !== null) {
            const nativeVal = price * qty;
            valueUsd = nativeVal * fxRate;
            valueText = showStockDisplayCurrency
                ? formatStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc)
                : formatCurrency(nativeVal);
            valueLoaded = true;
        } else if (price !== null && fxRate === null) {
            stockGrossValueKnown = false;
        }

        // Portfolio totals (USD)
        if (fxRate === null) {
            stockGrossValueKnown = false;
        } else {
            if (markPrice === null && closePrice === null) stockGrossValueKnown = false;
            if (markPrice !== null) markTotal += markPrice * qty * fxRate;
            if (closePrice !== null) prevTotal += closePrice * qty * fxRate;
        }

        // Flash delta (compare native values to match original behavior)
        const prevVal = previousValues[symbol] ?? null;
        const valueDelta = (prevVal !== null && price !== null) ? (price * qty) - prevVal : null;

        // LETF est val
        let estValText = null, estValLoaded = false, estValRaw = null;
        if (letfComponents && letfComponents.length > 0) {
            const marketClosed = symbolMarketClosed[symbol] !== false;
            const closeTimeMs = symbolTradingPeriodEndMs[symbol] ?? null;
            const stale = marketClosed && (closeTimeMs === null || nowMs - closeTimeMs > 12 * 3600 * 1000);
            if (stale) {
                estValText = '—';
            } else {
                const navPrice = navValues[symbol] ?? null;
                const basePrice = navPrice !== null ? navPrice : closePrice;
                if (basePrice !== null) {
                    let sumComponent = 0, allAvailable = true;
                    for (const comp of letfComponents) {
                        const dayPct = componentDayPercents[comp.sym];
                        if (dayPct === undefined) { allAvailable = false; break; }
                        sumComponent += comp.mult * dayPct / 100;
                    }
                    if (allAvailable) {
                        estValRaw = (1 + sumComponent) * basePrice;
                        estValText = estValRaw.toFixed(2);
                        estValLoaded = true;
                    }
                }
            }
        }

        perStockData[symbol] = {
            markText, markLoaded, markAfterHours,
            closeText, closeLoaded,
            dayChangeDollars, dayChangeText, dayChangeClass,
            dayPctText, dayPctClass,
            positionChangeText, positionChangeClass,
            valueText, valueLoaded, valueUsd,
            valueDelta,
            estValText, estValLoaded, estValRaw,
            qty, targetWeight, fxRate, stockCcy, markPrice, closePrice
        };
    }

    // Pass 2: weights, rebal, alloc
    const rebalTotal = getRebalTotal(markTotal, lastMarginUsd, rebalTargetUsd, marginTargetPct);
    const allocStocks = [];
    let totalStockValueForAlloc = 0;

    for (const stock of stocks) {
        const { symbol, targetWeight } = stock;
        const d = perStockData[symbol];

        // Weight
        let weightHTML = '', weightLoaded = false;
        if (!stockGrossValueKnown) {
            weightHTML = 'N/A';
        } else if (markTotal > 0 && d.valueUsd !== null) {
            const currentWeight = (d.valueUsd / markTotal) * 100;
            if (targetWeight !== null && !isNaN(targetWeight)) {
                const diff = currentWeight - targetWeight;
                const diffClass = Math.abs(diff) > 1.0 ? (diff > 0 ? 'alert-over' : 'alert-under')
                                : Math.abs(diff) > 0.2 ? 'warning' : 'good';
                const diffSign = diff >= 0 ? '+' : '';
                weightHTML = '<span class="weight-cur">' + currentWeight.toFixed(1) + '%</span>' +
                             '<span class="weight-sep">/</span>' +
                             '<span class="weight-tgt">' + targetWeight.toFixed(1) + '%</span>' +
                             '<span class="weight-diff ' + diffClass + '">' + diffSign + diff.toFixed(1) + '%</span>';
            } else {
                weightHTML = currentWeight.toFixed(1) + '%';
            }
            weightLoaded = true;
        }
        d.weightHTML = weightHTML;
        d.weightLoaded = weightLoaded;

        // Rebal
        let rebalDollarsText = '', rebalDollarsClass = 'action-neutral rebal-column';
        let rebalQtyText = '', rebalQtyClass = 'action-neutral rebal-column col-moreinfo';
        if (!stockGrossValueKnown) {
            rebalDollarsText = 'N/A'; rebalQtyText = 'N/A';
        } else if (rebalTotal > 0 && targetWeight !== null && !isNaN(targetWeight)) {
            const effectivePrice = d.markPrice !== null ? d.markPrice : d.closePrice;
            if (effectivePrice !== null) {
                const nativeValue = effectivePrice * d.qty;
                const targetValue = (targetWeight / 100) * rebalTotal;
                const rebalDollars = targetValue - nativeValue;
                const direction = Math.abs(rebalDollars) > 0.50
                    ? (rebalDollars > 0 ? 'action-positive' : 'action-negative') : 'action-neutral';
                rebalDollarsText = formatSignedCurrency(rebalDollars);
                rebalDollarsClass = 'action-neutral loaded rebal-column ' + direction;
                if (d.markPrice !== null && d.markPrice > 0) {
                    const rebalShares = rebalDollars / d.markPrice;
                    rebalQtyText = (rebalShares >= 0 ? '+' : '-') + Math.abs(rebalShares).toFixed(2);
                    rebalQtyClass = 'action-neutral loaded rebal-column col-moreinfo ' + direction;
                }
            }
        }
        d.rebalDollarsText = rebalDollarsText; d.rebalDollarsClass = rebalDollarsClass;
        d.rebalQtyText = rebalQtyText; d.rebalQtyClass = rebalQtyClass;

        // Alloc stock list
        const effectivePrice = d.markPrice !== null ? d.markPrice : d.closePrice;
        const nativeValue = effectivePrice !== null ? effectivePrice * d.qty : 0;
        allocStocks.push({ symbol, markPrice: d.markPrice, targetWeight, currentValue: nativeValue });
        totalStockValueForAlloc += nativeValue;
    }

    // Alloc columns
    const allocDelta = rebalTotal - markTotal;
    const allocMode = allocDelta >= 0 ? allocAddMode : allocReduceMode;
    const allocations = stockGrossValueKnown
        ? computeAllocations(allocDelta, allocStocks, totalStockValueForAlloc, allocMode)
        : {};

    for (const stock of stocks) {
        const { symbol } = stock;
        const d = perStockData[symbol];
        const s = allocStocks.find(x => x.symbol === symbol);
        let allocDollarsText = '', allocDollarsClass = 'action-neutral alloc-column';
        let allocQtyText = '', allocQtyClass = 'action-neutral alloc-column col-moreinfo';

        if (!stockGrossValueKnown) {
            allocDollarsText = 'N/A'; allocQtyText = 'N/A';
        } else {
            const amt = allocations[symbol];
            if (amt != null) {
                const dir = amt > 0.50 ? 'action-positive' : amt < -0.50 ? 'action-negative' : 'action-neutral';
                allocDollarsText = formatSignedCurrency(amt);
                allocDollarsClass = 'action-neutral loaded alloc-column ' + dir;
                if (s && s.markPrice > 0) {
                    const qty = amt / s.markPrice;
                    allocQtyText = (qty >= 0 ? '+' : '-') + Math.abs(qty).toFixed(2);
                    allocQtyClass = 'action-neutral loaded alloc-column col-moreinfo ' + dir;
                }
            }
        }
        d.allocDollarsText = allocDollarsText; d.allocDollarsClass = allocDollarsClass;
        d.allocQtyText = allocQtyText; d.allocQtyClass = allocQtyClass;
    }

    // Totals
    const dayChangeDollars = markTotal - prevTotal;
    const changePercent = prevTotal > 0 ? (dayChangeDollars / prevTotal) * 100 : 0;
    const changeClass = dayChangeDollars > 0 ? 'positive' : dayChangeDollars < 0 ? 'negative' : 'neutral';

    const stockGrossTotal = stockGrossValueKnown
        ? formatDisplayCurrency(markTotal, fxRates, cdc) : 'N/A';
    const portfolioDayChangeHTML = !stockGrossValueKnown ? 'N/A'
        : buildDayChangeHTML(dayChangeDollars, changePercent, changeClass, fxRates, cdc);

    const prevGrandTotal = prevTotal + lastCashTotalUsd;
    const totalChangePercent = prevGrandTotal !== 0 ? (dayChangeDollars / Math.abs(prevGrandTotal)) * 100 : 0;
    const totalDayChangeHTML = !stockGrossValueKnown ? 'N/A'
        : buildDayChangeHTML(dayChangeDollars, totalChangePercent, changeClass, fxRates, cdc);

    const grandTotal = (stockGrossValueKnown && cashTotalKnown)
        ? formatDisplayCurrency(markTotal + lastCashTotalUsd, fxRates, cdc) : 'N/A';

    // Margin
    const showMarginRow = lastMarginUsd < 0;
    let marginDisplay = '', marginPctText = '';
    if (lastMarginUsd < 0) {
        marginDisplay = formatDisplayCurrency(-lastMarginUsd, fxRates, cdc);
        const denominator = markTotal + lastMarginUsd;
        const pct = denominator !== 0 ? Math.abs(lastMarginUsd / denominator) * 100 : 0;
        marginPctText = ' (' + pct.toFixed(1) + '%)';
    }

    // Margin target display
    const marginTargetUsd = lastMarginUsd - (rebalTotal - markTotal);
    const marginTargetText = marginTargetUsd < 0 ? formatDisplayCurrency(-marginTargetUsd, fxRates, cdc) : '';

    // Margin input placeholder
    const marginInputPlaceholder = deriveMarginPct(rebalTotal, markTotal, lastMarginUsd).toFixed(1);

    // Rebal input placeholder
    const baseUsd = marginTargetPct !== null ? rebalTotal : markTotal + Math.max(lastMarginUsd, 0);
    const rebalTargetPlaceholder = Math.abs(toDisplayCurrency(baseUsd, fxRates, cdc)).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
    });

    // Build clean perStock result
    const perStock = {};
    for (const stock of stocks) {
        const { symbol } = stock;
        const d = perStockData[symbol];
        perStock[symbol] = {
            markText: d.markText, markLoaded: d.markLoaded, markAfterHours: d.markAfterHours,
            closeText: d.closeText, closeLoaded: d.closeLoaded,
            dayChangeDollars: d.dayChangeDollars,
            dayChangeText: d.dayChangeText, dayChangeClass: d.dayChangeClass,
            dayPctText: d.dayPctText, dayPctClass: d.dayPctClass,
            positionChangeText: d.positionChangeText, positionChangeClass: d.positionChangeClass,
            valueText: d.valueText, valueLoaded: d.valueLoaded, valueDelta: d.valueDelta,
            weightHTML: d.weightHTML, weightLoaded: d.weightLoaded,
            rebalDollarsText: d.rebalDollarsText, rebalDollarsClass: d.rebalDollarsClass,
            rebalQtyText: d.rebalQtyText, rebalQtyClass: d.rebalQtyClass,
            allocDollarsText: d.allocDollarsText, allocDollarsClass: d.allocDollarsClass,
            allocQtyText: d.allocQtyText, allocQtyClass: d.allocQtyClass,
            estValText: d.estValText, estValLoaded: d.estValLoaded, estValRaw: d.estValRaw
        };
    }

    return {
        perStock,
        totals: {
            stockGrossTotal, stockGrossValueKnown,
            stockGrossValRaw: markTotal,
            prevStockGrossValRaw: prevTotal,
            dayChangeDollarsRaw: dayChangeDollars,
            portfolioDayChangeHTML, totalDayChangeHTML,
            grandTotal, grandTotalKnown: stockGrossValueKnown && cashTotalKnown,
            marginDisplay, marginPctText, showMarginRow,
            marginTargetText, marginInputPlaceholder, rebalTargetPlaceholder
        }
    };
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
        const letfAttr = row.dataset.letf || null;
        let letfComponents = null;
        if (letfAttr) {
            const tokens = letfAttr.split(',');
            letfComponents = [];
            for (let i = 0; i + 1 < tokens.length; i += 2) {
                letfComponents.push({ mult: parseFloat(tokens[i]), sym: tokens[i + 1] });
            }
        }
        stocks.push({ symbol, qty, targetWeight, letfComponents });
    });

    const previousValues = {};
    for (const s of stocks) {
        const cell = document.getElementById('value-' + s.symbol);
        if (cell) previousValues[s.symbol] = parsePrice(cell.textContent);
    }

    // Merge server-rendered DOM prices as fallback for symbols not yet received via SSE.
    // This matches the original updateTotalValue behavior of falling back to cell text.
    const rawMarkPricesSnap = Object.assign({}, rawMarkPrices);
    const rawClosePricesSnap = Object.assign({}, rawClosePrices);
    for (const s of stocks) {
        if (rawMarkPricesSnap[s.symbol] == null) {
            const span = document.querySelector('#mark-' + s.symbol + ' .mark-price-value');
            const p = span ? parsePrice(span.textContent) : null;
            if (p !== null) rawMarkPricesSnap[s.symbol] = p;
        }
        if (rawClosePricesSnap[s.symbol] == null) {
            const cell = document.getElementById('close-' + s.symbol);
            const p = cell ? parsePrice(cell.textContent) : null;
            if (p !== null) rawClosePricesSnap[s.symbol] = p;
        }
    }

    return {
        stocks,
        previousValues,
        rawMarkPrices: rawMarkPricesSnap,
        rawClosePrices: rawClosePricesSnap,
        fxRates: Object.assign({}, fxRates),
        stockCurrencies: Object.assign({}, stockCurrencies),
        symbolMarketClosed: Object.assign({}, symbolMarketClosed),
        symbolTradingPeriodEndMs: Object.assign({}, symbolTradingPeriodEndMs),
        componentDayPercents: Object.assign({}, componentDayPercents),
        navValues: Object.assign({}, navValues),
        currentDisplayCurrency,
        showStockDisplayCurrency,
        lastMarginUsd,
        lastCashTotalUsd,
        cashTotalKnown,
        rebalTargetUsd,
        marginTargetPct,
        allocAddMode,
        allocReduceMode,
        nowMs: Date.now()
    };
}

// ── Section 4: DOM applier ────────────────────────────────────────────────────

function _applyDisplayResult(result) {
    const { perStock, totals } = result;

    // Write back globals so other code (groups, rebal inputs) sees fresh values
    lastStockGrossVal = totals.stockGrossValRaw;
    lastPrevPortfolioVal = totals.prevStockGrossValRaw;
    lastPortfolioDayChangeUsd = totals.dayChangeDollarsRaw;
    stockGrossValueKnown = totals.stockGrossValueKnown;

    // Per-stock cells
    for (const [symbol, d] of Object.entries(perStock)) {
        // Mark price
        const markCell = document.getElementById('mark-' + symbol);
        if (markCell) {
            const markValSpan = markCell.querySelector('.mark-price-value');
            if (markValSpan) {
                markValSpan.textContent = d.markText;
                markValSpan.classList.toggle('loaded', d.markLoaded);
            }
            markCell.classList.toggle('after-hours', d.markAfterHours);
        }

        // Day percent (inside mark cell)
        if (d.dayPctText) {
            const pctSpan = document.getElementById('day-percent-' + symbol);
            if (pctSpan) { pctSpan.textContent = d.dayPctText; pctSpan.className = d.dayPctClass; }
        }

        // Close price
        const closeCell = document.getElementById('close-' + symbol);
        if (closeCell) {
            closeCell.textContent = d.closeText;
            closeCell.classList.toggle('loaded', d.closeLoaded);
        }

        // Day change
        const changeCell = document.getElementById('day-change-' + symbol);
        if (changeCell && d.dayChangeText) {
            changeCell.textContent = d.dayChangeText;
            changeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
            changeCell.classList.add(...d.dayChangeClass.split(' ').filter(Boolean));
        }

        // Position change
        const posChangeCell = document.getElementById('position-change-' + symbol);
        if (posChangeCell) {
            posChangeCell.textContent = d.positionChangeText;
            if (d.positionChangeClass) {
                posChangeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
                posChangeCell.classList.add(...d.positionChangeClass.split(' ').filter(Boolean));
            }
        }

        // Mkt Val (value)
        const valueCell = document.getElementById('value-' + symbol);
        if (valueCell) {
            valueCell.textContent = d.valueText;
            valueCell.classList.toggle('loaded', d.valueLoaded);
        }

        // Row flash
        if (d.valueDelta !== null && Math.abs(d.valueDelta) > 0.01) {
            const row = valueCell ? valueCell.closest('tr') : null;
            if (row && !row.classList.contains('recently-updated')) {
                row.classList.add('recently-updated');
                setTimeout(() => row.classList.remove('recently-updated'), 10000);
            }
        }

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

        // Est val (LETF)
        const estValCell = document.getElementById('est-val-' + symbol);
        if (estValCell && d.estValText !== null) {
            estValCell.textContent = d.estValText;
            estValCell.classList.toggle('loaded', d.estValLoaded);
            if (d.estValRaw !== null) estValCell.dataset.estVal = d.estValRaw;
        }
    }

    // Totals
    const totalCell = document.getElementById('stock-gross-total');
    if (totalCell) totalCell.textContent = totals.stockGrossTotal;

    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) portfolioChangeCell.innerHTML = totals.portfolioDayChangeHTML;

    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) totalChangeCell.innerHTML = totals.totalDayChangeHTML;

    const grandEl = document.getElementById('portfolio-total');
    if (grandEl) grandEl.textContent = totals.grandTotal;

    // Margin
    const marginRow = document.querySelector('[data-margin-row]');
    if (marginRow) marginRow.style.display = totals.showMarginRow ? '' : 'none';
    const marginEl = document.getElementById('margin-total-usd');
    if (marginEl) marginEl.textContent = totals.marginDisplay;
    const marginPctEl = document.getElementById('margin-percent');
    if (marginPctEl) {
        marginPctEl.textContent = totals.marginPctText;
        marginPctEl.style.display = totals.marginPctText ? '' : 'none';
    }

    // Margin target
    const marginTargetEl = document.getElementById('margin-target-usd');
    if (marginTargetEl) marginTargetEl.textContent = totals.marginTargetText;

    // Margin input placeholder (only when user hasn't typed a value)
    const marginInput = document.getElementById('margin-target-input');
    if (marginInput && marginInput.value.trim() === '') {
        marginInput.placeholder = totals.marginInputPlaceholder;
    }

    // Rebal input placeholder (not .value)
    const rebalInput = document.getElementById('rebal-target-input');
    if (rebalInput) rebalInput.placeholder = totals.rebalTargetPlaceholder;

    // Group table (runs on main thread, reads updated globals)
    if (groupViewActive && typeof updateGroupTable === 'function') {
        updateGroupTable();
    }
}
