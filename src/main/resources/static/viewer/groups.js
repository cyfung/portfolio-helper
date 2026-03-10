// ── groups.js — Group table aggregation and toggle ──────────────────────────
// Depends on: globals.js, utils.js, rebalance.js

function parseGroupsAttr(attrValue, symbol) {
    const raw = (attrValue || '').trim();
    if (!raw) return [{ multiplier: 1, name: symbol }];
    return raw.split(';').map(entry => {
        const trimmed = entry.trim();
        const spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return null;
        const mult = parseFloat(trimmed.substring(0, spaceIdx));
        const name = trimmed.substring(spaceIdx + 1).trim();
        if (isNaN(mult) || !name) return null;
        return { multiplier: mult, name };
    }).filter(Boolean);
}

function buildGroupMap() {
    const groups = new Map();
    document.querySelectorAll('#stock-view-table tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        if (!symbol) return;

        if (!row.dataset.groups) return;
        const groupEntries = parseGroupsAttr(row.dataset.groups, symbol);
        const targetWeight = parseFloat(row.dataset.weight) || 0;
        const markPrice = rawMarkPrices[symbol] ?? parsePrice(document.getElementById('mark-' + symbol)?.textContent);
        const closePrice = rawClosePrices[symbol] ?? parsePrice(document.getElementById('close-' + symbol)?.textContent);
        const qty = parseFloat(document.getElementById('amount-' + symbol)?.textContent) || 0;

        const mktVal = markPrice !== null ? markPrice * qty : null;
        const prevMktVal = closePrice !== null ? closePrice * qty : null;

        for (const { multiplier, name } of groupEntries) {
            if (!groups.has(name)) {
                groups.set(name, {
                    mktVal: 0, prevMktVal: 0, targetWeight: 0,
                    stockCount: 0, singleSymbol: null, singleMarkPrice: null
                });
            }
            const g = groups.get(name);
            if (mktVal !== null)     g.mktVal     += mktVal     * multiplier;
            if (prevMktVal !== null) g.prevMktVal += prevMktVal * multiplier;
            g.targetWeight += targetWeight * multiplier;
            g.stockCount++;
            if (g.stockCount === 1) { g.singleSymbol = symbol; g.singleMarkPrice = markPrice; }
            else                    { g.singleSymbol = null;    g.singleMarkPrice = null; }
        }
    });
    return groups;
}

