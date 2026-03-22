// ── ui-helpers.js — Low-level DOM helpers shared across modules ───────────────
// Depends on: utils.js

// Returns CSS direction class based on a numeric value
function directionClass(val, isZero) {
    if (isZero) return 'neutral';
    return val > 0 ? 'positive' : val < 0 ? 'negative' : 'neutral';
}

// Applies direction + after-hours classes to a cell
function applyChangeClasses(cell, val, isZero, isMarketClosed) {
    const dir = directionClass(val, isZero);
    cell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
    cell.classList.add('loaded', dir);
    if (isMarketClosed) cell.classList.add('after-hours');
}

// Builds the day-change innerHTML string (dollars + percent)
function buildDayChangeHTML(changeDollars, changePercent, changeClass) {
    const sign = changeDollars >= 0 ? '+' : '-';
    return '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars) + '</span> ' +
        '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
}

// Sets a price cell to a formatted dollar value (or '—'), adding 'loaded' when set
function setPriceCell(cell, price) {
    if (!cell) return;
    cell.textContent = price !== null ? price.toFixed(2) : '—';
    if (price !== null) cell.classList.add('loaded');
}

// Flashes a copy button with a checkmark, then restores original content
function flashCopyButton(btn) {
    const orig = btn.innerHTML;
    btn.innerHTML = CHECKMARK_SVG;
    btn.classList.add('copied');
    setTimeout(() => { btn.innerHTML = orig; btn.classList.remove('copied'); }, 1500);
}

function updateGlobalTimestamp(timestamp) {
    const timeCell = document.getElementById('last-update-time');
    if (timeCell && timestamp) {
        timeCell.textContent = formatTimestamp(timestamp);
        timeCell.classList.add('loaded');
    }
}

// Updates the grand total element
function updateGrandTotal() {
    const grandEl = document.getElementById('portfolio-total');
    if (grandEl) grandEl.textContent = (stockGrossValueKnown && cashTotalKnown)
        ? formatDisplayCurrency(lastStockGrossVal + lastCashTotalUsd) : 'N/A';
}

// ── SSE apply functions ───────────────────────────────────────────────────────

