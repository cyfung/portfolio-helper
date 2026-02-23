// Global store for Day % of all symbols (portfolio + LETF components)
const componentDayPercents = {};
let globalIsMarketClosed = false;
let marketCloseTimeMs = null; // Unix ms of tradingPeriodEnd
// Display currency (USD by default; lastPortfolioVal/lastCashTotalUsd/etc. declared in inline script)
let currentDisplayCurrency = 'USD';
let rebalTargetUsd = null; // null = use lastPortfolioVal

// Connect to SSE for live price updates
const eventSource = new EventSource('/api/prices/stream');

eventSource.onmessage = (event) => {
    try {
        const data = JSON.parse(event.data);

        if (data.type === 'reload') {
            // Portfolio structure changed, reload page
            console.log('Portfolio reloaded, refreshing page...');
            location.reload();
        } else if (data.type === 'nav') {
            // NAV update
            updateNavInUI(data.symbol, data.nav);
        } else {
            // FX rate update for cash currency conversion
            if (data.symbol && data.symbol.endsWith('USD=X')) {
                const ccy = data.symbol.replace('USD=X', '');
                if (data.markPrice !== null && data.markPrice !== undefined) {
                    fxRates[ccy] = data.markPrice;
                    updateCashTotals();
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
};

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
        marketCloseTimeMs = tradingPeriodEnd * 1000; // seconds → ms
    }

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
                changeCell.className = 'price-change loaded neutral' + afterHoursClass;
            } else {
                const sign = changeDollars >= 0 ? '+' : '-';
                changeCell.textContent = sign + '$' + Math.abs(changeDollars).toFixed(2);
                const direction = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';
                changeCell.className = 'price-change loaded ' + direction + afterHoursClass;
            }
        }

        // Update day change % cell
        const changePercentCell = document.getElementById('day-percent-' + symbol);
        if (changePercentCell) {
            if (isZeroChange) {
                changePercentCell.textContent = '—';
                changePercentCell.className = 'price-change loaded neutral' + afterHoursClass;
            } else {
                const sign = changePercent >= 0 ? '+' : '-';
                changePercentCell.textContent = sign + Math.abs(changePercent).toFixed(2) + '%';
                const direction = changePercent > 0 ? 'positive' : changePercent < 0 ? 'negative' : 'neutral';
                changePercentCell.className = 'price-change loaded ' + direction + afterHoursClass;
            }
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
                    positionChangeCell.className = 'price-change loaded neutral' + afterHoursClass;
                } else {
                    positionChangeCell.textContent = formatSignedCurrency(positionChange);
                    const direction = positionChange > 0 ? 'positive' : positionChange < 0 ? 'negative' : 'neutral';
                    positionChangeCell.className = 'price-change loaded ' + direction + afterHoursClass;
                }
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

    // Calculate current total and previous day's total
    document.querySelectorAll('tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.querySelector('td:first-child').textContent.trim();
        const amountCell = document.getElementById('amount-' + symbol);
        const markCell = document.getElementById('mark-' + symbol);
        const closeCell = document.getElementById('close-' + symbol);

        if (amountCell && markCell && closeCell) {
            const amount = parseFloat(amountCell.textContent);
            const markPrice = parsePrice(markCell.textContent);
            const closePrice = parsePrice(closeCell.textContent);

            if (markPrice !== null) total += markPrice * amount;
            if (closePrice !== null) previousTotal += closePrice * amount;
        }
    });

    lastPortfolioVal = total;
    lastPrevPortfolioVal = previousTotal;
    updateRebalTargetPlaceholder();

    // Update portfolio total
    const totalCell = document.getElementById('portfolio-total');
    if (totalCell) totalCell.textContent = formatDisplayCurrency(total);

    // Update portfolio daily change
    const changeDollars = total - previousTotal;
    lastPortfolioDayChangeUsd = changeDollars;
    const changePercent = previousTotal > 0 ? (changeDollars / previousTotal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';

    // Portfolio day change (% relative to previous portfolio value)
    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        const sign = changeDollars >= 0 ? '+' : '-';
        portfolioChangeCell.innerHTML =
            '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
            '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
    }

    updateCurrentWeights(total);
    updateRebalancingColumns(getRebalTotal());
    updateMarginTargetDisplay();

    // Update grand total (portfolio + cash)
    const grandEl = document.getElementById('grand-total-value');
    if (grandEl) grandEl.textContent = formatDisplayCurrency(total + lastCashTotalUsd);

    // Total Value day change: same $ amount, but % relative to previous grand total
    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        const prevGrandTotal = previousTotal + lastCashTotalUsd;
        const totalChangePercent = prevGrandTotal !== 0 ? (changeDollars / Math.abs(prevGrandTotal)) * 100 : 0;
        const sign = changeDollars >= 0 ? '+' : '-';
        totalChangeCell.innerHTML =
            '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
            '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(totalChangePercent).toFixed(2) + '%)</span>';
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
    return (rebalTargetUsd !== null && rebalTargetUsd > 0) ? rebalTargetUsd : lastPortfolioVal;
}

