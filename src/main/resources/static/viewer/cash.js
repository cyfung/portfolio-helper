// ── cash.js — Cash totals, margin display, IBKR interest calculations ────────
// Depends on: utils.js, ui-helpers.js, rebalance.js

function updateMarginDisplay(marginUsd) {
    const marginRow = document.querySelector('[data-margin-row]');
    const marginEl = document.getElementById('margin-total-usd');
    if (!marginEl) return;

    if (marginUsd >= 0) {
        if (marginRow) marginRow.style.display = 'none';
        return;
    }
    if (marginRow) marginRow.style.display = '';

    if (!marginKnown) {
        marginEl.textContent = 'N/A';
        const marginPctEl = document.getElementById('margin-percent');
        if (marginPctEl) { marginPctEl.textContent = ''; marginPctEl.style.display = 'none'; }
        return;
    }

    marginEl.textContent = formatDisplayCurrency(-marginUsd);

    const marginPctEl = document.getElementById('margin-percent');
    const denominator = lastStockGrossVal + lastMarginUsd;
    const pct = denominator !== 0 ? Math.abs(marginUsd / denominator) * 100 : 0;
    if (marginPctEl) {
        marginPctEl.textContent = ' (' + pct.toFixed(1) + '%)';
        marginPctEl.style.display = '';
    }
}

/**
 * Compute blended IBKR rate (%) for a given native loan amount using tier data.
 * Returns null if amount is within the base tier (no blending needed).
 * tiers: [{upTo: number|null, rate: number}, ...]
 */
function blendedIbkrRate(tiers, amount) {
    if (amount <= 0 || !tiers.length) return null;
    const baseCap = tiers[0].upTo;
    if (baseCap === null || amount <= baseCap) return null;
    let remaining = amount, totalInterest = 0, prevUpTo = 0;
    for (const tier of tiers) {
        const capacity = tier.upTo !== null ? tier.upTo - prevUpTo : Infinity;
        const inTier = Math.min(remaining, capacity);
        totalInterest += inTier * tier.rate / 100;
        remaining -= inTier;
        if (remaining <= 0) break;
        prevUpTo = tier.upTo ?? 0;
    }
    return (totalInterest / amount) * 100;
}

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

