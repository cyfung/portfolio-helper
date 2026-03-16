// ── edit-mode.js — Edit table construction, save, delete, drag-and-drop, paste
// Depends on: utils.js, ui-helpers.js, rebalance.js

// ── HTML templates for dynamically added rows ─────────────────────────────────

const STOCK_ROW_HTML =
    '<td class="drag-handle-cell"><span class="drag-handle" draggable="true">⠿</span></td>' +
    '<td><input type="text" class="edit-input new-symbol-input" data-column="symbol" placeholder="TICKER" style="text-align:left;width:80px;display:block" /></td>' +
    '<td class="amount"><input type="number" class="edit-input" data-column="qty" value="0" min="0" step="any" style="display:block" /></td>' +
    '<td><input type="number" class="edit-input" data-column="weight" value="0" min="0" max="100" step="0.1" /></td>' +
    '<td><input type="text" class="edit-input" data-column="letf" placeholder="e.g. 2 IVV" style="text-align:left;width:180px" /></td>' +
    '<td><input type="text" class="edit-input" data-column="groups" placeholder="e.g. 1 Equity" style="text-align:left;width:180px" /></td>' +
    '<td><button type="button" class="delete-row-btn">\u00d7</button></td>';

const CASH_ROW_HTML =
    '<td><input type="text" class="edit-input cash-edit-key" placeholder="Cash.USD.M" /></td>' +
    '<td><input type="text" class="edit-input cash-edit-value" placeholder="0" /></td>' +
    '<td><button type="button" class="delete-cash-btn">\u00d7</button></td>' +
    '<td class="cash-type-badge-cell"><span class="cash-type-badge"></span></td>';

function addStockRow() {
    const tbody = document.querySelector('#stock-edit-table tbody');
    if (!tbody) return null;
    const tr = document.createElement('tr');
    tr.setAttribute('data-new-stock', 'true');
    tr.innerHTML = STOCK_ROW_HTML;
    tbody.appendChild(tr);
    return tr;
}

function addCashRow() {
    const tbody = document.querySelector('.cash-edit-table tbody');
    if (!tbody) return null;
    const tr = document.createElement('tr');
    tr.setAttribute('data-cash-edit-row', 'true');
    tr.setAttribute('data-new-cash', 'true');
    tr.innerHTML = CASH_ROW_HTML;
    tbody.appendChild(tr);
    return tr;
}

// Returns [symInput, qtyInput, weightInput, letfInput, groupsInput] for any stock row
function getStockRowInputs(tr) {
    return [
        tr.querySelector('.edit-symbol') || tr.querySelector('.new-symbol-input') || tr.querySelector('input[data-column="symbol"]'),
        tr.querySelector('.edit-qty')    || tr.querySelector('input[data-column="qty"]'),
        tr.querySelector('.edit-weight') || tr.querySelector('input[data-column="weight"]'),
        tr.querySelector('.edit-letf')   || tr.querySelector('input[data-column="letf"]'),
        tr.querySelector('.edit-groups') || tr.querySelector('input[data-column="groups"]'),
    ].filter(Boolean);
}

// Returns 0=sym, 1=qty, 2=weight, 3=letf, 4=groups — or -1 if not a stock input
function getStockColIndex(el) {
    if (el.classList.contains('edit-symbol') || el.classList.contains('new-symbol-input')) return 0;
    const col = el.getAttribute('data-column');
    if (el.classList.contains('edit-qty')    || col === 'qty')    return 1;
    if (el.classList.contains('edit-weight') || col === 'weight') return 2;
    if (el.classList.contains('edit-letf')   || col === 'letf')   return 3;
    if (el.classList.contains('edit-groups') || col === 'groups') return 4;
    return -1;
}

