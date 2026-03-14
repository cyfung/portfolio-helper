const RESTART_KEYS = new Set(['navUpdateInterval']);

const GLOBAL_DEFAULTS = {
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
    await loadPairedDevices();
}

function initPairing() {
    const container = document.getElementById('pairing-pin-display');
    if (!container) return;

    // Auto-generate a PIN on page load so the user can immediately pair
    generateAndShowPin(container);

    container.addEventListener('click', async (e) => {
        if (e.target.id === 'generate-pin-btn') {
            generateAndShowPin(container);
        }
    });
}

async function generateAndShowPin(container) {
    try {
        container.innerHTML = `<span class="config-env-override-note">Generating…</span>`;
        const r = await fetch('/api/pairing/generate', { method: 'POST' });
        const { pin } = await r.json();
        container.innerHTML = `
            <p class="pairing-pin-hint">Show this PIN on-screen. Enter it in the Android app to pair.</p>
            <div class="pin-number-display">${pin}</div>
            <p class="pairing-pin-hint">Expires in 5 minutes.</p>
            <button class="config-restore-btn" id="generate-pin-btn" type="button">Generate New PIN</button>
        `;
    } catch (err) {
        container.innerHTML = `
            <span class="config-env-override-note">Failed to generate PIN.</span>
            <button class="config-restore-btn" id="generate-pin-btn" type="button">Retry</button>
        `;
        console.error('Failed to generate PIN', err);
    }
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
