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
    const denominator = lastPortfolioVal + lastMarginUsd;
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

function updateIbkrDailyInterest() {
    const rows = document.querySelectorAll('.ibkr-rates-table tbody tr[data-ibkr-rate]');
    if (!rows.length) return;

    const loanUsd = lastMarginUsd < 0 ? -lastMarginUsd : 0;

    let currentUsd = 0;
    let cheapestUsd = null, cheapestCcy = null;
    rows.forEach(tr => {
        const ccy = tr.querySelector('.ibkr-rate-currency')?.textContent?.trim();
        if (!ccy) return;

        if (tr.dataset.ibkrTiers) {
            let tiers;
            try { tiers = JSON.parse(tr.dataset.ibkrTiers); } catch (e) { return; }
            const baseRate = tiers[0]?.rate;
            if (baseRate === undefined) return;
            const fxRate = ccy === 'USD' ? 1 : (fxRates[ccy] ?? null);
            const loanNative = (fxRate !== null && fxRate > 0) ? loanUsd / fxRate : 0;
            const blended = blendedIbkrRate(tiers, loanNative);
            const effectiveRate = blended !== null ? blended : baseRate;
            tr.dataset.ibkrRate = effectiveRate.toFixed(8);
            const valueCell = tr.querySelector('.ibkr-rate-value');
            if (valueCell) {
                valueCell.textContent = blended !== null
                    ? blended.toFixed(3) + '% (' + baseRate.toFixed(3) + '%)'
                    : baseRate.toFixed(3) + '%';
            }
        }

        const nativeDaily = parseFloat(tr.dataset.nativeDaily || '0');
        if (nativeDaily > 0) {
            const rate = ccy === 'USD' ? 1 : (fxRates[ccy] ?? null);
            if (rate !== null) currentUsd += nativeDaily * rate;
        }

        if (loanUsd > 0) {
            const rate = parseFloat(tr.dataset.ibkrRate);
            const days = parseInt(tr.dataset.ibkrDays, 10);
            const interest = loanUsd * rate / 100 / days;
            if (cheapestUsd === null || interest < cheapestUsd) {
                cheapestUsd = interest; cheapestCcy = ccy;
            }
        }
    });

    const diff = (cheapestUsd !== null && currentUsd > 0) ? currentUsd - cheapestUsd : null;

    const savingLabelEl = document.getElementById('ibkr-saving-label');
    let label = 'Saving';
    if (cheapestCcy != null && rows.length === 2) {
        if (cheapestCcy === 'USD') {
            const ccy = rows.find(r => r.currency !== 'USD').currency;
            label = 'Saving (Sell USD.' + ccy + ')';
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
