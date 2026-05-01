// ── RebalanceStrategyPage.tsx ─────────────────────────────────────────────────

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush,
} from 'recharts'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import RebalanceStrategyBlock, { type RebalanceStrategyBlockRef } from '@/components/rebalance/RebalanceStrategyBlock'
import SavedStrategiesBar, { type SavedStrategiesBarRef } from '@/components/rebalance/SavedStrategiesBar'
import { useChartTheme } from '@/lib/chartTheme'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, BacktestResults, emptyBlock, blockStateToAPIPortfolio,
  configToBlockState, PALETTE, CASHFLOW_FREQUENCY_OPTIONS,
} from '@/types/backtest'
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

function labelKey(label: string) {
  return label.trim().toLocaleLowerCase()
}

function makeUniqueStrategyLabels(strategies: RebalStrategyState[], portfolioLabel: string) {
  const taken = new Set<string>()
  if (portfolioLabel.trim()) taken.add(labelKey(portfolioLabel))

  return strategies.map((strategy, i) => {
    const base = strategy.label.trim() || `Strategy ${i + 1}`
    let label = base
    let suffix = 2
    while (taken.has(labelKey(label))) {
      label = `${base} (${suffix})`
      suffix += 1
    }
    taken.add(labelKey(label))
    return label === strategy.label ? strategy : { ...strategy, label }
  })
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function RebalanceStrategyPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [strategies, setStrategies] = useState<RebalStrategyState[]>([emptyStrategy(0), emptyStrategy(1)])
  const [fromDate, setFromDate]   = useState('')
  const [toDate, setToDate]       = useState('')
  const [startingBalance, setStartingBalance]     = useState('10000')
  const [cashflowAmount, setCashflowAmount]       = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState('NONE')
  const [importCode, setImportCode]               = useState('')
  const [configError, setConfigError] = useState('')
  const [rebalanceSliderMax, setRebalanceSliderMax] = useState(150)
  const [running, setRunning]     = useState(false)
  const [error, setError]         = useState('')
  const [results, setResults]     = useState<BacktestResults | null>(null)
  const [selected, setSelected]   = useState<Set<string>>(new Set())
  const [logScale, setLogScale]   = useState(false)

  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const savedStrategiesBarRef = useRef<SavedStrategiesBarRef>(null)
  const strategyBlockRefs = useRef<(RebalanceStrategyBlockRef | null)[]>([])
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

  // Restore the shared backtest portfolio cache, but only load portfolio slot 0.
  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (!req.portfolios) return
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate)   setToDate(req.toDate)
        if (req.startingBalance != null) setStartingBalance(String(req.startingBalance))
        if (req.cashflow?.amount != null) setCashflowAmount(String(req.cashflow.amount))
        if (req.cashflow?.frequency) setCashflowFrequency(req.cashflow.frequency)
        if (req.portfolios[0]) setPortfolio(configToBlockState(req.portfolios[0], req.portfolios[0].label || ''))
      })
      .catch(() => {})

    fetch('/api/admin/config-values')
      .then(r => r.json())
      .then((data: any) => {
        const max = parseInt(data.rebalanceSliderMax ?? '', 10)
        if (Number.isFinite(max) && max > 0) setRebalanceSliderMax(max)
      })
      .catch(() => {})
  }, [])

  // ── Run ───────────────────────────────────────────────────────────────────

  async function handleRun() {
    setError('')
    const portfolioApi = blockStateToAPIPortfolio(portfolio, 0)
    if (portfolioApi.tickers.length === 0) {
      setError('Add at least one ticker with a positive weight to the portfolio.'); return
    }
    const currentStrategies = strategies.map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    const runStrategies = makeUniqueStrategyLabels(currentStrategies, portfolioApi.label)
    if (runStrategies.some((s, i) => s.label !== strategies[i]?.label)) setStrategies(runStrategies)
    setRunning(true)
    try {
      const res = await fetch('/api/rebalance-strategy/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromDate: fromDate || null,
          toDate: toDate || null,
          startingBalance: parseFloat(startingBalance) || 10000,
          portfolio: portfolioApi,
          cashflow: cashflowAmount && cashflowFrequency !== 'NONE'
            ? { amount: parseFloat(cashflowAmount), frequency: cashflowFrequency }
            : null,
          strategies: runStrategies.map(s => strategyStateToAPI(s, portfolio.rebalance)),
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

  async function handleExport() {
    const currentStrategies = strategies.map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    const code = await compressToCode({
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: parseFloat(startingBalance) || 10000,
      portfolio: blockStateToAPIPortfolio(portfolio, 0),
      portfolioState: portfolio,
      cashflow: cashflowAmount && cashflowFrequency !== 'NONE'
        ? { amount: parseFloat(cashflowAmount), frequency: cashflowFrequency }
        : null,
      strategies: currentStrategies,
    })
    setImportCode(code)
    try { await navigator.clipboard.writeText(code) } catch (_) {}
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const req: any = await decompressFromCode(importCode.trim())
      if (req.fromDate) setFromDate(req.fromDate)
      if (req.toDate) setToDate(req.toDate)
      if (req.startingBalance != null) setStartingBalance(String(req.startingBalance))
      if (req.cashflow?.amount != null) setCashflowAmount(String(req.cashflow.amount))
      if (req.cashflow?.frequency) setCashflowFrequency(req.cashflow.frequency)
      if (req.portfolioState) setPortfolio(req.portfolioState)
      else if (req.portfolio) setPortfolio(configToBlockState(req.portfolio, req.portfolio.label || ''))
      if (Array.isArray(req.strategies)) setStrategies(req.strategies.slice(0, 2))
      setConfigError('')
    } catch (_) {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
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
    return { labels, mainData, ddData, rtrData, marginData }
  }, [results, selected])

  const allKeys    = results ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`)) : []
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0
  const showLine   = (key: string) => selected.size === 0 || selected.has(key)

  function toggleCurve(key: string, checked: boolean) {
    setSelected(prev => { const s = new Set(prev); checked ? s.add(key) : s.delete(key); return s })
  }
  function toggleAll(checked: boolean) { setSelected(checked ? new Set(allKeys) : new Set()) }

  const strategyHandlers = useMemo(
    () => [0, 1].map(i => (s: RebalStrategyState) =>
      setStrategies(prev => { const n = [...prev]; n[i] = s; return n })
    ),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])
  const refreshSavedStrategies = useCallback(() => savedStrategiesBarRef.current?.refresh(), [])

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
        <div className="backtest-section backtest-config-row">
          <DateFieldWithQuickSelect label="From Date" inputId="rs-from-date" value={fromDate} onChange={setFromDate} />
          <DateFieldWithQuickSelect label="To Date"   inputId="rs-to-date"   value={toDate}   onChange={setToDate} />

          <div className="backtest-config-controls">
            <label htmlFor="rs-import-code">Config Code</label>
            <div className="backtest-config-group">
              <input
                type="text" id="rs-import-code" placeholder="Paste code..." spellCheck={false}
                value={importCode} onChange={e => setImportCode(e.target.value)}
              />
              <button className="backtest-config-btn" onClick={handleImport}>Import</button>
              <button className="backtest-config-btn" onClick={handleExport}>Export</button>
              {configError && <div className="backtest-config-error">{configError}</div>}
            </div>
          </div>
        </div>

        <div className="backtest-section backtest-cashflow-row">
          <div>
            <label htmlFor="rs-starting-balance">Starting Balance</label>
            <input
              type="number" id="rs-starting-balance" min="0" step="100"
              value={startingBalance} onChange={e => setStartingBalance(e.target.value)}
            />
          </div>
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

        <SavedPortfoliosBar ref={savedBarRef} />
        <SavedStrategiesBar ref={savedStrategiesBarRef} />

        {/* Portfolio + Strategy blocks side by side */}
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', marginTop: '0.75rem' }}>
          <PortfolioBlock idx={0} value={portfolio} onChange={setPortfolio} onSavedRefresh={refreshSaved} />
          {strategies.map((s, i) => (
            <RebalanceStrategyBlock
              key={i}
              ref={el => { strategyBlockRefs.current[i] = el }}
              idx={i}
              value={s}
              onChange={strategyHandlers[i]}
              sliderMax={rebalanceSliderMax}
              onSavedRefresh={refreshSavedStrategies}
            />
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
              <LineChart data={chartData.mainData.rows} syncId="rs-backtest"
                margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis scale={logScale ? 'log' : 'linear'} domain={['auto', 'auto']}
                  allowDataOverflow={logScale} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)} width={72} />
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(2))} />
                <Legend content={renderLegend} />
                {chartData.mainData.datasets.map(ds => (
                  <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                ))}
                <Brush dataKey="x" height={26} stroke={gridColor}
                  fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
              </LineChart>
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
                  <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
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
                  <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* Margin Utilization chart */}
          {chartData.marginData.datasets.length > 0 && (
            <>
              <div className="backtest-chart-title">Margin Utilization</div>
              <div className="backtest-chart-container">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData.marginData.rows} syncId="rs-backtest"
                    margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                    <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                      interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                    <YAxis domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
                      tickFormatter={v => (Number(v) * 100).toFixed(0) + '%'} width={60} />
                    <Tooltip content={makeTooltip(v => (v * 100).toFixed(2) + '%')} />
                    <Legend content={renderLegend} />
                    {chartData.marginData.datasets.map(ds => (
                      <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                        stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                    ))}
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </>
          )}

        </>
      )}
    </div>
  )
}
