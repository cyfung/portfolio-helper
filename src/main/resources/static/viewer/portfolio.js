// ── portfolio.js — Price ingestion, value calculation, weight display ─────────
// Depends on: utils.js, ui-helpers.js, rebalance.js, cash.js

function updateNavInUI(symbol, nav) {
    const navCell = document.getElementById('nav-' + symbol);
    if (navCell) {
        navCell.textContent = nav !== null ? '$' + nav.toFixed(2) : '—';
        if (nav !== null) navCell.classList.add('loaded');
    }
    updateAllEstVals();
}

function updatePriceInUI(symbol, markPrice, lastClosePrice, isMarketClosed, tradingPeriodEnd) {
    // Update per-symbol market state
    symbolMarketClosed[symbol] = isMarketClosed;
    if (tradingPeriodEnd !== null && tradingPeriodEnd !== undefined) {
        const endMs = tradingPeriodEnd * 1000;
        if (endMs <= Date.now()) {
            symbolTradingPeriodEndMs[symbol] = endMs;
        }
    }

    // Store raw prices for high-precision rebalancing calculations
    if (markPrice !== null) rawMarkPrices[symbol] = markPrice;
    if (lastClosePrice !== null) rawClosePrices[symbol] = lastClosePrice;

    // Store Day % for LETF Est Val calculations
    if (markPrice !== null && lastClosePrice !== null && lastClosePrice !== 0) {
        componentDayPercents[symbol] = ((markPrice - lastClosePrice) / lastClosePrice) * 100;
    }

    const valueCell = document.getElementById('value-' + symbol);
    const amountCell = document.getElementById('amount-' + symbol);
    let previousValue = null;
    let valueChanged = false;

    if (valueCell) {
        previousValue = parsePrice(valueCell.textContent);
    }

    const markCell = document.getElementById('mark-' + symbol);
    setPriceCell(markCell?.querySelector('.mark-price-value'), markPrice);
    if (markCell) markCell.classList.toggle('after-hours', !!isMarketClosed);
    setPriceCell(document.getElementById('close-' + symbol), lastClosePrice);

    if (markPrice !== null && lastClosePrice !== null) {
        const changeDollars = markPrice - lastClosePrice;
        const changePercent = (changeDollars / lastClosePrice) * 100;
        const isZeroChange = Math.abs(changeDollars) < 0.001;

        const changeCell = document.getElementById('day-change-' + symbol);
        if (changeCell) {
            changeCell.textContent = (changeDollars >= 0 ? '+' : '-') + '$' + Math.abs(changeDollars).toFixed(2);
            applyChangeClasses(changeCell, changeDollars, isZeroChange, isMarketClosed);
        }

        const pctSpan = document.getElementById('day-percent-' + symbol);
        if (pctSpan) {
            const sign = changePercent >= 0 ? '+' : '−';
            pctSpan.textContent = sign + Math.abs(changePercent).toFixed(2) + '%';
            const isNeutral = Math.abs(changePercent) < 0.1;
            const colorClass = isNeutral ? 'neutral' : changePercent > 0 ? 'positive' : 'negative';
            const staleClass = isMarketClosed ? ' after-hours' : '';
            pctSpan.className = 'mark-day-pct ' + colorClass + staleClass;
        }

        if (amountCell) {
            const amount = parseFloat(amountCell.textContent);
            const positionChange = changeDollars * amount;
            const positionChangeCell = document.getElementById('position-change-' + symbol);
            if (positionChangeCell) {
                positionChangeCell.textContent = formatSignedCurrency(positionChange);
                applyChangeClasses(positionChangeCell, positionChange, isZeroChange, isMarketClosed);
            }
        }
    }

    if (valueCell && amountCell) {
        const amount = parseFloat(amountCell.textContent);
        const price = markPrice !== null ? markPrice : lastClosePrice;
        if (price !== null) {
            const newValue = price * amount;
            valueCell.textContent = formatCurrency(newValue);
            valueCell.classList.add('loaded');
            if (previousValue !== null && Math.abs(newValue - previousValue) > 0.01) {
                valueChanged = true;
            }
            updateTotalValue();
        }
    }

    if (valueChanged && amountCell) {
        const row = amountCell.closest('tr');
        if (row) {
            row.classList.add('recently-updated');
            setTimeout(() => { row.classList.remove('recently-updated'); }, 10000);
        }
    }

    updateAllEstVals();
}

function updateTotalValue() {
    let total = 0;
    let previousTotal = 0;
    stockGrossValueKnown = true;

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

            if (markPrice === null && closePrice === null) stockGrossValueKnown = false;
            if (markPrice !== null) total += markPrice * amount;
            if (closePrice !== null) previousTotal += closePrice * amount;
        }
    });

    lastStockGrossVal = total;
    lastPrevPortfolioVal = previousTotal;
    updateRebalTargetPlaceholder();

    const totalCell = document.getElementById('stock-gross-total');
    if (totalCell) totalCell.textContent = stockGrossValueKnown
        ? formatDisplayCurrency(total) : 'N/A';

    const changeDollars = total - previousTotal;
    lastPortfolioDayChangeUsd = changeDollars;
    const changePercent = previousTotal > 0 ? (changeDollars / previousTotal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';

    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        portfolioChangeCell.innerHTML = !stockGrossValueKnown ? 'N/A'
            : buildDayChangeHTML(changeDollars, changePercent, changeClass);
    }

    updateCurrentWeights(total);
    updateRebalancingColumns(getRebalTotal());
    updateAllocColumns(getAllocRebalTotal());
    updateMarginTargetDisplay();
    updateGrandTotal();

    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        if (!stockGrossValueKnown) {
            totalChangeCell.innerHTML = 'N/A';
        } else {
            const prevGrandTotal = previousTotal + lastCashTotalUsd;
            const totalChangePercent = prevGrandTotal !== 0 ? (changeDollars / Math.abs(prevGrandTotal)) * 100 : 0;
            totalChangeCell.innerHTML = buildDayChangeHTML(changeDollars, totalChangePercent, changeClass);
        }
    }

    updateMarginDisplay(lastMarginUsd);
}

function updateCurrentWeights(portfolioTotal) {
    if (!stockGrossValueKnown) {
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
        if (!weightCell) return;

        const currentWeight = (value / portfolioTotal) * 100;
        const viewRow = weightCell.closest('tr');
        const targetWeight = viewRow ? parseFloat(viewRow.dataset.weight) : null;

        if (targetWeight !== null && !isNaN(targetWeight)) {
            const diff = currentWeight - targetWeight;
            const diffClass = Math.abs(diff) > 1.0 ? (diff > 0 ? 'alert-over' : 'alert-under')
                            : Math.abs(diff) > 0.2 ? 'warning' : 'good';
            const diffSign = diff >= 0 ? '+' : '';
            const curHtml  = `<span class="weight-cur">${currentWeight.toFixed(1)}%</span>`;
            const sepHtml  = `<span class="weight-sep">/</span>`;
            const tgtHtml  = `<span class="weight-tgt">${targetWeight.toFixed(1)}%</span>`;
            const pillHtml = `<span class="weight-diff ${diffClass}">${diffSign}${diff.toFixed(1)}%</span>`;
            weightCell.innerHTML = curHtml + sepHtml + tgtHtml + pillHtml;
        } else {
            weightCell.textContent = currentWeight.toFixed(1) + '%';
        }
        weightCell.classList.add('loaded');
    });
}