function updateCashRowTypeBadge(tr) {
    const key = (tr.querySelector('.cash-edit-key')?.value || '').trim();
    const parts = key.split('.');
    const suffix = parts[parts.length - 1]?.toUpperCase();
    const currency = parts[parts.length - 2]?.toUpperCase();
    let type = 'normal', badgeText = '';
    if (suffix === 'M') { type = 'margin'; badgeText = 'M'; }
    else if (currency === 'P' || suffix === 'P') { type = 'ref'; badgeText = '\u2197'; }
    tr.dataset.entryType = type;
    const badge = tr.querySelector('.cash-type-badge');
    if (badge) badge.textContent = badgeText;
}

// Resets all cash edit row inputs to their original (data-attribute) values
function resetCashEditInputs() {
    document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
        const keyInput = tr.querySelector('.cash-edit-key');
        const valInput = tr.querySelector('.cash-edit-value');
        if (keyInput) keyInput.value = keyInput.getAttribute('data-original-key') || '';
        if (valInput) valInput.value = valInput.getAttribute('data-original-value') || '';
    });
}

// ── Edit table builder ────────────────────────────────────────────────────────

function buildStockEditTable() {
    const table = document.createElement('table');
    table.id = 'stock-edit-table';
    table.className = 'portfolio-table';

    const thead = table.createTHead();
    const headerRow = thead.insertRow();
    const mkTh = (html, cls) => {
        const th = document.createElement('th');
        if (cls) th.className = cls;
        th.innerHTML = html;
        headerRow.appendChild(th);
    };
    mkTh('<button type="button" class="copy-table-btn copy-col-btn" title="Copy table to clipboard (Google Sheets)">' + COPY_ICON_SVG + '</button>', 'drag-handle-col');
    mkTh('Symbol <button type="button" class="copy-col-btn" data-column="symbol" title="Copy Symbol column">' + COPY_ICON_SVG + '</button>');
    mkTh('Qty <button type="button" class="copy-col-btn col-num" data-column="qty" title="Copy Qty column">' + COPY_ICON_SVG + '</button>', 'col-num');
    mkTh('Target % <button type="button" class="copy-col-btn" data-column="weight" title="Copy Target % column">' + COPY_ICON_SVG + '</button>');
    mkTh('Letf');
    mkTh('Groups');
    mkTh('');

    const tbody = document.createElement('tbody');

    const makeInputCell = (cfg, tdClass) => {
        const td = document.createElement('td');
        if (tdClass) td.className = tdClass;
        const inp = document.createElement('input');
        inp.type = cfg.type || 'text';
        inp.className = 'edit-input ' + cfg.cls;
        inp.setAttribute('data-column', cfg.col);
        if (cfg.sym) inp.setAttribute('data-symbol', cfg.sym);
        if (cfg.origAttr) inp.setAttribute(cfg.origAttr, cfg.value);
        inp.value = cfg.value;
        if (cfg.min   !== undefined) inp.min  = cfg.min;
        if (cfg.max   !== undefined) inp.max  = cfg.max;
        if (cfg.step  !== undefined) inp.step = cfg.step;
        if (cfg.style) Object.assign(inp.style, cfg.style);
        td.appendChild(inp);
        return td;
    };

    document.querySelectorAll('#stock-view-table tbody tr').forEach(viewRow => {
        const sym = viewRow.dataset.symbol || '';
        const qty = viewRow.dataset.qty || '0';
        const weight = viewRow.dataset.weight || '0';
        const letfAttr = viewRow.dataset.letf || '';
        let letfStr = '';
        if (letfAttr) {
            const tokens = letfAttr.split(',');
            const parts = [];
            for (let i = 0; i + 1 < tokens.length; i += 2) parts.push(tokens[i] + ' ' + tokens[i + 1]);
            letfStr = parts.join(' ');
        }
        const groupsStr = viewRow.dataset.groups || '';

        const tr = document.createElement('tr');
        const tdDrag = document.createElement('td');
        tdDrag.className = 'drag-handle-cell';
        tdDrag.innerHTML = '<span class="drag-handle" draggable="true">⠿</span>';
        tr.appendChild(tdDrag);
        tr.appendChild(makeInputCell({ cls: 'edit-symbol', col: 'symbol', value: sym, origAttr: 'data-original-symbol', sym }));
        tr.appendChild(makeInputCell({ cls: 'edit-qty',    col: 'qty',    value: qty,    type: 'number', sym, min: '0', step: 'any' }, 'amount'));
        tr.appendChild(makeInputCell({ cls: 'edit-weight', col: 'weight', value: weight, type: 'number', sym, min: '0', max: '100', step: '0.1' }));
        tr.appendChild(makeInputCell({ cls: 'edit-letf',   col: 'letf',   value: letfStr,  sym, style: { textAlign: 'left', width: '180px' } }));
        tr.appendChild(makeInputCell({ cls: 'edit-groups', col: 'groups', value: groupsStr, sym, style: { textAlign: 'left', width: '180px' } }));

        const tdDel = document.createElement('td');
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'delete-row-btn';
        delBtn.textContent = '×';
        tdDel.appendChild(delBtn);
        tr.appendChild(tdDel);
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);

    const tfoot = document.createElement('tfoot');
    const tfRow = tfoot.insertRow();
    const mkTd = (id, text) => {
        const td = document.createElement('td');
        if (id) td.id = id;
        if (text) td.textContent = text;
        tfRow.appendChild(td);
    };
    mkTd('', '');
    mkTd('', 'Total');
    mkTd('', '');
    mkTd('target-weight-total', '');
    mkTd('', '');
    mkTd('', '');
    mkTd('', '');
    table.appendChild(tfoot);

    return table;
}

function showEditTable() {
    const editTable = buildStockEditTable();
    const viewTable = document.getElementById('stock-view-table');
    viewTable.parentNode.insertBefore(editTable, viewTable);
    viewTable.style.display = 'none';
    const groupContainer = document.getElementById('group-table-container');
    if (groupContainer) groupContainer.style.display = 'none';
    const stockHint = document.createElement('p');
    stockHint.className = 'edit-hint';
    stockHint.id = 'stock-edit-hint';
    stockHint.textContent = 'Paste from spreadsheet (Ctrl+V) fills from focused cell';
    editTable.parentNode.insertBefore(stockHint, editTable.nextSibling);

    initDragAndDrop(editTable.querySelector('tbody'));

    editTable.addEventListener('click', e => {
        const copyTableBtn = e.target.closest('.copy-table-btn');
        if (copyTableBtn) {
            const rows = Array.from(editTable.querySelectorAll('tbody tr'))
                .filter(row => !row.dataset.deleted)
                .map(row => {
                    const get = col => row.querySelector('input[data-column="' + col + '"]')?.value ?? '';
                    return [get('symbol'), get('qty'), get('weight'), get('letf'), get('groups')].join('\t');
                });
            navigator.clipboard.writeText(rows.join('\n')).then(() => flashCopyButton(copyTableBtn));
            return;
        }
        const copyColBtn = e.target.closest('.copy-col-btn[data-column]');
        if (copyColBtn) {
            const col = copyColBtn.getAttribute('data-column');
            const inputs = Array.from(editTable.querySelectorAll('tbody input[data-column="' + col + '"]'));
            navigator.clipboard.writeText(inputs.map(i => i.value).join('\n')).then(() => flashCopyButton(copyColBtn));
        }
    });

    updateTargetWeightTotal();

    if (editTable.querySelector('tbody').querySelectorAll('tr:not([data-deleted])').length === 0) {
        const tr = addStockRow();
        if (tr) tr.querySelector('.new-symbol-input').focus();
    }

    const divInput = document.getElementById('dividend-from-input');
    if (divInput) divInput.value = divInput.dataset.originalValue ?? '';
}

function removeEditTable() {
    document.getElementById('stock-edit-table')?.remove();
    document.getElementById('stock-edit-hint')?.remove();
    const viewTable = document.getElementById('stock-view-table');
    if (viewTable) viewTable.style.display = '';
    if (typeof applyGroupViewState === 'function') applyGroupViewState();
}

// ── Edit mode init ────────────────────────────────────────────────────────────

function initEditMode() {
    const editToggle = document.getElementById('edit-toggle');
    const saveBtn = document.getElementById('save-btn');
    const body = document.body;

    editToggle.addEventListener('click', () => {
        const isEditing = body.classList.toggle('editing-active');
        editToggle.classList.toggle('active');

        const allocControls = document.querySelector('.alloc-controls');

        if (isEditing) {
            if (allocControls) allocControls.style.display = 'none';
            showEditTable();
            resetCashEditInputs();
            const cashTbody = document.querySelector('.cash-edit-table tbody');
            if (cashTbody && cashTbody.querySelectorAll('tr:not([data-deleted])').length === 0) {
                addCashRow();
            }
            document.querySelectorAll('[data-cash-edit-row]').forEach(tr => updateCashRowTypeBadge(tr));
        } else {
            if (allocControls) allocControls.style.display = '';
            removeEditTable();
            document.querySelectorAll('[data-new-cash]').forEach(el => el.remove());
            resetCashEditInputs();
        }
        updateTargetWeightTotal();
    });

    saveBtn.addEventListener('click', () => {
        const updates = [];
        document.querySelectorAll('#stock-edit-table tbody tr').forEach(tr => {
            if (tr.dataset.deleted) return;
            const isNew = tr.dataset.newStock;
            const sym = isNew
                ? (tr.querySelector('.new-symbol-input')?.value || '').trim().toUpperCase()
                : (tr.querySelector('.edit-symbol')?.value || '').trim().toUpperCase();
            if (!sym) return;
            const qtyInput     = isNew ? tr.querySelector('input[data-column="qty"]')     : tr.querySelector('.edit-qty');
            const weightInput  = isNew ? tr.querySelector('input[data-column="weight"]')  : tr.querySelector('.edit-weight');
            const letfInput    = isNew ? tr.querySelector('input[data-column="letf"]')    : tr.querySelector('.edit-letf');
            const groupsInput  = isNew ? tr.querySelector('input[data-column="groups"]')  : tr.querySelector('.edit-groups');
            updates.push({
                symbol: sym,
                amount: parseFloat(qtyInput?.value) || 0,
                targetWeight: weightInput ? parseFloat(weightInput.value) || 0 : 0,
                letf: letfInput?.value || '',
                groups: groupsInput?.value || ''
            });
        });

        const cashUpdates = [];
        document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
            if (tr.dataset.deleted) return;
            const key = (tr.querySelector('.cash-edit-key')?.value || '').trim();
            let value = (tr.querySelector('.cash-edit-value')?.value || '').trim();
            if (!key) return;
            if (!value && tr.dataset.entryType !== 'ref') value = '0';
            cashUpdates.push({ key, value });
        });

        const dividendInput = document.getElementById('dividend-from-input');
        const dividendStartDate = dividendInput ? dividendInput.value : null;

        saveBtn.disabled = true;
        editToggle.disabled = true;
        saveBtn.querySelector('.toggle-label').textContent = 'Saving...';

        fetch('/api/portfolio/save-all?portfolio=' + portfolioId, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ stocks: updates, cash: cashUpdates, dividendStartDate })
        }).then(r => {
            if (!r.ok) throw new Error('Save failed');
        }).catch(err => {
            alert('Failed to save: ' + err.message);
            saveBtn.disabled = false;
            editToggle.disabled = false;
            saveBtn.querySelector('.toggle-label').textContent = 'Save';
        });
    });

    document.addEventListener('click', e => {
        const btn = e.target.closest('.delete-row-btn, .delete-cash-btn');
        if (!btn || !body.classList.contains('editing-active')) return;
        const row = btn.closest('tr');
        if (row) {
            row.setAttribute('data-deleted', 'true');
            row.style.display = 'none';
            updateTargetWeightTotal();
        }
    });

    document.addEventListener('input', e => {
        if (e.target.classList.contains('edit-weight') || e.target.getAttribute('data-column') === 'weight') {
            if (e.target.value.includes('%')) e.target.value = e.target.value.replace(/%/g, '');
            updateTargetWeightTotal();
        }
        if (e.target.classList.contains('cash-edit-key')) {
            const tr = e.target.closest('tr');
            if (tr) updateCashRowTypeBadge(tr);
        }
    });

    document.getElementById('add-stock-btn')?.addEventListener('click', () => {
        const tr = addStockRow();
        if (tr) tr.querySelector('.new-symbol-input').focus();
    });

    document.getElementById('add-cash-btn')?.addEventListener('click', () => {
        const tr = addCashRow();
        if (tr) tr.querySelector('.cash-edit-key').focus();
    });

    document.getElementById('virtual-rebal-btn')?.addEventListener('click', async () => {
        try {
            await fetch('/api/backup/trigger?portfolio=' + portfolioId + '&subfolder=rebalance', { method: 'POST' });
        } catch (_) { /* non-fatal */ }

        if (!body.classList.contains('editing-active')) {
            editToggle.click();
        }

        if (dividendCalcUpToDate) {
            const divInput = document.getElementById('dividend-from-input');
            if (divInput) {
                divInput.value = dividendCalcUpToDate;
            }
        }

        const portfolioTotal = getRebalTotal();
        document.querySelectorAll('#stock-edit-table tbody .edit-qty').forEach(input => {
            const sym = input.getAttribute('data-symbol');
            const viewRow = document.querySelector('#stock-view-table tbody tr[data-symbol="' + sym + '"]');
            if (!viewRow) return;
            const targetWeight = parseFloat(viewRow.dataset.weight);
            if (isNaN(targetWeight)) return;
            if (targetWeight <= 0) { input.value = 0; return; }
            const markCell = document.getElementById('mark-' + sym);
            const markPrice = rawMarkPrices[sym] ?? parsePrice(markCell ? markCell.textContent : null);
            if (!markPrice || markPrice <= 0) return;
            input.value = parseFloat(((targetWeight / 100) * portfolioTotal / markPrice).toFixed(2));
        });
    });
}

