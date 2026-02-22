'use strict';

/**
 * Compute NPV of a cash flow array at the given periodic rate.
 * cashFlows[t] is the cash flow at time t (t=0 is immediate).
 */
function npv(rate, cashFlows) {
    return cashFlows.reduce(function(sum, cf, t) {
        return sum + cf / Math.pow(1 + rate, t);
    }, 0);
}

/**
 * Find the periodic IRR using Brent's method.
 * Returns null if no sign change is found within the bracket,
 * meaning no real solution exists with these cash flows.
 */
function findIRR(cashFlows) {
    var lo = -0.9999;
    var hi = 100.0;
    var fLo = npv(lo, cashFlows);
    var fHi = npv(hi, cashFlows);

    if (fLo * fHi > 0) return null;

    // Brent's method
    var a = lo, b = hi;
    var fa = fLo, fb = fHi;
    var c = a, fc = fa;
    var mflag = true;
    var s = 0, d = 0;
    var tol = 1e-10;
    var maxIter = 200;

    for (var i = 0; i < maxIter; i++) {
        if (Math.abs(b - a) < tol) break;

        if (fa !== fc && fb !== fc) {
            // Inverse quadratic interpolation
            s = (a * fb * fc) / ((fa - fb) * (fa - fc))
              + (b * fa * fc) / ((fb - fa) * (fb - fc))
              + (c * fa * fb) / ((fc - fa) * (fc - fb));
        } else {
            // Secant method
            s = b - fb * (b - a) / (fb - fa);
        }

        var cond1 = !((3 * a + b) / 4 < s && s < b || b < s && s < (3 * a + b) / 4);
        var cond2 = mflag && Math.abs(s - b) >= Math.abs(b - c) / 2;
        var cond3 = !mflag && Math.abs(s - b) >= Math.abs(c - d) / 2;
        var cond4 = mflag && Math.abs(b - c) < tol;
        var cond5 = !mflag && Math.abs(c - d) < tol;

        if (cond1 || cond2 || cond3 || cond4 || cond5) {
            s = (a + b) / 2;
            mflag = true;
        } else {
            mflag = false;
        }

        var fs = npv(s, cashFlows);
        d = c;
        c = b;
        fc = fb;

        if (fa * fs < 0) {
            b = s; fb = fs;
        } else {
            a = s; fa = fs;
        }

        if (Math.abs(fa) < Math.abs(fb)) {
            var tmp = a; a = b; b = tmp;
            tmp = fa; fa = fb; fb = tmp;
        }
    }

    return b;
}

function showError(msg) {
    var errEl = document.getElementById('loan-error');
    errEl.textContent = msg;
    errEl.style.display = 'block';
    document.getElementById('loan-results').style.display = 'none';
}

function hideError() {
    document.getElementById('loan-error').style.display = 'none';
}

function formatPct(value) {
    return (value * 100).toFixed(4) + '%';
}

