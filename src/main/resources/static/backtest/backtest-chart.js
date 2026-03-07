// ── backtest-chart.js — Chart rendering and stats table ──────────────────────
// Depends on: PALETTE (backtest-blocks.js), stats-formatters.js, Chart.js (external)

var chartInstance = null;

function showError(msg) {
    const errorMsg = document.getElementById('error-msg');
    errorMsg.textContent = msg;
    errorMsg.style.display = '';
    document.getElementById('chart-container').style.display = 'none';
    document.getElementById('stats-container').style.display = 'none';
}

function renderChart(data) {
    const chartContainer = document.getElementById('chart-container');
    chartContainer.style.display = '';

    if (chartInstance) {
        chartInstance.destroy();
        chartInstance = null;
    }

    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
    const textColor = isDark ? '#c0c0c0' : '#495057';

    // Build sorted intersection of dates across all portfolios so the chart
    // starts at the latest common start date and every curve has a value at
    // every x-axis position (no leading gaps from differing ticker histories).
    let commonDates = new Set(data.portfolios[0].curves[0].points.map(p => p.date));
    for (let i = 1; i < data.portfolios.length; i++) {
        const portfolioDates = new Set(data.portfolios[i].curves[0].points.map(p => p.date));
        for (const d of commonDates) {
            if (!portfolioDates.has(d)) commonDates.delete(d);
        }
    }
    const labels = [...commonDates].sort();

    // Map each dataset's values by date; use null for dates outside its range
    // so Chart.js auto-scales the y-axis correctly across all curves.
    const datasets = [];
    data.portfolios.forEach((portfolio, pi) => {
        const palette = PALETTE[pi % PALETTE.length];
        portfolio.curves.forEach((curve, ci) => {
            const valueMap = new Map(curve.points.map(p => [p.date, p.value]));
            datasets.push({
                label: `${portfolio.label} \u2013 ${curve.label}`,
                data: labels.map(d => valueMap.get(d) ?? null),
                spanGaps: false,
                borderColor: palette[ci % palette.length],
                backgroundColor: 'transparent',
                borderWidth: 1.5,
                pointRadius: 0,
                pointHoverRadius: 4
            });
        });
    });

    const ctx = document.getElementById('backtest-chart').getContext('2d');
    chartInstance = new Chart(ctx, {
        type: 'line',
        data: { labels, datasets },
        options: {
            animation: false,
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { labels: { color: textColor } },
                tooltip: {
                    mode: 'index',
                    callbacks: {
                        title: items => items[0]?.label || '',
                        label: item => ` ${item.dataset.label}: $${item.raw.toFixed(2)}`
                    }
                }
            },
            scales: {
                x: {
                    ticks: { color: textColor, maxTicksLimit: 10, maxRotation: 0 },
                    grid: { color: gridColor }
                },
                y: {
                    ticks: { color: textColor, callback: v => '$' + v.toFixed(0) },
                    grid: { color: gridColor }
                }
            }
        }
    });
}

function renderStats(data) {
    const statsContainer = document.getElementById('stats-container');
    statsContainer.style.display = '';

    function trig(v)  { return v == null ? '\u2013' : v; }

    let html = '<table class="backtest-stats-table"><thead><tr>' +
        '<th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th><th>Sharpe</th>' +
        '<th title="Ulcer Index: RMS of drawdowns from peak">Ulcer</th>' +
        '<th title="Ulcer Performance Index (Martin Ratio): excess return / Ulcer Index">UPI</th>' +
        '<th title="Deviation triggers: ratio exceeded upper bound (market fell)">Upper \u2191</th>' +
        '<th title="Deviation triggers: ratio fell below lower bound (market rose)">Lower \u2193</th>' +
        '</tr></thead><tbody>';

    data.portfolios.forEach(portfolio => {
        portfolio.curves.forEach(curve => {
            const curveLabel = `${portfolio.label} \u2013 ${curve.label}`;
            const s = curve.stats;
            html += `<tr>` +
                `<td>${curveLabel}</td>` +
                `<td>${money(s.endingValue)}</td>` +
                `<td>${pct(s.cagr)}</td>` +
                `<td>${pct(s.maxDrawdown)}</td>` +
                `<td>${fmt2(s.sharpe)}</td>` +
                `<td>${pct(s.ulcerIndex)}</td>` +
                `<td>${fmt2(s.upi)}</td>` +
                `<td>${trig(s.marginUpperTriggers)}</td>` +
                `<td>${trig(s.marginLowerTriggers)}</td>` +
                `</tr>`;
        });
    });

    html += '</tbody></table>';
    statsContainer.innerHTML = html;
}
