// Shared SVG icons
const COPY_ICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
const CHECKMARK_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';

// Global store for Day % of all symbols (portfolio + LETF components)
const componentDayPercents = {};
// Global store for raw (unrounded) prices — avoids compounded rounding errors in rebalancing
const rawMarkPrices = {};
const rawClosePrices = {};
let globalIsMarketClosed = true;
let marketCloseTimeMs = null; // Unix ms of tradingPeriodEnd
// Display currency (USD by default; lastPortfolioVal/lastCashTotalUsd/etc. declared in inline script)
let currentDisplayCurrency = 'USD';
let rebalTargetUsd = null; // null = use lastPortfolioVal
let marginTargetPct = null; // non-null = margin mode (rebalTargetUsd derived from this)
let allocAddMode = (typeof savedAllocAddMode !== 'undefined' ? savedAllocAddMode : null)
    || localStorage.getItem('ib-viewer-alloc-add-mode') || 'PROPORTIONAL';
let allocReduceMode = (typeof savedAllocReduceMode !== 'undefined' ? savedAllocReduceMode : null)
    || localStorage.getItem('ib-viewer-alloc-reduce-mode') || 'PROPORTIONAL';
let lastAllocRebalTotal = 0;
let portfolioValueKnown = true;  // false if any stock has neither mark nor close price
let cashTotalKnown = true;       // false if any non-USD cash entry is missing its FX rate
let marginKnown = false;          // false if any margin-flagged entry is missing its FX rate


function parsePrice(priceText) {
    if (!priceText || priceText === '—') return null;
    const cleaned = priceText.replace(/[$,]/g, '');
    const parsed = parseFloat(cleaned);
    return isNaN(parsed) ? null : parsed;
}

function formatTimestamp(timestamp) {
    if (!timestamp) return 'Never';
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
    });
}

function updateGlobalTimestamp(timestamp) {
    const timeCell = document.getElementById('last-update-time');
    if (timeCell && timestamp) {
        timeCell.textContent = formatTimestamp(timestamp);
        timeCell.classList.add('loaded');
    }
}

function updateNavInUI(symbol, nav) {
    const navCell = document.getElementById('nav-' + symbol);
    if (navCell) {
        navCell.textContent = nav !== null ? '$' + nav.toFixed(2) : '—';
        if (nav !== null) navCell.classList.add('loaded');
    }
    // Recalculate Est Val (NAV is preferred base price)
    updateAllEstVals();
}

function updatePriceInUI(symbol, markPrice, lastClosePrice, isMarketClosed, tradingPeriodEnd) {
    // Update global market state
    globalIsMarketClosed = isMarketClosed;
    if (tradingPeriodEnd !== null && tradingPeriodEnd !== undefined) {
        const endMs = tradingPeriodEnd * 1000;
        if (endMs <= Date.now()) {          // only set if close is actually in the past
            marketCloseTimeMs = endMs;
        }
    }

    // Store raw prices for high-precision rebalancing calculations
    if (markPrice !== null) rawMarkPrices[symbol] = markPrice;
    if (lastClosePrice !== null) rawClosePrices[symbol] = lastClosePrice;

    // Store Day % for LETF Est Val calculations
    if (markPrice !== null && lastClosePrice !== null && lastClosePrice !== 0) {
        componentDayPercents[symbol] = ((markPrice - lastClosePrice) / lastClosePrice) * 100;
    }

    // Store previous value for comparison (to detect if row should be highlighted)
    const valueCell = document.getElementById('value-' + symbol);
    const amountCell = document.getElementById('amount-' + symbol);
    let previousValue = null;
    let valueChanged = false;

    if (valueCell) {
        const previousValueText = valueCell.textContent;
        previousValue = parsePrice(previousValueText);
    }

    // Update mark price
    const markCell = document.getElementById('mark-' + symbol);
    if (markCell) {
        markCell.textContent = markPrice !== null ? '$' + markPrice.toFixed(2) : '—';
        if (markPrice !== null) markCell.classList.add('loaded');
    }

    // Update last close price
    const closeCell = document.getElementById('close-' + symbol);
    if (closeCell) {
        closeCell.textContent = lastClosePrice !== null ? '$' + lastClosePrice.toFixed(2) : '—';
        if (lastClosePrice !== null) closeCell.classList.add('loaded');
    }

    // Calculate and update day change
    if (markPrice !== null && lastClosePrice !== null) {
        const changeDollars = markPrice - lastClosePrice;
        const changePercent = (changeDollars / lastClosePrice) * 100;

        // Check if change is effectively zero (within 0.001 tolerance for floating point)
        const isZeroChange = Math.abs(changeDollars) < 0.001;

        // Determine after-hours class
        const afterHoursClass = isMarketClosed ? ' after-hours' : '';

        // Update day change $ cell
        const changeCell = document.getElementById('day-change-' + symbol);
        if (changeCell) {
            if (isZeroChange) {
                changeCell.textContent = '—';
            } else {
                const sign = changeDollars >= 0 ? '+' : '-';
                changeCell.textContent = sign + '$' + Math.abs(changeDollars).toFixed(2);
            }
            const dir = isZeroChange ? 'neutral' : (changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral');
            changeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
            changeCell.classList.add('loaded', dir);
            if (isMarketClosed) changeCell.classList.add('after-hours');
        }

        // Update day change % cell
        const changePercentCell = document.getElementById('day-percent-' + symbol);
        if (changePercentCell) {
            if (isZeroChange) {
                changePercentCell.textContent = '—';
            } else {
                const sign = changePercent >= 0 ? '+' : '-';
                changePercentCell.textContent = sign + Math.abs(changePercent).toFixed(2) + '%';
            }
            const dir = isZeroChange ? 'neutral' : (changePercent > 0 ? 'positive' : changePercent < 0 ? 'negative' : 'neutral');
            changePercentCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
            changePercentCell.classList.add('loaded', dir);
            if (isMarketClosed) changePercentCell.classList.add('after-hours');
        }

        // Update position value change (Mkt Val Chg)
        // IMPORTANT: Always calculate from changeDollars * amount to avoid floating point errors
        if (amountCell) {
            const amount = parseFloat(amountCell.textContent);
            const positionChange = changeDollars * amount;

            const positionChangeCell = document.getElementById('position-change-' + symbol);
            if (positionChangeCell) {
                if (isZeroChange) {
                    positionChangeCell.textContent = '—';
                } else {
                    positionChangeCell.textContent = formatSignedCurrency(positionChange);
                }
                const dir = isZeroChange ? 'neutral' : (positionChange > 0 ? 'positive' : positionChange < 0 ? 'negative' : 'neutral');
                positionChangeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
                positionChangeCell.classList.add('loaded', dir);
                if (isMarketClosed) positionChangeCell.classList.add('after-hours');
            }
        }
    }

    // Calculate and update value (prefer mark price)
    if (valueCell && amountCell) {
        const amount = parseFloat(amountCell.textContent);
        const price = markPrice !== null ? markPrice : lastClosePrice;

        if (price !== null) {
            const newValue = price * amount;
            valueCell.textContent = formatCurrency(newValue);
            valueCell.classList.add('loaded');

            // Check if value actually changed (with 1 cent tolerance)
            if (previousValue !== null && Math.abs(newValue - previousValue) > 0.01) {
                valueChanged = true;
            }

            updateTotalValue();
        }
    }

    // Highlight row ONLY if value changed
    if (valueChanged && amountCell) {
        const row = amountCell.closest('tr');
        if (row) {
            row.classList.add('recently-updated');

            // Remove highlight after 10 seconds
            setTimeout(() => {
                row.classList.remove('recently-updated');
            }, 10000);
        }
    }

    // Recalculate Est Val for all LETF stocks
    updateAllEstVals();
}

function updateTotalValue() {
    let total = 0;
    let previousTotal = 0;
    portfolioValueKnown = true;  // reset each recalculation

    // Calculate current total and previous day's total
    document.querySelectorAll('tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const amountCell = row.querySelector('td.amount[id]');
        if (!amountCell) return;
        const symbol = amountCell.id.replace('amount-', '');
        const markCell = document.getElementById('mark-' + symbol);
        const closeCell = document.getElementById('close-' + symbol);

        if (markCell && closeCell) {
            const amount = parseFloat(amountCell.textContent);
            const markPrice = rawMarkPrices[symbol] ?? parsePrice(markCell.textContent);
            const closePrice = rawClosePrices[symbol] ?? parsePrice(closeCell.textContent);

            if (markPrice === null && closePrice === null) portfolioValueKnown = false;
            if (markPrice !== null) total += markPrice * amount;
            if (closePrice !== null) previousTotal += closePrice * amount;
        }
    });

    lastPortfolioVal = total;
    lastPrevPortfolioVal = previousTotal;
    updateRebalTargetPlaceholder();

    // Update portfolio total
    const totalCell = document.getElementById('portfolio-total');
    if (totalCell) totalCell.textContent = portfolioValueKnown
        ? formatDisplayCurrency(total) : 'N/A';

    // Update portfolio daily change
    const changeDollars = total - previousTotal;
    lastPortfolioDayChangeUsd = changeDollars;
    const changePercent = previousTotal > 0 ? (changeDollars / previousTotal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';

    // Portfolio day change (% relative to previous portfolio value)
    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        if (!portfolioValueKnown) {
            portfolioChangeCell.innerHTML = 'N/A';
        } else {
            const sign = changeDollars >= 0 ? '+' : '-';
            portfolioChangeCell.innerHTML =
                '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
                '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
        }
    }

    updateCurrentWeights(total);
    updateRebalancingColumns(getRebalTotal());
    updateAllocColumns(getAllocRebalTotal());
    updateMarginTargetDisplay();

    // Update grand total (portfolio + cash)
    const grandEl = document.getElementById('grand-total-value');
    if (grandEl) grandEl.textContent = (portfolioValueKnown && cashTotalKnown)
        ? formatDisplayCurrency(total + lastCashTotalUsd) : 'N/A';

    // Total Value day change: same $ amount, but % relative to previous grand total
    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        if (!portfolioValueKnown) {
            totalChangeCell.innerHTML = 'N/A';
        } else {
            const prevGrandTotal = previousTotal + lastCashTotalUsd;
            const totalChangePercent = prevGrandTotal !== 0 ? (changeDollars / Math.abs(prevGrandTotal)) * 100 : 0;
            const sign = changeDollars >= 0 ? '+' : '-';
            totalChangeCell.innerHTML =
                '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
                '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(totalChangePercent).toFixed(2) + '%)</span>';
        }
    }

    // Update margin % when portfolio value changes
    updateMarginDisplay(lastMarginUsd);
}