// ── Drag-and-drop row reordering ──────────────────────────────────────────────

function initDragAndDrop(tbody) {
    if (!tbody) return;
    let dragRow = null;

    tbody.addEventListener('dragstart', e => {
        if (!document.body.classList.contains('editing-active')) return;
        const handle = e.target.closest('.drag-handle');
        if (!handle) { e.preventDefault(); return; }
        dragRow = handle.closest('tr');
        dragRow.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', '');
    });

    tbody.addEventListener('dragover', e => {
        if (!dragRow) return;
        e.preventDefault();
        const row = e.target.closest('tr');
        tbody.querySelectorAll('.drag-over-top, .drag-over-bottom')
             .forEach(r => r.classList.remove('drag-over-top', 'drag-over-bottom'));
        if (!row || row === dragRow || row.dataset.deleted) {
            const rows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])')).filter(r => r !== dragRow);
            if (rows.length) rows[rows.length - 1].classList.add('drag-over-bottom');
            return;
        }
        const rect = row.getBoundingClientRect();
        row.classList.add(e.clientY < rect.top + rect.height / 2 ? 'drag-over-top' : 'drag-over-bottom');
    });

    tbody.addEventListener('drop', e => {
        if (!dragRow) return;
        e.preventDefault();
        const row = e.target.closest('tr');
        if (row && row !== dragRow && !row.dataset.deleted) {
            const rect = row.getBoundingClientRect();
            tbody.insertBefore(dragRow, e.clientY < rect.top + rect.height / 2 ? row : row.nextSibling);
        } else if (!row || row.dataset.deleted) {
            tbody.appendChild(dragRow);
        }
        cleanup();
    });

    tbody.addEventListener('dragend', cleanup);

    function cleanup() {
        tbody.querySelectorAll('tr').forEach(r =>
            r.classList.remove('drag-over-top', 'drag-over-bottom', 'dragging'));
        dragRow = null;
    }
}

