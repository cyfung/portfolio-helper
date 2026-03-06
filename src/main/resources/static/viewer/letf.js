// ── letf.js — Estimated value calculation for leveraged ETFs ─────────────────
// Depends on: utils.js

function updateAllEstVals() {
    const stale = globalIsMarketClosed && (
        marketCloseTimeMs === null ||
        Date.now() - marketCloseTimeMs > 12 * 3600 * 1000
    );

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