function formatCurrency(val) {
    const sign = val < 0 ? '-' : '';
    return sign + '$' + Math.abs(val).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
    });
}

function formatSignedCurrency(val) {
    const sign = val >= 0 ? '+' : '';
    return sign + formatCurrency(val);
}

function toDisplayCurrency(usdVal) {
    const rate = fxRates[currentDisplayCurrency];
    return (rate && rate !== 0) ? usdVal / rate : usdVal;
}

function formatDisplayCurrency(usdVal) {
    const converted = toDisplayCurrency(usdVal);
    const ccy = currentDisplayCurrency;
    const absVal = Math.abs(converted);
    const sign = converted < 0 ? '-' : '';
    const formatted = absVal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return ccy === 'USD' ? (sign + '$' + formatted) : (sign + formatted + '\u00A0' + ccy);
}

function formatSignedDisplayCurrency(usdVal) {
    const converted = toDisplayCurrency(usdVal);
    return (converted >= 0 ? '+' : '') + formatDisplayCurrency(usdVal);
}

function getRebalTotal() {
    if (marginTargetPct !== null) return deriveRebalFromMarginPct(marginTargetPct);
    if (rebalTargetUsd !== null && rebalTargetUsd > 0) return rebalTargetUsd;
    return lastPortfolioVal + Math.max(lastMarginUsd, 0);
}

function getAllocRebalTotal() {
    if (marginTargetPct !== null) return deriveRebalFromMarginPct(marginTargetPct);
    if (rebalTargetUsd !== null && rebalTargetUsd > 0) return rebalTargetUsd;
    // Add positive M-cash (deployable), ignore margin debt
    return lastPortfolioVal + Math.max(lastMarginUsd, 0);
}

function deriveMarginPct(rebalTotal) {
    const ec = lastPortfolioVal + lastMarginUsd;
    if (ec <= 0) return 0;
    const marginPct = (lastMarginUsd - (rebalTotal - lastPortfolioVal)) / ec * 100;
    if (marginPct >=0) return 0;
    return -marginPct;
}
function deriveRebalFromMarginPct(pct) {
    const ec = lastPortfolioVal + lastMarginUsd;
    return (pct / 100) * ec + lastPortfolioVal + lastMarginUsd;
}

function updateRebalTargetPlaceholder() {
    const input = document.getElementById('rebal-target-input');
    if (!input) return;
    const marginInput = document.getElementById('margin-target-input');
    if (marginInput && marginInput.value.trim() !== '') {
        const converted = toDisplayCurrency(getRebalTotal());
        input.placeholder = Math.abs(converted).toLocaleString('en-US', {
            minimumFractionDigits: 2, maximumFractionDigits: 2
        });
    } else {
        const converted = toDisplayCurrency(lastPortfolioVal + Math.max(lastMarginUsd, 0));
        input.placeholder = Math.abs(converted).toLocaleString('en-US', {
            minimumFractionDigits: 2, maximumFractionDigits: 2
        });
    }
}

function updateMarginInputPlaceholder() {
    const input = document.getElementById('margin-target-input');
    if (!input || input.value.trim() !== '') return;
    let pct = deriveMarginPct(getRebalTotal());
    input.placeholder = pct.toFixed(1);
}

function updateMarginTargetDisplay() {
    const marginTargetRow = document.getElementById('margin-target-row');
    if (!marginTargetRow) return;

    const marginTargetEl = document.getElementById('margin-target-usd');

    const rebalTotal = getRebalTotal();
    const marginTargetUsd = lastMarginUsd - (rebalTotal - lastPortfolioVal);
    if (marginTargetEl) {
        marginTargetEl.textContent = marginTargetUsd < 0 ? formatDisplayCurrency(-marginTargetUsd) : '';
    }

    updateMarginInputPlaceholder();
}

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
        if (marginEl) marginEl.textContent = 'N/A';
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

    // Recompute effective rate and update display text for each row
    rows.forEach(tr => {
        const ccy = tr.querySelector('.ibkr-rate-currency')?.textContent?.trim();
        if (!ccy || !tr.dataset.ibkrTiers) return;
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
    });

    // Current: sum of per-currency native daily interest converted to USD
    let currentUsd = 0;
    rows.forEach(tr => {
        const ccy = tr.querySelector('.ibkr-rate-currency')?.textContent?.trim();
        const nativeDaily = parseFloat(tr.dataset.nativeDaily || '0');
        if (nativeDaily > 0 && ccy) {
            const rate = ccy === 'USD' ? 1 : (fxRates[ccy] ?? null);
            if (rate !== null) currentUsd += nativeDaily * rate;
        }
    });

    // Cheapest: entire loan in one currency (min across rows)
    let cheapestUsd = null, cheapestCcy = null;
    if (loanUsd > 0) {
        rows.forEach(tr => {
            const ccy = tr.querySelector('.ibkr-rate-currency')?.textContent?.trim();
            const rate = parseFloat(tr.dataset.ibkrRate);
            const days = parseInt(tr.dataset.ibkrDays, 10);
            const interest = loanUsd * rate / 100 / days;
            if (cheapestUsd === null || interest < cheapestUsd) {
                cheapestUsd = interest; cheapestCcy = ccy;
            }
        });
    }

    // Difference
    const diff = (cheapestUsd !== null && currentUsd > 0) ? currentUsd - cheapestUsd : null;

    const savingLabelEl = document.getElementById('ibkr-saving-label');
    let label = 'Saving';
    if (cheapestCcy != null && rows.length === 2) {
        if (cheapestCcy === "USD") {
            const ccy = rows.find(r => r.currency !== "USD").currency;
            label = 'Saving (Sell USD.' + ccy +')';
        } else {
            label = 'Saving (Buy USD.' + cheapestCcy +')';
        }
    }
    savingLabelEl.textContent = label;

    // Update DOM
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
            // Non-USD entry with no FX rate yet
            hasUnknownFx = true;
            if (row.dataset.marginFlag === 'true') hasUnknownMarginFx = true;
            if (span) span.textContent = 'N/A';
            return; // continue forEach
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

    // Update grand total
    const grandEl = document.getElementById('grand-total-value');
    if (grandEl) grandEl.textContent = (portfolioValueKnown && cashTotalKnown)
        ? formatDisplayCurrency(lastPortfolioVal + totalUsd) : 'N/A';
}

function updatePortfolioRefValues(portfolioId, newPortfolioValue) {
    let updated = false;
    document.querySelectorAll(`[data-portfolio-ref="${portfolioId}"]`).forEach(row => {
        const mult = parseFloat(row.dataset.portfolioMultiplier || '1');
        const newAmount = mult * newPortfolioValue;
        row.dataset.amount = newAmount.toString();
        // Update raw-col display (shows "50,000.00 USD" style text)
        const rawCol = row.querySelector('.cash-raw-col');
        if (rawCol) rawCol.textContent = formatCurrency(Math.abs(newAmount)) + ' USD';
        updated = true;
    });
    if (updated) updateCashTotals();
}

function updateCurrentWeights(portfolioTotal) {
    if (!portfolioValueKnown) {
        document.querySelectorAll('.weight-display').forEach(cell => {
            cell.innerHTML = 'N/A';
            cell.classList.remove('loaded');
        });
        return;
    }
    if (portfolioTotal <= 0) return;

    document.querySelectorAll('.value.loaded').forEach(valueCell => {
        const value = parsePrice(valueCell.textContent);
        if (value === null) return;

        const symbol = valueCell.id.replace('value-', '');
        const weightCell = document.getElementById('current-weight-' + symbol);

        if (weightCell) {
            const currentWeight = (value / portfolioTotal) * 100;

            // Find target weight from view table row's data-weight attribute
            const viewRow = weightCell.closest('tr');
            const targetWeight = viewRow ? parseFloat(viewRow.dataset.weight) : null;

            if (targetWeight !== null && !isNaN(targetWeight)) {
                const diff = currentWeight - targetWeight;
                const sign = diff >= 0 ? '-' : '+';
                const diffClass = Math.abs(diff) > 2.0 ? 'alert' :
                                  Math.abs(diff) > 1.0 ? 'warning' : 'good';

                weightCell.innerHTML =
                    currentWeight.toFixed(1) + '% ' +
                    '<span class="weight-diff ' + diffClass + '">(' + sign + Math.abs(diff).toFixed(1) + '%)</span>';
            } else {
                weightCell.textContent = currentWeight.toFixed(1) + '%';
            }

            weightCell.classList.add('loaded');
        }
    });
}

