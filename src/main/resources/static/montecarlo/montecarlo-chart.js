// ── montecarlo-chart.js — Chart rendering and stats table for MC ──────────────
// Depends on: PALETTE (backtest-blocks.js), Chart.js (external)

var mcChartInstance = null;
var mcCurrentPercentile = 50;
var mcLastData = null;

function showError(msg) {
    const el = document.getElementById('error-msg');
    el.textContent = msg;
    el.style.display = '';
    document.getElementById('chart-container').style.display = 'none';
    document.getElementById('stats-container').style.display = 'none';
    document.getElementById('mc-percentile-bar').style.display = 'none';
}

function renderMcResults(data) {
    mcLastData = data;
    document.getElementById('mc-percentile-bar').style.display = '';
    renderMcChart(data, mcCurrentPercentile);
    renderMcStats(data, mcCurrentPercentile);
}

function renderMcChart(data, percentile) {
    const chartContainer = document.getElementById('chart-container');
    chartContainer.style.display = '';

    if (mcChartInstance) { mcChartInstance.destroy(); mcChartInstance = null; }

    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    const gridColor  = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
    const textColor  = isDark ? '#c0c0c0' : '#495057';

    const simulatedYears = data.simulatedYears;
    const targetDays = simulatedYears * 252;
    // X-axis labels: one per year (every 252 points), 0..simulatedYears
    const xLabels = Array.from({length: targetDays + 1}, (_, i) => i);

    const datasets = [];
    data.portfolios.forEach((portfolio, pi) => {
        const palette = PALETTE[pi % PALETTE.length];
        portfolio.curves.forEach((curve, ci) => {
            const pp = curve.percentilePaths.find(p => p.percentile === percentile);
            if (!pp) return;
            datasets.push({
                label: `${portfolio.label} \u2013 ${curve.label}`,
                data: pp.points,
                borderColor: palette[ci % palette.length],
                backgroundColor: 'transparent',
                borderWidth: 1.5,
                pointRadius: 0,
                pointHoverRadius: 4
            });
        });
    });

    const ctx = document.getElementById('mc-chart').getContext('2d');
    mcChartInstance = new Chart(ctx, {
        type: 'line',
        data: { labels: xLabels, datasets },
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
                        title: items => {
                            const idx = items[0]?.dataIndex ?? 0;
                            const yr = (idx / 252).toFixed(1);
                            return `Year ${yr}`;
                        },
                        label: item => ` ${item.dataset.label}: $${item.raw.toFixed(0)}`
                    }
                }
            },
            scales: {
                x: {
                    ticks: {
                        color: textColor,
                        maxRotation: 0,
                        callback: (value, index) => {
                            if (index % 252 === 0) return index / 252;
                            return '';
                        }
                    },
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

function renderMcStats(data, percentile) {
    const statsContainer = document.getElementById('stats-container');
    statsContainer.style.display = '';

    function pct(v)   { return (v * 100).toFixed(2) + '%'; }
    function money(v) { return '$' + v.toFixed(0); }

    let html = `<div class="mc-stats-header">Results at <strong>${percentile}th percentile</strong> (${data.numSimulations} simulations, ${data.simulatedYears}yr)</div>`;
    html += '<table class="summary-table backtest-stats-table"><thead><tr>' +
        '<th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th>' +
        '</tr></thead><tbody>';

    data.portfolios.forEach(portfolio => {
        portfolio.curves.forEach(curve => {
            const pp = curve.percentilePaths.find(p => p.percentile === percentile);
            if (!pp) return;
            const curveLabel = `${portfolio.label} \u2013 ${curve.label}`;
            html += `<tr>` +
                `<td>${curveLabel}</td>` +
                `<td>${money(pp.endValue)}</td>` +
                `<td>${pct(pp.cagr)}</td>` +
                `<td>${pct(pp.maxDrawdown)}</td>` +
                `</tr>`;
        });
    });

    html += '</tbody></table>';
    statsContainer.innerHTML = html;
}

function initPercentileTabs() {
    document.querySelectorAll('.mc-pct-tab').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.mc-pct-tab').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            mcCurrentPercentile = parseInt(btn.dataset.pct, 10);
            if (mcLastData) {
                renderMcChart(mcLastData, mcCurrentPercentile);
                renderMcStats(mcLastData, mcCurrentPercentile);
            }
        });
    });
}
