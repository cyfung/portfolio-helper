// ── backtest-chart.js — Chart rendering and stats table ──────────────────────
// Depends on: PALETTE (backtest-blocks.js), stats-formatters.js, Chart.js (external)

var chartInstance         = null;
var drawdownChartInstance = null;
var rtrChartInstance      = null;
var selectedCurves = new Set();
var backtestLastData = null;
var logScaleEnabled = false;

function resetCurveSelection() { selectedCurves.clear(); }

function showError(msg) {
    const errorMsg = document.getElementById('error-msg');
    errorMsg.textContent = msg;
    errorMsg.style.display = '';
    document.getElementById('chart-container').style.display    = 'none';
    document.getElementById('drawdown-container').style.display = 'none';
    document.getElementById('rtr-container').style.display      = 'none';
    document.getElementById('stats-container').style.display    = 'none';
}

function buildCommonLabels(data) {
    let commonDates = new Set(data.portfolios[0].curves[0].points.map(p => p.date));
    for (let i = 1; i < data.portfolios.length; i++) {
        const portfolioDates = new Set(data.portfolios[i].curves[0].points.map(p => p.date));
        for (const d of commonDates) { if (!portfolioDates.has(d)) commonDates.delete(d); }
    }
    return [...commonDates].sort();
}

function buildCurveDatasets(data, labels, valueExtractor) {
    const datasets = [];
    data.portfolios.forEach((portfolio, pi) => {
        const palette = PALETTE[pi % PALETTE.length];
        portfolio.curves.forEach((curve, ci) => {
            if (selectedCurves.size > 0 && !selectedCurves.has(`${pi}-${ci}`)) return;
            const derived = valueExtractor(curve.points);
            const derivedByDate = new Map(curve.points.map((p, i) => [p.date, derived[i]]));
            datasets.push({
                label: `${portfolio.label} \u2013 ${curve.label}`,
                data: labels.map(d => derivedByDate.get(d) ?? null),
                spanGaps: false,
                borderColor: palette[ci % palette.length],
                backgroundColor: 'transparent',
                borderWidth: 1.5,
                pointRadius: 0,
                pointHoverRadius: 4
            });
        });
    });
    return datasets;
}

function computeDrawdown(points) {
    let peak = -Infinity;
    return points.map(p => {
        if (p.value > peak) peak = p.value;
        return (p.value / peak) - 1;
    });
}

function computeRTR(points) {
    let peak = -Infinity;
    return points.map(p => {
        if (p.value > peak) peak = p.value;
        return p.value > 0 ? peak / p.value : null;
    });
}

function renderChart(data) {
    backtestLastData = data;
    const chartContainer = document.getElementById('chart-container');
    chartContainer.style.display = '';

    if (chartInstance) {
        chartInstance.destroy();
        chartInstance = null;
    }

    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
    const textColor = isDark ? '#c0c0c0' : '#495057';

    const labels = buildCommonLabels(data);
    const datasets = buildCurveDatasets(data, labels, points => points.map(p => p.value));

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
                title: { display: true, text: 'Portfolio Value', color: textColor, font: { size: 13 } },
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
                    type: logScaleEnabled ? 'logarithmic' : 'linear',
                    ticks: { color: textColor, callback: v => '$' + v.toFixed(0) },
                    grid: { color: gridColor }
                }
            }
        }
    });

    const logBtn = document.getElementById('log-scale-toggle');
    if (logBtn) {
        logBtn.classList.toggle('active', logScaleEnabled);
        logBtn.onclick = () => {
            logScaleEnabled = !logScaleEnabled;
            if (backtestLastData) renderChart(backtestLastData);
        };
    }

    renderDrawdownChart(data);
    renderRTRChart(data);
}

