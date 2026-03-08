const RESTART_KEYS = new Set(['bindHost', 'navUpdateInterval', 'dataDir']);

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();

    // Snapshot originals for restart detection
    document.querySelectorAll('[data-config-key]').forEach(el => {
        el.dataset.originalValue = el.type === 'checkbox' ? String(el.checked) : el.value;
    });

    document.getElementById('config-save-btn').addEventListener('click', async () => {
        const globalUpdates = {};
        const portfolioUpdates = {};  // { portfolioId: { twsAccount: "..." } }
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
});

function showStatus(msg, type) {
    const el = document.getElementById('config-status');
    el.textContent = msg;
    el.className = 'config-status config-status-' + type;
}
