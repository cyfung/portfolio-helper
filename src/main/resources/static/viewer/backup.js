// ── backup.js — Backup/restore modal, TWS sync, save-to-backtest ─────────────
// Depends on: utils.js, edit-mode.js, rebalance.js

function initBackupPanel() {
    document.getElementById('restore-backup-btn')?.addEventListener('click', async () => {
        try {
            await fetch('/api/backup/trigger?portfolio=' + portfolioId, { method: 'POST' });
        } catch (_) { /* non-fatal */ }

        let allBackups;
        try {
            const resp = await fetch('/api/backup/list?portfolio=' + portfolioId);
            allBackups = await resp.json();
        } catch (e) {
            alert('Failed to load backup list.');
            return;
        }

        const groups = Object.entries(allBackups);
        const totalCount = groups.reduce((sum, [, v]) => sum + v.length, 0);

        const overlay = document.createElement('div');
        overlay.className = 'backup-modal-overlay';

        const modal = document.createElement('div');
        modal.className = 'backup-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');

        const titleEl = document.createElement('p');
        titleEl.className = 'backup-modal-title';
        titleEl.textContent = 'Restore from Backup';
        modal.appendChild(titleEl);

        if (totalCount === 0) {
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
            groups.forEach(([key, dates], idx) => {
                const displayName = key === 'default' ? 'Daily' : key.charAt(0).toUpperCase() + key.slice(1);

                if (groups.length > 1) {
                    const tab = document.createElement('button');
                    tab.className = 'backup-modal-tab' + (idx === 0 ? ' active' : '');
                    tab.textContent = displayName;
                    tab.addEventListener('click', () => {
                        tabBar.querySelectorAll('.backup-modal-tab').forEach(t => t.classList.remove('active'));
                        tab.classList.add('active');
                        Object.entries(panels).forEach(([k, p]) => { p.hidden = k !== key; });
                    });
                    tabBar.appendChild(tab);
                }

                const panel = document.createElement('div');
                panel.className = 'backup-modal-panel';
                panel.hidden = idx !== 0;

                if (dates.length === 0) {
                    const empty = document.createElement('p');
                    empty.className = 'backup-modal-empty';
                    empty.textContent = 'No backups available.';
                    panel.appendChild(empty);
                } else {
                    const list = document.createElement('ul');
                    list.className = 'backup-modal-list';
                    dates.forEach(date => {
                        const item = document.createElement('li');
                        item.className = 'backup-modal-item';
                        const label = document.createElement('span');
                        label.textContent = date;
                        const restoreBtn = document.createElement('button');
                        restoreBtn.textContent = 'Restore';
                        restoreBtn.addEventListener('click', async () => {
                            restoreBtn.disabled = true;
                            restoreBtn.textContent = '…';
                            const resetRestoreBtn = () => { restoreBtn.disabled = false; restoreBtn.textContent = 'Restore'; };
                            const subParam = key !== 'default' ? '&subfolder=' + encodeURIComponent(key) : '';
                            try {
                                const r = await fetch('/api/backup/restore?portfolio=' + portfolioId + '&date=' + encodeURIComponent(date) + subParam, { method: 'POST' });
                                const json = await r.json();
                                if (json.status === 'ok') {
                                    document.body.removeChild(overlay);
                                    location.reload();
                                } else {
                                    alert('Restore failed: ' + (json.message || 'Unknown error'));
                                    resetRestoreBtn();
                                }
                            } catch (e) {
                                alert('Restore failed.');
                                resetRestoreBtn();
                            }
                        });
                        item.appendChild(label);
                        item.appendChild(restoreBtn);
                        list.appendChild(item);
                    });
                    panel.appendChild(list);
                }
                panels[key] = panel;
            });

            if (groups.length > 1) bodyEl.appendChild(tabBar);
            Object.values(panels).forEach(p => bodyEl.appendChild(p));
            modal.appendChild(bodyEl);
        }

        const footer = document.createElement('div');
        footer.className = 'backup-modal-footer';
        const closeBtn = document.createElement('button');
        closeBtn.className = 'backup-modal-close';
        closeBtn.textContent = 'Cancel';
        closeBtn.addEventListener('click', () => document.body.removeChild(overlay));
        footer.appendChild(closeBtn);
        modal.appendChild(footer);

        overlay.appendChild(modal);
        overlay.addEventListener('click', e => { if (e.target === overlay) document.body.removeChild(overlay); });
        document.body.appendChild(overlay);
        closeBtn.focus();
    });

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

// ── TWS Sync ──────────────────────────────────────────────────────────────────

function showTwsSyncError(msg) {
    let el = document.getElementById('tws-sync-error');
    if (!el) {
        el = document.createElement('span');
        el.id = 'tws-sync-error';
        el.className = 'tws-sync-error';
        document.getElementById('tws-sync-btn')?.insertAdjacentElement('afterend', el);
    }
    el.textContent = msg;
    el.style.display = 'inline';
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
            if (data.error) { showTwsSyncError('TWS sync error: ' + data.error); return; }

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

        const name = document.querySelector('h1')?.textContent?.trim() || 'Portfolio';

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
