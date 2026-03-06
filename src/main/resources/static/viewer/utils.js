// ── utils.js — Pure helpers: parsing, formatting, display currency ────────────

// Shared SVG icons (used across modules)
const COPY_ICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
const CHECKMARK_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';

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