function updateGroupTable() {
    const container = document.getElementById('group-table-container');
    if (!container) return;
    container.innerHTML = '';

    const groups = buildGroupMap();
    if (groups.size === 0) return;

    const rebalTotal = getRebalTotal();
    const delta = rebalTotal - lastPortfolioVal;

    // Compute per-symbol alloc using the same strategy as the stock table
    const stocksForAlloc = [];
    let totalStockValue = 0;
    document.querySelectorAll('#stock-view-table tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        if (!symbol) return;
        const markPrice = rawMarkPrices[symbol] ?? parsePrice(document.getElementById('mark-' + symbol)?.textContent);
        const targetWeight = parseFloat(row.dataset.weight) || 0;
        const valueCell = document.getElementById('value-' + symbol);
        const currentValue = valueCell ? (parsePrice(valueCell.textContent) ?? 0) : 0;
        stocksForAlloc.push({ symbol, markPrice, targetWeight, currentValue });
        totalStockValue += currentValue;
    });
    const allocMode = delta >= 0 ? allocAddMode : allocReduceMode;
    const perSymbolAlloc = computeAllocations(delta, stocksForAlloc, totalStockValue, allocMode);

    // Aggregate per-symbol alloc into groups (respecting multipliers)
    const groupAllocMap = new Map();
    document.querySelectorAll('#stock-view-table tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        if (!symbol) return;
        if (!row.dataset.groups) return;
        const symAlloc = perSymbolAlloc[symbol];
        if (symAlloc == null) return;
        for (const { multiplier, name } of parseGroupsAttr(row.dataset.groups, symbol)) {
            groupAllocMap.set(name, (groupAllocMap.get(name) ?? 0) + symAlloc * multiplier);
        }
    });

    const table = document.createElement('table');
    table.className = 'portfolio-table';
    table.id = 'group-view-table';

    const hRow = table.createTHead().insertRow();
    [
        ['Group', ''],
        ['Day %', 'col-num col-market-data'],
        ['Mkt Val Chg', 'col-num col-market-data'],
        ['Mkt Val', 'col-num col-market-data'],
        ['Weight', 'rebal-column'],
        ['Rebal $', 'rebal-column'],
        ['Alloc $', 'alloc-column'],
    ].forEach(([text, cls]) => {
        const th = document.createElement('th');
        if (cls) th.className = cls;
        th.textContent = text;
        hRow.appendChild(th);
    });

    const tbody = table.createTBody();
    groups.forEach((g, name) => {
        const mktValChg = g.mktVal - g.prevMktVal;
        const weightPct = lastPortfolioVal > 0 ? (g.mktVal / lastPortfolioVal) * 100 : 0;
        const targetWeightPct = g.targetWeight;
        const weightDiff = weightPct - targetWeightPct;
        const rebalDollars = (targetWeightPct / 100) * rebalTotal - g.mktVal;
        const allocDollars = groupAllocMap.get(name) ?? 0;

        const isZeroChg = Math.abs(mktValChg) < 0.01;
        const chgCls = isZeroChg ? 'neutral' : mktValChg > 0 ? 'positive' : 'negative';
        const rebalDir = Math.abs(rebalDollars) > 0.50 ? (rebalDollars > 0 ? 'positive' : 'negative') : 'neutral';
        const allocDir = Math.abs(allocDollars) > 0.50 ? (allocDollars > 0 ? 'positive' : 'negative') : 'neutral';
        const diffCls = Math.abs(weightDiff) > 2.0 ? 'alert' : Math.abs(weightDiff) > 1.0 ? 'warning' : 'good';
        const diffSign = weightDiff >= 0 ? '-' : '+';

        const tr = tbody.insertRow();
        const mk = (html, cls, isHtml) => {
            const td = document.createElement('td');
            if (cls) td.className = cls;
            if (isHtml) td.innerHTML = html; else td.textContent = html;
            tr.appendChild(td);
        };
        const dayPct = g.prevMktVal > 0 ? (mktValChg / g.prevMktVal) * 100 : null;
        const dayPctText = (isZeroChg || dayPct === null) ? '—' : (dayPct >= 0 ? '+' : '') + dayPct.toFixed(2) + '%';

        mk(name, '');
        mk(dayPctText, 'col-num col-market-data price-change ' + chgCls);
        mk(isZeroChg ? '—' : formatSignedCurrency(mktValChg), 'price-change ' + chgCls);
        mk(formatCurrency(g.mktVal), 'col-num col-market-data value');
        mk(
            weightPct.toFixed(1) + '% <span class="weight-diff ' + diffCls + '">(' + diffSign + Math.abs(weightDiff).toFixed(1) + '%)</span>',
            'col-num rebal-column value', true
        );
        mk(formatSignedCurrency(rebalDollars), 'price-change ' + rebalDir + ' rebal-column');
        mk(formatSignedCurrency(allocDollars), 'price-change ' + allocDir + ' alloc-column');
    });

    container.appendChild(table);
}

function applyGroupViewState() {
    const stockView = document.getElementById('stock-view-table');
    const groupContainer = document.getElementById('group-table-container');
    if (!stockView || !groupContainer) return;
    if (groupViewActive) {
        stockView.style.display = 'none';
        groupContainer.style.display = '';
    } else {
        stockView.style.display = '';
        groupContainer.style.display = 'none';
    }
}

function initGroupViewToggle() {
    const btn = document.getElementById('groups-toggle');
    if (!btn) return;
    const hasGroups = Array.from(document.querySelectorAll('#stock-view-table tbody tr'))
        .some(row => row.dataset.groups);
    if (!hasGroups) { btn.style.display = 'none'; return; }
    groupViewActive = localStorage.getItem('ib-viewer-group-view') === 'true';
    if (groupViewActive) {
        btn.classList.add('active');
        applyGroupViewState();
        updateGroupTable();
    }
    btn.addEventListener('click', () => {
        groupViewActive = !groupViewActive;
        btn.classList.toggle('active', groupViewActive);
        localStorage.setItem('ib-viewer-group-view', groupViewActive);
        applyGroupViewState();
        if (groupViewActive) updateGroupTable();
    });
}
