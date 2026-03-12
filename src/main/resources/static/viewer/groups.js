// ── groups.js — Group table aggregation and toggle ──────────────────────────
// Depends on: globals.js, utils.js, rebalance.js, rebalance-ga.js

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
                    stockCount: 0, singleSymbol: null, singleMarkPrice: null,
                    members: []
                });
            }
            const g = groups.get(name);
            if (mktVal !== null)     g.mktVal     += mktVal     * multiplier;
            if (prevMktVal !== null) g.prevMktVal += prevMktVal * multiplier;
            g.targetWeight += targetWeight * multiplier;
            g.stockCount++;
            if (!g.members.includes(symbol)) g.members.push(symbol);
            if (g.stockCount === 1) { g.singleSymbol = symbol; g.singleMarkPrice = markPrice; }
            else                    { g.singleSymbol = null;    g.singleMarkPrice = null; }
        }
    });
    return groups;
}

function updateGroupTable() {
    const container = document.getElementById('group-table-container');
    if (!container) return;

    const groups = buildGroupMap();
    if (groups.size === 0) { container.innerHTML = ''; return; }

    computeGAAllocations((perSymbolAlloc) => {
        // Re-read rebalTotal at render time — it's cheap and avoids a stale closure
        _renderGroupTable(container, groups, perSymbolAlloc, getRebalTotal());
    });
}

function _renderGroupTable(container, groups, perSymbolAlloc, rebalTotal) {
    container.innerHTML = '';

    // Aggregate per-symbol alloc into groups (respecting multipliers)
    const groupAllocMap = new Map();
    document.querySelectorAll('#stock-view-table tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        if (!symbol || !row.dataset.groups) return;
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
        ['Group', '', false],
        ['Day %', 'col-num col-market-data', false],
        ['Mkt Val Chg', 'col-num col-market-data', false],
        ['Mkt Val', 'col-num col-market-data col-moreinfo', false],
        ['Weight <span class="th-sub">Cur / Tgt / Dev</span>', 'col-num', true],
        ['Rebal $', 'rebal-column', false],
        ['Alloc $', 'alloc-column', false],
    ].forEach(([text, cls, isHtml]) => {
        const th = document.createElement('th');
        if (cls) th.className = cls;
        if (isHtml) th.innerHTML = text; else th.textContent = text;
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

        const tr = tbody.insertRow();
        tr.dataset.groupMembers = g.members.join(',');
        tr.addEventListener('mouseenter', _showGroupTooltip);
        tr.addEventListener('mousemove',  _moveGroupTooltip);
        tr.addEventListener('mouseleave', _hideGroupTooltip);
        const mk = (html, cls, isHtml) => {
            const td = document.createElement('td');
            if (cls) td.className = cls;
            if (isHtml) td.innerHTML = html; else td.textContent = html;
            tr.appendChild(td);
        };
        const dayPct = g.prevMktVal > 0 ? (mktValChg / g.prevMktVal) * 100 : null;
        const dayPctText = (isZeroChg || dayPct === null) ? '—' : (dayPct >= 0 ? '+' : '') + dayPct.toFixed(2) + '%';

        const isNeutral = dayPct !== null && Math.abs(dayPct) < 0.1;
        const dayPctColorClass = (isZeroChg || dayPct === null) ? 'neutral'
            : isNeutral ? 'neutral'
            : dayPct > 0 ? 'positive' : 'negative';
        const dayPctHtml = (isZeroChg || dayPct === null)
            ? ''
            : `<span class="mark-day-pct ${dayPctColorClass}">${dayPct >= 0 ? '+' : '−'}${Math.abs(dayPct).toFixed(2)}%</span>`;

        mk(name, '');
        mk(dayPctHtml, 'col-num col-market-data', true);
        mk(isZeroChg ? '—' : formatSignedCurrency(mktValChg), 'price-change ' + chgCls);
        mk(formatCurrency(g.mktVal), 'col-num col-market-data value col-moreinfo');

        if (!portfolioValueKnown) {
            mk('N/A', 'col-num value');
            mk('N/A', 'action-neutral rebal-column');
            mk('N/A', 'action-neutral alloc-column');
        } else {
            const rebalDir = Math.abs(rebalDollars) > 0.50 ? (rebalDollars > 0 ? 'action-positive' : 'action-negative') : 'action-neutral';
            const allocDir = Math.abs(allocDollars) > 0.50 ? (allocDollars > 0 ? 'action-positive' : 'action-negative') : 'action-neutral';
            const diffClass = Math.abs(weightDiff) > 1.0 ? (weightDiff > 0 ? 'alert-over' : 'alert-under')
                            : Math.abs(weightDiff) > 0.2 ? 'warning' : 'good';
            const pillSign  = weightDiff >= 0 ? '+' : '';
            const curHtml   = `<span class="weight-cur">${weightPct.toFixed(1)}%</span>`;
            const sepHtml   = `<span class="weight-sep">/</span>`;
            const tgtHtml   = `<span class="weight-tgt">${targetWeightPct.toFixed(1)}%</span>`;
            const pillHtml  = `<span class="weight-diff ${diffClass}">${pillSign}${weightDiff.toFixed(1)}%</span>`;
            mk(curHtml + sepHtml + tgtHtml + pillHtml, 'col-num value', true);
            mk(formatSignedCurrency(rebalDollars), 'action-neutral ' + rebalDir + ' rebal-column');
            mk(formatSignedCurrency(allocDollars), 'action-neutral ' + allocDir + ' alloc-column');
        }
    });

    const warning = document.createElement('p');
    warning.style.cssText = 'font-size:var(--font-size-md); opacity:0.7; margin:0.5rem 0 0.75rem;';
    warning.textContent = '\u26A0\uFE0E Group values should be interpreted cautiously — their meaning depends heavily on how groups are defined.';
    container.appendChild(warning);
    container.appendChild(table);
}