function updateRebalancingColumns(portfolioTotal) {
    if (!portfolioValueKnown) {
        document.querySelectorAll('[id^="rebal-dollars-"]').forEach(c => {
            c.textContent = 'N/A'; c.className = 'price-change rebal-column';
        });
        document.querySelectorAll('[id^="rebal-qty-"]').forEach(c => {
            c.textContent = 'N/A'; c.className = 'price-change rebal-column';
        });
        return;
    }
    if (portfolioTotal <= 0) return;

    document.querySelectorAll('.value.loaded').forEach(valueCell => {
        const symbol = valueCell.id.replace('value-', '');
        const amountCell = document.getElementById('amount-' + symbol);
        const amount = amountCell ? parseFloat(amountCell.textContent) : null;

        // Use raw prices for precision; fall back to DOM-parsed values if not yet received
        const markCell = document.getElementById('mark-' + symbol);
        const markPrice = rawMarkPrices[symbol] ?? parsePrice(markCell ? markCell.textContent : null);
        const effectivePrice = markPrice ?? (rawClosePrices[symbol] ?? null);

        const value = (amount !== null && effectivePrice !== null)
            ? amount * effectivePrice
            : parsePrice(valueCell.textContent);
        if (value === null) return;

        // Get target weight from view table row's data-weight attribute
        const weightCell = document.getElementById('current-weight-' + symbol);
        const viewRow = weightCell ? weightCell.closest('tr') : null;
        const targetWeight = viewRow ? parseFloat(viewRow.dataset.weight) : null;

        if (targetWeight !== null) {
            // Calculate rebalancing dollar amount
            const targetValue = (targetWeight / 100) * portfolioTotal;
            const rebalDollars = targetValue - value;

            const rebalDollarsCell = document.getElementById('rebal-dollars-' + symbol);
            if (rebalDollarsCell) {
                rebalDollarsCell.textContent = formatSignedCurrency(rebalDollars);

                // Update color class
                const direction = Math.abs(rebalDollars) > 0.50 ?
                    (rebalDollars > 0 ? 'positive' : 'negative') : 'neutral';
                rebalDollarsCell.className = 'price-change loaded rebal-column ' + direction;
            }

            // Calculate rebalancing share amount
            if (markPrice !== null && markPrice > 0) {
                const rebalShares = rebalDollars / markPrice;

                const rebalQtyCell = document.getElementById('rebal-qty-' + symbol);
                if (rebalQtyCell) {
                    const sign = rebalShares >= 0 ? '+' : '-';
                    rebalQtyCell.textContent = sign + Math.abs(rebalShares).toFixed(2);

                    // Update color class (same direction as dollars)
                    const direction = Math.abs(rebalDollars) > 0.50 ?
                        (rebalDollars > 0 ? 'positive' : 'negative') : 'neutral';
                    rebalQtyCell.className = 'price-change loaded rebal-column ' + direction;
                }
            }
        }
    });
}

function updateAllocColumns(rebalTotal) {
    if (!portfolioValueKnown) {
        document.querySelectorAll('[id^="alloc-dollars-"]').forEach(c => {
            c.textContent = 'N/A'; c.className = 'price-change alloc-column';
        });
        document.querySelectorAll('[id^="alloc-qty-"]').forEach(c => {
            c.textContent = 'N/A'; c.className = 'price-change alloc-column';
        });
        return;
    }
    lastAllocRebalTotal = rebalTotal;
    const delta = rebalTotal - lastPortfolioVal;

    // Collect per-stock data
    const stocks = [];
    let totalStockValue = 0;
    document.querySelectorAll('.value.loaded').forEach(valueCell => {
        const symbol = valueCell.id.replace('value-', '');
        const markPrice = rawMarkPrices[symbol] ??
            parsePrice(document.getElementById('mark-' + symbol)?.textContent);
        const weightCell = document.getElementById('current-weight-' + symbol);
        const viewRow = weightCell?.closest('tr');
        const targetWeight = viewRow ? parseFloat(viewRow.dataset.weight) : null;
        const currentValue = parsePrice(valueCell.textContent) ?? 0;
        stocks.push({ symbol, markPrice, targetWeight, currentValue });
        totalStockValue += currentValue;
    });

    const mode = delta >= 0 ? allocAddMode : allocReduceMode;
    const allocations = computeAllocations(delta, stocks, totalStockValue, mode);

    for (const s of stocks) {
        const dollarsCell = document.getElementById('alloc-dollars-' + s.symbol);
        const qtyCell = document.getElementById('alloc-qty-' + s.symbol);
        const amt = allocations[s.symbol];
        if (amt == null) {
            if (dollarsCell) dollarsCell.textContent = '';
            if (qtyCell) qtyCell.textContent = '';
            continue;
        }
        const dir = amt > 0.50 ? 'positive' : amt < -0.50 ? 'negative' : 'neutral';
        if (dollarsCell) {
            dollarsCell.textContent = formatSignedCurrency(amt);
            dollarsCell.className = 'price-change loaded alloc-column ' + dir;
        }
        if (qtyCell && s.markPrice > 0) {
            const qty = amt / s.markPrice;
            qtyCell.textContent = (qty >= 0 ? '+' : '-') + Math.abs(qty).toFixed(2);
            qtyCell.className = 'price-change loaded alloc-column ' + dir;
        }
    }
}

function computeUndervalueFirst(eligible, totalStockValue, delta) {
    const alloc = {};
    for (const s of eligible) alloc[s.symbol] = 0;

    const finalTotal = totalStockValue + delta;
    const sign = delta >= 0 ? 1 : -1;

    const sorted = [...eligible].sort((a, b) =>
        sign * (
            (a.currentValue / finalTotal - a.targetWeight / 100) -
            (b.currentValue / finalTotal - b.targetWeight / 100)
        )
    );

    let remaining = Math.abs(delta);
    for (const s of sorted) {
        if (remaining <= 0) break;
        const target = finalTotal * (s.targetWeight / 100);
        const amount = Math.min(remaining, Math.max(0, (target - s.currentValue) * sign));
        alloc[s.symbol] = amount * sign;
        remaining -= amount;
    }

    if (remaining > 0) {
        for (const s of eligible)
            alloc[s.symbol] = (alloc[s.symbol] ?? 0) + (s.targetWeight / 100) * remaining * sign;
    }

    return alloc;
}

function computeWaterfall(eligible, totalStockValue, delta) {
    const alloc = {};
    for (const s of eligible) alloc[s.symbol] = 0;

    const finalTotal = totalStockValue + delta;
    const sign = delta >= 0 ? 1 : -1;

    const currentDev = {};
    for (const s of eligible) {
        currentDev[s.symbol] = (s.currentValue / finalTotal) - (s.targetWeight / 100);
    }

    const sorted = [...eligible].sort((a, b) => sign * (currentDev[a.symbol] - currentDev[b.symbol]));
    let remaining = Math.abs(delta);

    for (let i = 0; i < sorted.length && remaining > 0; i++) {
        const groupDev = currentDev[sorted[0].symbol];
        const nextDev = i + 1 < sorted.length ? currentDev[sorted[i + 1].symbol] : sign * Infinity;
        const groupSize = i + 1;

        const costToLevel = (nextDev - groupDev) * sign * finalTotal * groupSize;

        if (remaining >= costToLevel) {
            for (let j = 0; j <= i; j++) {
                alloc[sorted[j].symbol] += (nextDev - groupDev) * finalTotal;
                currentDev[sorted[j].symbol] = nextDev;
            }
            remaining -= costToLevel;
        } else {
            const perStock = remaining / groupSize;
            for (let j = 0; j <= i; j++) {
                alloc[sorted[j].symbol] += perStock * sign;
                currentDev[sorted[j].symbol] += (perStock / finalTotal) * sign;
            }
            remaining = 0;
        }
    }

    if (remaining > 0) {
        for (const s of eligible) {
            alloc[s.symbol] += (s.targetWeight / 100) * remaining * sign;
        }
    }

    return alloc;
}

function computeAllocations(delta, stocks, totalStockValue, mode) {
    const result = {};
    if (mode === 'PROPORTIONAL') {
        for (const s of stocks)
            result[s.symbol] = s.targetWeight !== null ? (s.targetWeight / 100) * delta : null;

    } else if (mode === 'CURRENT_WEIGHT') {
        for (const s of stocks) {
            const w = totalStockValue > 0 ? s.currentValue / totalStockValue : 0;
            result[s.symbol] = w * delta;
        }

    } else if (mode === 'UNDERVALUED_PRIORITY') {
        const eligible = stocks.filter(s => s.targetWeight !== null);
        const alloc = computeUndervalueFirst(eligible, totalStockValue, delta);
        for (const s of eligible) result[s.symbol] = alloc[s.symbol] ?? 0;
    } else if (mode === 'WATERFALL') {
        const eligible = stocks.filter(s => s.targetWeight !== null);
        const alloc = computeWaterfall(eligible, totalStockValue, delta);
        for (const s of eligible) result[s.symbol] = alloc[s.symbol] ?? 0;
    }
    return result;
}

function updateAllEstVals() {
    const stale = globalIsMarketClosed && (
        marketCloseTimeMs === null ||
        Date.now() - marketCloseTimeMs > 12 * 3600 * 1000
    );

    document.querySelectorAll('tbody tr[data-letf]').forEach(row => {
        const symbol = row.querySelector('td:first-child').textContent.trim();
        const letfAttr = row.getAttribute('data-letf');
        if (!letfAttr) return;

        const estValCell = document.getElementById('est-val-' + symbol);

        if (stale) {
            if (estValCell) {
                estValCell.textContent = '—';
                estValCell.classList.remove('loaded');
            }
            return;
        }

        // Parse components: "1,CTA,1,IVV" → [{mult: 1, sym: "CTA"}, {mult: 1, sym: "IVV"}]
        const tokens = letfAttr.split(',');
        const components = [];
        for (let i = 0; i + 1 < tokens.length; i += 2) {
            components.push({ mult: parseFloat(tokens[i]), sym: tokens[i + 1] });
        }

        // Get base price: prefer NAV, fallback to close
        const navCell = document.getElementById('nav-' + symbol);
        const closeCell = document.getElementById('close-' + symbol);
        const navPrice = navCell ? parsePrice(navCell.textContent) : null;
        const closePrice = closeCell ? parsePrice(closeCell.textContent) : null;
        const basePrice = navPrice !== null ? navPrice : closePrice;

        if (basePrice === null) return;

        // Calculate sum of (multiplier * dayPercent / 100)
        let sumComponent = 0;
        let allAvailable = true;
        for (const comp of components) {
            const dayPct = componentDayPercents[comp.sym];
            if (dayPct === undefined) {
                allAvailable = false;
                break;
            }
            sumComponent += comp.mult * dayPct / 100;
        }

        if (estValCell && allAvailable) {
            const estVal = (1 + sumComponent) * basePrice;
            estValCell.textContent = '$' + estVal.toFixed(2);
            estValCell.classList.add('loaded');
        }
    });
}