function applyStockDisplay(data) {
    if (data.portfolioId !== portfolioId) return;
    lastStockDisplayData = data;

    // Capture previous mark texts for row flash detection
    const previousMarkTexts = {};
    for (const stock of data.stocks) {
        const span = document.querySelector('#mark-' + stock.symbol + ' .mark-price-value');
        if (span && span.textContent && span.textContent !== '—') {
            previousMarkTexts[stock.symbol] = span.textContent.trim();
        }
    }

    for (const stock of data.stocks) {
        const { symbol, markPrice, closePrice, dayChangeDollars, dayChangePct,
                qty, currency, positionValueUsd, isMarketClosed, estPriceNative } = stock;

        // Store server-computed data for display-worker (weight/rebal/alloc)
        lastServerStocks[symbol] = { markPrice, closePrice, positionValueUsd, currency };

        const markText = markPrice !== null ? markPrice.toFixed(2) : '—';
        const markLoaded = markPrice !== null;

        // Mark price cell
        const markCell = document.getElementById('mark-' + symbol);
        if (markCell) {
            const markValSpan = markCell.querySelector('.mark-price-value');
            if (markValSpan) {
                markValSpan.textContent = markText;
                markValSpan.classList.toggle('loaded', markLoaded);
            }
            markCell.classList.toggle('loaded', markLoaded);
            markCell.classList.toggle('after-hours', isMarketClosed);
        }

        // Day percent (inside mark cell)
        if (dayChangePct !== null && dayChangePct !== undefined) {
            const pctSpan = document.getElementById('day-percent-' + symbol);
            if (pctSpan) {
                const sign = dayChangePct >= 0 ? '+' : '\u2212';
                pctSpan.textContent = sign + Math.abs(dayChangePct).toFixed(2) + '%';
                const isNeutral = Math.abs(dayChangePct) < 0.1;
                const colorClass = isNeutral ? 'neutral' : dayChangePct > 0 ? 'positive' : 'negative';
                pctSpan.className = 'mark-day-pct ' + colorClass + (isMarketClosed ? ' after-hours' : '');
            }
        }

        // Close price
        const closeCell = document.getElementById('close-' + symbol);
        if (closeCell) {
            closeCell.textContent = closePrice !== null ? closePrice.toFixed(2) : '—';
            closeCell.classList.toggle('loaded', closePrice !== null);
            closeCell.classList.toggle('after-hours', isMarketClosed);
        }

        // Day change (per share, native ccy)
        const changeCell = document.getElementById('day-change-' + symbol);
        if (changeCell && dayChangeDollars !== null && dayChangeDollars !== undefined) {
            const sign = dayChangeDollars >= 0 ? '+' : '-';
            changeCell.textContent = sign + Math.abs(dayChangeDollars).toFixed(2);
            const isNeutral = dayChangePct !== null && Math.abs(dayChangePct) < 0.1;
            const colorClass = isNeutral ? 'neutral' : dayChangeDollars > 0 ? 'positive' : 'negative';
            changeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
            changeCell.classList.add('loaded', colorClass);
            if (isMarketClosed) changeCell.classList.add('after-hours');
        }

        // Position change (Mkt Val Chg, native ccy)
        const posChangeCell = document.getElementById('position-change-' + symbol);
        if (posChangeCell && dayChangeDollars !== null && dayChangeDollars !== undefined) {
            const nativeChange = dayChangeDollars * qty;
            const isNeutral = Math.abs(nativeChange) < 0.005;
            const colorClass = isNeutral ? 'neutral' : nativeChange > 0 ? 'positive' : 'negative';
            posChangeCell.textContent = showStockDisplayCurrency
                ? formatSignedStockDisplayCurrency(nativeChange, currency)
                : formatSignedCurrency(nativeChange);
            posChangeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
            posChangeCell.classList.add('loaded', colorClass);
            if (isMarketClosed) posChangeCell.classList.add('after-hours');
        }

        // Mkt Val (native ccy)
        const valueCell = document.getElementById('value-' + symbol);
        if (valueCell) {
            const effectivePrice = markPrice !== null ? markPrice : closePrice;
            if (effectivePrice !== null) {
                const nativeVal = effectivePrice * qty;
                valueCell.textContent = showStockDisplayCurrency
                    ? formatStockDisplayCurrency(nativeVal, currency)
                    : formatCurrency(nativeVal);
                valueCell.classList.add('loaded');
            } else {
                valueCell.textContent = '—';
                valueCell.classList.remove('loaded');
            }
        }

        // Row flash on mark price change
        const prevMarkText = previousMarkTexts[symbol] ?? null;
        if (prevMarkText !== null && markText !== '—' && markText !== prevMarkText) {
            const row = valueCell ? valueCell.closest('tr') : null;
            if (row && !row.classList.contains('recently-updated')) {
                row.classList.add('recently-updated');
                setTimeout(() => row.classList.remove('recently-updated'), 10000);
            }
        }

        // Est val (LETF per-share price)
        const estValCell = document.getElementById('est-val-' + symbol);
        if (estValCell) {
            if (estPriceNative !== null && estPriceNative !== undefined) {
                estValCell.textContent = estPriceNative.toFixed(2);
                estValCell.dataset.estVal = estPriceNative;
                estValCell.classList.add('loaded');
                estValCell.classList.toggle('after-hours', isMarketClosed);
            } else if (estPriceNative === null) {
                estValCell.textContent = '—';
                estValCell.classList.remove('loaded');
            }
        }
    }

    // Update globals for display-worker
    lastStockGrossVal = data.stockGrossUsd;
    stockGrossValueKnown = data.stockGrossKnown;
    lastPortfolioDayChangeUsd = data.dayChangeUsd;
    lastPrevPortfolioVal = data.prevDayUsd;

    scheduleDisplayUpdate();
}

function applyCashDisplay(data) {
    if (data.portfolioId !== portfolioId) return;
    lastCashDisplayData = data;

    // Update per-entry cash USD cells
    for (const entry of data.entries) {
        const el = document.getElementById('cash-usd-' + entry.entryId);
        if (el) {
            el.textContent = entry.valueUsd !== null ? formatDisplayCurrency(entry.valueUsd) : '\u2014';
        }
    }

    // Cash total
    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = data.totalKnown ? formatDisplayCurrency(data.totalUsd) : 'N/A';

    // Update globals
    lastCashTotalUsd = data.totalUsd;
    cashTotalKnown = data.totalKnown;
    lastMarginUsd = data.marginUsd;
    marginKnown = data.totalKnown;
}

