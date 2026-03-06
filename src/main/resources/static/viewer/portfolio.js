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
    // Update global market state
    globalIsMarketClosed = isMarketClosed;
    if (tradingPeriodEnd !== null && tradingPeriodEnd !== undefined) {
        const endMs = tradingPeriodEnd * 1000;
        if (endMs <= Date.now()) {
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

    const valueCell = document.getElementById('value-' + symbol);
    const amountCell = document.getElementById('amount-' + symbol);
    let previousValue = null;
    let valueChanged = false;

    if (valueCell) {
        previousValue = parsePrice(valueCell.textContent);
    }

    setPriceCell(document.getElementById('mark-' + symbol), markPrice);
    setPriceCell(document.getElementById('close-' + symbol), lastClosePrice);

    if (markPrice !== null && lastClosePrice !== null) {
        const changeDollars = markPrice - lastClosePrice;
        const changePercent = (changeDollars / lastClosePrice) * 100;
        const isZeroChange = Math.abs(changeDollars) < 0.001;

        const changeCell = document.getElementById('day-change-' + symbol);
        if (changeCell) {
            changeCell.textContent = isZeroChange ? '—' : (changeDollars >= 0 ? '+' : '-') + '$' + Math.abs(changeDollars).toFixed(2);
            applyChangeClasses(changeCell, changeDollars, isZeroChange, isMarketClosed);
        }

        const changePercentCell = document.getElementById('day-percent-' + symbol);
        if (changePercentCell) {
            changePercentCell.textContent = isZeroChange ? '—' : (changePercent >= 0 ? '+' : '-') + Math.abs(changePercent).toFixed(2) + '%';
            applyChangeClasses(changePercentCell, changePercent, isZeroChange, isMarketClosed);
        }

        if (amountCell) {
            const amount = parseFloat(amountCell.textContent);
            const positionChange = changeDollars * amount;
            const positionChangeCell = document.getElementById('position-change-' + symbol);
            if (positionChangeCell) {
                positionChangeCell.textContent = isZeroChange ? '—' : formatSignedCurrency(positionChange);
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
    portfolioValueKnown = true;

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

    const totalCell = document.getElementById('portfolio-total');
    if (totalCell) totalCell.textContent = portfolioValueKnown
        ? formatDisplayCurrency(total) : 'N/A';

    const changeDollars = total - previousTotal;
    lastPortfolioDayChangeUsd = changeDollars;
    const changePercent = previousTotal > 0 ? (changeDollars / previousTotal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';

    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        portfolioChangeCell.innerHTML = !portfolioValueKnown ? 'N/A'
            : buildDayChangeHTML(changeDollars, changePercent, changeClass);
    }

    updateCurrentWeights(total);
    updateRebalancingColumns(getRebalTotal());
    updateAllocColumns(getAllocRebalTotal());
    updateMarginTargetDisplay();
    updateGrandTotal();

    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        if (!portfolioValueKnown) {
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
        if (!weightCell) return;

        const currentWeight = (value / portfolioTotal) * 100;
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
    });
}
