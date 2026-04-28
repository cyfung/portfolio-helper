// ── RebalanceStrategyPage.tsx ─────────────────────────────────────────────────

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ComposedChart, LineChart, Line, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush,
} from 'recharts'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'
import RebalanceStrategyBlock from '@/components/rebalance/RebalanceStrategyBlock'
import { useChartTheme } from '@/lib/chartTheme'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, BacktestResults, emptyBlock, blockStateToAPIPortfolio,
  PALETTE, CASHFLOW_FREQUENCY_OPTIONS,
} from '@/types/backtest'
import { scaleDash } from '@/lib/colorScheme'
import {
  buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR,
} from '@/lib/chartData'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import {
  RebalStrategyState, emptyStrategy, strategyStateToAPI,
} from '@/types/rebalanceStrategy'

// ── Legend line ───────────────────────────────────────────────────────────────

function LegendLine({ color, strokeWidth, strokeDasharray }: { color: string; strokeWidth: number; strokeDasharray?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current; if (!canvas) return
    const ctx = canvas.getContext('2d'); if (!ctx) return
    ctx.clearRect(0, 0, 28, 10)
    ctx.strokeStyle = color; ctx.lineWidth = strokeWidth
    ctx.setLineDash(strokeDasharray ? strokeDasharray.split(' ').map(Number) : [])
    ctx.beginPath(); ctx.moveTo(2, 5); ctx.lineTo(26, 5); ctx.stroke()
  }, [color, strokeWidth, strokeDasharray])
  return <canvas ref={ref} width={28} height={10} style={{ display: 'inline-block', verticalAlign: 'middle' }} />
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function RebalanceStrategyPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [strategies, setStrategies] = useState<RebalStrategyState[]>([emptyStrategy(0), emptyStrategy(1)])
  const [fromDate, setFromDate]   = useState('')
  const [toDate, setToDate]       = useState('')
  const [cashflowAmount, setCashflowAmount]       = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState('NONE')
  const [running, setRunning]     = useState(false)
  const [error, setError]         = useState('')
  const [results, setResults]     = useState<BacktestResults | null>(null)
  const [selected, setSelected]   = useState<Set<string>>(new Set())
  const [logScale, setLogScale]   = useState(false)

  const [chartWidth, setChartWidth] = useState(1000)
  const chartObsRef = useRef<ResizeObserver | null>(null)
  const chartContainerRef = useCallback((node: HTMLDivElement | null) => {
    chartObsRef.current?.disconnect(); chartObsRef.current = null
    if (!node) return
    const obs = new ResizeObserver(entries => setChartWidth(entries[0].contentRect.width))
    obs.observe(node); chartObsRef.current = obs
  }, [])

  const theme = useChartTheme()
  const { gridColor, textColor } = theme

  // ── Run ───────────────────────────────────────────────────────────────────

  async function handleRun() {
    setError('')
    const portfolioApi = blockStateToAPIPortfolio(portfolio, 0)
    if (portfolioApi.tickers.length === 0) {
      setError('Add at least one ticker with a positive weight to the portfolio.'); return
    }
    for (const [i, s] of strategies.entries()) {
      const n = i + 1
      if (s.sellHighEnabled && (!s.sellHighDeviationPct.trim() || parseFloat(s.sellHighDeviationPct) <= 0))
        { setError(`Strategy ${n}: Sell on High Margin threshold must be greater than 0`); return }
      if (s.buyLowEnabled && (!s.buyLowDeviationPct.trim() || parseFloat(s.buyLowDeviationPct) <= 0))
        { setError(`Strategy ${n}: Buy on Low Margin threshold must be greater than 0`); return }
    }

    setRunning(true)
    try {
      const res = await fetch('/api/rebalance-strategy/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromDate: fromDate || null,
          toDate: toDate || null,
          portfolio: portfolioApi,
          cashflow: cashflowAmount && cashflowFrequency !== 'NONE'
            ? { amount: parseFloat(cashflowAmount), frequency: cashflowFrequency }
            : null,
          strategies: strategies.map(s => strategyStateToAPI(s, portfolio.rebalance)),
        }),
      })
      const data: BacktestResults = await res.json()
      if (!res.ok || data.error) { setError(data.error || `Server error ${res.status}`); return }
      setResults(data)
      setSelected(new Set(data.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))))
    } catch (e: any) {
      setError('Request failed: ' + e.message)
    } finally {
      setRunning(false)
    }
  }

  // ── Chart data ────────────────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!results) return null
    const labels = buildCommonLabels(results)
    const mainData   = buildRechartsData(results, labels, selected, pts => pts.map(p => p.value))
    const ddData     = buildRechartsData(results, labels, selected, computeDrawdown)
    const rtrData    = buildRechartsData(results, labels, selected, computeRTR)
    const marginData = buildRechartsData(results, labels, selected, pts => pts.map(p => p.value), c => c.marginPoints)
    const marginDatasets = marginData.datasets.map(ds => ({ ...ds, label: `m:${ds.label}` }))
    const combinedRows = mainData.rows.map((row, i) => {
      const extra: Record<string, any> = {}
      for (const [k, v] of Object.entries(marginData.rows[i])) {
        if (k !== 'x') extra[`m:${k}`] = v
      }
      return { ...row, ...extra }
    })
    return { labels, mainData, ddData, rtrData, marginDatasets, combinedRows }
  }, [results, selected])

  const allKeys    = results ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`)) : []
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0
  const showLine   = (key: string) => selected.size === 0 || selected.has(key)

  function toggleCurve(key: string, checked: boolean) {
    setSelected(prev => { const s = new Set(prev); checked ? s.add(key) : s.delete(key); return s })
  }
  function toggleAll(checked: boolean) { setSelected(checked ? new Set(allKeys) : new Set()) }

  const updateStrategy = useCallback((i: number) =>
    (s: RebalStrategyState) => setStrategies(prev => { const n = [...prev]; n[i] = s; return n }),
    [],
  )

  const numPoints      = chartData?.labels.length ?? 2
  const pixelsPerPoint = chartWidth / Math.max(numPoints - 1, 1)

  const makeTooltip = (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) =>
    makeRechartsTooltip(theme, valueFmt, labelFmt)

  const commonLineProps = {
    type: 'monotone' as const, dot: false as const,
    activeDot: { r: 4 }, connectNulls: false, isAnimationActive: false,
  }

  const renderLegend = (props: any) => {
    const { payload } = props
    if (!payload?.length) return null
    return (
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem 0.8rem', fontSize: '0.78em', color: textColor, padding: '4px 8px 0' }}>
        {payload.map((entry: any, i: number) => (
          <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
            <LegendLine color={entry.color as string} strokeWidth={2} />
            <span>{entry.value}</span>
          </span>
        ))}
      </div>
    )
  }

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/rebalance-strategy" /></div>
        <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
      </div>

      <div className="backtest-form-card">
        <div className="backtest-section backtest-grid-2">
          <DateFieldWithQuickSelect label="From Date" inputId="rs-from-date" value={fromDate} onChange={setFromDate} />
          <DateFieldWithQuickSelect label="To Date"   inputId="rs-to-date"   value={toDate}   onChange={setToDate} />

          <div>
            <label htmlFor="rs-cashflow-amount">Cashflow Amount</label>
            <input
              type="number" id="rs-cashflow-amount" placeholder="e.g. 1000" min="0" step="100"
              value={cashflowAmount} onChange={e => setCashflowAmount(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="rs-cashflow-frequency">Cashflow Frequency</label>
            <select id="rs-cashflow-frequency" value={cashflowFrequency} onChange={e => setCashflowFrequency(e.target.value)}>
              {CASHFLOW_FREQUENCY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
        </div>

        {/* Portfolio + Strategy blocks side by side */}
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', marginTop: '0.75rem' }}>
          <PortfolioBlock idx={0} value={portfolio} onChange={setPortfolio} onSavedRefresh={() => {}} />
          {strategies.map((s, i) => (
            <RebalanceStrategyBlock key={i} idx={i} value={s} onChange={updateStrategy(i)} />
          ))}
        </div>

        <button className="run-backtest-btn" type="button" onClick={handleRun} disabled={running}>
          {running ? <>Running…<span className="btn-spinner" /></> : 'Run Rebalance Strategy'}
        </button>
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {results && chartData && (
        <>
          {/* Stats table */}
          <div className="stats-container">
            <table className="backtest-stats-table">
              <thead>
                <tr>
                  <th>
                    <input
                      type="checkbox" checked={allChecked}
                      ref={el => { if (el) el.indeterminate = anyChecked && !allChecked }}
                      onChange={e => toggleAll(e.target.checked)}
                    />
                  </th>
                  <th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th>
                  <th title="Peak-to-recovery duration of the worst drawdown">Longest DD</th>
                  <th title="Annualised volatility of daily returns">Volatility</th>
                  <th>Sharpe</th>
                  <th title="Ulcer Index">Ulcer</th>
                  <th title="Ulcer Performance Index">UPI</th>
                </tr>
              </thead>
              <tbody>
                {results.portfolios.flatMap((portfolio, pi) =>
                  portfolio.curves.map((curve, ci) => {
                    const key = `${pi}-${ci}`
                    const s = curve.stats
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td style={{ color: PALETTE[pi % PALETTE.length][ci % PALETTE[pi % PALETTE.length].length] }}>
                          {portfolio.label} – {curve.label}
                        </td>
                        <td>{money(s.endingValue)}</td>
                        <td>{pct(s.cagr)}</td>
                        <td>{pct(s.maxDrawdown)}</td>
                        <td>{dur(s.longestDrawdownDays)}</td>
                        <td>{pct(s.annualVolatility)}</td>
                        <td>{fmt2(s.sharpe)}</td>
                        <td>{pct(s.ulcerIndex)}</td>
                        <td>{fmt2(s.upi)}</td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Portfolio Value + Margin Utilization chart */}
          <div className="backtest-chart-title">Portfolio Value</div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.25rem' }}>
            <button className={`chart-scale-toggle${logScale ? ' active' : ''}`} type="button"
              style={{ position: 'static' }} onClick={() => setLogScale(l => !l)}>Log</button>
          </div>
          <div className="backtest-chart-container" ref={chartContainerRef}>
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={chartData.combinedRows} syncId="rs-backtest"
                margin={{ top: 8, right: chartData.marginDatasets.length > 0 ? 64 : 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis yAxisId="main" scale={logScale ? 'log' : 'linear'} domain={['auto', 'auto']}
                  allowDataOverflow={logScale} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)} width={72} />
                {chartData.marginDatasets.length > 0 && (
                  <YAxis yAxisId="margin" orientation="right" domain={['auto', 'auto']}
                    tick={{ fill: '#c75d1a', fontSize: 11 }}
                    tickFormatter={v => (Number(v) * 100).toFixed(0) + '%'} width={48} />
                )}
                <Tooltip
                  contentStyle={{ background: theme.isDark ? '#1e1e1e' : '#fff', border: `1px solid ${gridColor}`, fontSize: '0.78rem' }}
                  formatter={(value: any, name: string) => {
                    if (name.startsWith('m:')) return [(Number(value) * 100).toFixed(2) + '%', name.slice(2) + ' (Margin)']
                    return ['$' + Number(value).toFixed(2), name]
                  }}
                />
                <Legend content={renderLegend} />
                {chartData.mainData.datasets.map(ds => (
                  <Line key={ds.label} {...commonLineProps} yAxisId="main" dataKey={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                ))}
                {chartData.marginDatasets.map(ds => (
                  <Area key={ds.label} {...commonLineProps} yAxisId="margin" dataKey={ds.label}
                    name={ds.label} stroke={ds.color} fill={ds.color} fillOpacity={0.12}
                    strokeWidth={1} strokeDasharray="4 2" />
                ))}
                <Brush dataKey="x" height={26} stroke={gridColor}
                  fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>

          {/* Drawdown chart */}
          <div className="backtest-chart-title">Drawdown</div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.ddData.rows} syncId="rs-backtest"
                margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => (Number(v) * 100).toFixed(1) + '%'} width={60} />
                <Tooltip content={makeTooltip(v => (v * 100).toFixed(2) + '%')} />
                <Legend content={renderLegend} />
                {chartData.ddData.datasets.map(ds => (
                  <Line key={ds.label} {...commonLineProps} dataKey={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* RTR chart */}
          <div className="backtest-chart-title">Return Required to Recover</div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.rtrData.rows} syncId="rs-backtest"
                margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => Number(v).toFixed(2) + 'x'} width={60} />
                <Tooltip content={makeTooltip(v => v.toFixed(2) + 'x')} />
                <Legend content={renderLegend} />
                {chartData.rtrData.datasets.map(ds => (
                  <Line key={ds.label} {...commonLineProps} dataKey={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>

        </>
      )}
    </div>
  )
}
