// ── backtest-run.js — Form collection, run handler, date helpers, import/export
// Depends on: backtest-blocks.js, backtest-chart.js

function collectRequest() {
    const fromDate = document.getElementById('from-date').value;
    const toDate   = document.getElementById('to-date').value;
    return { fromDate: fromDate || null, toDate: toDate || null, portfolios: collectAllPortfolios() };
}

function initRunButton() {
    const runBtn = document.getElementById('run-backtest-btn');

    runBtn.addEventListener('click', async () => {
        document.getElementById('error-msg').style.display = 'none';
        document.getElementById('error-msg').textContent = '';

        const reqBody = collectRequest();
        if (reqBody.portfolios.length === 0) {
            showError('Add at least one ticker with a positive weight to any portfolio block.');
            return;
        }
        if (reqBody.portfolios.some(p => !p.includeNoMargin && p.marginStrategies.length === 0)) {
            showError('Each portfolio must have Unlevered enabled or at least one margin row.');
            return;
        }

        runBtn.disabled = true;
        runBtn.textContent = 'Running…';

        try {
            const res = await fetch('/api/backtest/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(reqBody)
            });
            const data = await res.json();
            if (!res.ok || data.error) {
                showError(data.error || `Server error ${res.status}`);
                return;
            }
            resetCurveSelection();
            renderChart(data);
            renderStats(data);
        } catch (e) {
            showError('Request failed: ' + e.message);
        } finally {
            runBtn.disabled = false;
            runBtn.textContent = 'Run Backtest';
        }
    });
}

// ── Date quick-selectors ──────────────────────────────────────────────────────
// setDateYearsAgo and updateDateClearBtns live in backtest-blocks.js

function initDateClearBtns() {
    document.querySelectorAll('.date-clear-btn').forEach(btn => {
        const input = document.getElementById(btn.dataset.target);
        input.addEventListener('change', updateDateClearBtns);
        btn.addEventListener('click', () => { input.value = ''; updateDateClearBtns(); });
    });
}

function initDateQuickSelectors() {
    document.getElementById('from-date-quick').addEventListener('change', e => {
        if (e.target.value === '') return;
        setDateYearsAgo('from-date', parseInt(e.target.value));
        e.target.value = '';
        updateDateClearBtns();
    });

    document.getElementById('to-date-quick').addEventListener('change', e => {
        if (e.target.value === '') return;
        setDateYearsAgo('to-date', parseInt(e.target.value));
        e.target.value = '';
        updateDateClearBtns();
    });
}

// ── Import / Export config ────────────────────────────────────────────────────

function generateConfigCode() {
    return btoa(JSON.stringify(collectRequest()));
}

function showConfigError(msg) {
    const el = document.getElementById('backtest-config-error');
    el.textContent = msg;
    setTimeout(() => { el.textContent = ''; }, 3000);
}

function applyConfigCode(code) {
    try {
        const req = JSON.parse(atob(code));
        if (req.fromDate) document.getElementById('from-date').value = req.fromDate;
        if (req.toDate)   document.getElementById('to-date').value   = req.toDate;
        updateDateClearBtns();
        if (req.portfolios) {
            req.portfolios.forEach((p, i) => {
                if (i >= 3) return;
                loadPortfolioIntoBlock(i, p, p.label || '');
            });
        }
    } catch (_) {
        showConfigError('Invalid config code.');
    }
}

function initImportExport() {
    document.getElementById('backtest-export-btn').addEventListener('click', () => {
        const code = generateConfigCode();
        document.getElementById('backtest-import-code').value = code;
        navigator.clipboard.writeText(code).then(() => {
            const btn = document.getElementById('backtest-export-btn');
            const original = btn.textContent;
            btn.textContent = 'Copied!';
            setTimeout(() => { btn.textContent = original; }, 1500);
        });
    });

    document.getElementById('backtest-import-btn').addEventListener('click', () => {
        const code = document.getElementById('backtest-import-code').value.trim();
        if (code) applyConfigCode(code);
    });
}
