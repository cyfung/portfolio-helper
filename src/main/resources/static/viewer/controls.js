// ── controls.js — Currency, column visibility, rebalance/margin inputs, theme ─
// Depends on: utils.js, ui-helpers.js, rebalance.js, cash.js

// ── Column visibility ─────────────────────────────────────────────────────────

function initColumnVisibility() {
    const rebalToggle = document.getElementById('rebal-toggle');
    const body = document.body;

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
}

// ── Currency controls ─────────────────────────────────────────────────────────

function refreshDisplayCurrency() {
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

    const totalCell = document.getElementById('portfolio-total');
    if (totalCell) totalCell.textContent = portfolioValueKnown
        ? formatDisplayCurrency(lastPortfolioVal) : 'N/A';

    const changeDollars = lastPortfolioDayChangeUsd;
    const changePercent = lastPrevPortfolioVal > 0 ? (changeDollars / lastPrevPortfolioVal) * 100 : 0;
    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';
    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) {
        portfolioChangeCell.innerHTML = !portfolioValueKnown ? 'N/A'
            : buildDayChangeHTML(changeDollars, changePercent, changeClass);
    }

    document.querySelectorAll('[data-cash-entry]').forEach(row => {
        const ccy = row.dataset.currency;
        const amount = parseFloat(row.dataset.amount);
        const rate = fxRates[ccy];
        const span = document.getElementById('cash-usd-' + row.dataset.entryId);
        if (span) span.textContent = rate !== undefined ? formatDisplayCurrency(amount * rate) : 'N/A';
    });

    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = cashTotalKnown ? formatDisplayCurrency(lastCashTotalUsd) : 'N/A';
    updateMarginDisplay(lastMarginUsd);
    updateGrandTotal();

    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) {
        if (!portfolioValueKnown) {
            totalChangeCell.innerHTML = 'N/A';
        } else {
            const prevGrand = lastPrevPortfolioVal + lastCashTotalUsd;
            const totalChangePct = prevGrand !== 0 ? (changeDollars / Math.abs(prevGrand)) * 100 : 0;
            totalChangeCell.innerHTML = buildDayChangeHTML(changeDollars, totalChangePct, changeClass);
        }
    }

    updateRebalTargetPlaceholder();
    updateRebalancingColumns(getRebalTotal());
    updateAllocColumns(getAllocRebalTotal());
    updateMarginTargetDisplay();
    updateIbkrDailyInterest();
}

function initCurrencyControls() {
    function setDisplayCurrency(ccy) {
        currentDisplayCurrency = ccy;
        localStorage.setItem('ib-viewer-display-currency', ccy);
        refreshDisplayCurrency();
    }

    const currencyToggle = document.getElementById('currency-toggle');
    if (currencyToggle) {
        const currencies = currencyToggle.getAttribute('data-currencies').split(',');
        const saved = localStorage.getItem('ib-viewer-display-currency');
        if (saved && currencies.includes(saved)) {
            currentDisplayCurrency = saved;
            currencyToggle.querySelector('.toggle-label').textContent = saved;
        }
        currencyToggle.addEventListener('click', () => {
            const next = currencies[(currencies.indexOf(currentDisplayCurrency) + 1) % currencies.length];
            currencyToggle.querySelector('.toggle-label').textContent = next;
            setDisplayCurrency(next);
        });
    }

    const currencySelect = document.getElementById('currency-select');
    if (currencySelect) {
        const savedSel = localStorage.getItem('ib-viewer-display-currency');
        if (savedSel) { currentDisplayCurrency = savedSel; currencySelect.value = savedSel; }
        currencySelect.addEventListener('change', () => setDisplayCurrency(currencySelect.value));
    }
}

// ── Rebalance target / margin target inputs ───────────────────────────────────

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
            refreshRebalUI();
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
            refreshRebalUI();
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