function updateIbkrDailyInterest() {
    const rows = document.querySelectorAll('.ibkr-rates-table tbody tr[data-ibkr-rate]');
    if (!rows.length) return;

    const loanUsd = lastMarginUsd < 0 ? -lastMarginUsd : 0;

    let currentUsd = 0;
    let cheapestUsd = null, cheapestCcy = null;

    rows.forEach(tr => {
        const ccy = tr.querySelector('.ibkr-rate-currency')?.textContent?.trim();
        if (!ccy) return;

        if (!tr.dataset.ibkrTiers) return;
        let tiers;
        try { tiers = JSON.parse(tr.dataset.ibkrTiers); } catch (e) { return; }
        const baseRate = tiers[0]?.rate;
        if (baseRate === undefined) return;

        const fxRate = ccy === 'USD' ? 1 : (fxRates[ccy] ?? null);
        if (fxRate === null || fxRate <= 0) return;

        // Sum actual margin entries for this specific currency
        let nativeLoan = 0;
        document.querySelectorAll('[data-cash-entry][data-margin-flag="true"]').forEach(row => {
            if (row.dataset.currency?.toUpperCase() === ccy.toUpperCase()) {
                const amount = parseFloat(row.dataset.amount || '0');
                if (amount < 0) nativeLoan += -amount;
            }
        });

        // Recalculate effective rate using this currency's own native loan
        const blended = nativeLoan > 0 ? blendedIbkrRate(tiers, nativeLoan) : null;
        const effectiveRate = blended !== null ? blended : baseRate;

        // Hypothetical: if entire USD loan were in this currency
        const hypotheticalNative = loanUsd > 0 ? loanUsd / fxRate : nativeLoan;
        const hypotheticalBlended = blendedIbkrRate(tiers, hypotheticalNative);

        const valueCell = tr.querySelector('.ibkr-rate-value');
        if (valueCell) {
            valueCell.textContent = hypotheticalBlended !== null
                ? `${hypotheticalBlended.toFixed(3)}% (${baseRate.toFixed(3)}%)`
                : `${baseRate.toFixed(3)}%`;
        }

        const days = parseInt(tr.dataset.ibkrDays, 10);

        // Current interest: each currency's own native loan * its own rate
        const nativeDaily = nativeLoan > 0 ? nativeLoan * effectiveRate / 100.0 / days : 0;
        currentUsd += nativeDaily * fxRate;

        // Cheapest: if we moved entire USD loan to this currency, what would interest be
        if (loanUsd > 0) {
            const hypotheticalRate = hypotheticalBlended !== null ? hypotheticalBlended : baseRate;
            const interest = hypotheticalNative * hypotheticalRate / 100.0 / days * fxRate;
            if (cheapestUsd === null || interest < cheapestUsd) {
                cheapestUsd = interest;
                cheapestCcy = ccy;
            }
        }
    });

    const diff = (cheapestUsd !== null && currentUsd > 0) ? currentUsd - cheapestUsd : null;

    const savingLabelEl = document.getElementById('ibkr-saving-label');
    let label = 'Saving';
    if (cheapestCcy != null && rows.length === 2) {
        if (cheapestCcy === 'USD') {
            const otherRow = [...rows].find(r => r.querySelector('.ibkr-rate-currency')?.textContent?.trim() !== 'USD');
            const ccy = otherRow?.querySelector('.ibkr-rate-currency')?.textContent?.trim();
            if (ccy) label = 'Saving (Sell USD.' + ccy + ')';
        } else {
            label = 'Saving (Buy USD.' + cheapestCcy + ')';
        }
    }
    if (savingLabelEl) savingLabelEl.textContent = label;

    const currentEl = document.getElementById('ibkr-current-interest');
    if (currentEl) currentEl.textContent = currentUsd > 0 ? formatDisplayCurrency(currentUsd) : '—';

    const cheapestEl = document.getElementById('ibkr-cheapest-interest');
    const cheapestCcyEl = document.getElementById('ibkr-cheapest-ccy');
    if (cheapestEl) cheapestEl.textContent = cheapestUsd !== null ? formatDisplayCurrency(cheapestUsd) : '—';
    if (cheapestCcyEl) cheapestCcyEl.textContent = cheapestCcy ? '(' + cheapestCcy + ')' : '';

    const diffEl = document.getElementById('ibkr-interest-diff');
    if (diffEl) {
        diffEl.textContent = (diff !== null && diff >= 0.005) ? formatDisplayCurrency(diff) : '—';
        diffEl.className = (diff !== null && diff >= 0.005) ? 'ibkr-rate-diff' : '';
    }
}

function updateCashTotals() {
    if (document.querySelectorAll('[data-cash-entry]').length === 0) return;
    let totalUsd = 0;
    let marginUsd = 0;
    let hasUnknownFx = false;
    let hasUnknownMarginFx = false;

    document.querySelectorAll('[data-cash-entry]').forEach(row => {
        const ccy = row.dataset.currency;
        const amount = parseFloat(row.dataset.amount);
        const rate = fxRates[ccy];
        const entryId = row.dataset.entryId;
        const span = document.getElementById('cash-usd-' + entryId);
        let usd = 0;
        if (rate !== undefined) {
            usd = amount * rate;
            if (span) span.textContent = formatDisplayCurrency(usd);
        } else {
            hasUnknownFx = true;
            if (row.dataset.marginFlag === 'true') hasUnknownMarginFx = true;
            if (span) span.textContent = 'N/A';
            return;
        }
        totalUsd += usd;
        if (row.dataset.marginFlag === 'true') marginUsd += usd;
    });

    cashTotalKnown = !hasUnknownFx;
    marginKnown = !hasUnknownMarginFx;
    lastCashTotalUsd = totalUsd;

    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = cashTotalKnown ? formatDisplayCurrency(totalUsd) : 'N/A';

    lastMarginUsd = marginUsd;
    updateMarginDisplay(marginUsd);
    updateIbkrDailyInterest();
    updateGrandTotal();
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
    if (updated) updateCashTotals();
}
