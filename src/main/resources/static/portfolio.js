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
            // Populate cash inputs from row data-amount
            document.querySelectorAll('.cash-amount-input').forEach(input => {
                const row = input.closest('[data-cash-entry]');
                if (row) input.value = row.dataset.amount;
            });
        }
    });

    // Save button
    saveBtn.addEventListener('click', () => {
        const updates = [];
        document.querySelectorAll('.edit-qty').forEach(input => {
            const sym = input.getAttribute('data-symbol');
            const weightInput = document.querySelector('.edit-weight[data-symbol="' + sym + '"]');
            updates.push({
                symbol: sym,
                amount: parseFloat(input.value) || 0,
                targetWeight: weightInput ? parseFloat(weightInput.value) || 0 : 0
            });
        });

        const cashUpdates = [];
        document.querySelectorAll('.cash-amount-input').forEach(input => {
            cashUpdates.push({
                key: input.getAttribute('data-key'),
                amount: parseFloat(input.value) || 0
            });
        });

        saveBtn.disabled = true;
        saveBtn.querySelector('.toggle-label').textContent = 'Saving...';

        const saves = [
            fetch('/api/portfolio/update?portfolio=' + portfolioId, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updates)
            }),
            cashUpdates.length > 0
                ? fetch('/api/cash/update?portfolio=' + portfolioId, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(cashUpdates)
                  })
                : Promise.resolve({ ok: true })
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
    document.addEventListener('paste', (e) => {
        if (!body.classList.contains('editing-active')) return;

        const activeEl = document.activeElement;
        if (!activeEl || !activeEl.classList.contains('edit-input')) return;

        const clipText = (e.clipboardData || window.clipboardData).getData('text');
        const lines = clipText.split(/[\r\n]+/).filter(l => l.trim() !== '');

        // Only intercept if multi-line (Google Sheets column paste)
        if (lines.length <= 1) return;

        e.preventDefault();

        const column = activeEl.getAttribute('data-column');
        const allInputs = Array.from(document.querySelectorAll('.edit-input[data-column="' + column + '"]'));
        const startIdx = allInputs.indexOf(activeEl);

        for (let i = 0; i < lines.length && (startIdx + i) < allInputs.length; i++) {
            const val = lines[i].trim().replace(/,/g, '');
            const num = parseFloat(val);
            if (!isNaN(num)) {
                allInputs[startIdx + i].value = num.toString();
            }
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
        });
    }

    // Initialize cash totals on page load (USD entries are pre-filled server-side)
    updateCashTotals();

    // Refresh display currency for any server-rendered values (portfolio total, day change)
    if (currentDisplayCurrency !== 'USD') {
        refreshDisplayCurrency();
    }

    updateRebalTargetPlaceholder();
    updateMarginTargetDisplay();

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