function updateRebalTargetPlaceholder() {
    const input = document.getElementById('rebal-target-input');
    if (!input) return;
    const converted = toDisplayCurrency(lastPortfolioVal);
    input.placeholder = Math.abs(converted).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
    });
}

function updateMarginTargetDisplay() {
    const marginTargetRow = document.getElementById('margin-target-row');
    if (!marginTargetRow) return;

    if (rebalTargetUsd === null) {
        marginTargetRow.style.display = 'none';
        return;
    }

    const rebalTotal = getRebalTotal();
    const marginTargetUsd = lastMarginUsd - (rebalTotal - lastPortfolioVal);

    if (marginTargetUsd >= 0) {
        marginTargetRow.style.display = 'none';
        return;
    }
    marginTargetRow.style.display = '';

    const marginTargetEl = document.getElementById('margin-target-usd');
    if (marginTargetEl) marginTargetEl.textContent = formatDisplayCurrency(-marginTargetUsd);

    const marginTargetPctEl = document.getElementById('margin-target-percent');
    const denominator = rebalTotal + lastEquityUsd + marginTargetUsd;
    const pct = denominator !== 0 ? Math.abs(marginTargetUsd / denominator) * 100 : 0;
    if (marginTargetPctEl) {
        marginTargetPctEl.textContent = ' (' + pct.toFixed(1) + '%)';
        marginTargetPctEl.style.display = '';
    }
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

    marginEl.textContent = formatDisplayCurrency(-marginUsd);

    const marginPctEl = document.getElementById('margin-percent');
    const denominator = lastPortfolioVal + lastEquityUsd + lastMarginUsd;
    const pct = denominator !== 0 ? Math.abs(marginUsd / denominator) * 100 : 0;
    if (marginPctEl) {
        marginPctEl.textContent = ' (' + pct.toFixed(1) + '%)';
        marginPctEl.style.display = '';
    }
}

function updateIbkrDailyInterest() {
    const rows = document.querySelectorAll('.ibkr-rates-table tbody tr[data-ibkr-rate]');
    if (!rows.length) return;

    const loanUsd = lastMarginUsd < 0 ? -lastMarginUsd : 0;

    // First pass: compute all interests to find the minimum
    const interests = Array.from(rows).map(tr => {
        const rate = parseFloat(tr.dataset.ibkrRate);
        const days = parseInt(tr.dataset.ibkrDays, 10);
        return loanUsd > 0 ? loanUsd * rate / 100 / days : null;
    });
    const minInterest = interests.reduce((m, v) => (v !== null && (m === null || v < m)) ? v : m, null);

    // Second pass: update DOM
    rows.forEach((tr, i) => {
        const ccy = tr.querySelector('.ibkr-rate-currency')?.textContent?.trim();
        if (!ccy) return;
        const cell = document.getElementById('ibkr-daily-' + ccy);
        if (!cell) return;
        const interest = interests[i];
        if (interest === null) { cell.textContent = '—'; return; }
        const diff = interest - (minInterest ?? 0);
        cell.textContent = formatCurrency(interest);
        if (diff >= 0.005) {
            const s = document.createElement('span');
            s.className = 'ibkr-rate-diff';
            s.textContent = ' (+' + formatCurrency(diff) + ')';
            cell.appendChild(s);
        }
    });
}

