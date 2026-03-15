// ── cash.js — Cash table structure rebuild and portfolio-ref updates ──────────
// Depends on: utils.js, ui-helpers.js, rebalance.js
// Note: cash totals, margin display, and IBKR interest are computed inside
// display-worker.js and applied via _applyDisplayResult().

function buildIbkrRatesTable(data) {
    const wrapper = document.querySelector('.ibkr-rates-wrapper');
    if (!wrapper) return;

    // Remove existing rate table if any (rebuild on rate update)
    const existing = wrapper.querySelector('.ibkr-rates-table');
    if (existing) existing.remove();
    const existingSummary = wrapper.querySelector('.ibkr-interest-summary');
    if (existingSummary) existingSummary.remove();

    if (!data.currencies || data.currencies.length === 0) return;

    // Always include USD; add other currencies that have actual margin loans
    const marginCurrencies = new Set(['USD']);
    document.querySelectorAll('[data-cash-entry][data-margin-flag="true"]').forEach(row => {
        const ccy = row.dataset.currency?.toUpperCase();
        if (ccy && ccy !== 'P') marginCurrencies.add(ccy);
    });

    const filteredCurrencies = data.currencies.filter(c =>
        marginCurrencies.has(c.currency.toUpperCase())
    );

    if (filteredCurrencies.length === 0) return;

    // Build rates table
    const table = document.createElement('table');
    table.className = 'ibkr-rates-table';
    const thead = document.createElement('thead');
    thead.innerHTML = '<tr><th>CCY</th><th>IBKR Pro Rate</th></tr>';
    const tbody = document.createElement('tbody');

    filteredCurrencies.forEach(c => {
        const tr = document.createElement('tr');
        tr.setAttribute('data-ibkr-rate', '0');
        tr.setAttribute('data-ibkr-days', c.days);
        tr.setAttribute('data-ibkr-tiers', JSON.stringify(c.tiers));
        tr.innerHTML = `<td class="ibkr-rate-currency">${c.currency}</td>
                        <td class="ibkr-rate-value">${c.baseRate.toFixed(3)}%</td>`;
        tbody.appendChild(tr);
    });

    table.appendChild(thead);
    table.appendChild(tbody);

    // Build interest summary table
    const summary = document.createElement('table');
    summary.className = 'ibkr-interest-summary';
    summary.innerHTML = `<tbody>
        <tr><td>Current Daily Interest</td><td id="ibkr-current-interest" class="ibkr-value-muted">—</td></tr>
        <tr><td>Cheapest <span id="ibkr-cheapest-ccy"></span></td><td id="ibkr-cheapest-interest" class="ibkr-value-muted">—</td></tr>
        <tr><td id="ibkr-saving-label">Saving</td><td id="ibkr-interest-diff">—</td></tr>
    </tbody>`;

    // Insert before footer
    const footer = wrapper.querySelector('.ibkr-rates-footer');
    wrapper.insertBefore(table, footer);
    wrapper.insertBefore(summary, footer);

    // Update last fetch timestamp
    const fetchEl = wrapper.querySelector('.ibkr-last-fetch');
    if (fetchEl && data.lastFetch > 0) {
        fetchEl.id = 'ibkr-last-fetch';
        fetchEl.textContent = new Date(data.lastFetch).toLocaleTimeString();
    }

    // Update reload button data-last-fetch
    const reloadBtn = wrapper.querySelector('.ibkr-reload-btn');
    if (reloadBtn) reloadBtn.dataset.lastFetch = data.lastFetch;
}

function updatePortfolioRefValues(portfolioId, newPortfolioValue) {
    let updated = false;
    document.querySelectorAll(`[data-portfolio-ref="${portfolioId}"]`).forEach(row => {
        const mult = parseFloat(row.dataset.portfolioMultiplier || '1');
        const newAmount = mult * newPortfolioValue;
        row.dataset.amount = newAmount.toString();
        const rawCol = row.querySelector('.cash-raw-col');
        if (rawCol) rawCol.textContent = formatCurrency(Math.abs(newAmount)) + ' USD';
        updated = true;
    });
    if (updated) { scheduleDisplayUpdate(); }
}
