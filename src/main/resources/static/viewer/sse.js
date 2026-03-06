// ── sse.js — Server-Sent Events connection and dispatch ───────────────────────
// Depends on: ui-helpers.js, portfolio.js, cash.js, letf.js

function initSseConnection() {
    const timeEl = document.getElementById('last-update-time');
    if (timeEl) {
        const dot = document.createElement('span');
        dot.id = 'sse-status-dot';
        dot.className = 'sse-dot';
        dot.title = 'Connecting…';
        timeEl.after(dot);
    }

    let sseLastActivity = Date.now();

    function setSseStatus(ok) {
        const dot = document.getElementById('sse-status-dot');
        if (dot) {
            dot.className = 'sse-dot ' + (ok ? 'sse-dot--ok' : 'sse-dot--err');
            dot.title = ok ? 'Live' : 'Disconnected';
        }
    }

    const eventSource = new EventSource('/api/prices/stream');

    eventSource.onopen = () => {
        sseLastActivity = Date.now();
        setSseStatus(true);
    };

    eventSource.onmessage = (event) => {
        sseLastActivity = Date.now();
        try {
            const data = JSON.parse(event.data);

            if (data.type === 'reload') {
                console.log('Portfolio reloaded, refreshing page...');
                location.reload();
            } else if (data.type === 'nav') {
                updateNavInUI(data.symbol, data.nav);
            } else if (data.type === 'portfolio-value') {
                updatePortfolioRefValues(data.portfolioId, data.value);
            } else {
                // FX rate update
                if (data.symbol && data.symbol.endsWith('USD=X')) {
                    const ccy = data.symbol.replace('USD=X', '');
                    if (data.markPrice !== null && data.markPrice !== undefined) {
                        fxRates[ccy] = data.markPrice;
                        updateCashTotals();
                        updateIbkrDailyInterest();
                    }
                    return;
                }

                updateGlobalTimestamp(data.timestamp);
                updatePriceInUI(data.symbol, data.markPrice, data.lastClosePrice, data.isMarketClosed || false, data.tradingPeriodEnd);
            }
        } catch (e) {
            console.error('Failed to parse SSE data:', e);
        }
    };

    eventSource.onerror = (error) => {
        console.error('SSE connection error:', error);
        setSseStatus(false);
    };

    // Reload page if SSE has been broken for 5 minutes
    setInterval(() => {
        if (eventSource.readyState !== EventSource.OPEN && Date.now() - sseLastActivity > 5 * 60_000) {
            console.warn('SSE disconnected for 5 minutes, reloading page to recover...');
            location.reload();
        }
    }, 60_000);
}
