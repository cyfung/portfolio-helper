// ── montecarlo-chart.js — Chart rendering and stats table for MC ──────────────
// Depends on: PALETTE (backtest-blocks.js), stats-formatters.js, Chart.js (external)

var mcChartInstance = null;
var mcCurrentPercentile = 50;
var mcLastData = null;
var mcSortMetric = 'CAGR';

function showError(msg) {
    const el = document.getElementById('error-msg');
    el.textContent = msg;
    el.style.display = '';
    document.getElementById('chart-container').style.display = 'none';
    document.getElementById('stats-container').style.display = 'none';
    document.getElementById('mc-percentile-bar').style.display = 'none';
}

function renderMcResults(data, sortMetric) {
    mcLastData = data;
    if (sortMetric) mcSortMetric = sortMetric;
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

    const cols = [
        { metric: 'CAGR',        label: 'CAGR' },
        { metric: 'MAX_DD',      label: 'Max DD' },
        { metric: 'SHARPE',      label: 'Sharpe' },
        { metric: 'ULCER_INDEX', label: 'Ulcer' },
        { metric: 'UPI',         label: 'UPI' }
    ];

    function cellValue(pp, metric) {
        switch (metric) {
            case 'CAGR':        return pct(pp.cagr);
            case 'MAX_DD':      return pct(pp.maxDrawdown);
            case 'SHARPE':      return fmt2(pp.sharpe);
            case 'ULCER_INDEX': return pct(pp.ulcerIndex);
            case 'UPI':         return fmt2(pp.upi);
        }
    }

    function highlight(metric) { return metric === mcSortMetric ? ' class="mc-sort-target"' : ''; }

    let html = `<div class="mc-stats-header">Results at <strong>${percentile}th percentile</strong> (${data.numSimulations} simulations, ${data.simulatedYears}yr)</div>`;
    html += '<table class="backtest-stats-table"><thead><tr><th>Curve</th>';
    cols.forEach(c => { html += `<th data-metric="${c.metric}"${highlight(c.metric)}>${c.label}</th>`; });
    html += '</tr></thead><tbody>';

    data.portfolios.forEach(portfolio => {
        portfolio.curves.forEach(curve => {
            const pp = curve.percentilePaths.find(p => p.percentile === percentile);
            if (!pp) return;
            const curveLabel = `${portfolio.label} \u2013 ${curve.label}`;
            html += `<tr><td>${curveLabel}</td>`;
            cols.forEach(c => {
                html += `<td data-metric="${c.metric}"${highlight(c.metric)}>${cellValue(pp, c.metric)}</td>`;
            });
            html += '</tr>';
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
                renderMcStats(mcLastData, mcCurrentPercentile);  // uses mcSortMetric global
            }
        });
    });
}
