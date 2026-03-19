// ── letf.js — Estimated value calculation for leveraged ETFs ─────────────────
// Depends on: utils.js

function updateAllEstVals() {
    document.querySelectorAll('tbody tr[data-letf]').forEach(row => {
        const symbol = row.querySelector('td:first-child').textContent.trim();
        const letfAttr = row.getAttribute('data-letf');
        if (!letfAttr) return;

        const marketClosed = symbolMarketClosed[symbol] !== false; // undefined → assume closed
        const closeTimeMs = symbolTradingPeriodEndMs[symbol] ?? null;
        const stale = marketClosed && (closeTimeMs === null || Date.now() - closeTimeMs > 12 * 3600 * 1000);

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
            estValCell.textContent = estVal.toFixed(2);
            estValCell.dataset.estVal = estVal;
            estValCell.classList.add('loaded');
        }
    });
}

// ── Est Val price ladder tooltip ──────────────────────────────────────────────

let _estValTooltip = null;

function getEstValTooltip() {
    if (!_estValTooltip) {
        _estValTooltip = document.createElement('div');
        _estValTooltip.id = 'est-val-tooltip';
        document.body.appendChild(_estValTooltip);
    }
    return _estValTooltip;
}

document.addEventListener('mouseenter', e => {
    const cell = e.target.closest('td[id^="est-val-"].loaded');
    if (!cell) return;
    const estVal = parseFloat(cell.dataset.estVal);
    if (isNaN(estVal)) return;

    const deltas = [0.002, 0.001, 0, -0.001, -0.002];
    let html = '';
    for (const d of deltas) {
        const price = estVal * (1 + d);
        if (d === 0) {
            html += '<hr class="ladder-separator">';
        } else {
            const sign = d > 0 ? '+' : '−';
            const label = sign + Math.abs(d * 100).toFixed(1) + '%';
            const cls = d > 0 ? 'ladder-up' : 'ladder-down';
            html += `<span class="${cls}">${label}  ${price.toFixed(2)}</span>\n`;
        }
    }

    const tip = getEstValTooltip();
    tip.innerHTML = html;
    tip.style.display = 'block';

    const rect = cell.getBoundingClientRect();
    tip.style.left = (rect.right + 8) + 'px';
    tip.style.top = rect.top + 'px';
}, true);

document.addEventListener('mouseleave', e => {
    const cell = e.target.closest('td[id^="est-val-"].loaded');
    if (!cell) return;
    if (_estValTooltip) _estValTooltip.style.display = 'none';
}, true);
