const RESTART_KEYS = new Set(['bindHost', 'navUpdateInterval', 'dataDir']);

const GLOBAL_DEFAULTS = {
    bindHost: 'localhost',
    openBrowser: 'true',
    dataDir: '',
    navUpdateInterval: '',
    exchangeSuffixes: 'SBF=.PA,LSEETF=.L',
    twsHost: '127.0.0.1',
    twsPort: '7496',
    ibkrRateInterval: '3600',
    autoUpdate: 'true',
    updateCheckInterval: '86400'
};

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initUpdates();
    initPairing();

    // Snapshot originals for restart detection
    document.querySelectorAll('[data-config-key]').forEach(el => {
        el.dataset.originalValue = el.type === 'checkbox' ? String(el.checked) : el.value;
    });

    document.getElementById('config-save-btn').addEventListener('click', async () => {
        const globalUpdates = {};
        const portfolioUpdates = {};  // { portfolioId: { twsAccount: "...", virtualBalance: "..." } }
        let requiresRestart = false;

        document.querySelectorAll('[data-config-key]').forEach(el => {
            if (el.disabled) return;
            const key = el.dataset.configKey;
            const portfolioId = el.dataset.portfolioId;
            const value = el.type === 'checkbox' ? String(el.checked) : el.value.trim();

            if (portfolioId) {
                portfolioUpdates[portfolioId] = portfolioUpdates[portfolioId] || {};
                portfolioUpdates[portfolioId][key] = value;
            } else {
                globalUpdates[key] = value;
                if (RESTART_KEYS.has(key) && value !== el.dataset.originalValue) requiresRestart = true;
            }
        });

        try {
            // Save global config
            await fetch('/api/config/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(globalUpdates)
            }).then(r => { if (!r.ok) throw new Error(`Global save failed: ${r.statusText}`); });

            // Save per-portfolio configs
            for (const [pid, updates] of Object.entries(portfolioUpdates)) {
                await fetch(`/api/portfolio-config/save?portfolio=${encodeURIComponent(pid)}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(updates)
                }).then(r => { if (!r.ok) throw new Error(`Portfolio save failed: ${r.statusText}`); });
            }

            showStatus(
                requiresRestart ? 'Saved. Restart required for some changes to take effect.' : 'Settings saved.',
                requiresRestart ? 'warn' : 'ok'
            );
        } catch (err) {
            showStatus('Error: ' + err.message, 'error');
        }
    });

    document.getElementById('config-restore-btn').addEventListener('click', () => {
        // Reset global inputs to defaults
        document.querySelectorAll('[data-config-key]:not([data-portfolio-id])').forEach(el => {
            if (el.disabled) return;
            const def = GLOBAL_DEFAULTS[el.dataset.configKey];
            if (def === undefined) return;
            if (el.type === 'checkbox') el.checked = def === 'true';
            else el.value = def;
        });
        // Reset per-portfolio inputs to empty/false
        document.querySelectorAll('[data-portfolio-id]').forEach(el => {
            if (el.type === 'checkbox') el.checked = false;
            else el.value = '';
        });
        // Trigger save
        document.getElementById('config-save-btn').click();
    });
});

async function refreshPairingUI() {
    await Promise.all([loadPairedDevices(), loadPendingPairings()]);
}

async function loadPairedDevices() {
    const list = document.getElementById('paired-devices-list');
    if (!list) return;

    try {
        const r = await fetch('/api/paired-devices');
        const devices = await r.json();

        if (devices.length === 0) {
            list.innerHTML = '<p class="config-env-override-note">No devices paired.</p>';
            return;
        }

        let html = '<table class="portfolio-config-table"><thead><tr><th>Device</th><th>Last IP</th><th>Paired At</th><th>Action</th></tr></thead><tbody>';
        devices.forEach(d => {
            const date = new Date(d.pairedAt).toLocaleString();
            html += `<tr>
                <td><strong>${d.name}</strong><br><small style="color:var(--text-tertiary)">${d.id}</small></td>
                <td>${d.lastIp}</td>
                <td>${date}</td>
                <td><button class="config-text-link-btn" style="color:var(--negative)" onclick="unpairDevice('${d.id}')">Unlink</button></td>
            </tr>`;
        });
        html += '</tbody></table>';
        list.innerHTML = html;
    } catch (err) {
        list.innerHTML = '<p class="config-status-error">Failed to load devices.</p>';
    }
}

async function loadPendingPairings() {
    const container = document.getElementById('pairing-pin-display');
    if (!container) return;

    try {
        const r = await fetch('/api/pending-pairings');
        const pending = await r.json();

        if (pending.length === 0) {
            container.innerHTML = '<p class="config-env-override-note">No pending pairing requests. Open the Android app to begin.</p>';
            return;
        }

        let html = '<div style="display:flex; flex-direction:column; gap:12.dp;">';
        pending.forEach(p => {
            html += `
                <div class="pending-pairing-item" style="border:1px solid var(--border-color); padding:12px; border-radius:8px; margin-bottom:8px;">
                    <div style="display:flex; justify-content:space-between; align-items:center;">
                        <div>
                            <strong>${p.name}</strong> from ${p.ip}<br>
                            <small style="color:var(--text-tertiary)">ID: ${p.id}</small>
                        </div>
                        <div style="display:flex; gap:8px; align-items:center;">
                            <input type="text" placeholder="PIN" id="pin-${p.id}" style="width:60px; text-align:center;" maxlength="4">
                            <button class="config-save-btn" onclick="authorizeDevice('${p.id}')">Authorize</button>
                        </div>
                    </div>
                </div>
            `;
        });
        html += '</div>';
        container.innerHTML = html;
    } catch (err) {
        container.innerHTML = '<p class="config-status-error">Failed to load pending requests.</p>';
    }
}

window.authorizeDevice = async (deviceId) => {
    const pinEl = document.getElementById(`pin-${deviceId}`);
    const pin = pinEl ? pinEl.value.trim() : "";
    if (pin.length !== 4) {
        alert("Enter the 4-digit PIN displayed on the phone.");
        return;
    }

    try {
        const r = await fetch(`/api/authorize-device?deviceId=${encodeURIComponent(deviceId)}&pin=${encodeURIComponent(pin)}`, { method: 'POST' });
        if (r.ok) {
            refreshPairingUI();
        } else {
            const msg = await r.text();
            alert("Authorization failed: " + msg);
        }
    } catch (err) {
        alert("Error authorizing device");
    }
};

window.unpairDevice = async (id) => {
    if (!confirm('Unlink this device? It will no longer be able to sync until re-paired.')) return;
    try {
        await fetch(`/api/unpair-device?deviceId=${encodeURIComponent(id)}`, { method: 'POST' });
        refreshPairingUI();
    } catch (err) {
        alert('Unpairing failed');
    }
};

function initPairing() {
    const unpairAllBtn = document.getElementById('unpair-all-btn');
    if (unpairAllBtn) {
        unpairAllBtn.addEventListener('click', async () => {
            if (!confirm('Unlink ALL devices?')) return;
            try {
                await fetch('/api/unpair-device', { method: 'POST' });
                refreshPairingUI();
            } catch (err) {
                alert('Action failed');
            }
        });
    }

    refreshPairingUI();
    // Poll for pending pairings every 5 seconds while on config page
    setInterval(loadPendingPairings, 5000);
}

function showStatus(msg, type) {
    const el = document.getElementById('config-status');
    el.textContent = msg;
    el.className = 'config-status config-status-' + type;
}

function showUpdateStatus(msg, type) {
    const el = document.getElementById('update-status');
    if (!el) return;
    el.textContent = msg;
    el.className = 'config-status config-status-' + (type || 'ok');
}

function attemptReconnect() {
    fetch('/').then(r => {
        if (r.ok) location.reload();
        else setTimeout(attemptReconnect, 1000);
    }).catch(() => setTimeout(attemptReconnect, 1000));
}

function initUpdates() {
    let downloadPollTimer = null;

    function fmtBytes(bytes) {
        if (bytes <= 0) return '?';
        return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    }

    function updateProgressUI(info) {
        const row = document.getElementById('update-progress-row');
        const bar = document.getElementById('update-progress-bar');
        const label = document.getElementById('update-progress-label');
        if (!row || !bar || !label) return;

        const phase = info.download?.phase;
        if (phase === 'DOWNLOADING' || phase === 'READY' || phase === 'APPLYING') {
            row.classList.add('visible');
            const received = info.download.bytesReceived || 0;
            const total = info.download.totalBytes || 0;
            const pct = total > 0 ? Math.round(received / total * 100) : 0;
            bar.style.width = pct + '%';
            if (phase === 'READY') {
                bar.style.width = '100%';
                label.textContent = 'Download complete';
            } else if (phase === 'APPLYING') {
                label.textContent = 'Applying update…';
            } else {
                label.textContent = fmtBytes(received) + ' / ' + fmtBytes(total);
            }
        } else {
            row.classList.remove('visible');
        }
    }

    function updateLatestVersionUI(info) {
        const valueEl = document.getElementById('latest-version-value');
        const badgeEl = document.getElementById('latest-version-badge');
        if (valueEl) {
            if (info.latestVersion) {
                const href = info.releaseUrl || '#';
                valueEl.innerHTML = `<a href="${href}" target="_blank" rel="noopener">v${info.latestVersion}</a>`;
            } else if (info.lastCheckError) {
                valueEl.innerHTML = `<span class="config-env-override-note">Check failed: ${info.lastCheckError}</span>`;
            } else {
                valueEl.innerHTML = `<span class="config-env-override-note">Not checked yet</span>`;
            }
        }
        if (badgeEl) {
            badgeEl.hidden = !info.hasUpdate;
        }
    }

    function setButtonStates(info) {
        const dlBtn = document.getElementById('download-update-btn');
        const applyBtn = document.getElementById('apply-update-btn');
        const phase = info?.download?.phase || 'IDLE';
        if (dlBtn) {
            dlBtn.disabled = !info?.hasUpdate || phase !== 'IDLE';
        }
        if (applyBtn) {
            applyBtn.hidden = phase !== 'READY';
        }
    }

    function startDownloadPoll() {
        if (downloadPollTimer) return;
        downloadPollTimer = setInterval(async () => {
            try {
                const r = await fetch('/api/admin/update-info');
                const info = await r.json();
                updateProgressUI(info);
                setButtonStates(info);
                if (info.download?.phase !== 'DOWNLOADING') {
                    clearInterval(downloadPollTimer);
                    downloadPollTimer = null;
                    if (info.download?.phase === 'READY') {
                        showUpdateStatus('Download complete. Click "Apply Update & Restart" to install.', 'ok');
                    } else if (info.download?.phase === 'IDLE' && info.lastCheckError) {
                        showUpdateStatus('Download failed: ' + info.lastCheckError, 'error');
                        document.getElementById('download-update-btn').disabled = false;
                    }
                }
            } catch (_) {}
        }, 1000);
    }

    // Initial state
    fetch('/api/admin/update-info').then(r => r.json()).then(info => {
        updateProgressUI(info);
        setButtonStates(info);
        if (info.download?.phase === 'DOWNLOADING') startDownloadPoll();
    }).catch(() => {});

    const checkBtn = document.getElementById('check-update-btn');
    if (checkBtn) {
        checkBtn.addEventListener('click', async () => {
            checkBtn.disabled = true;
            showUpdateStatus('Checking for updates…', 'ok');
            try {
                const r = await fetch('/api/admin/check-update', { method: 'POST' });
                const info = await r.json();
                setButtonStates(info);
                updateLatestVersionUI(info);
                if (info.lastCheckError) {
                    showUpdateStatus('Check failed: ' + info.lastCheckError, 'error');
                } else if (info.hasUpdate) {
                    showUpdateStatus('Update available: v' + info.latestVersion, 'warn');
                } else {
                    showUpdateStatus('You are up to date (v' + info.currentVersion + ').', 'ok');
                }
            } catch (err) {
                showUpdateStatus('Error: ' + err.message, 'error');
            } finally {
                checkBtn.disabled = false;
            }
        });
    }

    const dlBtn = document.getElementById('download-update-btn');
    if (dlBtn) {
        dlBtn.addEventListener('click', async () => {
            dlBtn.disabled = true;
            showUpdateStatus('Starting download…', 'ok');
            try {
                const r = await fetch('/api/admin/download-update', { method: 'POST' });
                if (r.status === 409) {
                    const body = await r.json().catch(() => ({}));
                    if (body.status === 'already-downloading') {
                        showUpdateStatus('Download already in progress.', 'warn');
                        startDownloadPoll();
                    } else {
                        showUpdateStatus('Not supported on this install type.', 'error');
                        dlBtn.disabled = false;
                    }
                } else {
                    startDownloadPoll();
                }
            } catch (err) {
                showUpdateStatus('Error: ' + err.message, 'error');
                dlBtn.disabled = false;
            }
        });
    }

    const applyBtn = document.getElementById('apply-update-btn');
    if (applyBtn) {
        applyBtn.addEventListener('click', async () => {
            applyBtn.disabled = true;
            showUpdateStatus('Applying update and restarting…', 'ok');
            try {
                await fetch('/api/admin/apply-update', { method: 'POST' });
                showUpdateStatus('Restarting… reconnecting when ready.', 'ok');
                setTimeout(attemptReconnect, 2000);
            } catch (err) {
                showUpdateStatus('Error: ' + err.message, 'error');
            }
        });
    }

    const restartBtn = document.getElementById('restart-btn');
    if (restartBtn) {
        restartBtn.addEventListener('click', async () => {
            restartBtn.disabled = true;
            showUpdateStatus('Restarting app…', 'ok');
            try {
                await fetch('/api/admin/restart', { method: 'POST' });
                showUpdateStatus('Restarting… reconnecting when ready.', 'ok');
                setTimeout(attemptReconnect, 2000);
            } catch (err) {
                showUpdateStatus('Restart signal sent. Reconnecting…', 'ok');
                setTimeout(attemptReconnect, 2000);
            }
        });
    }
}