function refreshDisplayCurrency() {
    // Convert rebalance target input to new display currency (USD value stays intact)
    // Skip when in margin mode — margin % doesn't change with currency, rebal input stays empty
    const rebalInput = document.getElementById('rebal-target-input');
    if (rebalInput) {
        if (marginTargetPct !== null) {
            rebalInput.value = '';
        } else if (rebalTargetUsd !== null && rebalTargetUsd > 0) {
            const displayVal = toDisplayCurrency(rebalTargetUsd);
            rebalInput.value = displayVal.toLocaleString('en-US', {
                minimumFractionDigits: 2, maximumFractionDigits: 2
            });
        } else {
            rebalInput.value = '';
        }
    }

    // Portfolio total
    const totalCell = document.getElementById('portfolio-total');
    if (totalCell) totalCell.textContent = portfolioValueKnown
        ? formatDisplayCurrency(lastPortfolioVal) : 'N/A';

    // Portfolio day change
    const changeDollars = lastPortfolioDayChangeUsd;
    const changePercent = lastPrevPortfolioVal > 0 ? (changeDollars / lastPrevPortfolioVal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';
    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        if (!portfolioValueKnown) {
            portfolioChangeCell.innerHTML = 'N/A';
        } else {
            const sign = changeDollars >= 0 ? '+' : '-';
            portfolioChangeCell.innerHTML =
                '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
                '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
        }
    }

    // Cash entry USD spans
    document.querySelectorAll('[data-cash-entry]').forEach(row => {
        const ccy = row.dataset.currency;
        const amount = parseFloat(row.dataset.amount);
        const rate = fxRates[ccy];
        const span = document.getElementById('cash-usd-' + row.dataset.entryId);
        if (span) span.textContent = rate !== undefined ? formatDisplayCurrency(amount * rate) : 'N/A';
    });

    // Cash total, margin, grand total
    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = cashTotalKnown ? formatDisplayCurrency(lastCashTotalUsd) : 'N/A';
    updateMarginDisplay(lastMarginUsd);
    const grandEl = document.getElementById('grand-total-value');
    if (grandEl) grandEl.textContent = (portfolioValueKnown && cashTotalKnown)
        ? formatDisplayCurrency(lastPortfolioVal + lastCashTotalUsd) : 'N/A';

    // Total day change
    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        if (!portfolioValueKnown) {
            totalChangeCell.innerHTML = 'N/A';
        } else {
            const prevGrand = lastPrevPortfolioVal + lastCashTotalUsd;
            const totalChangePct = prevGrand !== 0 ? (changeDollars / Math.abs(prevGrand)) * 100 : 0;
            const sign = changeDollars >= 0 ? '+' : '-';
            totalChangeCell.innerHTML =
                '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
                '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(totalChangePct).toFixed(2) + '%)</span>';
        }
    }

    updateRebalTargetPlaceholder();
    updateRebalancingColumns(getRebalTotal());
    updateAllocColumns(getAllocRebalTotal());
    updateMarginTargetDisplay();
    updateIbkrDailyInterest();
}

function updateTargetWeightTotal() {
    // Edit mode total: from edit-weight inputs in the edit table (drives the tfoot display)
    let editTotal = 0;
    (document.querySelectorAll('#stock-edit-table tbody tr:not([data-deleted])') || []).forEach(tr => {
        const input = tr.querySelector('.edit-weight') || tr.querySelector('input[data-column="weight"]');
        if (input) editTotal += parseFloat(input.value) || 0;
    });
    const totalCell = document.getElementById('target-weight-total');
    if (totalCell) {
        totalCell.textContent = editTotal.toFixed(1) + '%';
        totalCell.classList.toggle('weight-total-error', Math.abs(editTotal - 100) > 0.05);
    }

    // Rebalance mode warning: from view table rows' data-weight (what rebalancing actually uses)
    let rebalTotal = 0;
    document.querySelectorAll('#stock-view-table tbody tr').forEach(tr => {
        rebalTotal += parseFloat(tr.dataset.weight) || 0;
    });
    const warningEl = document.getElementById('rebal-weight-warning');
    if (warningEl) {
        const isError = Math.abs(rebalTotal - 100) > 0.05;
        const rebalVisible = document.body.classList.contains('rebalancing-visible');
        const isEditing = document.body.classList.contains('editing-active');
        if (rebalVisible && !isEditing && isError) {
            warningEl.textContent = '\u26a0 Target weights sum to ' + rebalTotal.toFixed(1) + '% (must be 100%)';
            warningEl.style.display = 'block';
        } else {
            warningEl.style.display = 'none';
        }
    }
}

// ── HTML templates and helpers for dynamically added rows ─────────────────────

const STOCK_ROW_HTML =
    '<td class="drag-handle-cell"><span class="drag-handle" draggable="true">⠿</span></td>' +
    '<td><input type="text" class="edit-input new-symbol-input" data-column="symbol" placeholder="TICKER" style="text-align:left;width:80px;display:block" /></td>' +
    '<td class="amount"><input type="number" class="edit-input" data-column="qty" value="0" min="0" step="any" style="display:block" /></td>' +
    '<td><input type="number" class="edit-input" data-column="weight" value="0" min="0" max="100" step="0.1" /></td>' +
    '<td><input type="text" class="edit-input" data-column="letf" placeholder="e.g. 2 IVV" style="text-align:left;width:120px" /></td>' +
    '<td><button type="button" class="delete-row-btn">\u00d7</button></td>';

const CASH_ROW_HTML =
    '<td><input type="text" class="edit-input cash-edit-key" placeholder="Cash.USD.M" /></td>' +
    '<td><input type="text" class="edit-input cash-edit-value" placeholder="0" /></td>' +
    '<td><button type="button" class="delete-cash-btn">\u00d7</button></td>';

function addStockRow() {
    const tbody = document.querySelector('#stock-edit-table tbody');
    if (!tbody) return null;
    const tr = document.createElement('tr');
    tr.setAttribute('data-new-stock', 'true');
    tr.innerHTML = STOCK_ROW_HTML;
    tbody.appendChild(tr);
    return tr;
}

function addCashRow() {
    const tbody = document.querySelector('.cash-edit-table tbody');
    if (!tbody) return null;
    const tr = document.createElement('tr');
    tr.setAttribute('data-cash-edit-row', 'true');
    tr.setAttribute('data-new-cash', 'true');
    tr.innerHTML = CASH_ROW_HTML;
    tbody.appendChild(tr);
    return tr;
}

// Returns [symInput, qtyInput, weightInput, letfInput] for any stock row (existing or new)
function getStockRowInputs(tr) {
    return [
        tr.querySelector('.edit-symbol') || tr.querySelector('.new-symbol-input') || tr.querySelector('input[data-column="symbol"]'),
        tr.querySelector('.edit-qty')    || tr.querySelector('input[data-column="qty"]'),
        tr.querySelector('.edit-weight') || tr.querySelector('input[data-column="weight"]'),
        tr.querySelector('.edit-letf')   || tr.querySelector('input[data-column="letf"]'),
    ].filter(Boolean);
}

// Returns 0=sym, 1=qty, 2=weight, 3=letf — or -1 if not a stock input
function getStockColIndex(el) {
    if (el.classList.contains('edit-symbol') || el.classList.contains('new-symbol-input')) return 0;
    const col = el.getAttribute('data-column');
    if (el.classList.contains('edit-qty')    || col === 'qty')    return 1;
    if (el.classList.contains('edit-weight') || col === 'weight') return 2;
    if (el.classList.contains('edit-letf')   || col === 'letf')   return 3;
    return -1;
}

// ── SSE connection ─────────────────────────────────────────────────────────────

function initSseConnection() {
    // Inject SSE status dot next to the timestamp
    const timeEl = document.getElementById('last-update-time');
    if (timeEl) {
        const dot = document.createElement('span');
        dot.id = 'sse-status-dot';
        dot.className = 'sse-dot';
        dot.title = 'Connecting…';
        timeEl.after(dot);
    }

    let sseLastActivity = Date.now();

    function setSseStatus(ok) {
        const dot = document.getElementById('sse-status-dot');
        if (dot) {
            dot.className = 'sse-dot ' + (ok ? 'sse-dot--ok' : 'sse-dot--err');
            dot.title = ok ? 'Live' : 'Disconnected';
        }
    }

    const eventSource = new EventSource('/api/prices/stream');

    eventSource.onopen = () => {
        sseLastActivity = Date.now();
        setSseStatus(true);
    };

    eventSource.onmessage = (event) => {
        sseLastActivity = Date.now();
        try {
            const data = JSON.parse(event.data);

            if (data.type === 'reload') {
                // Portfolio structure changed, reload page
                console.log('Portfolio reloaded, refreshing page...');
                location.reload();
            } else if (data.type === 'nav') {
                // NAV update
                updateNavInUI(data.symbol, data.nav);
            } else if (data.type === 'portfolio-value') {
                // Cross-portfolio cash reference update
                updatePortfolioRefValues(data.portfolioId, data.value);
            } else {
                // FX rate update for cash currency conversion
                if (data.symbol && data.symbol.endsWith('USD=X')) {
                    const ccy = data.symbol.replace('USD=X', '');
                    if (data.markPrice !== null && data.markPrice !== undefined) {
                        fxRates[ccy] = data.markPrice;
                        updateCashTotals();
                        updateIbkrDailyInterest();
                    }
                    return;
                }

                // Update global timestamp
                updateGlobalTimestamp(data.timestamp);

                // Update price in UI
                updatePriceInUI(data.symbol, data.markPrice, data.lastClosePrice, data.isMarketClosed || false, data.tradingPeriodEnd);
            }
        } catch (e) {
            console.error('Failed to parse SSE data:', e);
        }
    };

    eventSource.onerror = (error) => {
        console.error('SSE connection error:', error);
        setSseStatus(false);
    };

    // Safety net: if SSE has been broken for 5 minutes, reload the page to restore it
    setInterval(() => {
        if (eventSource.readyState !== EventSource.OPEN && Date.now() - sseLastActivity > 5 * 60_000) {
            console.warn('SSE disconnected for 5 minutes, reloading page to recover...');
            location.reload();
        }
    }, 60_000);
}

