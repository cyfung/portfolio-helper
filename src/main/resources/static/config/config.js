const RESTART_KEYS = new Set(['bindHost', 'navUpdateInterval', 'dataDir']);

const GLOBAL_DEFAULTS = {
    bindHost: 'localhost',
    openBrowser: 'true',
    dataDir: '',
    navUpdateInterval: '',
    exchangeSuffixes: 'SBF=.PA,LSEETF=.L',
    twsHost: '127.0.0.1',
    twsPort: '7496',
    ibkrRateInterval: '3600'
};

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();

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

function showStatus(msg, type) {
    const el = document.getElementById('config-status');
    el.textContent = msg;
    el.className = 'config-status config-status-' + type;
}