function formatCurrency(value) {
    return value.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

function calculate() {
    hideError();

    var loanAmount    = parseFloat(document.getElementById('loan-amount').value);
    var numPeriods    = parseInt(document.getElementById('num-periods').value, 10);
    var paymentVal    = document.getElementById('payment').value.trim();
    var rateApyVal    = document.getElementById('rate-apy').value.trim();
    var rateFlatVal     = document.getElementById('rate-flat').value.trim();
    var ppy           = parseInt(document.getElementById('period-length').value, 10);

    if (isNaN(loanAmount) || loanAmount <= 0) { showError('Enter a valid loan amount > 0.'); return; }
    if (isNaN(numPeriods) || numPeriods < 1)  { showError('Number of periods must be at least 1.'); return; }

    var payment;
    if (rateApyVal !== '') {
        var apy = parseFloat(rateApyVal) / 100;
        if (isNaN(apy) || apy < 0) { showError('Enter a valid APY %.'); return; }
        var r = Math.pow(1 + apy, 1 / ppy) - 1;
        payment = r === 0
            ? loanAmount / numPeriods
            : loanAmount * r / (1 - Math.pow(1 + r, -numPeriods));
    } else if (rateFlatVal !== '') {
        // Flat rate (add-on): interest = principal × rate × numPeriods
        var flatRate = parseFloat(rateFlatVal) / 100;
        if (isNaN(flatRate) || flatRate < 0) { showError('Enter a valid Flat Rate %.'); return; }
        payment = loanAmount * (1 + flatRate * numPeriods) / numPeriods;
    } else {
        payment = parseFloat(paymentVal);
        if (isNaN(payment) || payment < 0) { showError('Enter a valid payment amount ≥ 0.'); return; }
    }

    // Build cash flow array indexed by period [0 .. numPeriods]
    var cashFlows = new Array(numPeriods + 1).fill(0);
    cashFlows[0] = loanAmount;        // t=0: borrower receives loan
    for (var t = 1; t <= numPeriods; t++) {
        cashFlows[t] = -payment;      // t=1..n: borrower pays
    }

    // Apply extra cash flows
    var extraRows = document.querySelectorAll('.cashflow-row');
    var extraPositiveTotal = 0;
    extraRows.forEach(function(row) {
        var cfAmount = parseFloat(row.querySelector('.cf-amount').value);
        var cfPeriod = parseInt(row.querySelector('.cf-period').value, 10);
        if (!isNaN(cfAmount) && !isNaN(cfPeriod) && cfPeriod >= 0 && cfPeriod <= numPeriods) {
            cashFlows[cfPeriod] += cfAmount;
            if (cfAmount > 0) extraPositiveTotal += cfAmount;
        }
    });

    var r = findIRR(cashFlows);
    if (r === null || !isFinite(r)) {
        showError('Could not solve for rate. Check that total payments exceed the loan amount and inputs are consistent.');
        return;
    }

    var apr = r * ppy;
    var apy = Math.pow(1 + r, ppy) - 1;
    var totalPayments = payment * numPeriods - extraPositiveTotal;
    var totalInterest = totalPayments - loanAmount;

    document.getElementById('result-periodic-rate').textContent = formatPct(r);
    document.getElementById('result-apr').textContent           = formatPct(apr);
    document.getElementById('result-apy').textContent           = formatPct(apy);
    document.getElementById('result-total-payments').textContent = formatCurrency(totalPayments);
    document.getElementById('result-total-interest').textContent = formatCurrency(totalInterest);

    document.getElementById('loan-results').style.display = 'block';
}

function addCashflowRow() {
    var container = document.getElementById('cashflow-rows');
    var row = document.createElement('div');
    row.className = 'cashflow-row';
    row.innerHTML =
        '<input type="number" class="cf-amount" placeholder="Amount" step="any">' +
        '<span class="cf-label">at period</span>' +
        '<input type="number" class="cf-period" placeholder="Period" min="0" step="1">' +
        '<button type="button" class="cf-remove" aria-label="Remove row">✕</button>';
    container.appendChild(row);
}

document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('calculate-btn').addEventListener('click', calculate);

    // Mutual exclusion: filling one rate/payment input clears the others
    var paymentEl      = document.getElementById('payment');
    var rateApyEl      = document.getElementById('rate-apy');
    var rateFlatEl     = document.getElementById('rate-flat');

    paymentEl.addEventListener('input', function() {
        rateApyEl.value = '';
        rateFlatEl.value = '';
    });
    rateApyEl.addEventListener('input', function() {
        paymentEl.value = '';
        rateFlatEl.value = '';
    });
    rateFlatEl.addEventListener('input', function() {
        paymentEl.value = '';
        rateApyEl.value = '';
    });

    document.getElementById('add-cashflow').addEventListener('click', addCashflowRow);

    document.getElementById('cashflow-rows').addEventListener('click', function(e) {
        if (e.target.classList.contains('cf-remove')) {
            e.target.closest('.cashflow-row').remove();
        }
    });

    document.querySelector('.loan-card').addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && e.target.tagName !== 'BUTTON') {
            e.preventDefault();
            calculate();
        }
    });
});
