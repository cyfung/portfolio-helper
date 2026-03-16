// ── backup.js — Backup/restore modal, TWS sync, save-to-backtest ─────────────
// Depends on: utils.js, edit-mode.js, rebalance.js

function formatSavedAt(millis) {
    const d = new Date(millis);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} `
         + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function initBackupPanel() {
    document.getElementById('restore-backup-btn')?.addEventListener('click', async () => {
        try {
            await fetch('/api/backup/trigger?portfolio=' + portfolioId, { method: 'POST' });
        } catch (_) { /* non-fatal */ }

        let entries;
        try {
            const resp = await fetch('/api/backup/list-db?portfolio=' + portfolioId);
            entries = await resp.json();
        } catch (e) {
            alert('Failed to load backup list.');
            return;
        }

        // Group by label: "" → "Daily", otherwise capitalise
        const groupMap = new Map();
        entries.forEach(e => {
            const tab = e.label === '' ? 'Daily' : e.label.charAt(0).toUpperCase() + e.label.slice(1);
            if (!groupMap.has(tab)) groupMap.set(tab, []);
            groupMap.get(tab).push(e);
        });
        const groups = [...groupMap.entries()]; // [[tabName, [entry,...]], ...]

        const overlay = document.createElement('div');
        overlay.className = 'backup-modal-overlay';

        const modal = document.createElement('div');
        modal.className = 'backup-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');

        const headerEl = document.createElement('div');
        headerEl.className = 'backup-modal-header';

        const titleEl = document.createElement('p');
        titleEl.className = 'backup-modal-title';
        titleEl.textContent = 'Backups';

        const removeAllBtn = document.createElement('button');
        removeAllBtn.className = 'backup-modal-remove-all';
        removeAllBtn.textContent = 'Remove All';
        removeAllBtn.hidden = entries.length === 0;
        removeAllBtn.addEventListener('click', async () => {
            const confirmed = await window.showConfirmOverlay('Delete all backups for this portfolio? This cannot be undone.', 'Delete All');
            if (!confirmed) return;
            removeAllBtn.disabled = true;
            try {
                await fetch('/api/backup/delete-all?portfolio=' + portfolioId, { method: 'DELETE' });
                document.body.removeChild(overlay);
            } catch (_) {
                removeAllBtn.disabled = false;
            }
        });

        headerEl.appendChild(titleEl);
        headerEl.appendChild(removeAllBtn);
        modal.appendChild(headerEl);

        if (entries.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'backup-modal-empty';
            empty.textContent = 'No backups available.';
            modal.appendChild(empty);
        } else {
            const bodyEl = document.createElement('div');
            bodyEl.className = 'backup-modal-body';

            const tabBar = document.createElement('div');
            tabBar.className = 'backup-modal-tabs';

            const panels = {};
            groups.forEach(([tabName, tabEntries], idx) => {
                if (groups.length > 1) {
                    const tab = document.createElement('button');
                    tab.className = 'backup-modal-tab' + (idx === 0 ? ' active' : '');
                    tab.textContent = tabName;
                    tab.addEventListener('click', () => {
                        tabBar.querySelectorAll('.backup-modal-tab').forEach(t => t.classList.remove('active'));
                        tab.classList.add('active');
                        Object.entries(panels).forEach(([k, p]) => { p.hidden = k !== tabName; });
                    });
                    tabBar.appendChild(tab);
                }

                const panel = document.createElement('div');
                panel.className = 'backup-modal-panel';
                panel.hidden = idx !== 0;

                const list = document.createElement('ul');
                list.className = 'backup-modal-list';
                tabEntries.forEach(entry => {
                    const item = document.createElement('li');
                    item.className = 'backup-modal-item';

                    const label = document.createElement('span');
                    label.textContent = formatSavedAt(entry.createdAt);

                    const restoreBtn = document.createElement('button');
                    restoreBtn.textContent = 'Restore';
                    restoreBtn.addEventListener('click', async () => {
                        restoreBtn.disabled = true;
                        restoreBtn.textContent = '…';
                        const reset = () => { restoreBtn.disabled = false; restoreBtn.textContent = 'Restore'; };
                        try {
                            const r = await fetch(
                                '/api/backup/restore-db?portfolio=' + portfolioId + '&id=' + entry.id,
                                { method: 'POST' }
                            );
                            const json = await r.json();
                            if (json.status === 'ok') {
                                document.body.removeChild(overlay);
                                location.reload();
                            } else {
                                alert('Restore failed: ' + (json.message || 'Unknown error'));
                                reset();
                            }
                        } catch (e) {
                            alert('Restore failed.');
                            reset();
                        }
                    });

                    const delBtn = document.createElement('button');
                    delBtn.className = 'backup-modal-item-del';
                    delBtn.textContent = '✕';
                    delBtn.title = 'Delete this backup';
                    delBtn.addEventListener('click', async () => {
                        delBtn.disabled = true;
                        try {
                            await fetch(
                                '/api/backup/delete-db?portfolio=' + portfolioId + '&id=' + entry.id,
                                { method: 'DELETE' }
                            );
                            item.remove();
                        } catch (_) {
                            delBtn.disabled = false;
                        }
                    });

                    const actions = document.createElement('div');
                    actions.className = 'backup-modal-item-actions';
                    actions.appendChild(restoreBtn);
                    actions.appendChild(delBtn);

                    item.appendChild(label);
                    item.appendChild(actions);
                    list.appendChild(item);
                });
                panel.appendChild(list);
                panels[tabName] = panel;
            });

            if (groups.length > 1) bodyEl.appendChild(tabBar);
            Object.values(panels).forEach(p => bodyEl.appendChild(p));
            modal.appendChild(bodyEl);
        }

        const footer = document.createElement('div');
        footer.className = 'backup-modal-footer';

        const importBtn = document.createElement('button');
        importBtn.className = 'backup-modal-import btn-outline-accent';
        importBtn.textContent = 'Import';
        importBtn.addEventListener('click', () => {
            document.getElementById('import-file-input')?.click();
        });

        const exportBtn = document.createElement('button');
        exportBtn.className = 'backup-modal-export btn-outline-accent';
        exportBtn.textContent = 'Export';
        exportBtn.addEventListener('click', async () => {
            exportBtn.disabled = true;
            try {
                const resp = await fetch('/api/backup/export-json?portfolio=' + portfolioId);
                if (!resp.ok) { alert('Export failed.'); return; }
                const blob = await resp.blob();
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'backup-' + portfolioId + '.json';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            } catch (e) {
                alert('Export failed: ' + e.message);
            } finally {
                exportBtn.disabled = false;
            }
        });

        const closeBtn = document.createElement('button');
        closeBtn.className = 'backup-modal-close';
        closeBtn.textContent = 'Cancel';
        closeBtn.addEventListener('click', () => document.body.removeChild(overlay));

        footer.appendChild(importBtn);
        footer.appendChild(exportBtn);
        footer.appendChild(closeBtn);
        modal.appendChild(footer);

        overlay.appendChild(modal);
        overlay.addEventListener('click', e => { if (e.target === overlay) document.body.removeChild(overlay); });
        document.body.appendChild(overlay);
        closeBtn.focus();
    });

    initImportFile();

    // IBKR margin rates reload button
    const ibkrReloadBtn = document.getElementById('ibkr-reload-btn');
    if (ibkrReloadBtn) {
        const COOLDOWN_MS = 10 * 60 * 1000;
        const lastFetch = parseInt(ibkrReloadBtn.dataset.lastFetch || '0', 10);
        const elapsed = lastFetch > 0 ? Date.now() - lastFetch : COOLDOWN_MS + 1;
        if (elapsed < COOLDOWN_MS) {
            ibkrReloadBtn.disabled = true;
            setTimeout(() => { ibkrReloadBtn.disabled = false; }, COOLDOWN_MS - elapsed);
        }
        ibkrReloadBtn.addEventListener('click', async () => {
            ibkrReloadBtn.disabled = true;
            ibkrReloadBtn.textContent = '…';
            try {
                const resp = await fetch('/api/margin-rates/reload', { method: 'POST' });
                if (resp.ok) {
                    location.reload();
                } else {
                    ibkrReloadBtn.textContent = '↻';
                    setTimeout(() => { ibkrReloadBtn.disabled = false; }, 60_000);
                }
            } catch (e) {
                ibkrReloadBtn.textContent = '↻';
                ibkrReloadBtn.disabled = false;
            }
        });
    }
}

function initImportFile() {
    const input = document.getElementById('import-file-input');
    if (!input) return;

    input.addEventListener('change', async () => {
        const file = input.files?.[0];
        if (!file) return;

        const formData = new FormData();
        formData.append('file', file);

        let json;
        try {
            const resp = await fetch('/api/backup/import-file?portfolio=' + portfolioId, {
                method: 'POST',
                body: formData
            });
            json = await resp.json();
        } catch (e) {
            alert('Import failed: ' + e.message);
            input.value = '';
            return;
        }

        if (json.error) {
            alert('Import error: ' + json.error);
            input.value = '';
            return;
        }

        // Close any open backup modal
        document.querySelector('.backup-modal-overlay')?.remove();

        // Ensure edit mode is active
        if (!document.body.classList.contains('editing-active')) {
            document.getElementById('edit-toggle')?.click();
        }

        // Populate stocks table
        if (json.stocks) {
            // Mark all existing stock rows as deleted
            document.querySelectorAll('#stock-edit-table tbody tr').forEach(tr => {
                tr.dataset.deleted = 'true';
                tr.hidden = true;
            });
            for (const s of json.stocks) {
                const tr = addStockRow();
                if (!tr) continue;
                const [symIn, qtyIn, weightIn, letfIn, groupsIn] = getStockRowInputs(tr);
                if (symIn) symIn.value = s.symbol;
                if (qtyIn) qtyIn.value = s.amount;
                if (weightIn) weightIn.value = s.targetWeight ?? 0;
                if (letfIn) letfIn.value = s.letf ?? '';
                if (groupsIn) groupsIn.value = s.groups ?? '';
            }
        }

        // Populate cash table
        if (json.cash) {
            // Mark all existing cash rows as deleted
            document.querySelectorAll('.cash-edit-table tbody tr').forEach(tr => {
                tr.dataset.deleted = 'true';
                tr.hidden = true;
            });
            for (const c of json.cash) {
                const tr = addCashRow();
                if (!tr) continue;
                const keyInput = tr.querySelector('.cash-edit-key');
                const valInput = tr.querySelector('.cash-edit-value');
                if (keyInput) keyInput.value = c.key;
                if (valInput) valInput.value = c.value;
            }
        }

        // Reset so same file can be re-selected
        input.value = '';
    });
}

// ── TWS Sync ──────────────────────────────────────────────────────────────────

function showTwsSyncError(msg) {
    let el = document.getElementById('tws-sync-error');
    if (!el) {
        el = document.createElement('div');
        el.id = 'tws-sync-error';
        el.className = 'tws-sync-error';
        const anchor = document.querySelector('.summary-and-rates');
        if (anchor) anchor.insertAdjacentElement('beforebegin', el);
        else document.getElementById('tws-sync-btn')?.insertAdjacentElement('afterend', el);
    }
    el.textContent = msg;
    el.style.display = 'block';
    clearTimeout(el._hideTimer);
    el._hideTimer = setTimeout(() => { el.style.display = 'none'; }, 4000);
}

function updateOrAddCashRow(key, value) {
    const rows = document.querySelectorAll('[data-cash-edit-row]');
    for (const tr of rows) {
        if (tr.dataset.deleted) continue;
        const keyInput = tr.querySelector('.cash-edit-key');
        if (keyInput && keyInput.value.toLowerCase() === key.toLowerCase()) {
            const valInput = tr.querySelector('.cash-edit-value');
            if (valInput) valInput.value = value;
            return;
        }
    }
    const tr = addCashRow();
    if (tr) {
        const keyInput = tr.querySelector('.cash-edit-key');
        const valInput = tr.querySelector('.cash-edit-value');
        if (keyInput) keyInput.value = key;
        if (valInput) valInput.value = value;
    }
}

async function initTwsSync() {
    const btn = document.getElementById('tws-sync-btn');
    if (!btn) return;

    btn.addEventListener('click', async () => {
        btn.disabled = true;
        btn.textContent = 'Syncing\u2026';
        try {
            const res = await fetch('/api/tws/snapshot?portfolio=' + portfolioId);
            const data = await res.json();
            if (!res.ok || data.error) { showTwsSyncError('TWS sync error: ' + (data.error || res.statusText)); return; }

            if (!document.body.classList.contains('editing-active')) {
                document.getElementById('edit-toggle')?.click();
            }

            document.querySelectorAll('#stock-edit-table .edit-qty').forEach(input => {
                input.value = '';
            });

            for (const pos of data.positions) {
                const sym = pos.symbol;
                const qty = pos.qty;
                let qtyInput = document.querySelector(
                    '#stock-edit-table .edit-qty[data-symbol="' + sym + '"]'
                );
                if (qtyInput) {
                    qtyInput.value = qty;
                } else {
                    const tr = addStockRow();
                    if (tr) {
                        const [symIn, qtyIn] = getStockRowInputs(tr);
                        if (symIn) symIn.value = sym;
                        if (qtyIn) qtyIn.value = qty;
                    }
                }
            }

            for (const [ccy, amt] of Object.entries(data.cashBalances)) {
                updateOrAddCashRow('Cash.' + ccy + '.M', String(amt));
            }

            for (const [ccy, amt] of Object.entries(data.accruedCash)) {
                updateOrAddCashRow('MTD Interest.' + ccy, String(amt));
            }

            updateTargetWeightTotal();
        } catch (e) {
            showTwsSyncError('TWS sync failed: ' + e.message);
        } finally {
            btn.disabled = false;
            btn.textContent = 'Sync TWS';
        }
    });
}

// ── Save to Backtest ──────────────────────────────────────────────────────────

function initSaveToBacktest() {
    document.getElementById('save-to-backtest-btn')?.addEventListener('click', async () => {
        const tickers = [...document.querySelectorAll('#stock-view-table tbody tr')].map(row => ({
            ticker: row.dataset.symbol,
            weight: parseFloat(row.dataset.weight) || 0
        })).filter(t => t.ticker && t.weight > 0);

        const marginTargetInput = document.getElementById('margin-target-input');
        const marginPercentEl = document.getElementById('margin-percent');
        const marginTargetPctVal = marginTargetInput?.value
            ? parseFloat(marginTargetInput.value)
            : parseFloat(marginPercentEl?.textContent?.replace(/[()%]/g, '')) || 0;

        const allocAddModeVal = document.getElementById('alloc-add-mode')?.value || 'PROPORTIONAL';
        const allocReduceModeVal = document.getElementById('alloc-reduce-mode')?.value || 'PROPORTIONAL';

        const config = {
            tickers,
            rebalanceStrategy: 'YEARLY',
            marginStrategies: marginTargetPctVal > 0 ? [{
                marginRatio:          marginTargetPctVal / 100,
                marginSpread:         0.015,
                marginDeviationUpper: 0.05,
                marginDeviationLower: 0.05,
                upperRebalanceMode:   allocReduceModeVal,
                lowerRebalanceMode:   allocAddModeVal
            }] : []
        };

        const name = (typeof portfolioName !== 'undefined' && portfolioName) ? portfolioName : (document.querySelector('h1')?.textContent?.trim() || 'Portfolio');

        const res = await fetch('/api/backtest/savedPortfolios', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, config })
        });

        if (res.ok) {
            const btn = document.getElementById('save-to-backtest-btn');
            const original = btn.textContent;
            btn.textContent = 'Saved!';
            setTimeout(() => { btn.textContent = original; }, 1500);
        }
    });
}
