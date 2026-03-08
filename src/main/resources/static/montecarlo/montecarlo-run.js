// ── montecarlo-run.js — Form collection, run handler, date helpers ────────────
// Depends on: backtest-blocks.js, montecarlo-chart.js

function collectMcRequest() {
    const fromDate = document.getElementById('mc-from-date').value;
    const toDate   = document.getElementById('mc-to-date').value;
    const minChunkYears  = parseFloat(document.getElementById('mc-min-chunk').value)  || 3;
    const maxChunkYears  = parseFloat(document.getElementById('mc-max-chunk').value)  || 8;
    const simulatedYears = parseInt(document.getElementById('mc-sim-years').value, 10) || 20;
    const numSimulations = parseInt(document.getElementById('mc-num-sims').value, 10)  || 500;

    const portfolios = collectAllPortfolios();

    const sortMetric = document.getElementById('mc-sort-metric').value;
    return { fromDate: fromDate || null, toDate: toDate || null,
             minChunkYears, maxChunkYears, simulatedYears, numSimulations, sortMetric, portfolios };
}

function initMcRunButton() {
    const runBtn = document.getElementById('run-mc-btn');
    const progressEl = document.getElementById('mc-progress');
    let pollInterval = null;

    function startPolling(total) {
        progressEl.textContent = `0/${total}`;
        progressEl.style.display = '';
        pollInterval = setInterval(async () => {
            try {
                const r = await fetch('/api/montecarlo/progress');
                const p = await r.json();
                progressEl.textContent = `${p.completed}/${p.total}`;
            } catch (_) {}
        }, 300);
    }

    function stopPolling() {
        clearInterval(pollInterval);
        pollInterval = null;
        progressEl.style.display = 'none';
    }

    runBtn.addEventListener('click', async () => {
        document.getElementById('error-msg').style.display = 'none';
        document.getElementById('error-msg').textContent = '';

        const reqBody = collectMcRequest();
        if (reqBody.portfolios.length === 0) {
            showError('Add at least one ticker with a positive weight to any portfolio block.');
            return;
        }
        if (reqBody.portfolios.some(p => !p.includeNoMargin && p.marginStrategies.length === 0)) {
            showError('Each portfolio must have Unlevered enabled or at least one margin row.');
            return;
        }

        runBtn.disabled = true;
        runBtn.textContent = 'Running\u2026';
        startPolling(reqBody.numSimulations);

        try {
            const res = await fetch('/api/montecarlo/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(reqBody)
            });
            const data = await res.json();
            if (!res.ok || data.error) {
                showError(data.error || `Server error ${res.status}`);
                return;
            }
            resetMcCurveSelection();
            renderMcResults(data, reqBody.sortMetric);
        } catch (e) {
            showError('Request failed: ' + e.message);
        } finally {
            stopPolling();
            runBtn.disabled = false;
            runBtn.textContent = 'Run Simulation';
        }
    });
}

// ── Date quick-selectors ──────────────────────────────────────────────────────
// setDateYearsAgo and updateDateClearBtns live in backtest-blocks.js

function initMcDateClearBtns() {
    document.querySelectorAll('.date-clear-btn').forEach(btn => {
        const input = document.getElementById(btn.dataset.target);
        if (!input) return;
        input.addEventListener('change', updateDateClearBtns);
        btn.addEventListener('click', () => { input.value = ''; updateDateClearBtns(); });
    });
}

// ── Import / Export config ────────────────────────────────────────────────────

function generateMcConfigCode() { return btoa(JSON.stringify(collectMcRequest())); }

function showMcConfigError(msg) {
    const el = document.getElementById('mc-config-error');
    el.textContent = msg;
    setTimeout(() => { el.textContent = ''; }, 3000);
}

function applyMcConfigCode(code) {
    try {
        const req = JSON.parse(atob(code));
        if (req.fromDate) document.getElementById('mc-from-date').value = req.fromDate;
        if (req.toDate)   document.getElementById('mc-to-date').value   = req.toDate;
        updateDateClearBtns();
        if (req.portfolios) req.portfolios.forEach((p, i) => {
            if (i < 3) loadPortfolioIntoBlock(i, p, p.label || '');
        });
        if (req.minChunkYears  != null) document.getElementById('mc-min-chunk').value   = req.minChunkYears;
        if (req.maxChunkYears  != null) document.getElementById('mc-max-chunk').value   = req.maxChunkYears;
        if (req.simulatedYears != null) document.getElementById('mc-sim-years').value   = req.simulatedYears;
        if (req.numSimulations != null) document.getElementById('mc-num-sims').value    = req.numSimulations;
        if (req.sortMetric     != null) document.getElementById('mc-sort-metric').value = req.sortMetric;
    } catch (_) {
        showMcConfigError('Invalid config code.');
    }
}

function initMcImportExport() {
    document.getElementById('mc-export-btn').addEventListener('click', () => {
        const code = generateMcConfigCode();
        document.getElementById('mc-import-code').value = code;
        navigator.clipboard.writeText(code).then(() => {
            const btn = document.getElementById('mc-export-btn');
            const orig = btn.textContent;
            btn.textContent = 'Copied!';
            setTimeout(() => { btn.textContent = orig; }, 1500);
        }).catch(() => {});
    });
    document.getElementById('mc-import-btn').addEventListener('click', () => {
        const code = document.getElementById('mc-import-code').value.trim();
        if (code) applyMcConfigCode(code);
    });
}

function initMcDateQuickSelectors() {
    const fromQuick = document.getElementById('mc-from-date-quick');
    const toQuick   = document.getElementById('mc-to-date-quick');
    if (fromQuick) {
        fromQuick.addEventListener('change', e => {
            if (!e.target.value) return;
            setDateYearsAgo('mc-from-date', parseInt(e.target.value));
            e.target.value = '';
            updateDateClearBtns();
        });
    }
    if (toQuick) {
        toQuick.addEventListener('change', e => {
            if (!e.target.value) return;
            setDateYearsAgo('mc-to-date', parseInt(e.target.value));
            e.target.value = '';
            updateDateClearBtns();
        });
    }
}