// ── Paste handler ─────────────────────────────────────────────────────────────

function initPasteHandler() {
    document.addEventListener('paste', (e) => {
        if (!document.body.classList.contains('editing-active')) return;

        const activeEl = document.activeElement;
        if (!activeEl || !activeEl.classList.contains('edit-input')) return;

        const clipText = (e.clipboardData || window.clipboardData).getData('text');
        const lines = clipText.split(/[\r\n]+/).filter(l => l.trim() !== '');

        if (lines.length <= 1) {
            if (activeEl.classList.contains('edit-weight') || activeEl.getAttribute('data-column') === 'weight') {
                const stripped = clipText.replace(/%/g, '').trim();
                if (stripped !== clipText.trim()) {
                    e.preventDefault();
                    activeEl.value = stripped;
                    activeEl.dispatchEvent(new Event('input', { bubbles: true }));
                }
            }
            return;
        }

        e.preventDefault();

        const rows = lines.map(l => l.split('\t'));
        const isMultiCol = rows.some(r => r.length >= 2);

        const isCashKey   = activeEl.classList.contains('cash-edit-key');
        const isCashValue = activeEl.classList.contains('cash-edit-value');

        if (isCashKey || isCashValue) {
            if (isMultiCol) {
                const tbody = document.querySelector('.cash-edit-table tbody');
                if (!tbody) return;
                let allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])'));
                const startRow = activeEl.closest('tr');
                let startIdx = allRows.indexOf(startRow);
                if (startIdx < 0) startIdx = allRows.length;
                rows.forEach((cols, i) => {
                    let tr = allRows[startIdx + i];
                    if (!tr) { tr = addCashRow(); allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])')); }
                    if (!tr) return;
                    const k = tr.querySelector('.cash-edit-key');
                    const v = tr.querySelector('.cash-edit-value');
                    if (k) k.value = cols[0].trim();
                    if (v && cols[1] !== undefined) v.value = cols[1].trim();
                });
            } else {
                const sel = isCashKey ? '.cash-edit-key' : '.cash-edit-value';
                let allInputs = Array.from(document.querySelectorAll(sel));
                const startIdx = allInputs.indexOf(activeEl);
                if (startIdx < 0) return;
                lines.forEach((line, i) => {
                    if (startIdx + i < allInputs.length) {
                        allInputs[startIdx + i].value = line.trim();
                    } else {
                        const tr = addCashRow();
                        if (!tr) return;
                        allInputs = Array.from(document.querySelectorAll(sel));
                        const inp = tr.querySelector(sel);
                        if (inp) inp.value = line.trim();
                    }
                });
            }
        } else {
            const startColIdx = getStockColIndex(activeEl);
            if (startColIdx < 0) return;

            const tbody = document.querySelector('#stock-edit-table tbody');
            if (!tbody) return;
            let allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])'));
            const startRow = activeEl.closest('tr');
            let startRowIdx = allRows.indexOf(startRow);
            if (startRowIdx < 0) startRowIdx = allRows.length;

            rows.forEach((cols, i) => {
                let tr = allRows[startRowIdx + i];
                if (!tr) { tr = addStockRow(); allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])')); }
                if (!tr) return;
                const inputs = getStockRowInputs(tr);
                cols.forEach((val, j) => {
                    const idx = startColIdx + j;
                    if (idx < inputs.length) {
                        const inp = inputs[idx];
                        const isWeight = inp.classList.contains('edit-weight') || inp.getAttribute('data-column') === 'weight';
                        inp.value = isWeight ? val.replace(/%/g, '').trim() : val.trim();
                    }
                });
            });
            updateTargetWeightTotal();
        }
    });
}