// ── Column visibility controls ─────────────────────────────────────────────────

function initColumnVisibility() {
    const rebalToggle = document.getElementById('rebal-toggle');
    const body = document.body;

    // Load saved state from localStorage
    const rebalVisible = localStorage.getItem('ib-viewer-rebal-visible') === 'true';
    if (rebalVisible) {
        body.classList.add('rebalancing-visible');
        rebalToggle.classList.add('active');
    }

    rebalToggle.addEventListener('click', () => {
        const isVisible = body.classList.toggle('rebalancing-visible');
        rebalToggle.classList.toggle('active');
        localStorage.setItem('ib-viewer-rebal-visible', isVisible);
        updateTargetWeightTotal();
    });

    // Copy button handlers for the edit table are added via delegation in showEditTable()
}

// ── Dynamic edit table ────────────────────────────────────────────────────────

function buildStockEditTable() {
    const table = document.createElement('table');
    table.id = 'stock-edit-table';
    table.className = 'portfolio-table';

    // thead
    const thead = table.createTHead();
    const headerRow = thead.insertRow();
    const mkTh = (html, cls) => {
        const th = document.createElement('th');
        if (cls) th.className = cls;
        th.innerHTML = html;
        headerRow.appendChild(th);
    };
    mkTh('<button type="button" class="copy-table-btn copy-col-btn" title="Copy table to clipboard (Google Sheets)">' + COPY_ICON_SVG + '</button>', 'drag-handle-col');
    mkTh('Symbol <button type="button" class="copy-col-btn" data-column="symbol" title="Copy Symbol column">' + COPY_ICON_SVG + '</button>');
    mkTh('Qty <button type="button" class="copy-col-btn col-num" data-column="qty" title="Copy Qty column">' + COPY_ICON_SVG + '</button>', 'col-num');
    mkTh('Target % <button type="button" class="copy-col-btn" data-column="weight" title="Copy Target % column">' + COPY_ICON_SVG + '</button>');
    mkTh('Letf');
    mkTh('');

    // tbody — one row per view-table stock row
    const tbody = document.createElement('tbody');
    document.querySelectorAll('#stock-view-table tbody tr').forEach(viewRow => {
        const sym = viewRow.dataset.symbol || '';
        const qty = viewRow.dataset.qty || '0';
        const weight = viewRow.dataset.weight || '0';
        const letfAttr = viewRow.dataset.letf || '';
        let letfStr = '';
        if (letfAttr) {
            const tokens = letfAttr.split(',');
            const parts = [];
            for (let i = 0; i + 1 < tokens.length; i += 2) parts.push(tokens[i] + ' ' + tokens[i + 1]);
            letfStr = parts.join(' ');
        }

        const tr = document.createElement('tr');

        // Drag handle
        const tdDrag = document.createElement('td');
        tdDrag.className = 'drag-handle-cell';
        tdDrag.innerHTML = '<span class="drag-handle" draggable="true">⠿</span>';
        tr.appendChild(tdDrag);

        // Symbol input
        const tdSym = document.createElement('td');
        const symInput = document.createElement('input');
        symInput.type = 'text';
        symInput.className = 'edit-input edit-symbol';
        symInput.setAttribute('data-original-symbol', sym);
        symInput.setAttribute('data-column', 'symbol');
        symInput.value = sym;
        tdSym.appendChild(symInput);
        tr.appendChild(tdSym);

        // Qty input
        const tdQty = document.createElement('td');
        tdQty.className = 'amount';
        const qtyInput = document.createElement('input');
        qtyInput.type = 'number';
        qtyInput.className = 'edit-input edit-qty';
        qtyInput.setAttribute('data-symbol', sym);
        qtyInput.setAttribute('data-column', 'qty');
        qtyInput.value = qty;
        qtyInput.min = '0';
        qtyInput.step = 'any';
        tdQty.appendChild(qtyInput);
        tr.appendChild(tdQty);

        // Weight input
        const tdWeight = document.createElement('td');
        const weightInput = document.createElement('input');
        weightInput.type = 'number';
        weightInput.className = 'edit-input edit-weight';
        weightInput.setAttribute('data-symbol', sym);
        weightInput.setAttribute('data-column', 'weight');
        weightInput.value = weight;
        weightInput.min = '0';
        weightInput.max = '100';
        weightInput.step = '0.1';
        tdWeight.appendChild(weightInput);
        tr.appendChild(tdWeight);

        // Letf input
        const tdLetf = document.createElement('td');
        const letfInput = document.createElement('input');
        letfInput.type = 'text';
        letfInput.className = 'edit-input edit-letf';
        letfInput.setAttribute('data-symbol', sym);
        letfInput.setAttribute('data-column', 'letf');
        letfInput.value = letfStr;
        letfInput.style.textAlign = 'left';
        letfInput.style.width = '120px';
        tdLetf.appendChild(letfInput);
        tr.appendChild(tdLetf);

        // Delete button
        const tdDel = document.createElement('td');
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'delete-row-btn';
        delBtn.textContent = '×';
        tdDel.appendChild(delBtn);
        tr.appendChild(tdDel);

        tbody.appendChild(tr);
    });
    table.appendChild(tbody);

    // tfoot with weight total
    const tfoot = document.createElement('tfoot');
    const tfRow = tfoot.insertRow();
    const mkTd = (id, text) => {
        const td = document.createElement('td');
        if (id) td.id = id;
        if (text) td.textContent = text;
        tfRow.appendChild(td);
    };
    mkTd('', '');       // drag handle col
    mkTd('', 'Total');  // symbol col
    mkTd('', '');       // qty col
    mkTd('target-weight-total', ''); // weight col
    mkTd('', '');       // letf col
    mkTd('', '');       // delete col
    table.appendChild(tfoot);

    return table;
}

function showEditTable() {
    const editTable = buildStockEditTable();
    const viewTable = document.getElementById('stock-view-table');
    viewTable.parentNode.insertBefore(editTable, viewTable);
    viewTable.style.display = 'none';

    initDragAndDrop(editTable.querySelector('tbody'));

    // Event delegation on edit table for copy buttons
    editTable.addEventListener('click', e => {
        const copyTableBtn = e.target.closest('.copy-table-btn');
        if (copyTableBtn) {
            const rows = Array.from(editTable.querySelectorAll('tbody tr'))
                .filter(row => !row.dataset.deleted)
                .map(row => {
                    const get = col => row.querySelector('input[data-column="' + col + '"]')?.value ?? '';
                    return [get('symbol'), get('qty'), get('weight'), get('letf')].join('\t');
                });
            navigator.clipboard.writeText(rows.join('\n')).then(() => {
                const orig = copyTableBtn.innerHTML;
                copyTableBtn.innerHTML = CHECKMARK_SVG;
                copyTableBtn.classList.add('copied');
                setTimeout(() => { copyTableBtn.innerHTML = orig; copyTableBtn.classList.remove('copied'); }, 1500);
            });
            return;
        }
        const copyColBtn = e.target.closest('.copy-col-btn[data-column]');
        if (copyColBtn) {
            const col = copyColBtn.getAttribute('data-column');
            const inputs = Array.from(editTable.querySelectorAll('tbody input[data-column="' + col + '"]'));
            navigator.clipboard.writeText(inputs.map(i => i.value).join('\n')).then(() => {
                const orig = copyColBtn.innerHTML;
                copyColBtn.innerHTML = CHECKMARK_SVG;
                copyColBtn.classList.add('copied');
                setTimeout(() => { copyColBtn.innerHTML = orig; copyColBtn.classList.remove('copied'); }, 1500);
            });
        }
    });

    updateTargetWeightTotal();

    if (editTable.querySelector('tbody').querySelectorAll('tr:not([data-deleted])').length === 0) {
        const tr = addStockRow();
        if (tr) tr.querySelector('.new-symbol-input').focus();
    }
}

function removeEditTable() {
    document.getElementById('stock-edit-table')?.remove();
    const viewTable = document.getElementById('stock-view-table');
    if (viewTable) viewTable.style.display = '';
}

// ── Edit mode ─────────────────────────────────────────────────────────────────

