// ── Theme ─────────────────────────────────────────────────────────────────────

(function () {
    const stored = localStorage.getItem('ib-viewer-theme');
    const theme = stored || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', theme);
})();

// Script is loaded with defer — DOM is fully parsed when this runs.
{
    const themeToggle = document.getElementById('theme-toggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const current = document.documentElement.getAttribute('data-theme');
            const next = current === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('ib-viewer-theme', next);
        });
    }

    // ── Color palette ─────────────────────────────────────────────────────────
    // PALETTE[portfolioIndex][curveIndex % 3]

    const PALETTE = [
        ['#1a6bc7', '#5599e8', '#99c3f5'],  // blues   (Portfolio 1)
        ['#c75d1a', '#e88a55', '#f5b899'],  // oranges (Portfolio 2)
        ['#1a8a5c', '#55b388', '#99d4bb'],  // greens  (Portfolio 3)
    ];

    // ── Block helpers (module-level so restoreSettings can call them directly) ─

    function updateWeightHint(blockIdx) {
        const blockEl = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
        const tickerRowsEl = blockEl.querySelector('.ticker-rows');
        const weightHint = blockEl.querySelector('.backtest-weight-hint');
        const rows = tickerRowsEl.querySelectorAll('.backtest-ticker-row');
        let total = 0;
        rows.forEach(r => {
            const v = parseFloat(r.querySelector('.weight-input').value) || 0;
            total += v;
        });
        const diff = Math.round((total - 100) * 100) / 100;
        if (rows.length === 0) {
            weightHint.textContent = '';
            weightHint.className = 'backtest-weight-hint';
        } else if (Math.abs(diff) < 0.001) {
            weightHint.textContent = 'Total: 100% ✓';
            weightHint.className = 'backtest-weight-hint hint-ok';
        } else {
            weightHint.textContent = `Total: ${total.toFixed(2)}% (${diff > 0 ? '+' : ''}${diff.toFixed(2)}%)`;
            weightHint.className = 'backtest-weight-hint hint-warn';
        }
    }

    function modeSelectHtml(cls, titleStr) {
        return `<select class="mc-mode ${cls}" title="${titleStr}">
              <option value="PROPORTIONAL">Target Weight</option>
              <option value="CURRENT_WEIGHT">Current Weight</option>
              <option value="FULL_REBALANCE">Full Rebal</option>
              <option value="UNDERVALUED_PRIORITY">Underval First</option>
              <option value="DAILY">Daily</option>
            </select>`;
    }

    function addTickerRow(blockIdx, ticker = '', weight = '') {
        const blockEl = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
        const tickerRowsEl = blockEl.querySelector('.ticker-rows');
        const row = document.createElement('div');
        row.className = 'backtest-ticker-row';
        row.innerHTML = `
                <input type="text" class="ticker-input" placeholder='e.g. VT or: 1 KMLM 1 VT S=1.5' value="${ticker}" />
                <input type="text" class="weight-input" placeholder="Weight %" value="${weight}" />
                <span class="weight-unit">%</span>
                <button type="button" class="remove-ticker-btn" title="Remove">✕</button>
            `;
        row.querySelector('.remove-ticker-btn').addEventListener('click', () => {
            row.remove();
            updateWeightHint(blockIdx);
        });
        row.querySelector('.weight-input').addEventListener('input', () => updateWeightHint(blockIdx));
        row.querySelector('.ticker-input').addEventListener('input', () => updateWeightHint(blockIdx));
        tickerRowsEl.appendChild(row);
        updateWeightHint(blockIdx);
    }

    function addMarginRow(blockIdx, ratio = 50, spread = 1.5, devUpper = 5, devLower = 5, upperMode = 'PROPORTIONAL', lowerMode = 'PROPORTIONAL') {
        const blockEl = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
        const marginRowsEl = blockEl.querySelector('.margin-config-rows');
        const row = document.createElement('div');
        row.className = 'margin-config-row';
        row.innerHTML = `
                <input type="text" class="mc-ratio" value="${ratio}" title="Margin % of Equity" placeholder="%" />
                <input type="text" class="mc-spread" value="${spread}" title="Spread % (annualised)" placeholder="%" />
                <input type="text" class="mc-dev-upper" value="${devUpper}" title="Upper Deviation %" placeholder="%" />
                <input type="text" class="mc-dev-lower" value="${devLower}" title="Lower Deviation %" placeholder="%" />
                ${modeSelectHtml('mc-mode-upper', 'Upper breach rebalance mode')}
                ${modeSelectHtml('mc-mode-lower', 'Lower breach rebalance mode')}
                <button type="button" class="remove-margin-btn" title="Remove">✕</button>
            `;
        row.querySelector('.mc-mode-upper').value = upperMode;
        row.querySelector('.mc-mode-lower').value = lowerMode;
        row.querySelector('.remove-margin-btn').addEventListener('click', () => row.remove());
        marginRowsEl.appendChild(row);
    }

    // ── Block config helpers ──────────────────────────────────────────────────

    function collectBlockConfig(blockIdx) {
        const block = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
        const tickers = [...block.querySelectorAll('.backtest-ticker-row')].map(row => ({
            ticker: row.querySelector('.ticker-input').value.trim().toUpperCase(),
            weight: parseFloat(row.querySelector('.weight-input').value) || 0
        })).filter(t => t.ticker);
        const rebalanceStrategy = block.querySelector('.rebalance-select').value;
        const marginStrategies = [...block.querySelectorAll('.margin-config-row')].map(row => ({
            marginRatio: (parseFloat(row.querySelector('.mc-ratio').value) || 0) / 100,
            marginSpread: (parseFloat(row.querySelector('.mc-spread').value) || 1.5) / 100,
            marginDeviationUpper: (parseFloat(row.querySelector('.mc-dev-upper').value) || 5) / 100,
            marginDeviationLower: (parseFloat(row.querySelector('.mc-dev-lower').value) || 5) / 100,
            upperRebalanceMode: row.querySelector('.mc-mode-upper').value,
            lowerRebalanceMode: row.querySelector('.mc-mode-lower').value
        }));
        return { tickers, rebalanceStrategy, marginStrategies };
    }

    function loadPortfolioIntoBlock(blockIdx, config, name) {
        const block = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
        const labelInput = block.querySelector('.portfolio-label');
        if (name != null) labelInput.value = name;
        block.querySelector('.ticker-rows').innerHTML = '';
        (config.tickers || []).forEach(t => addTickerRow(blockIdx, t.ticker, t.weight));
        block.querySelector('.rebalance-select').value = config.rebalanceStrategy || 'YEARLY';
        block.querySelector('.margin-config-rows').innerHTML = '';
        const r = v => Math.round(v * 10000) / 100;
        (config.marginStrategies || []).forEach(m =>
            addMarginRow(blockIdx, r(m.marginRatio), r(m.marginSpread),
                r(m.marginDeviationUpper), r(m.marginDeviationLower),
                m.upperRebalanceMode || 'PROPORTIONAL', m.lowerRebalanceMode || 'PROPORTIONAL'));
        updateSaveBtn(block);
    }

    function updateSaveBtn(block) {
        const btn = block.querySelector('.save-portfolio-btn');
        const val = block.querySelector('.portfolio-label').value.trim();
        btn.disabled = !val;
    }

    // ── Block initialisation ──────────────────────────────────────────────────

    function initBlock(blockIdx) {
        const block = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
        const tickerRowsEl = block.querySelector('.ticker-rows');
        const addTickerBtn = block.querySelector('.add-ticker-btn');
        const addMarginBtn = block.querySelector('.add-margin-btn');
        const labelInput = block.querySelector('.portfolio-label');
        const saveBtn = block.querySelector('.save-portfolio-btn');

        // Wire up listeners for any rows that were server-rendered into the HTML
        tickerRowsEl.querySelectorAll('.backtest-ticker-row').forEach(row => {
            row.querySelector('.remove-ticker-btn').addEventListener('click', () => {
                row.remove();
                updateWeightHint(blockIdx);
            });
            row.querySelector('.weight-input').addEventListener('input', () => updateWeightHint(blockIdx));
            row.querySelector('.ticker-input').addEventListener('input', () => updateWeightHint(blockIdx));
        });
        updateWeightHint(blockIdx);

        addTickerBtn.addEventListener('click', () => addTickerRow(blockIdx));
        addMarginBtn.addEventListener('click', () => addMarginRow(blockIdx));

        labelInput.addEventListener('input', () => updateSaveBtn(block));

        saveBtn.addEventListener('click', async () => {
            const name = labelInput.value.trim();
            if (!name) return;
            const config = collectBlockConfig(blockIdx);
            const res = await fetch('/api/backtest/savedPortfolios', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, config })
            });
            if (res.ok) refreshSavedPortfolios();
        });

        // Drag-and-drop: accept chips dropped onto this block
        block.addEventListener('dragover', e => {
            if (document.querySelector('.saved-portfolio-chip[draggable="true"]')) {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'copy';
                block.classList.add('drag-over');
            }
        });
        block.addEventListener('dragleave', () => block.classList.remove('drag-over'));
        block.addEventListener('drop', e => {
            e.preventDefault();
            block.classList.remove('drag-over');
            const name = e.dataTransfer.getData('text/plain');
            const chip = document.querySelector(`.saved-portfolio-chip[data-name="${CSS.escape(name)}"]`);
            if (!chip) return;
            const config = JSON.parse(chip.dataset.config);
            loadPortfolioIntoBlock(blockIdx, config, name);
        });
    }

    // Initialise all 3 blocks; block 0's rows are already in the HTML (server-rendered)
    [0, 1, 2].forEach(i => initBlock(i));

    // ── Saved portfolios bar ──────────────────────────────────────────────────

    async function refreshSavedPortfolios() {
        try {
            const res = await fetch('/api/backtest/savedPortfolios');
            if (!res.ok) return;
            const list = await res.json();
            renderSavedBar(list);
        } catch (_) { /* silently ignore */ }
    }

    function renderSavedBar(list) {
        const bar = document.getElementById('saved-portfolios-bar');
        bar.innerHTML = '';
        bar.style.display = list.length ? '' : 'none';
        list.forEach(p => {
            const chip = document.createElement('div');
            chip.className = 'saved-portfolio-chip';
            chip.draggable = true;
            chip.dataset.config = JSON.stringify(p.config);
            chip.dataset.name = p.name;

            const label = document.createElement('span');
            label.textContent = p.name;

            const del = document.createElement('button');
            del.className = 'saved-portfolio-chip-del';
            del.type = 'button';
            del.title = 'Delete';
            del.textContent = '✕';
            del.addEventListener('click', async e => {
                e.stopPropagation();
                await fetch(`/api/backtest/savedPortfolios?name=${encodeURIComponent(p.name)}`, { method: 'DELETE' });
                refreshSavedPortfolios();
            });

            chip.appendChild(label);
            chip.appendChild(del);
            chip.addEventListener('dragstart', e => {
                e.dataTransfer.setData('text/plain', p.name);
                e.dataTransfer.effectAllowed = 'copy';
            });
            bar.appendChild(chip);
        });
    }

    refreshSavedPortfolios();

    // ── Restore saved settings ────────────────────────────────────────────────

    (async function restoreSettings() {
        try {
            const res = await fetch('/api/backtest/settings');
            if (!res.ok) return;
            const req = await res.json();
            if (!req.portfolios) return;

            if (req.fromDate) document.getElementById('from-date').value = req.fromDate;
            if (req.toDate) document.getElementById('to-date').value = req.toDate;

            req.portfolios.forEach((p, i) => {
                if (i >= 3) return;
                loadPortfolioIntoBlock(i, p, p.label || '');
            });
        } catch (_) { /* silently ignore */ }
    })();

    // ── Form collection ───────────────────────────────────────────────────────

    function collectRequest() {
        const fromDate = document.getElementById('from-date').value;
        const toDate = document.getElementById('to-date').value;

        const portfolios = [0, 1, 2].map(i => {
            const block = document.querySelector(`[data-portfolio-index="${i}"]`);
            const label = block.querySelector('.portfolio-label').value.trim() || `Portfolio ${i + 1}`;
            const tickers = [...block.querySelectorAll('.backtest-ticker-row')].map(row => ({
                ticker: row.querySelector('.ticker-input').value.trim().toUpperCase(),
                weight: parseFloat(row.querySelector('.weight-input').value) || 0
            })).filter(t => t.ticker && t.weight > 0);
            const rebalanceStrategy = block.querySelector('.rebalance-select').value;
            const marginStrategies = [...block.querySelectorAll('.margin-config-row')].map(row => ({
                marginRatio: (parseFloat(row.querySelector('.mc-ratio').value) || 0) / 100,
                marginSpread: (parseFloat(row.querySelector('.mc-spread').value) || 1.5) / 100,
                marginDeviationUpper: (parseFloat(row.querySelector('.mc-dev-upper').value) || 5) / 100,
                marginDeviationLower: (parseFloat(row.querySelector('.mc-dev-lower').value) || 5) / 100,
                upperRebalanceMode: row.querySelector('.mc-mode-upper').value,
                lowerRebalanceMode: row.querySelector('.mc-mode-lower').value
            }));
            return { label, tickers, rebalanceStrategy, marginStrategies };
        }).filter(p => p.tickers.length > 0);

        return {
            fromDate: fromDate || null,
            toDate: toDate || null,
            portfolios
        };
    }

    // ── Run backtest ──────────────────────────────────────────────────────────

    const runBtn = document.getElementById('run-backtest-btn');
    const errorMsg = document.getElementById('error-msg');
    const chartContainer = document.getElementById('chart-container');
    const statsContainer = document.getElementById('stats-container');

    let chartInstance = null;

    runBtn.addEventListener('click', async () => {
        errorMsg.style.display = 'none';
        errorMsg.textContent = '';

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

    function showError(msg) {
        errorMsg.textContent = msg;
        errorMsg.style.display = '';
        chartContainer.style.display = 'none';
        statsContainer.style.display = 'none';
    }

    // ── Chart rendering ───────────────────────────────────────────────────────

    function renderChart(data) {
        chartContainer.style.display = '';

        if (chartInstance) {
            chartInstance.destroy();
            chartInstance = null;
        }

        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
        const textColor = isDark ? '#c0c0c0' : '#495057';

        // Build sorted intersection of dates across all portfolios so the chart
        // starts at the latest common start date and every curve has a value at
        // every x-axis position (no leading gaps from differing ticker histories).
        let commonDates = new Set(data.portfolios[0].curves[0].points.map(p => p.date));
        for (let i = 1; i < data.portfolios.length; i++) {
            const portfolioDates = new Set(data.portfolios[i].curves[0].points.map(p => p.date));
            for (const d of commonDates) {
                if (!portfolioDates.has(d)) commonDates.delete(d);
            }
        }
        const labels = [...commonDates].sort();

        // Map each dataset's values by date; use null for dates outside its range
        // so Chart.js auto-scales the y-axis correctly across all curves.
        const datasets = [];
        data.portfolios.forEach((portfolio, pi) => {
            const palette = PALETTE[pi % PALETTE.length];
            portfolio.curves.forEach((curve, ci) => {
                const valueMap = new Map(curve.points.map(p => [p.date, p.value]));
                datasets.push({
                    label: `${portfolio.label} \u2013 ${curve.label}`,
                    data: labels.map(d => valueMap.get(d) ?? null),
                    spanGaps: false,
                    borderColor: palette[ci % palette.length],
                    backgroundColor: 'transparent',
                    borderWidth: 1.5,
                    pointRadius: 0,
                    pointHoverRadius: 4
                });
            });
        });

        const ctx = document.getElementById('backtest-chart').getContext('2d');
        chartInstance = new Chart(ctx, {
            type: 'line',
            data: { labels, datasets },
            options: {
                animation: false,
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    mode: 'index',
                    intersect: false
                },
                plugins: {
                    legend: {
                        labels: { color: textColor }
                    },
                    tooltip: {
                        mode: 'index',
                        callbacks: {
                            title: items => items[0]?.label || '',
                            label: item => ` ${item.dataset.label}: $${item.raw.toFixed(2)}`
                        }
                    }
                },
                scales: {
                    x: {
                        ticks: {
                            color: textColor,
                            maxTicksLimit: 10,
                            maxRotation: 0
                        },
                        grid: { color: gridColor }
                    },
                    y: {
                        ticks: {
                            color: textColor,
                            callback: v => '$' + v.toFixed(0)
                        },
                        grid: { color: gridColor }
                    }
                }
            }
        });
    }

    // ── Stats table ───────────────────────────────────────────────────────────

    function renderStats(data) {
        statsContainer.style.display = '';

        function pct(v) { return (v * 100).toFixed(2) + '%'; }
        function fmt2(v) { return v.toFixed(2); }
        function money(v) { return '$' + v.toFixed(0); }
        function trig(v) { return v == null ? '\u2013' : v; }

        let html = '<table class="summary-table backtest-stats-table"><thead><tr>' +
            '<th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th><th>Sharpe</th>' +
            '<th title="Ulcer Index: RMS of drawdowns from peak">Ulcer</th>' +
            '<th title="Ulcer Performance Index (Martin Ratio): excess return / Ulcer Index">UPI</th>' +
            '<th title="Deviation triggers: ratio exceeded upper bound (market fell)">Upper \u2191</th>' +
            '<th title="Deviation triggers: ratio fell below lower bound (market rose)">Lower \u2193</th>' +
            '</tr></thead><tbody>';

        data.portfolios.forEach(portfolio => {
            portfolio.curves.forEach(curve => {
                const curveLabel = `${portfolio.label} \u2013 ${curve.label}`;
                const s = curve.stats;
                html += `<tr>` +
                    `<td>${curveLabel}</td>` +
                    `<td>${money(s.endingValue)}</td>` +
                    `<td>${pct(s.cagr)}</td>` +
                    `<td>${pct(s.maxDrawdown)}</td>` +
                    `<td>${fmt2(s.sharpe)}</td>` +
                    `<td>${pct(s.ulcerIndex)}</td>` +
                    `<td>${fmt2(s.upi)}</td>` +
                    `<td>${trig(s.marginUpperTriggers)}</td>` +
                    `<td>${trig(s.marginLowerTriggers)}</td>` +
                    `</tr>`;
            });
        });

        html += '</tbody></table>';
        statsContainer.innerHTML = html;
    }
}
