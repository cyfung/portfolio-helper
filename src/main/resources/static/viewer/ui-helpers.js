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
