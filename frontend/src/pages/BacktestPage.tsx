// ── BacktestPage.tsx — Full React port of backtest runner ─────────────────────

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush,
} from 'recharts'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { getChartTheme } from '@/lib/chartTheme'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, BacktestResults, emptyBlock, blockStateToAPIPortfolio,
  configToBlockState, PALETTE,
} from '@/types/backtest'
import {
  buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR,
} from '@/lib/chartData'
import { makeRechartsTooltip } from '@/lib/chartTooltip'

// ── Component ─────────────────────────────────────────────────────────────────

export default function BacktestPage() {
  const [blocks, setBlocks]         = useState<BlockState[]>([0, 1, 2].map(emptyBlock))
  const [fromDate, setFromDate]     = useState('')
  const [toDate, setToDate]         = useState('')
  const [importCode, setImportCode] = useState('')
  const [configError, setConfigError] = useState('')
  const [running, setRunning]       = useState(false)
  const [error, setError]           = useState('')
  const [results, setResults]       = useState<BacktestResults | null>(null)
  const [selected, setSelected]     = useState<Set<string>>(new Set())
  const [logScale, setLogScale]     = useState(false)

  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)

  // Restore settings on mount
  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (!req.portfolios) return
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate)   setToDate(req.toDate)
        setBlocks(prev => {
          const next = [...prev]
          req.portfolios.forEach((p: any, i: number) => {
            if (i < 3) next[i] = configToBlockState(p, p.label || '')
          })
          return next
        })
      })
      .catch(() => {})
  }, [])

  // ── Computed chart data ───────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!results) return null
    const labels = buildCommonLabels(results)
    return {
      labels,
      mainData: buildRechartsData(results, labels, selected, pts => pts.map(p => p.value)),
      ddData:   buildRechartsData(results, labels, selected, computeDrawdown),
      rtrData:  buildRechartsData(results, labels, selected, computeRTR),
    }
  }, [results, selected])

  // ── Run ───────────────────────────────────────────────────────────────────

  async function handleRun() {
    setError('')
    const portfolios = blocks
      .map((b, i) => blockStateToAPIPortfolio(b, i))
      .filter(p => p.tickers.length > 0)

    if (portfolios.length === 0) {
      setError('Add at least one ticker with a positive weight to any portfolio block.')
      return
    }
    if (portfolios.some(p => !p.includeNoMargin && p.marginStrategies.length === 0)) {
      setError('Each portfolio must have Unlevered enabled or at least one margin row.')
      return
    }

    setRunning(true)
    try {
      const res = await fetch('/api/backtest/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fromDate: fromDate || null, toDate: toDate || null, portfolios }),
      })
      const data: BacktestResults = await res.json()
      if (!res.ok || data.error) { setError(data.error || `Server error ${res.status}`); return }
      setSelected(new Set())
      setResults(data)
    } catch (e: any) {
      setError('Request failed: ' + e.message)
    } finally {
      setRunning(false)
    }
  }

  // ── Import / Export ───────────────────────────────────────────────────────

  async function handleExport() {
    const portfolios = blocks.map((b, i) => blockStateToAPIPortfolio(b, i))
    const code = await compressToCode({ fromDate: fromDate || null, toDate: toDate || null, portfolios })
    setImportCode(code)
    try { await navigator.clipboard.writeText(code) } catch (_) {}
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const req: any = await decompressFromCode(importCode.trim())
      if (req.fromDate) setFromDate(req.fromDate)
      if (req.toDate)   setToDate(req.toDate)
      if (req.portfolios) {
        setBlocks(prev => {
          const next = [...prev]
          req.portfolios.forEach((p: any, i: number) => {
            if (i < 3) next[i] = configToBlockState(p, p.label || '')
          })
          return next
        })
      }
      setConfigError('')
    } catch (_) {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }

  // ── Curve toggle ──────────────────────────────────────────────────────────

  const allKeys = results
    ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))
    : []
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0

  function toggleCurve(key: string, checked: boolean) {
    setSelected(prev => { const s = new Set(prev); checked ? s.add(key) : s.delete(key); return s })
  }

  function toggleAll(checked: boolean) {
    setSelected(checked ? new Set(allKeys) : new Set())
  }

  const updateBlock = useCallback((i: number) =>
    (s: BlockState) => setBlocks(prev => { const n = [...prev]; n[i] = s; return n }),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])

  // ── Chart helpers ─────────────────────────────────────────────────────────

  const theme = getChartTheme()
  const { isDark, gridColor, textColor } = theme
  const makeTooltip = (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) =>
    makeRechartsTooltip(theme, valueFmt, labelFmt)

  const commonLineProps = {
    type: 'monotone' as const,
    dot: false as const,
    activeDot: { r: 4 },
    strokeWidth: 1.5,
    connectNulls: false,
    isAnimationActive: false,
  }

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/backtest" /></div>
        <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
      </div>

      <div className="backtest-form-card">
        <div className="backtest-section backtest-grid-2">
          <DateFieldWithQuickSelect label="From Date" inputId="from-date" value={fromDate} onChange={setFromDate} />
          <DateFieldWithQuickSelect label="To Date"   inputId="to-date"   value={toDate}   onChange={setToDate} />

          <div className="backtest-config-controls">
            <label htmlFor="backtest-import-code">Config Code</label>
            <div className="backtest-config-group">
              <input
                type="text" id="backtest-import-code" placeholder="Paste code…" spellCheck={false}
                value={importCode} onChange={e => setImportCode(e.target.value)}
              />
              <button className="backtest-config-btn" onClick={handleImport}>Import</button>
              <button className="backtest-config-btn" onClick={handleExport}>Export</button>
              {configError && <div className="backtest-config-error">{configError}</div>}
            </div>
          </div>
        </div>

        <SavedPortfoliosBar ref={savedBarRef} />

        <div className="portfolio-blocks">
          {blocks.map((b, i) => (
            <PortfolioBlock
              key={i} idx={i} value={b}
              onChange={updateBlock(i)}
              onSavedRefresh={refreshSaved}
            />
          ))}
        </div>

        <button className="run-backtest-btn" type="button" onClick={handleRun} disabled={running}>
          {running ? 'Running…' : 'Run Backtest'}
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
                      type="checkbox"
                      checked={allChecked}
                      ref={el => { if (el) el.indeterminate = anyChecked && !allChecked }}
                      onChange={e => toggleAll(e.target.checked)}
                    />
                  </th>
                  <th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th>
                  <th title="Peak-to-recovery duration of the worst drawdown">Longest DD</th>
                  <th title="Annualised volatility of daily returns">Volatility</th>
                  <th>Sharpe</th>
                  <th title="Ulcer Index: RMS of drawdowns from peak">Ulcer</th>
                  <th title="Ulcer Performance Index (Martin Ratio)">UPI</th>
                  <th title="# upper-band rebalance triggers">Rebal↑</th>
                  <th title="# lower-band rebalance triggers">Rebal↓</th>
                </tr>
              </thead>
              <tbody>
                {results.portfolios.flatMap((portfolio, pi) =>
                  portfolio.curves.map((curve, ci) => {
                    const key = `${pi}-${ci}`
                    const s = curve.stats
                    const trig = (v: number | null | undefined) => v == null ? '–' : String(v)
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td>{portfolio.label} – {curve.label}</td>
                        <td>{money(s.endingValue)}</td>
                        <td>{pct(s.cagr)}</td>
                        <td>{pct(s.maxDrawdown)}</td>
                        <td>{dur(s.longestDrawdownDays)}</td>
                        <td>{pct(s.annualVolatility)}</td>
                        <td>{fmt2(s.sharpe)}</td>
                        <td>{pct(s.ulcerIndex)}</td>
                        <td>{fmt2(s.upi)}</td>
                        <td>{trig(s.marginUpperTriggers)}</td>
                        <td>{trig(s.marginLowerTriggers)}</td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Portfolio Value chart */}
          <div className="backtest-chart-title">Portfolio Value</div>
          <div className="backtest-chart-container">
            <button
              className={`chart-scale-toggle${logScale ? ' active' : ''}`}
              type="button"
              onClick={() => setLogScale(l => !l)}
            >
              Log
            </button>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.mainData.rows} syncId="backtest" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis
                  dataKey="x"
                  tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))}
                />
                <YAxis
                  scale={logScale ? 'log' : 'linear'}
                  domain={['auto', 'auto']}
                  allowDataOverflow={logScale}
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)}
                  width={72}
                />
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(2))} />
                <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />
                {chartData.mainData.datasets.map(ds => (
                  <Line key={ds.label} dataKey={ds.label} stroke={ds.color} {...commonLineProps} />
                ))}
                <Brush
                  dataKey="x"
                  height={26}
                  stroke={gridColor}
                  fill={isDark ? '#1a1a1a' : '#f8f8f8'}
                  travellerWidth={6}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* Drawdown chart */}
          <div className="backtest-chart-title">Drawdown</div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.ddData.rows} syncId="backtest" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis
                  dataKey="x"
                  tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))}
                />
                <YAxis
                  domain={['auto', 'auto']}
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => (Number(v) * 100).toFixed(1) + '%'}
                  width={60}
                />
                <Tooltip content={makeTooltip(v => (v * 100).toFixed(2) + '%')} />
                <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />
                {chartData.ddData.datasets.map(ds => (
                  <Line key={ds.label} dataKey={ds.label} stroke={ds.color} {...commonLineProps} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* RTR chart */}
          <div className="backtest-chart-title">Return Required to Recover</div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.rtrData.rows} syncId="backtest" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis
                  dataKey="x"
                  tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))}
                />
                <YAxis
                  domain={['auto', 'auto']}
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => Number(v).toFixed(2) + 'x'}
                  width={60}
                />
                <Tooltip content={makeTooltip(v => v.toFixed(2) + 'x')} />
                <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />
                {chartData.rtrData.datasets.map(ds => (
                  <Line key={ds.label} dataKey={ds.label} stroke={ds.color} {...commonLineProps} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  )
}
