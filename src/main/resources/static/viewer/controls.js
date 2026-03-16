// ── controls.js — Currency, column visibility, rebalance/margin inputs, theme ─
// Depends on: utils.js, ui-helpers.js, rebalance.js, cash.js

// ── Column visibility ─────────────────────────────────────────────────────────

function initMoreInfoToggle() {
    const btn = document.getElementById('more-info-toggle');
    if (!btn) return;
    const body = document.body;
    const visible = (localStorage.getItem('portfolio-helper-more-info-visible') || localStorage.getItem('ib-viewer-more-info-visible')) === 'true';
    if (visible) {
        body.classList.add('more-info-visible');
        btn.classList.add('active');
    }
    btn.addEventListener('click', () => {
        const isVisible = body.classList.toggle('more-info-visible');
        btn.classList.toggle('active', isVisible);
        localStorage.setItem('portfolio-helper-more-info-visible', isVisible);
    });
}

function initColumnVisibility() {
    const rebalToggle = document.getElementById('rebal-toggle');
    const body = document.body;

    const rebalVisible = (localStorage.getItem('portfolio-helper-rebal-visible') || localStorage.getItem('ib-viewer-rebal-visible')) === 'true';
    if (rebalVisible) {
        body.classList.add('rebalancing-visible');
        rebalToggle.classList.add('active');
    }

    rebalToggle.addEventListener('click', () => {
        const isVisible = body.classList.toggle('rebalancing-visible');
        rebalToggle.classList.toggle('active');
        localStorage.setItem('portfolio-helper-rebal-visible', isVisible);
        updateTargetWeightTotal();
    });
}

// ── Currency controls ─────────────────────────────────────────────────────────

function refreshDisplayCurrency() {
    // Convert saved rebal target to new display currency
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

    // All cells (stock, cash, margin, IBKR interest) re-rendered via worker
    scheduleDisplayUpdate();
}

function initCurrencyControls() {
    function setDisplayCurrency(ccy) {
        currentDisplayCurrency = ccy;
        localStorage.setItem('portfolio-helper-display-currency', ccy);
        refreshDisplayCurrency();
    }

    const currencyToggle = document.getElementById('currency-toggle');
    if (currencyToggle) {
        const currencies = currencyToggle.getAttribute('data-currencies').split(',');
        const saved = localStorage.getItem('portfolio-helper-display-currency') || localStorage.getItem('ib-viewer-display-currency');
        if (saved && currencies.includes(saved)) {
            currentDisplayCurrency = saved;
            currencyToggle.querySelector('.toggle-label').textContent = saved;
        }
        currencyToggle.classList.add('active');
        currencyToggle.addEventListener('click', () => {
            const next = currencies[(currencies.indexOf(currentDisplayCurrency) + 1) % currencies.length];
            currencyToggle.querySelector('.toggle-label').textContent = next;
            setDisplayCurrency(next);
        });
    }

    const currencySelect = document.getElementById('currency-select');
    if (currencySelect) {
        const savedSel = localStorage.getItem('portfolio-helper-display-currency') || localStorage.getItem('ib-viewer-display-currency');
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
            scheduleDisplayUpdate();
        });
    }
    if (allocReduceSelect) {
        allocReduceSelect.value = allocReduceMode;
        allocReduceSelect.addEventListener('change', () => {
            allocReduceMode = allocReduceSelect.value;
            fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=allocReduceMode`, { method: 'POST', body: allocReduceMode });
            scheduleDisplayUpdate();
        });
    }
}