function applyPortfolioTotals(data) {
    if (data.portfolioId === portfolioId) lastPortfolioTotalsData = data;
    if (data.portfolioId === portfolioId) {
        // Update globals
        lastStockGrossVal = data.stockGrossUsd;
        stockGrossValueKnown = data.stockGrossKnown;
        lastCashTotalUsd = data.cashTotalUsd;
        cashTotalKnown = data.cashKnown ?? true;
        lastMarginUsd = data.marginUsd;
        lastPortfolioDayChangeUsd = data.dayChangeUsd;
        lastPrevPortfolioVal = data.prevDayUsd;

        // Stock gross total
        const totalCell = document.getElementById('stock-gross-total');
        if (totalCell) totalCell.textContent = data.stockGrossKnown
            ? formatDisplayCurrency(data.stockGrossUsd) : 'N/A';

        // Grand total
        const grandEl = document.getElementById('portfolio-total');
        if (grandEl) grandEl.textContent = data.grandTotalKnown
            ? formatDisplayCurrency(data.grandTotalUsd) : 'N/A';

        // Day change (portfolio, excludes cash)
        const portfolioChangeCell = document.getElementById('portfolio-day-change');
        if (portfolioChangeCell) {
            if (!data.stockGrossKnown) {
                portfolioChangeCell.innerHTML = 'N/A';
            } else {
                const dayChange = data.dayChangeUsd;
                const prevDay = data.prevDayUsd;
                const changePercent = prevDay > 0 ? (dayChange / prevDay) * 100 : 0;
                const isZero = Math.abs(dayChange) < 0.005;
                const cls = isZero ? 'neutral' : dayChange > 0 ? 'positive' : 'negative';
                portfolioChangeCell.innerHTML = buildDayChangeHTML(dayChange, changePercent, cls);
            }
        }

        // Day change (total, denominator includes cash)
        const totalChangeCell = document.getElementById('total-day-change');
        if (totalChangeCell) {
            if (!data.stockGrossKnown) {
                totalChangeCell.innerHTML = 'N/A';
            } else {
                const dayChange = data.dayChangeUsd;
                const prevGrandTotal = data.prevDayUsd + data.cashTotalUsd;
                const totalChangePct = prevGrandTotal !== 0 ? (dayChange / Math.abs(prevGrandTotal)) * 100 : 0;
                const isZero = Math.abs(dayChange) < 0.005;
                const cls = isZero ? 'neutral' : dayChange > 0 ? 'positive' : 'negative';
                totalChangeCell.innerHTML = buildDayChangeHTML(dayChange, totalChangePct, cls);
            }
        }

        // Margin
        const marginUsd = data.marginUsd;
        const marginRow = document.querySelector('[data-margin-row]');
        if (marginRow) marginRow.style.display = marginUsd < 0 ? '' : 'none';
        const marginEl = document.getElementById('margin-total-usd');
        if (marginEl) {
            marginEl.textContent = marginUsd < 0
                ? (data.cashKnown !== false ? formatDisplayCurrency(-marginUsd) : 'N/A')
                : '';
        }
        const marginPctEl = document.getElementById('margin-percent');
        if (marginPctEl && marginUsd < 0) {
            const denominator = data.stockGrossUsd + marginUsd;
            const pct = denominator !== 0 ? Math.abs(marginUsd / denominator) * 100 : 0;
            const pctText = ' (' + pct.toFixed(1) + '%)';
            marginPctEl.textContent = pctText;
            marginPctEl.style.display = '';
        } else if (marginPctEl) {
            marginPctEl.textContent = '';
            marginPctEl.style.display = 'none';
        }

        scheduleDisplayUpdate();

        if (groupViewActive && typeof updateGroupTable === 'function') {
            updateGroupTable();
        }
    }

    // Portfolio-ref values — update any cash entries referencing this portfolio's total
    updatePortfolioRefValues(data.portfolioId, data.grandTotalUsd);
}

function applyIbkrInterest(data) {
    if (data.portfolioId !== portfolioId) return;

    // Update per-currency rate display text in existing rates table rows
    for (const ci of data.perCurrency) {
        const ccy = ci.currency;
        const row = [...document.querySelectorAll('.ibkr-rates-table tbody tr')].find(
            tr => tr.querySelector('.ibkr-rate-currency')?.textContent?.trim() === ccy
        );
        if (row) {
            const cell = row.querySelector('.ibkr-rate-value');
            if (cell) cell.textContent = ci.displayRateText;
        }
    }

    const savingLabelEl = document.getElementById('ibkr-saving-label');
    if (savingLabelEl) savingLabelEl.textContent = data.label;

    const currentEl = document.getElementById('ibkr-current-interest');
    if (currentEl) currentEl.textContent = data.currentDailyUsd > 0
        ? formatDisplayCurrency(data.currentDailyUsd) : '\u2014';

    const cheapestEl = document.getElementById('ibkr-cheapest-interest');
    if (cheapestEl) cheapestEl.textContent = data.cheapestCcy !== null
        ? formatDisplayCurrency(data.cheapestDailyUsd) : '\u2014';

    const cheapestCcyEl = document.getElementById('ibkr-cheapest-ccy');
    if (cheapestCcyEl) cheapestCcyEl.textContent = data.cheapestCcy ? '(' + data.cheapestCcy + ')' : '';

    const diffEl = document.getElementById('ibkr-interest-diff');
    if (diffEl) {
        const savingsUsd = data.savingsUsd;
        diffEl.textContent = (savingsUsd !== null && savingsUsd >= 0.005)
            ? formatDisplayCurrency(savingsUsd) : '\u2014';
        diffEl.className = (savingsUsd !== null && savingsUsd >= 0.005) ? 'ibkr-rate-diff' : '';
    }
}