function initEditMode() {
    const editToggle = document.getElementById('edit-toggle');
    const saveBtn = document.getElementById('save-btn');
    const body = document.body;

    editToggle.addEventListener('click', () => {
        const isEditing = body.classList.toggle('editing-active');
        editToggle.classList.toggle('active');

        if (isEditing) {
            // Build and show edit table for stocks
            showEditTable();

            // Reset cash edit table inputs to original values
            document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
                const keyInput = tr.querySelector('.cash-edit-key');
                const valInput = tr.querySelector('.cash-edit-value');
                if (keyInput) keyInput.value = keyInput.getAttribute('data-original-key') || '';
                if (valInput) valInput.value = valInput.getAttribute('data-original-value') || '';
            });

            // Add empty row if cash edit table has no entries
            const cashTbody = document.querySelector('.cash-edit-table tbody');
            if (cashTbody && cashTbody.querySelectorAll('tr:not([data-deleted])').length === 0) {
                addCashRow();
            }

        } else {
            // Remove the dynamic edit table, restore view table
            removeEditTable();
            // Remove dynamically added new cash rows
            document.querySelectorAll('[data-new-cash]').forEach(el => el.remove());
            // Reset cash edit table inputs to original values
            document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
                const keyInput = tr.querySelector('.cash-edit-key');
                const valInput = tr.querySelector('.cash-edit-value');
                if (keyInput) keyInput.value = keyInput.getAttribute('data-original-key') || '';
                if (valInput) valInput.value = valInput.getAttribute('data-original-value') || '';
            });
        }
        updateTargetWeightTotal();
    });

    // Save button
    saveBtn.addEventListener('click', () => {
        const updates = [];
        // Existing stock rows (not dynamically added)
        document.querySelectorAll('#stock-edit-table tbody tr:not([data-new-stock])').forEach(tr => {
            if (tr.dataset.deleted) return;
            const symInput = tr.querySelector('.edit-symbol');
            const sym = symInput ? symInput.value.trim().toUpperCase() : null;
            if (!sym) return;
            const qtyInput = tr.querySelector('.edit-qty');
            const weightInput = tr.querySelector('.edit-weight');
            const letfInput = tr.querySelector('.edit-letf');
            updates.push({
                symbol: sym,
                amount: parseFloat(qtyInput?.value) || 0,
                targetWeight: weightInput ? parseFloat(weightInput.value) || 0 : 0,
                letf: letfInput?.value || ''
            });
        });
        // New stock rows added in edit mode
        document.querySelectorAll('#stock-edit-table tbody tr[data-new-stock]').forEach(tr => {
            if (tr.dataset.deleted) return;
            const sym = (tr.querySelector('.new-symbol-input')?.value || '').trim().toUpperCase();
            if (!sym) return;
            updates.push({
                symbol: sym,
                amount: parseFloat(tr.querySelector('input[data-column="qty"]')?.value) || 0,
                targetWeight: parseFloat(tr.querySelector('input[data-column="weight"]')?.value) || 0,
                letf: tr.querySelector('input[data-column="letf"]')?.value || ''
            });
        });

        const cashUpdates = [];
        // All cash edit rows (existing server-rendered + dynamically added, same structure)
        document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
            if (tr.dataset.deleted) return;
            const key = (tr.querySelector('.cash-edit-key')?.value || '').trim();
            const value = (tr.querySelector('.cash-edit-value')?.value || '').trim();
            if (!key) return;
            cashUpdates.push({ key, value });
        });

        saveBtn.disabled = true;
        editToggle.disabled = true;
        saveBtn.querySelector('.toggle-label').textContent = 'Saving...';

        const saves = [
            fetch('/api/portfolio/update?portfolio=' + portfolioId, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updates)
            }),
            fetch('/api/cash/update?portfolio=' + portfolioId, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(cashUpdates)
            })
        ];

        Promise.all(saves).then(results => {
            if (results.every(r => r.ok)) {
                // File watcher would detect changes and triggers SSE reload
            } else throw new Error('Save failed');
        }).catch(err => {
            alert('Failed to save: ' + err.message);
            saveBtn.disabled = false;
            editToggle.disabled = false;
            saveBtn.querySelector('.toggle-label').textContent = 'Save';
        });
    });

    // Delete button handler (event delegation for both static and dynamic rows)
    document.addEventListener('click', e => {
        const btn = e.target.closest('.delete-row-btn, .delete-cash-btn');
        if (!btn || !body.classList.contains('editing-active')) return;
        const row = btn.closest('tr');
        if (row) {
            row.setAttribute('data-deleted', 'true');
            row.style.display = 'none';
            updateTargetWeightTotal();
        }
    });

    // Input handler for target weight column: strip % and update live total
    document.addEventListener('input', e => {
        if (e.target.classList.contains('edit-weight') || e.target.getAttribute('data-column') === 'weight') {
            if (e.target.value.includes('%')) e.target.value = e.target.value.replace(/%/g, '');
            updateTargetWeightTotal();
        }
    });

    // Add Stock button handler
    document.getElementById('add-stock-btn')?.addEventListener('click', () => {
        const tr = addStockRow();
        if (tr) tr.querySelector('.new-symbol-input').focus();
    });

    // Add Cash Entry button handler
    document.getElementById('add-cash-btn')?.addEventListener('click', () => {
        const tr = addCashRow();
        if (tr) tr.querySelector('.cash-edit-key').focus();
    });

    // Virtual Rebalance button — backup current state, enter edit mode, set qty to target weight allocation
    document.getElementById('virtual-rebal-btn')?.addEventListener('click', async () => {
        // Snapshot current state before modifying (separate rebalance backup folder)
        try {
            await fetch('/api/backup/trigger?portfolio=' + portfolioId + '&subfolder=rebalance', { method: 'POST' });
        } catch (_) { /* non-fatal */ }

        // Enter edit mode if not already active
        if (!body.classList.contains('editing-active')) {
            editToggle.click();
        }

        // Apply rebalancing: new qty = targetWeight% * portfolioTotal / markPrice
        const portfolioTotal = getRebalTotal();
        document.querySelectorAll('#stock-edit-table tbody .edit-qty').forEach(input => {
            const sym = input.getAttribute('data-symbol');
            const viewRow = document.querySelector('#stock-view-table tbody tr[data-symbol="' + sym + '"]');
            if (!viewRow) return;
            const targetWeight = parseFloat(viewRow.dataset.weight);
            if (isNaN(targetWeight)) return;
            if (targetWeight <= 0) {
                input.value = 0;
                return;
            }
            const markCell = document.getElementById('mark-' + sym);
            const markPrice = rawMarkPrices[sym] ?? parsePrice(markCell ? markCell.textContent : null);
            if (!markPrice || markPrice <= 0) return;
            input.value = parseFloat(((targetWeight / 100) * portfolioTotal / markPrice).toFixed(2));
        });
    });
}

// ── Paste handler ─────────────────────────────────────────────────────────────

function initPasteHandler() {
    document.addEventListener('paste', (e) => {
        if (!document.body.classList.contains('editing-active')) return;

        const activeEl = document.activeElement;
        if (!activeEl || !activeEl.classList.contains('edit-input')) return;

        const clipText = (e.clipboardData || window.clipboardData).getData('text');
        const lines = clipText.split(/[\r\n]+/).filter(l => l.trim() !== '');

        // For single-value paste into a weight field, strip % before the browser rejects it
        if (lines.length <= 1) {
            if (activeEl.classList.contains('edit-weight') || activeEl.getAttribute('data-column') === 'weight') {
                const stripped = clipText.replace(/%/g, '').trim();
                if (stripped !== clipText.trim()) {
                    e.preventDefault();
                    activeEl.value = stripped;
                    activeEl.dispatchEvent(new Event('input', { bubbles: true }));
                }
            }
            return;
        }

        e.preventDefault();

        const rows = lines.map(l => l.split('\t'));
        const isMultiCol = rows.some(r => r.length >= 2);

        const isCashKey   = activeEl.classList.contains('cash-edit-key');
        const isCashValue = activeEl.classList.contains('cash-edit-value');

        if (isCashKey || isCashValue) {
            // === Cash edit table ===
            if (isMultiCol) {
                // Multi-column: col0 → key, col1 → value (any extra cols ignored)
                const tbody = document.querySelector('.cash-edit-table tbody');
                if (!tbody) return;
                let allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])'));
                const startRow = activeEl.closest('tr');
                let startIdx = allRows.indexOf(startRow);
                if (startIdx < 0) startIdx = allRows.length;

                rows.forEach((cols, i) => {
                    let tr = allRows[startIdx + i];
                    if (!tr) { tr = addCashRow(); allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])')); }
                    if (!tr) return;
                    const k = tr.querySelector('.cash-edit-key');
                    const v = tr.querySelector('.cash-edit-value');
                    if (k) k.value = cols[0].trim();
                    if (v && cols[1] !== undefined) v.value = cols[1].trim();
                });
            } else {
                // Single-column: fill focused column only, add rows as needed
                const sel = isCashKey ? '.cash-edit-key' : '.cash-edit-value';
                let allInputs = Array.from(document.querySelectorAll(sel));
                const startIdx = allInputs.indexOf(activeEl);
                if (startIdx < 0) return;
                lines.forEach((line, i) => {
                    if (startIdx + i < allInputs.length) {
                        allInputs[startIdx + i].value = line.trim();
                    } else {
                        const tr = addCashRow();
                        if (!tr) return;
                        allInputs = Array.from(document.querySelectorAll(sel));
                        const inp = tr.querySelector(sel);
                        if (inp) inp.value = line.trim();
                    }
                });
            }
        } else {
            // === Stock table ===
            const startColIdx = getStockColIndex(activeEl);
            if (startColIdx < 0) return;

            const tbody = document.querySelector('#stock-edit-table tbody');
            if (!tbody) return;
            let allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])'));
            const startRow = activeEl.closest('tr');
            let startRowIdx = allRows.indexOf(startRow);
            if (startRowIdx < 0) startRowIdx = allRows.length;

            rows.forEach((cols, i) => {
                let tr = allRows[startRowIdx + i];
                if (!tr) { tr = addStockRow(); allRows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])')); }
                if (!tr) return;
                const inputs = getStockRowInputs(tr);
                cols.forEach((val, j) => {
                    const idx = startColIdx + j;
                    if (idx < inputs.length) {
                        const inp = inputs[idx];
                        const isWeight = inp.classList.contains('edit-weight') || inp.getAttribute('data-column') === 'weight';
                        inp.value = isWeight ? val.replace(/%/g, '').trim() : val.trim();
                    }
                });
            });
            updateTargetWeightTotal();
        }
    });
}

// ── Currency controls ─────────────────────────────────────────────────────────