function renderDrawdownChart(data) {
    if (drawdownChartInstance) {
        drawdownChartInstance.destroy();
        drawdownChartInstance = null;
    }

    const isDark    = document.documentElement.getAttribute('data-theme') === 'dark';
    const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
    const textColor = isDark ? '#c0c0c0' : '#495057';
    const labels    = buildCommonLabels(data);
    const datasets  = buildCurveDatasets(data, labels, computeDrawdown);

    const ctx = document.getElementById('drawdown-chart').getContext('2d');
    drawdownChartInstance = new Chart(ctx, {
        type: 'line',
        data: { labels, datasets },
        options: {
            animation: false,
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                title: { display: true, text: 'Drawdown', color: textColor, font: { size: 13 } },
                legend: { labels: { color: textColor } },
                tooltip: {
                    mode: 'index',
                    callbacks: {
                        title: items => items[0]?.label || '',
                        label: item => ` ${item.dataset.label}: ${(item.raw * 100).toFixed(2)}%`
                    }
                }
            },
            scales: {
                x: {
                    ticks: { color: textColor, maxTicksLimit: 10, maxRotation: 0 },
                    grid: { color: gridColor }
                },
                y: {
                    ticks: { color: textColor, callback: v => (v * 100).toFixed(1) + '%' },
                    grid: { color: gridColor }
                }
            }
        }
    });

    document.getElementById('drawdown-container').style.display = '';
}

function renderRTRChart(data) {
    if (rtrChartInstance) {
        rtrChartInstance.destroy();
        rtrChartInstance = null;
    }

    const isDark    = document.documentElement.getAttribute('data-theme') === 'dark';
    const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
    const textColor = isDark ? '#c0c0c0' : '#495057';
    const labels    = buildCommonLabels(data);
    const datasets  = buildCurveDatasets(data, labels, computeRTR);

    const ctx = document.getElementById('rtr-chart').getContext('2d');
    rtrChartInstance = new Chart(ctx, {
        type: 'line',
        data: { labels, datasets },
        options: {
            animation: false,
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                title: { display: true, text: 'Return Required to Recover', color: textColor, font: { size: 13 } },
                legend: { labels: { color: textColor } },
                tooltip: {
                    mode: 'index',
                    callbacks: {
                        title: items => items[0]?.label || '',
                        label: item => ` ${item.dataset.label}: ${item.raw.toFixed(2)}x`
                    }
                }
            },
            scales: {
                x: {
                    ticks: { color: textColor, maxTicksLimit: 10, maxRotation: 0 },
                    grid: { color: gridColor }
                },
                y: {
                    ticks: { color: textColor, callback: v => v.toFixed(2) + 'x' },
                    grid: { color: gridColor }
                }
            }
        }
    });

    document.getElementById('rtr-container').style.display = '';
}

function renderStats(data) {
    const statsContainer = document.getElementById('stats-container');
    statsContainer.style.display = '';

    function trig(v)  { return v == null ? '\u2013' : v; }

    const allKeys = [];
    data.portfolios.forEach((portfolio, pi) => {
        portfolio.curves.forEach((curve, ci) => { allKeys.push(`${pi}-${ci}`); });
    });

    let html = '<table class="backtest-stats-table"><thead><tr>' +
        '<th><input type="checkbox" class="curve-toggle" id="curve-toggle-all"></th>' +
        '<th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th><th>Sharpe</th>' +
        '<th title="Ulcer Index: RMS of drawdowns from peak">Ulcer</th>' +
        '<th title="Ulcer Performance Index (Martin Ratio): excess return / Ulcer Index">UPI</th>' +
        '<th title="# of times the margin ratio exceeded target + upper deviation band (market fell → over-leveraged), triggering an extra rebalance">Rebal\u2191</th>' +
        '<th title="# of times the margin ratio fell below target \u2212 lower deviation band (market rose → under-leveraged), triggering an extra rebalance">Rebal\u2193</th>' +
        '</tr></thead><tbody>';

    data.portfolios.forEach((portfolio, pi) => {
        portfolio.curves.forEach((curve, ci) => {
            const key = `${pi}-${ci}`;
            const curveLabel = `${portfolio.label} \u2013 ${curve.label}`;
            const s = curve.stats;
            html += `<tr>` +
                `<td><input type="checkbox" class="curve-toggle" data-key="${key}"></td>` +
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

    wireCurveToggles(statsContainer, allKeys, selectedCurves, 'curve-toggle-all',
        () => { if (backtestLastData) renderChart(backtestLastData); });
}
