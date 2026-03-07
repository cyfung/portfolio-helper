// ── backtest-blocks.js — Portfolio block UI: ticker rows, margin rows, init ───

var PALETTE = [
    ['#1a6bc7', '#5599e8', '#99c3f5'],  // blues   (Portfolio 1)
    ['#c75d1a', '#e88a55', '#f5b899'],  // oranges (Portfolio 2)
    ['#1a8a5c', '#55b388', '#99d4bb'],  // greens  (Portfolio 3)
];

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
              <option value="WATERFALL">Waterfall</option>
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
        <input type="text" class="mc-ratio"     value="${ratio}"    title="Margin % of Equity"   placeholder="%" />
        <input type="text" class="mc-spread"    value="${spread}"   title="Spread % (annualised)" placeholder="%" />
        <input type="text" class="mc-dev-upper" value="${devUpper}" title="Upper Deviation %"     placeholder="%" />
        <input type="text" class="mc-dev-lower" value="${devLower}" title="Lower Deviation %"     placeholder="%" />
        ${modeSelectHtml('mc-mode-upper', 'Upper breach rebalance mode')}
        ${modeSelectHtml('mc-mode-lower', 'Lower breach rebalance mode')}
        <button type="button" class="remove-margin-btn" title="Remove">✕</button>
    `;
    row.querySelector('.mc-mode-upper').value = upperMode;
    row.querySelector('.mc-mode-lower').value = lowerMode;
    row.querySelector('.remove-margin-btn').addEventListener('click', () => row.remove());

    const handle = document.createElement('span');
    handle.className = 'margin-drag-handle';
    handle.textContent = '⠿';
    handle.draggable = true;
    handle.addEventListener('dragstart', e => {
        const cfg = {
            ratio:     row.querySelector('.mc-ratio').value,
            spread:    row.querySelector('.mc-spread').value,
            devUpper:  row.querySelector('.mc-dev-upper').value,
            devLower:  row.querySelector('.mc-dev-lower').value,
            modeUpper: row.querySelector('.mc-mode-upper').value,
            modeLower: row.querySelector('.mc-mode-lower').value,
        };
        e.dataTransfer.setData('application/x-margin-config', JSON.stringify(cfg));
        e.dataTransfer.effectAllowed = 'copy';
    });
    row.prepend(handle);

    marginRowsEl.appendChild(row);
}

// ── Block config helpers ──────────────────────────────────────────────────────

function collectBlockConfig(blockIdx) {
    const block = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
    const tickers = [...block.querySelectorAll('.backtest-ticker-row')].map(row => ({
        ticker: row.querySelector('.ticker-input').value.trim().toUpperCase(),
        weight: parseFloat(row.querySelector('.weight-input').value) || 0
    })).filter(t => t.ticker);
    const rebalanceStrategy = block.querySelector('.rebalance-select').value;
    const marginStrategies = [...block.querySelectorAll('.margin-config-row')].map(row => ({
        marginRatio:          (parseFloat(row.querySelector('.mc-ratio').value)     || 0)   / 100,
        marginSpread:         (parseFloat(row.querySelector('.mc-spread').value)    || 1.5) / 100,
        marginDeviationUpper: (parseFloat(row.querySelector('.mc-dev-upper').value) || 5)   / 100,
        marginDeviationLower: (parseFloat(row.querySelector('.mc-dev-lower').value) || 5)   / 100,
        upperRebalanceMode: row.querySelector('.mc-mode-upper').value,
        lowerRebalanceMode: row.querySelector('.mc-mode-lower').value
    }));
    const includeNoMargin = block.querySelector('.include-no-margin-btn').dataset.include === 'true';
    return { tickers, rebalanceStrategy, marginStrategies, includeNoMargin };
}

function loadPortfolioIntoBlock(blockIdx, config, name) {
    const block = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
    const labelInput = block.querySelector('.portfolio-label');
    if (name != null) labelInput.value = name;
    block.querySelector('.ticker-rows').innerHTML = '';
    (config.tickers || []).forEach(t => addTickerRow(blockIdx, t.ticker, t.weight));
    block.querySelector('.rebalance-select').value = config.rebalanceStrategy || 'YEARLY';
    block.querySelector('.margin-config-rows').innerHTML = '';
    const noMarginBtn = block.querySelector('.include-no-margin-btn');
    if (noMarginBtn) {
        const include = config.includeNoMargin !== false;
        noMarginBtn.dataset.include = include;
        noMarginBtn.textContent = include ? 'Unlevered: On' : 'Unlevered: Off';
    }
    const r = v => Math.round(v * 10000) / 100;
    (config.marginStrategies || []).forEach(m =>
        addMarginRow(blockIdx, r(m.marginRatio), r(m.marginSpread),
            r(m.marginDeviationUpper), r(m.marginDeviationLower),
            m.upperRebalanceMode || 'PROPORTIONAL', m.lowerRebalanceMode || 'PROPORTIONAL'));
    updateSaveBtn(block);
}

function updateSaveBtn(block) {
    const val = block.querySelector('.portfolio-label').value.trim();
    block.querySelectorAll('.save-portfolio-btn, .overwrite-portfolio-btn').forEach(btn => {
        btn.disabled = !val;
    });
}

// ── Block initialisation ──────────────────────────────────────────────────────

function initBlock(blockIdx) {
    const block = document.querySelector(`[data-portfolio-index="${blockIdx}"]`);
    const tickerRowsEl = block.querySelector('.ticker-rows');
    const addTickerBtn = block.querySelector('.add-ticker-btn');
    const addMarginBtn = block.querySelector('.add-margin-btn');
    const labelInput = block.querySelector('.portfolio-label');

    async function handleSave(btn, overwrite) {
        const name = labelInput.value.trim();
        if (!name) return;
        const config = collectBlockConfig(blockIdx);
        if (overwrite) {
            await fetch(`/api/backtest/savedPortfolios?name=${encodeURIComponent(name)}`, { method: 'DELETE' });
        }
        const res = await fetch('/api/backtest/savedPortfolios', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, config })
        });
        if (res.ok) {
            refreshSavedPortfolios();
            const original = btn.textContent;
            btn.textContent = 'Saved!';
            setTimeout(() => { btn.textContent = original; }, 1500);
        }
    }

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

    const noMarginBtn = block.querySelector('.include-no-margin-btn');
    if (noMarginBtn) {
        noMarginBtn.addEventListener('click', () => {
            const include = noMarginBtn.dataset.include !== 'true';
            noMarginBtn.dataset.include = include;
            noMarginBtn.textContent = include ? 'Unlevered: On' : 'Unlevered: Off';
        });
    }
    labelInput.addEventListener('input', () => updateSaveBtn(block));

    block.querySelectorAll('.save-portfolio-btn, .overwrite-portfolio-btn').forEach(btn => {
        btn.addEventListener('click', () => handleSave(btn, btn.classList.contains('overwrite-portfolio-btn')));
    });

    block.querySelector('.clear-portfolio-btn').addEventListener('click', () => {
        labelInput.value = '';
        block.querySelector('.ticker-rows').innerHTML = '';
        block.querySelector('.margin-config-rows').innerHTML = '';
        block.querySelector('.rebalance-select').value = 'YEARLY';
        updateWeightHint(blockIdx);
        updateSaveBtn(block);
    });

    // Drag-and-drop: accept chips or margin rows dropped onto this block
    block.addEventListener('dragover', e => {
        const isChip   = e.dataTransfer.types.includes('text/plain')
                         && document.querySelector('.saved-portfolio-chip[draggable="true"]');
        const isMargin = e.dataTransfer.types.includes('application/x-margin-config');
        if (isChip || isMargin) {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'copy';
            if (isMargin) block.classList.add('drag-over-margin');
            else          block.classList.add('drag-over');
        }
    });
    block.addEventListener('dragleave', () => block.classList.remove('drag-over', 'drag-over-margin'));
    block.addEventListener('drop', e => {
        e.preventDefault();
        block.classList.remove('drag-over', 'drag-over-margin');

        if (e.dataTransfer.types.includes('application/x-margin-config')) {
            const cfg = JSON.parse(e.dataTransfer.getData('application/x-margin-config'));
            addMarginRow(blockIdx,
                parseFloat(cfg.ratio),    parseFloat(cfg.spread),
                parseFloat(cfg.devUpper), parseFloat(cfg.devLower),
                cfg.modeUpper, cfg.modeLower);
            return;
        }

        const name = e.dataTransfer.getData('text/plain');
        const chip = document.querySelector(`.saved-portfolio-chip[data-name="${CSS.escape(name)}"]`);
        if (!chip) return;
        const config = JSON.parse(chip.dataset.config);
        loadPortfolioIntoBlock(blockIdx, config, name);
    });
}