function updateCashTotals() {
    if (document.querySelectorAll('[data-cash-entry]').length === 0) return;
    let totalUsd = 0;
    let marginUsd = 0;
    let equityUsd = 0;
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
            usd = (ccy === 'USD' ? amount : 0);
        }
        totalUsd += usd;
        if (row.dataset.marginFlag === 'true') marginUsd += usd;
        if (row.dataset.equityFlag === 'true') equityUsd += usd;
    });
    lastEquityUsd = equityUsd;
    lastCashTotalUsd = totalUsd;
    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = formatDisplayCurrency(totalUsd);
    lastMarginUsd = marginUsd;
    updateMarginDisplay(marginUsd);
    updateIbkrDailyInterest();

    // Update grand total
    const grandEl = document.getElementById('grand-total-value');
    if (grandEl) grandEl.textContent = formatDisplayCurrency(lastPortfolioVal + totalUsd);
}

function updateCurrentWeights(portfolioTotal) {
    if (portfolioTotal <= 0) return;

    document.querySelectorAll('.value.loaded').forEach(valueCell => {
        const value = parsePrice(valueCell.textContent);
        if (value === null) return;

        const symbol = valueCell.id.replace('value-', '');
        const weightCell = document.getElementById('current-weight-' + symbol);

        if (weightCell) {
            const currentWeight = (value / portfolioTotal) * 100;

            // Find target weight (hidden span)
            const targetWeightSpan = weightCell.querySelector('.target-weight-hidden');
            const targetWeight = targetWeightSpan ? parseFloat(targetWeightSpan.textContent) : null;

            if (targetWeight !== null) {
                const diff = currentWeight - targetWeight;
                const sign = diff >= 0 ? '+' : '-';
                const diffClass = Math.abs(diff) > 2.0 ? 'alert' :
                                  Math.abs(diff) > 1.0 ? 'warning' : 'good';

                weightCell.innerHTML =
                    currentWeight.toFixed(1) + '% ' +
                    '<span class="weight-diff ' + diffClass + '">(' + sign + Math.abs(diff).toFixed(1) + '%)</span>' +
                    '<span class="target-weight-hidden" style="display:none;">' + targetWeight + '</span>';
            } else {
                weightCell.textContent = currentWeight.toFixed(1) + '%';
            }

            weightCell.classList.add('loaded');
        }
    });
}

function updateRebalancingColumns(portfolioTotal) {
    if (portfolioTotal <= 0) return;

    document.querySelectorAll('.value.loaded').forEach(valueCell => {
        const value = parsePrice(valueCell.textContent);
        if (value === null) return;

        const symbol = valueCell.id.replace('value-', '');
        const markCell = document.getElementById('mark-' + symbol);
        const markPrice = parsePrice(markCell ? markCell.textContent : null);

        // Get target weight from hidden span
        const weightCell = document.getElementById('current-weight-' + symbol);
        const targetWeightSpan = weightCell ? weightCell.querySelector('.target-weight-hidden') : null;
        const targetWeight = targetWeightSpan ? parseFloat(targetWeightSpan.textContent) : null;

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

                const rebalSharesCell = document.getElementById('rebal-shares-' + symbol);
                if (rebalSharesCell) {
                    const sign = rebalShares >= 0 ? '+' : '-';
                    rebalSharesCell.textContent = sign + Math.abs(rebalShares).toFixed(2);

                    // Update color class (same direction as dollars)
                    const direction = Math.abs(rebalDollars) > 0.50 ?
                        (rebalDollars > 0 ? 'positive' : 'negative') : 'neutral';
                    rebalSharesCell.className = 'price-change loaded rebal-column ' + direction;
                }
            }
        }
    });
}