// ── Group row hover tooltip ──────────────────────────────────────────────────

let _groupTooltip = null;

function _ensureGroupTooltip() {
    if (_groupTooltip) return _groupTooltip;
    _groupTooltip = document.createElement('div');
    _groupTooltip.id = 'group-hover-tooltip';
    document.body.appendChild(_groupTooltip);
    return _groupTooltip;
}

function _showGroupTooltip(e) {
    const members = (this.dataset.groupMembers || '').split(',').filter(Boolean);
    if (!members.length) return;

    const rows = members.map(symbol => {
        const allocCell = document.getElementById('alloc-dollars-' + symbol);
        const allocText = allocCell ? allocCell.textContent.trim() : '';
        const allocVal = allocText ? parseFloat(allocText.replace(/[^0-9.\-]/g, '')) : NaN;
        const isReady  = allocText && allocText !== '—' && !isNaN(allocVal);
        const allocClass = !isReady ? '' : allocVal > 0.5 ? 'action-positive' : allocVal < -0.5 ? 'action-negative' : 'action-neutral';
        const allocDisplay = isReady
            ? `<span class="price-change ${allocClass}">${allocText}</span>`
            : `<span class="group-tooltip-na">N/A</span>`;
        return `<tr>
            <td class="group-tooltip-symbol">${symbol}</td>
            <td class="group-tooltip-alloc">${allocDisplay}</td>
        </tr>`;
    }).join('');

    const tip = _ensureGroupTooltip();
    tip.innerHTML = `<table>${rows}</table>`;
    tip.style.display = 'block';
    _moveGroupTooltip(e);
}

function _moveGroupTooltip(e) {
    if (!_groupTooltip) return;
    const offset = 14;
    const tw = _groupTooltip.offsetWidth;
    const th = _groupTooltip.offsetHeight;
    let x = e.clientX + offset;
    let y = e.clientY + offset;
    if (x + tw > window.innerWidth  - 8) x = e.clientX - tw - offset;
    if (y + th > window.innerHeight - 8) y = e.clientY - th - offset;
    _groupTooltip.style.left = x + 'px';
    _groupTooltip.style.top  = y + 'px';
}

function _hideGroupTooltip() {
    if (_groupTooltip) _groupTooltip.style.display = 'none';
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
