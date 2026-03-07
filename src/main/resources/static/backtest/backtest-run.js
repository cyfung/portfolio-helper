// ── backtest-run.js — Form collection, run handler, date helpers, import/export
// Depends on: backtest-blocks.js, backtest-chart.js

function collectRequest() {
    const fromDate = document.getElementById('from-date').value;
    const toDate   = document.getElementById('to-date').value;

    const portfolios = [0, 1, 2].map(i => {
        const block = document.querySelector(`[data-portfolio-index="${i}"]`);
        const label = block.querySelector('.portfolio-label').value.trim() || `Portfolio ${i + 1}`;
        const tickers = [...block.querySelectorAll('.backtest-ticker-row')].map(row => ({
            ticker: row.querySelector('.ticker-input').value.trim().toUpperCase(),
            weight: parseFloat(row.querySelector('.weight-input').value) || 0
        })).filter(t => t.ticker && t.weight > 0);
        const rebalanceStrategy = block.querySelector('.rebalance-select').value;
        const marginStrategies = [...block.querySelectorAll('.margin-config-row')].map(row => ({
            marginRatio:          (parseFloat(row.querySelector('.mc-ratio').value)     || 0)   / 100,
            marginSpread:         (parseFloat(row.querySelector('.mc-spread').value)    || 1.5) / 100,
            marginDeviationUpper: (parseFloat(row.querySelector('.mc-dev-upper').value) || 5)   / 100,
            marginDeviationLower: (parseFloat(row.querySelector('.mc-dev-lower').value) || 5)   / 100,
            upperRebalanceMode: row.querySelector('.mc-mode-upper').value,
            lowerRebalanceMode: row.querySelector('.mc-mode-lower').value
        }));
        return { label, tickers, rebalanceStrategy, marginStrategies, includeNoMargin: block.querySelector('.include-no-margin-btn').dataset.include === 'true' };
    }).filter(p => p.tickers.length > 0);

    return { fromDate: fromDate || null, toDate: toDate || null, portfolios };
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

function setDateYearsAgo(inputId, yearsAgo) {
    const date = new Date();
    date.setFullYear(date.getFullYear() - yearsAgo);
    document.getElementById(inputId).value = date.toISOString().split('T')[0];
}

function updateDateClearBtns() {
    document.querySelectorAll('.date-clear-btn').forEach(btn => {
        const input = document.getElementById(btn.dataset.target);
        if (input) btn.style.visibility = input.value ? 'visible' : 'hidden';
    });
}

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