function updateAllEstVals() {
    const stale = globalIsMarketClosed &&
        marketCloseTimeMs !== null &&
        (Date.now() - marketCloseTimeMs > 12 * 3600 * 1000);

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
    // Clear stale rebalance target when currency changes
    const rebalInput = document.getElementById('rebal-target-input');
    if (rebalInput) rebalInput.value = '';
    rebalTargetUsd = null;

    // Portfolio total
    const totalCell = document.getElementById('portfolio-total');
    if (totalCell) totalCell.textContent = formatDisplayCurrency(lastPortfolioVal);

    // Portfolio day change
    const changeDollars = lastPortfolioDayChangeUsd;
    const changePercent = lastPrevPortfolioVal > 0 ? (changeDollars / lastPrevPortfolioVal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';
    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        const sign = changeDollars >= 0 ? '+' : '-';
        portfolioChangeCell.innerHTML =
            '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
            '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
    }

    // Cash entry USD spans
    document.querySelectorAll('[data-cash-entry]').forEach(row => {
        const ccy = row.dataset.currency;
        const amount = parseFloat(row.dataset.amount);
        const rate = fxRates[ccy];
        const span = document.getElementById('cash-usd-' + row.dataset.entryId);
        if (span && rate !== undefined) span.textContent = formatDisplayCurrency(amount * rate);
    });

    // Cash total, margin, grand total
    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = formatDisplayCurrency(lastCashTotalUsd);
    updateMarginDisplay(lastMarginUsd);
    const grandEl = document.getElementById('grand-total-value');
    if (grandEl) grandEl.textContent = formatDisplayCurrency(lastPortfolioVal + lastCashTotalUsd);

    // Total day change
    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        const prevGrand = lastPrevPortfolioVal + lastCashTotalUsd;
        const totalChangePct = prevGrand !== 0 ? (changeDollars / Math.abs(prevGrand)) * 100 : 0;
        const sign = changeDollars >= 0 ? '+' : '-';
        totalChangeCell.innerHTML =
            '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
            '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(totalChangePct).toFixed(2) + '%)</span>';
    }

    updateRebalTargetPlaceholder();
    updateRebalancingColumns(getRebalTotal());
    updateMarginTargetDisplay();
}