function initCurrencyControls() {
    // Currency toggle button (≤3 currencies)
    const currencyToggle = document.getElementById('currency-toggle');
    if (currencyToggle) {
        const currencies = currencyToggle.getAttribute('data-currencies').split(',');
        const saved = localStorage.getItem('ib-viewer-display-currency');
        if (saved && currencies.includes(saved)) {
            currentDisplayCurrency = saved;
            currencyToggle.querySelector('.toggle-label').textContent = saved;
        }
        currencyToggle.addEventListener('click', () => {
            const idx = currencies.indexOf(currentDisplayCurrency);
            currentDisplayCurrency = currencies[(idx + 1) % currencies.length];
            currencyToggle.querySelector('.toggle-label').textContent = currentDisplayCurrency;
            localStorage.setItem('ib-viewer-display-currency', currentDisplayCurrency);
            refreshDisplayCurrency();
        });
    }

    // Currency select dropdown (>3 currencies)
    const currencySelect = document.getElementById('currency-select');
    if (currencySelect) {
        const savedSel = localStorage.getItem('ib-viewer-display-currency');
        if (savedSel) { currentDisplayCurrency = savedSel; currencySelect.value = savedSel; }
        currencySelect.addEventListener('change', () => {
            currentDisplayCurrency = currencySelect.value;
            localStorage.setItem('ib-viewer-display-currency', currentDisplayCurrency);
            refreshDisplayCurrency();
        });
    }
}

// ── Rebalance controls ────────────────────────────────────────────────────────

function initRebalanceControls() {
    let rebalSaveTimer = null;
    const rebalTargetInput = document.getElementById('rebal-target-input');
    const marginTargetInput = document.getElementById('margin-target-input');

    if (rebalTargetInput) {
        rebalTargetInput.addEventListener('input', () => {
            const raw = rebalTargetInput.value.trim().replace(/,/g, '');
            if (raw === '' || isNaN(parseFloat(raw))) {
                rebalTargetUsd = null;
            } else {
                const inputNum = parseFloat(raw);
                const rate = fxRates[currentDisplayCurrency];
                rebalTargetUsd = (rate && rate !== 0) ? inputNum * rate : inputNum;
            }
            marginTargetPct = null;
            if (marginTargetInput) marginTargetInput.value = '';
            updateRebalancingColumns(getRebalTotal());
            updateAllocColumns(getAllocRebalTotal());
            updateMarginTargetDisplay();
            updateRebalTargetPlaceholder();
            // Debounced save to server
            clearTimeout(rebalSaveTimer);
            rebalSaveTimer = setTimeout(() => {
                fetch('/api/portfolio-config/save?portfolio=' + portfolioId + '&key=rebalTarget', {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: rebalTargetUsd !== null && rebalTargetUsd > 0 ? rebalTargetUsd.toString() : ''
                });
            }, 1000);
        });
    }

    // Margin Target % input
    if (marginTargetInput) {
        marginTargetInput.addEventListener('input', () => {
            const raw = marginTargetInput.value.trim();
            if (raw === '' || isNaN(parseFloat(raw))) {
                marginTargetPct = null;
                rebalTargetUsd = null;
            } else {
                marginTargetPct = parseFloat(raw);
                rebalTargetUsd = null;
            }
            rebalTargetInput.value = '';
            updateRebalTargetPlaceholder();
            updateRebalancingColumns(getRebalTotal());
            updateAllocColumns(getAllocRebalTotal());
            updateMarginTargetDisplay();
            // Debounced save to server (saves margin % as-is, server clears rebalTarget)
            clearTimeout(rebalSaveTimer);
            rebalSaveTimer = setTimeout(() => {
                fetch('/api/portfolio-config/save?portfolio=' + portfolioId + '&key=marginTarget', {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: marginTargetPct !== null ? marginTargetPct.toString() : ''
                });
            }, 1000);
        });
    }

    // Alloc mode dropdowns
    const allocAddSelect = document.getElementById('alloc-add-mode');
    const allocReduceSelect = document.getElementById('alloc-reduce-mode');
    if (allocAddSelect) {
        allocAddSelect.value = allocAddMode;
        allocAddSelect.addEventListener('change', () => {
            allocAddMode = allocAddSelect.value;
            fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=allocAddMode`, { method: 'POST', body: allocAddMode });
            updateAllocColumns(getAllocRebalTotal());
        });
    }
    if (allocReduceSelect) {
        allocReduceSelect.value = allocReduceMode;
        allocReduceSelect.addEventListener('change', () => {
            allocReduceMode = allocReduceSelect.value;
            fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=allocReduceMode`, { method: 'POST', body: allocReduceMode });
            updateAllocColumns(getAllocRebalTotal());
        });
    }
}

// ── Backup panel ─────────────────────────────────────────────────────────────

function initBackupPanel() {
    // Restore Backup button
    document.getElementById('restore-backup-btn')?.addEventListener('click', async () => {
        // Backup current state before showing restore options
        try {
            await fetch('/api/backup/trigger?portfolio=' + portfolioId, { method: 'POST' });
        } catch (_) { /* non-fatal */ }

        let allBackups;
        try {
            const resp = await fetch('/api/backup/list?portfolio=' + portfolioId);
            allBackups = await resp.json();
        } catch (e) {
            alert('Failed to load backup list.');
            return;
        }

        const groups = Object.entries(allBackups); // [["default", [...]], ["rebalance", [...]]]
        const totalCount = groups.reduce((sum, [, v]) => sum + v.length, 0);

        // Build modal
        const overlay = document.createElement('div');
        overlay.className = 'backup-modal-overlay';

        const modal = document.createElement('div');
        modal.className = 'backup-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');

        const titleEl = document.createElement('p');
        titleEl.className = 'backup-modal-title';
        titleEl.textContent = 'Restore from Backup';
        modal.appendChild(titleEl);

        if (totalCount === 0) {
            const empty = document.createElement('p');
            empty.className = 'backup-modal-empty';
            empty.textContent = 'No backups available.';
            modal.appendChild(empty);
        } else {
            const bodyEl = document.createElement('div');
            bodyEl.className = 'backup-modal-body';

            const tabBar = document.createElement('div');
            tabBar.className = 'backup-modal-tabs';

            const panels = {};
            groups.forEach(([key, dates], idx) => {
                const displayName = key === 'default' ? 'Daily' : key.charAt(0).toUpperCase() + key.slice(1);

                // Tab button (only rendered when multiple groups exist)
                if (groups.length > 1) {
                    const tab = document.createElement('button');
                    tab.className = 'backup-modal-tab' + (idx === 0 ? ' active' : '');
                    tab.textContent = displayName;
                    tab.addEventListener('click', () => {
                        tabBar.querySelectorAll('.backup-modal-tab').forEach(t => t.classList.remove('active'));
                        tab.classList.add('active');
                        Object.entries(panels).forEach(([k, p]) => { p.hidden = k !== key; });
                    });
                    tabBar.appendChild(tab);
                }

                // Panel
                const panel = document.createElement('div');
                panel.className = 'backup-modal-panel';
                panel.hidden = idx !== 0;

                if (dates.length === 0) {
                    const empty = document.createElement('p');
                    empty.className = 'backup-modal-empty';
                    empty.textContent = 'No backups available.';
                    panel.appendChild(empty);
                } else {
                    const list = document.createElement('ul');
                    list.className = 'backup-modal-list';
                    dates.forEach(date => {
                        const item = document.createElement('li');
                        item.className = 'backup-modal-item';
                        const label = document.createElement('span');
                        label.textContent = date;
                        const restoreBtn = document.createElement('button');
                        restoreBtn.textContent = 'Restore';
                        restoreBtn.addEventListener('click', async () => {
                            restoreBtn.disabled = true;
                            restoreBtn.textContent = '…';
                            const subParam = key !== 'default' ? '&subfolder=' + encodeURIComponent(key) : '';
                            try {
                                const r = await fetch('/api/backup/restore?portfolio=' + portfolioId + '&date=' + encodeURIComponent(date) + subParam, { method: 'POST' });
                                const json = await r.json();
                                if (json.status === 'ok') {
                                    document.body.removeChild(overlay);
                                    location.reload();
                                } else {
                                    alert('Restore failed: ' + (json.message || 'Unknown error'));
                                    restoreBtn.disabled = false;
                                    restoreBtn.textContent = 'Restore';
                                }
                            } catch (e) {
                                alert('Restore failed.');
                                restoreBtn.disabled = false;
                                restoreBtn.textContent = 'Restore';
                            }
                        });
                        item.appendChild(label);
                        item.appendChild(restoreBtn);
                        list.appendChild(item);
                    });
                    panel.appendChild(list);
                }
                panels[key] = panel;
            });

            if (groups.length > 1) bodyEl.appendChild(tabBar);
            Object.values(panels).forEach(p => bodyEl.appendChild(p));
            modal.appendChild(bodyEl);
        }

        const footer = document.createElement('div');
        footer.className = 'backup-modal-footer';
        const closeBtn = document.createElement('button');
        closeBtn.className = 'backup-modal-close';
        closeBtn.textContent = 'Cancel';
        closeBtn.addEventListener('click', () => document.body.removeChild(overlay));
        footer.appendChild(closeBtn);
        modal.appendChild(footer);

        overlay.appendChild(modal);
        overlay.addEventListener('click', e => { if (e.target === overlay) document.body.removeChild(overlay); });
        document.body.appendChild(overlay);
        closeBtn.focus();
    });

    // IBKR margin rates reload button
    const ibkrReloadBtn = document.getElementById('ibkr-reload-btn');
    if (ibkrReloadBtn) {
        const COOLDOWN_MS = 10 * 60 * 1000;
        const lastFetch = parseInt(ibkrReloadBtn.dataset.lastFetch || '0', 10);
        const elapsed = lastFetch > 0 ? Date.now() - lastFetch : COOLDOWN_MS + 1;
        if (elapsed < COOLDOWN_MS) {
            ibkrReloadBtn.disabled = true;
            setTimeout(() => { ibkrReloadBtn.disabled = false; }, COOLDOWN_MS - elapsed);
        }
        ibkrReloadBtn.addEventListener('click', async () => {
            ibkrReloadBtn.disabled = true;
            ibkrReloadBtn.textContent = '…';
            try {
                const resp = await fetch('/api/margin-rates/reload', { method: 'POST' });
                if (resp.ok) {
                    location.reload();
                } else {
                    ibkrReloadBtn.textContent = '↻';
                    // If server says cooldown still active, re-check after 1 min
                    setTimeout(() => { ibkrReloadBtn.disabled = false; }, 60_000);
                }
            } catch (e) {
                ibkrReloadBtn.textContent = '↻';
                ibkrReloadBtn.disabled = false;
            }
        });
    }
}

