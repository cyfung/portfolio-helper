// ── montecarlo-run.js — Form collection, run handler, date helpers ────────────
// Depends on: backtest-blocks.js, montecarlo-chart.js

function collectMcRequest() {
    const fromDate = document.getElementById('mc-from-date').value;
    const toDate   = document.getElementById('mc-to-date').value;
    const minChunkYears  = parseFloat(document.getElementById('mc-min-chunk').value)  || 3;
    const maxChunkYears  = parseFloat(document.getElementById('mc-max-chunk').value)  || 8;
    const simulatedYears = parseInt(document.getElementById('mc-sim-years').value, 10) || 20;
    const numSimulations = parseInt(document.getElementById('mc-num-sims').value, 10)  || 500;

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
        return { label, tickers, rebalanceStrategy, marginStrategies };
    }).filter(p => p.tickers.length > 0);

    return { fromDate: fromDate || null, toDate: toDate || null,
             minChunkYears, maxChunkYears, simulatedYears, numSimulations, portfolios };
}

function initMcRunButton() {
    const runBtn = document.getElementById('run-mc-btn');

    runBtn.addEventListener('click', async () => {
        document.getElementById('error-msg').style.display = 'none';
        document.getElementById('error-msg').textContent = '';

        const reqBody = collectMcRequest();
        if (reqBody.portfolios.length === 0) {
            showError('Add at least one ticker with a positive weight to any portfolio block.');
            return;
        }

        runBtn.disabled = true;
        runBtn.textContent = 'Running\u2026';

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
            renderMcResults(data);
        } catch (e) {
            showError('Request failed: ' + e.message);
        } finally {
            runBtn.disabled = false;
            runBtn.textContent = 'Run Simulation';
        }
    });
}

// ── Date quick-selectors (mirrors backtest-run.js) ────────────────────────────

function mcSetDateYearsAgo(inputId, yearsAgo) {
    const date = new Date();
    date.setFullYear(date.getFullYear() - yearsAgo);
    document.getElementById(inputId).value = date.toISOString().split('T')[0];
}

function mcUpdateDateClearBtns() {
    document.querySelectorAll('.date-clear-btn').forEach(btn => {
        const input = document.getElementById(btn.dataset.target);
        if (input) btn.style.visibility = input.value ? 'visible' : 'hidden';
    });
}

function initMcDateClearBtns() {
    document.querySelectorAll('.date-clear-btn').forEach(btn => {
        const input = document.getElementById(btn.dataset.target);
        if (!input) return;
        input.addEventListener('change', mcUpdateDateClearBtns);
        btn.addEventListener('click', () => { input.value = ''; mcUpdateDateClearBtns(); });
    });
}

function initMcDateQuickSelectors() {
    const fromQuick = document.getElementById('mc-from-date-quick');
    const toQuick   = document.getElementById('mc-to-date-quick');
    if (fromQuick) {
        fromQuick.addEventListener('change', e => {
            if (!e.target.value) return;
            mcSetDateYearsAgo('mc-from-date', parseInt(e.target.value));
            e.target.value = '';
            mcUpdateDateClearBtns();
        });
    }
    if (toQuick) {
        toQuick.addEventListener('change', e => {
            if (!e.target.value) return;
            mcSetDateYearsAgo('mc-to-date', parseInt(e.target.value));
            e.target.value = '';
            mcUpdateDateClearBtns();
        });
    }
}
