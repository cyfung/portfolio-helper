// ── backtest-saved.js — Saved portfolios bar: fetch, render, drag, delete ─────
// Depends on: backtest-blocks.js (loadPortfolioIntoBlock)

async function refreshSavedPortfolios() {
    try {
        const res = await fetch('/api/backtest/savedPortfolios');
        if (!res.ok) return;
        const list = await res.json();
        renderSavedBar(list);
    } catch (_) { /* silently ignore */ }
}

function renderSavedBar(list) {
    const bar = document.getElementById('saved-portfolios-bar');
    bar.innerHTML = '';
    bar.style.display = list.length ? '' : 'none';
    list.forEach(p => {
        const chip = document.createElement('div');
        chip.className = 'saved-portfolio-chip';
        chip.draggable = true;
        chip.dataset.config = JSON.stringify(p.config);
        chip.dataset.name = p.name;

        const label = document.createElement('span');
        label.textContent = p.name;

        const del = document.createElement('button');
        del.className = 'saved-portfolio-chip-del';
        del.type = 'button';
        del.title = 'Delete';
        del.textContent = '✕';
        del.addEventListener('click', async e => {
            e.stopPropagation();
            await fetch(`/api/backtest/savedPortfolios?name=${encodeURIComponent(p.name)}`, { method: 'DELETE' });
            refreshSavedPortfolios();
        });

        chip.appendChild(label);
        chip.appendChild(del);
        chip.addEventListener('dragstart', e => {
            e.dataTransfer.setData('text/plain', p.name);
            e.dataTransfer.effectAllowed = 'copy';
        });
        bar.appendChild(chip);
    });
}