// ── Drag-and-drop row reordering ──────────────────────────────────────────────

function initDragAndDrop(tbody) {
    if (!tbody) return;
    let dragRow = null;

    tbody.addEventListener('dragstart', e => {
        if (!document.body.classList.contains('editing-active')) return;
        const handle = e.target.closest('.drag-handle');
        if (!handle) { e.preventDefault(); return; }
        dragRow = handle.closest('tr');
        dragRow.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', '');
    });

    tbody.addEventListener('dragover', e => {
        if (!dragRow) return;
        e.preventDefault();
        const row = e.target.closest('tr');
        tbody.querySelectorAll('.drag-over-top, .drag-over-bottom')
             .forEach(r => r.classList.remove('drag-over-top', 'drag-over-bottom'));
        if (!row || row === dragRow || row.dataset.deleted) {
            // Cursor is over tbody gap or a deleted row — highlight last non-deleted row's bottom
            const rows = Array.from(tbody.querySelectorAll('tr:not([data-deleted])')).filter(r => r !== dragRow);
            if (rows.length) rows[rows.length - 1].classList.add('drag-over-bottom');
            return;
        }
        const rect = row.getBoundingClientRect();
        row.classList.add(e.clientY < rect.top + rect.height / 2 ? 'drag-over-top' : 'drag-over-bottom');
    });

    tbody.addEventListener('drop', e => {
        if (!dragRow) return;
        e.preventDefault();
        const row = e.target.closest('tr');
        if (row && row !== dragRow && !row.dataset.deleted) {
            const rect = row.getBoundingClientRect();
            tbody.insertBefore(dragRow, e.clientY < rect.top + rect.height / 2 ? row : row.nextSibling);
        } else if (!row || row.dataset.deleted) {
            tbody.appendChild(dragRow);
        }
        cleanup();
    });

    tbody.addEventListener('dragend', cleanup);

    function cleanup() {
        tbody.querySelectorAll('tr').forEach(r =>
            r.classList.remove('drag-over-top', 'drag-over-bottom', 'dragging'));
        dragRow = null;
    }
}

// ── TWS Sync ──────────────────────────────────────────────────────────────────

async function initTwsSync() {
    const btn = document.getElementById('tws-sync-btn');
    if (!btn) return;

    btn.addEventListener('click', async () => {
        btn.disabled = true;
        btn.textContent = 'Syncing\u2026';
        try {
            const res = await fetch('/api/tws/snapshot?portfolio=' + portfolioId);
            const data = await res.json();
            if (data.error) { showTwsSyncError('TWS sync error: ' + data.error); return; }

            // Enter edit mode if not already active
            if (!document.body.classList.contains('editing-active')) {
                document.getElementById('edit-toggle')?.click();
            }

            // ── Clear all existing stock qty values ──
            document.querySelectorAll('#stock-edit-table .edit-qty').forEach(input => {
                input.value = '';
            });

            // ── Update stock qty ──
            for (const pos of data.positions) {
                const sym = pos.symbol;
                const qty = pos.qty;
                let qtyInput = document.querySelector(
                    '#stock-edit-table .edit-qty[data-symbol="' + sym + '"]'
                );
                if (qtyInput) {
                    qtyInput.value = qty;
                } else {
                    const tr = addStockRow();
                    if (tr) {
                        const [symIn, qtyIn] = getStockRowInputs(tr);
                        if (symIn) symIn.value = sym;
                        if (qtyIn) qtyIn.value = qty;
                    }
                }
            }

            // ── Update Cash.XXX.M entries ──
            for (const [ccy, amt] of Object.entries(data.cashBalances)) {
                updateOrAddCashRow('Cash.' + ccy + '.M', String(amt));
            }

            // ── Update MTD Interest.XXX entries ──
            for (const [ccy, amt] of Object.entries(data.accruedCash)) {
                updateOrAddCashRow('MTD Interest.' + ccy, String(amt));
            }

            updateTargetWeightTotal();
        } catch (e) {
            showTwsSyncError('TWS sync failed: ' + e.message);
        } finally {
            btn.disabled = false;
            btn.textContent = 'Sync TWS';
        }
    });
}

function initSaveToBacktest() {
  document.getElementById('save-to-backtest-btn')?.addEventListener('click', async () => {
      // Collect tickers + target weights from the table
      const tickers = [...document.querySelectorAll('#stock-view-table tbody tr')].map(row => ({
          ticker: row.dataset.symbol,
          weight: parseFloat(row.dataset.weight) || 0
      })).filter(t => t.ticker && t.weight > 0);

      // Margin: use saved target, fall back to current displayed margin %
      const marginTargetInput = document.getElementById('margin-target-input');
      const marginPercentEl = document.getElementById('margin-percent');
      const marginTargetPct = marginTargetInput?.value
          ? parseFloat(marginTargetInput.value)
          : parseFloat(marginPercentEl?.textContent?.replace(/[()%]/g, '')) || 0;

      const allocAddMode = document.getElementById('alloc-add-mode')?.value || 'PROPORTIONAL';
      const allocReduceMode = document.getElementById('alloc-reduce-mode')?.value || 'PROPORTIONAL';

      const config = {
          tickers,
          rebalanceStrategy: 'YEARLY',
          marginStrategies: marginTargetPct > 0 ? [{
              marginRatio:          marginTargetPct / 100,
              marginSpread:         0.015,
              marginDeviationUpper: 0.05,
              marginDeviationLower: 0.05,
              upperRebalanceMode:   allocReduceMode,
              lowerRebalanceMode:   allocAddMode
          }] : []
      };

      const name = document.querySelector('h1')?.textContent?.trim() || 'Portfolio';

      const res = await fetch('/api/backtest/savedPortfolios', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, config })
      });

      if (res.ok) {
          const btn = document.getElementById('save-to-backtest-btn');
          const original = btn.textContent;
          btn.textContent = 'Saved!';
          setTimeout(() => { btn.textContent = original; }, 1500);
      }
  });
}

function initThemeToggle() {
    const themeToggle = document.getElementById('theme-toggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const current = document.documentElement.getAttribute('data-theme');
            const next = current === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('ib-viewer-theme', next);
        });
    }
}

function showTwsSyncError(msg) {
    let el = document.getElementById('tws-sync-error');
    if (!el) {
        el = document.createElement('span');
        el.id = 'tws-sync-error';
        el.className = 'tws-sync-error';
        document.getElementById('tws-sync-btn')?.insertAdjacentElement('afterend', el);
    }
    el.textContent = msg;
    el.style.display = 'inline';
    clearTimeout(el._hideTimer);
    el._hideTimer = setTimeout(() => { el.style.display = 'none'; }, 4000);
}

function updateOrAddCashRow(key, value) {
    const rows = document.querySelectorAll('[data-cash-edit-row]');
    for (const tr of rows) {
        if (tr.dataset.deleted) continue;
        const keyInput = tr.querySelector('.cash-edit-key');
        if (keyInput && keyInput.value.toLowerCase() === key.toLowerCase()) {
            const valInput = tr.querySelector('.cash-edit-value');
            if (valInput) valInput.value = value;
            return;
        }
    }
    const tr = addCashRow();
    if (tr) {
        const keyInput = tr.querySelector('.cash-edit-key');
        const valInput = tr.querySelector('.cash-edit-value');
        if (keyInput) keyInput.value = key;
        if (valInput) valInput.value = value;
    }
}

// ── Bootstrap ─────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    initSseConnection();
    initColumnVisibility();
    initEditMode();
    initPasteHandler();
    initCurrencyControls();
    initRebalanceControls();
    initBackupPanel();
    initTwsSync();
    initSaveToBacktest();
    initThemeToggle();

    // Initialize cash totals on page load (USD entries are pre-filled server-side)
    // Must run before restoring targets so lastMarginUsd is correct
    updateCashTotals();

    // Restore saved target on page load — margin % takes priority over rebal USD
    const marginTargetInput = document.getElementById('margin-target-input');
    const rebalTargetInput = document.getElementById('rebal-target-input');
    if (savedMarginTargetPct > 0 && marginTargetInput) {
        marginTargetPct = savedMarginTargetPct;
        marginTargetInput.value = savedMarginTargetPct.toLocaleString('en-US', {
            minimumFractionDigits: 1, maximumFractionDigits: 4
        });
        updateRebalancingColumns(getRebalTotal());
        updateAllocColumns(getAllocRebalTotal());
        updateMarginTargetDisplay();
    } else if (savedRebalTargetUsd > 0 && rebalTargetInput) {
        rebalTargetUsd = savedRebalTargetUsd;
        const rate = fxRates[currentDisplayCurrency];
        const displayVal = (rate && rate !== 0) ? savedRebalTargetUsd / rate : savedRebalTargetUsd;
        rebalTargetInput.value = displayVal.toLocaleString('en-US', {
            minimumFractionDigits: 2, maximumFractionDigits: 2
        });
        updateRebalancingColumns(getRebalTotal());
        updateAllocColumns(getAllocRebalTotal());
        updateMarginTargetDisplay();
    }

    // Refresh display currency for any server-rendered values (portfolio total, day change)
    if (currentDisplayCurrency !== 'USD') {
        refreshDisplayCurrency();
    }

    updateRebalTargetPlaceholder();
    updateMarginTargetDisplay();
    updateTargetWeightTotal();
});