// Rebalancing columns toggle
document.addEventListener('DOMContentLoaded', () => {
    const rebalToggle = document.getElementById('rebal-toggle');
    const body = document.body;

    // Load saved state from localStorage
    const rebalVisible = localStorage.getItem('ib-viewer-rebal-visible') === 'true';
    if (rebalVisible) {
        body.classList.add('rebalancing-visible');
        rebalToggle.classList.add('active');
    }

    // Toggle on click
    rebalToggle.addEventListener('click', () => {
        const isVisible = body.classList.toggle('rebalancing-visible');
        rebalToggle.classList.toggle('active');
        localStorage.setItem('ib-viewer-rebal-visible', isVisible);
    });

    // Edit mode toggle
    const editToggle = document.getElementById('edit-toggle');
    const saveBtn = document.getElementById('save-btn');

    editToggle.addEventListener('click', () => {
        const isEditing = body.classList.toggle('editing-active');
        editToggle.classList.toggle('active');

        if (isEditing) {
            // Populate stock qty inputs from current display values
            document.querySelectorAll('.edit-qty').forEach(input => {
                const sym = input.getAttribute('data-symbol');
                const amountCell = document.getElementById('amount-' + sym);
                const displaySpan = amountCell ? amountCell.querySelector('.display-value') : null;
                if (displaySpan) input.value = displaySpan.textContent.trim();
            });

            // Add empty row if stock table has no entries
            const stockTbody = document.querySelector('.portfolio-table tbody');
            if (stockTbody && stockTbody.querySelectorAll('tr:not([data-deleted])').length === 0) {
                const tr = addStockRow();
                if (tr) tr.querySelector('.new-symbol-input').focus();
            }

            // Add empty row if cash edit table has no entries
            const cashTbody = document.querySelector('.cash-edit-table tbody');
            if (cashTbody && cashTbody.querySelectorAll('tr:not([data-deleted])').length === 0) {
                addCashRow();
            }

        } else {
            // Restore deleted rows
            document.querySelectorAll('[data-deleted="true"]').forEach(el => {
                el.removeAttribute('data-deleted');
                el.style.display = '';
            });
            // Remove dynamically added new rows
            document.querySelectorAll('[data-new-stock], [data-new-cash]').forEach(el => el.remove());
            // Reset symbol inputs to original values
            document.querySelectorAll('.edit-symbol').forEach(input => {
                input.value = input.getAttribute('data-original-symbol') || '';
            });
            // Reset cash edit table inputs to original values
            document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
                const keyInput = tr.querySelector('.cash-edit-key');
                const valInput = tr.querySelector('.cash-edit-value');
                if (keyInput) keyInput.value = keyInput.getAttribute('data-original-key') || '';
                if (valInput) valInput.value = valInput.getAttribute('data-original-value') || '';
            });
        }
    });

    // Save button
    saveBtn.addEventListener('click', () => {
        const updates = [];
        // Existing stock rows (not dynamically added)
        document.querySelectorAll('.portfolio-table tbody tr:not([data-new-stock])').forEach(tr => {
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
        document.querySelectorAll('.portfolio-table tbody tr[data-new-stock]').forEach(tr => {
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
                // File watcher detects changes and triggers SSE reload
                body.classList.remove('editing-active');
                editToggle.classList.remove('active');
            } else throw new Error('Save failed');
        }).catch(err => {
            alert('Failed to save: ' + err.message);
            saveBtn.disabled = false;
            saveBtn.querySelector('.toggle-label').textContent = 'Save';
        });
    });

    // Paste handler for Google Sheets column paste
    // --- Edit mode helpers ---

    const STOCK_ROW_HTML =
        '<td><input type="text" class="edit-input new-symbol-input" data-column="symbol" placeholder="TICKER" style="text-align:left;width:80px;display:block" /></td>' +
        '<td class="amount"><input type="number" class="edit-input" data-column="qty" value="0" min="0" step="any" style="display:block" /></td>' +
        '<td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td>' +
        '<td class="edit-column"><input type="number" class="edit-input" data-column="weight" value="0" min="0" max="100" step="0.1" /></td>' +
        '<td class="edit-column"><input type="text" class="edit-input" data-column="letf" placeholder="e.g. 2 IVV" style="text-align:left;width:120px" /></td>' +
        '<td class="edit-column"><button type="button" class="delete-row-btn">\u00d7</button></td>';

    const CASH_ROW_HTML =
        '<td><input type="text" class="edit-input cash-edit-key" placeholder="Cash.USD.M" /></td>' +
        '<td><input type="text" class="edit-input cash-edit-value" placeholder="0" /></td>' +
        '<td><button type="button" class="delete-cash-btn">\u00d7</button></td>';

    const addStockRow = () => {
        const tbody = document.querySelector('.portfolio-table tbody');
        if (!tbody) return null;
        const tr = document.createElement('tr');
        tr.setAttribute('data-new-stock', 'true');
        tr.innerHTML = STOCK_ROW_HTML;
        tbody.appendChild(tr);
        return tr;
    };

    const addCashRow = () => {
        const tbody = document.querySelector('.cash-edit-table tbody');
        if (!tbody) return null;
        const tr = document.createElement('tr');
        tr.setAttribute('data-cash-edit-row', 'true');
        tr.setAttribute('data-new-cash', 'true');
        tr.innerHTML = CASH_ROW_HTML;
        tbody.appendChild(tr);
        return tr;
    };

    // Returns [symInput, qtyInput, weightInput, letfInput] for any stock row (existing or new)
    const getStockRowInputs = (tr) => [
        tr.querySelector('.edit-symbol') || tr.querySelector('.new-symbol-input'),
        tr.querySelector('.edit-qty')    || tr.querySelector('input[data-column="qty"]'),
        tr.querySelector('.edit-weight') || tr.querySelector('input[data-column="weight"]'),
        tr.querySelector('.edit-letf')   || tr.querySelector('input[data-column="letf"]'),
    ].filter(Boolean);

    // Returns 0=sym, 1=qty, 2=weight, 3=letf — or -1 if not a stock input
    const getStockColIndex = (el) => {
        if (el.classList.contains('edit-symbol') || el.classList.contains('new-symbol-input')) return 0;
        const col = el.getAttribute('data-column');
        if (el.classList.contains('edit-qty')    || col === 'qty')    return 1;
        if (el.classList.contains('edit-weight') || col === 'weight') return 2;
        if (el.classList.contains('edit-letf')   || col === 'letf')   return 3;
        return -1;
    };

    document.addEventListener('paste', (e) => {
        if (!body.classList.contains('editing-active')) return;

        const activeEl = document.activeElement;
        if (!activeEl || !activeEl.classList.contains('edit-input')) return;

        const clipText = (e.clipboardData || window.clipboardData).getData('text');
        const lines = clipText.split(/[\r\n]+/).filter(l => l.trim() !== '');

        // Only intercept multi-line
        if (lines.length <= 1) return;

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

            const tbody = document.querySelector('.portfolio-table tbody');
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
                    if (idx < inputs.length) inputs[idx].value = val.trim();
                });
            });
        }
    });

    // Copy column button handler
    document.querySelectorAll('.copy-col-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const col = btn.getAttribute('data-column');
            const inputs = Array.from(
                document.querySelectorAll('.edit-input[data-column="' + col + '"]')
            );
            const text = inputs.map(i => i.value).join('\n');
            navigator.clipboard.writeText(text).then(() => {
                const orig = btn.innerHTML;
                btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
                btn.classList.add('copied');
                setTimeout(() => {
                    btn.innerHTML = orig;
                    btn.classList.remove('copied');
                }, 1500);
            });
        });
    });

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

    // Rebalance Target input
    let rebalSaveTimer = null;
    const rebalTargetInput = document.getElementById('rebal-target-input');
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
            updateRebalancingColumns(getRebalTotal());
            updateMarginTargetDisplay();
            // Debounced save to server
            clearTimeout(rebalSaveTimer);
            rebalSaveTimer = setTimeout(() => {
                fetch('/api/rebal-target/save?portfolio=' + portfolioId, {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: rebalTargetUsd !== null ? rebalTargetUsd.toString() : '0'
                });
            }, 1000);
        });

        // Restore saved rebalance target on page load
        if (savedRebalTargetUsd > 0) {
            rebalTargetUsd = savedRebalTargetUsd;
            const rate = fxRates[currentDisplayCurrency];
            const displayVal = (rate && rate !== 0) ? savedRebalTargetUsd / rate : savedRebalTargetUsd;
            rebalTargetInput.value = displayVal.toLocaleString('en-US', {
                minimumFractionDigits: 2, maximumFractionDigits: 2
            });
            updateRebalancingColumns(getRebalTotal());
            updateMarginTargetDisplay();
        }
    }

    // Initialize cash totals on page load (USD entries are pre-filled server-side)
    updateCashTotals();

    // Refresh display currency for any server-rendered values (portfolio total, day change)
    if (currentDisplayCurrency !== 'USD') {
        refreshDisplayCurrency();
    }

    updateRebalTargetPlaceholder();
    updateMarginTargetDisplay();

    // Delete button handler (event delegation for both static and dynamic rows)
    document.addEventListener('click', e => {
        const btn = e.target.closest('.delete-row-btn, .delete-cash-btn');
        if (!btn || !body.classList.contains('editing-active')) return;
        const row = btn.closest('tr');
        if (row) {
            row.setAttribute('data-deleted', 'true');
            row.style.display = 'none';
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

    // Restore Backup button
    document.getElementById('restore-backup-btn')?.addEventListener('click', async () => {
        // Backup current state before showing restore options
        try {
            await fetch('/api/backup/trigger?portfolio=' + portfolioId, { method: 'POST' });
        } catch (_) { /* non-fatal */ }

        let dates;
        try {
            const resp = await fetch('/api/backup/list?portfolio=' + portfolioId);
            dates = await resp.json();
        } catch (e) {
            alert('Failed to load backup list.');
            return;
        }

        // Build modal
        const overlay = document.createElement('div');
        overlay.className = 'backup-modal-overlay';

        const modal = document.createElement('div');
        modal.className = 'backup-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');

        const title = document.createElement('p');
        title.className = 'backup-modal-title';
        title.textContent = 'Restore from Backup';
        modal.appendChild(title);

        if (dates.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'backup-modal-empty';
            empty.textContent = 'No backups available.';
            modal.appendChild(empty);
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
                    try {
                        const r = await fetch('/api/backup/restore?portfolio=' + portfolioId + '&date=' + date, { method: 'POST' });
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
            modal.appendChild(list);
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
});
