// ── MonteCarloPage.tsx — Full React port of Monte Carlo simulation ────────────

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
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, MonteCarloResults, McCurve, emptyBlock,
  blockStateToAPIPortfolio, configToBlockState,
  PALETTE, PERCENTILE_COLORS, PERCENTILE_LIST,
} from '@/types/backtest'

// ── Effective curves helper ───────────────────────────────────────────────────

function getEffectiveCurves(data: MonteCarloResults, selected: Set<string>) {
  const result: { portfolio: { label: string; curves: McCurve[] }; pi: number; curve: McCurve; ci: number }[] = []
  data.portfolios.forEach((portfolio, pi) => {
    portfolio.curves.forEach((curve, ci) => {
      if (selected.size === 0 || selected.has(`${pi}-${ci}`)) {
        result.push({ portfolio, pi, curve, ci })
      }
    })
  })
  return result
}

const MC_COLS = [
  { metric: 'END_VALUE', label: 'End Value' }, { metric: 'CAGR', label: 'CAGR' },
  { metric: 'MAX_DD', label: 'Max DD' }, { metric: 'LONGEST_DD', label: 'Longest DD' },
  { metric: 'ANN_VOL', label: 'Volatility' }, { metric: 'SHARPE', label: 'Sharpe' },
  { metric: 'ULCER_INDEX', label: 'Ulcer' }, { metric: 'UPI', label: 'UPI' },
]

// ── Component ─────────────────────────────────────────────────────────────────

export default function MonteCarloPage() {
  const [blocks, setBlocks]           = useState<BlockState[]>([0, 1, 2].map(emptyBlock))
  const [fromDate, setFromDate]       = useState('')
  const [toDate, setToDate]           = useState('')
  const [minChunk, setMinChunk]       = useState('3')
  const [maxChunk, setMaxChunk]       = useState('8')
  const [simYears, setSimYears]       = useState('20')
  const [numSims, setNumSims]         = useState('500')
  const [importCode, setImportCode]   = useState('')
  const [configError, setConfigError] = useState('')
  const [running, setRunning]         = useState(false)
  const [progress, setProgress]       = useState('')
  const [error, setError]             = useState('')
  const [results, setResults]         = useState<MonteCarloResults | null>(null)
  const [lastSeed, setLastSeed]       = useState<number | null>(null)
  const [percentile, setPercentile]   = useState(50)
  const [selected, setSelected]       = useState<Set<string>>(new Set())
  const [logScale, setLogScale]       = useState(false)

  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const pollRef     = useRef<number | null>(null)

  // Restore settings on mount
  useEffect(() => {
    fetch('/api/montecarlo/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (!req || !Object.keys(req).length) return
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate)   setToDate(req.toDate)
        if (req.minChunkYears  != null) setMinChunk(String(req.minChunkYears))
        if (req.maxChunkYears  != null) setMaxChunk(String(req.maxChunkYears))
        if (req.simulatedYears != null) setSimYears(String(req.simulatedYears))
        if (req.numSimulations != null) setNumSims(String(req.numSimulations))
        if (req.portfolios) {
          setBlocks(prev => {
            const next = [...prev]
            req.portfolios.forEach((p: any, i: number) => {
              if (i < 3) next[i] = configToBlockState(p, p.label || '')
            })
            return next
          })
        }
      })
      .catch(() => {})
  }, [])

  // ── Computed chart data ───────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!results) return null
    const targetDays = results.simulatedYears * 252
    const effectiveCurves = getEffectiveCurves(results, selected)
    const singleCurve = effectiveCurves.length === 1

    // Build rows: one object per day index
    const rows: Record<string, any>[] = Array.from({ length: targetDays + 1 }, (_, i) => ({ x: i }))

    interface McDataset { label: string; color: string; strokeDasharray?: string; strokeWidth: number }
    const datasets: McDataset[] = []

    if (singleCurve) {
      const { curve } = effectiveCurves[0]
      PERCENTILE_LIST.forEach((p, idx) => {
        const pp = curve.percentilePaths.find(x => x.percentile === p)
        if (!pp) return
        const key = `P${p}`
        pp.points.forEach((val, i) => { rows[i][key] = val })
        datasets.push({
          label: key,
          color: PERCENTILE_COLORS[idx],
          strokeDasharray: p !== 50 && idx < 3 ? '4 2' : undefined,
          strokeWidth: p === 50 ? 2.5 : 1.5,
        })
      })
    } else {
      effectiveCurves.forEach(({ portfolio, pi, curve, ci }, arrIdx) => {
        const palette = PALETTE[pi % PALETTE.length]
        const pp = curve.percentilePaths.find(x => x.percentile === percentile)
        if (!pp) return
        const key = `${portfolio.label} \u2013 ${curve.label}`
        pp.points.forEach((val, i) => { rows[i][key] = val })
        datasets.push({
          label: key,
          color: palette[ci % palette.length],
          strokeWidth: 1.5,
        })
      })
    }

    const yearTicks = Array.from({ length: results.simulatedYears + 1 }, (_, i) => i * 252)
    return { rows, datasets, yearTicks, targetDays, effectiveCurves }
  }, [results, percentile, selected])

  // ── Run ───────────────────────────────────────────────────────────────────

  async function doRun(seed: number | null = null) {
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

    const ns = parseInt(numSims, 10) || 500
    setRunning(true)
    setProgress(`0/${ns}`)

    pollRef.current = window.setInterval(async () => {
      try {
        const r = await fetch('/api/montecarlo/progress')
        const p = await r.json()
        setProgress(`${p.completed}/${p.total}`)
      } catch (_) {}
    }, 300)

    const reqBody: any = {
      fromDate: fromDate || null,
      toDate: toDate || null,
      minChunkYears:  parseFloat(minChunk)  || 3,
      maxChunkYears:  parseFloat(maxChunk)  || 8,
      simulatedYears: parseInt(simYears, 10) || 20,
      numSimulations: ns,
      portfolios,
    }
    if (seed != null) reqBody.seed = seed

    try {
      const res = await fetch('/api/montecarlo/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(reqBody),
      })
      const data: MonteCarloResults = await res.json()
      if (!res.ok || data.error) { setError(data.error || `Server error ${res.status}`); return }
      if (data.seed != null) setLastSeed(data.seed)
      setSelected(new Set())
      setResults(data)
    } catch (e: any) {
      setError('Request failed: ' + e.message)
    } finally {
      clearInterval(pollRef.current!)
      pollRef.current = null
      setRunning(false)
      setProgress('')
    }
  }

  // ── Import / Export ───────────────────────────────────────────────────────

  async function handleExport() {
    const portfolios = blocks.map((b, i) => blockStateToAPIPortfolio(b, i))
    const code = await compressToCode({
      fromDate: fromDate || null, toDate: toDate || null,
      minChunkYears: parseFloat(minChunk) || 3, maxChunkYears: parseFloat(maxChunk) || 8,
      simulatedYears: parseInt(simYears, 10) || 20, numSimulations: parseInt(numSims, 10) || 500,
      portfolios,
    })
    setImportCode(code)
    try { await navigator.clipboard.writeText(code) } catch (_) {}
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const req: any = await decompressFromCode(importCode.trim())
      if (req.fromDate) setFromDate(req.fromDate)
      if (req.toDate)   setToDate(req.toDate)
      if (req.minChunkYears  != null) setMinChunk(String(req.minChunkYears))
      if (req.maxChunkYears  != null) setMaxChunk(String(req.maxChunkYears))
      if (req.simulatedYears != null) setSimYears(String(req.simulatedYears))
      if (req.numSimulations != null) setNumSims(String(req.numSimulations))
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
  function toggleAll(checked: boolean) { setSelected(checked ? new Set(allKeys) : new Set()) }

  const updateBlock = useCallback((i: number) =>
    (s: BlockState) => setBlocks(prev => { const n = [...prev]; n[i] = s; return n }),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])

  // ── Stats helpers ─────────────────────────────────────────────────────────

  function cellValue(curve: McCurve, pctIdx: number, metric: string, pp: any): string {
    switch (metric) {
      case 'END_VALUE':   return money(pp.endValue)
      case 'CAGR':        return pct(pp.cagr)
      case 'MAX_DD':      return pct(curve.maxDdPercentiles[pctIdx])
      case 'LONGEST_DD':  return dur(curve.longestDrawdownPercentiles[pctIdx])
      case 'ANN_VOL':     return pct(curve.volatilityPercentiles[pctIdx])
      case 'SHARPE':      return fmt2(curve.sharpePercentiles[pctIdx])
      case 'ULCER_INDEX': return pct(curve.ulcerPercentiles[pctIdx])
      case 'UPI':         return fmt2(curve.upiPercentiles[pctIdx])
      default:            return '–'
    }
  }

  // ── Theme ─────────────────────────────────────────────────────────────────

  const theme = getChartTheme()
  const { isDark, gridColor, textColor } = theme
  const makeTooltip = (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) =>
    makeRechartsTooltip(theme, valueFmt, labelFmt)

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/montecarlo" /></div>
        <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
      </div>

      <div className="backtest-form-card">
        <div className="backtest-section backtest-grid-2">
          <DateFieldWithQuickSelect label="From Date (pool)" inputId="mc-from-date" value={fromDate} onChange={setFromDate} />
          <DateFieldWithQuickSelect label="To Date (pool)"   inputId="mc-to-date"   value={toDate}   onChange={setToDate} />

          <div className="backtest-config-controls">
            <label>Config Code</label>
            <div className="backtest-config-group">
              <input
                type="text" id="mc-import-code" placeholder="Paste code…" spellCheck={false}
                value={importCode} onChange={e => setImportCode(e.target.value)}
              />
              <button className="backtest-config-btn" onClick={handleImport}>Import</button>
              <button className="backtest-config-btn" onClick={handleExport}>Export</button>
              {configError && <div className="backtest-config-error">{configError}</div>}
            </div>
          </div>
        </div>

        <div className="backtest-section mc-params-grid">
          {[
            { label: 'Min Chunk Years', val: minChunk, set: setMinChunk, id: 'mc-min-chunk' },
            { label: 'Max Chunk Years', val: maxChunk, set: setMaxChunk, id: 'mc-max-chunk' },
            { label: 'Simulated Years', val: simYears, set: setSimYears, id: 'mc-sim-years' },
            { label: 'Simulations',     val: numSims,  set: setNumSims,  id: 'mc-num-sims'  },
          ].map(({ label, val, set, id }) => (
            <div key={id} className="backtest-date-field">
              <label htmlFor={id}>{label}</label>
              <input type="text" id={id} inputMode="decimal" value={val} onChange={e => set(e.target.value)} />
            </div>
          ))}
        </div>

        <SavedPortfoliosBar ref={savedBarRef} />

        <div className="portfolio-blocks">
          {blocks.map((b, i) => (
            <PortfolioBlock key={i} idx={i} value={b} onChange={updateBlock(i)} onSavedRefresh={refreshSaved} />
          ))}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <button className="run-backtest-btn" type="button" onClick={() => doRun(null)} disabled={running}>
            {running ? 'Running…' : 'Run Simulation'}
          </button>
          {lastSeed != null && (
            <button
              className="run-backtest-btn" type="button"
              style={{ opacity: 0.75 }} disabled={running}
              onClick={() => doRun(lastSeed)}
            >
              Rerun (same seed)
            </button>
          )}
          {progress && (
            <span style={{ marginLeft: '0.25rem', fontSize: '0.85em', opacity: 0.7 }}>{progress}</span>
          )}
        </div>
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {results && chartData && (
        <>
          <div style={{ opacity: 0.7, margin: '0.5rem 0 1rem', lineHeight: 1.5 }}>
            <p style={{ fontSize: 'var(--font-size-md)', margin: 0 }}>
              ⚠︎ Each metric is independently ranked across all simulations.
            </p>
            <p style={{ fontSize: '0.82em', margin: 0 }}>
              At P50, CAGR shows the median CAGR outcome, Max DD shows the median worst drawdown (ranked by drawdown), and so on.
            </p>
            <p style={{ fontSize: '0.82em', margin: 0 }}>
              The chart always shows the path at the selected percentile when simulations are ranked by CAGR.
            </p>
          </div>

          {/* Percentile tabs */}
          <div className="mc-percentile-tabs">
            {PERCENTILE_LIST.map(p => (
              <button
                key={p} type="button"
                className={`mc-pct-tab${p === percentile ? ' active' : ''}`}
                onClick={() => setPercentile(p)}
              >
                {p}th
              </button>
            ))}
          </div>

          {/* Stats table */}
          <div className="stats-container">
            <div className="mc-stats-header">
              Results at <strong>{percentile}th percentile</strong> ({results.numSimulations} simulations, {results.simulatedYears}yr)
            </div>
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
                  <th>Curve</th>
                  {MC_COLS.map(c => <th key={c.metric}>{c.label}</th>)}
                </tr>
              </thead>
              <tbody>
                {results.portfolios.flatMap((portfolio, pi) =>
                  portfolio.curves.map((curve, ci) => {
                    const key = `${pi}-${ci}`
                    const pctIdx = PERCENTILE_LIST.indexOf(percentile)
                    const pp = curve.percentilePaths.find(p => p.percentile === percentile)
                    if (!pp) return null
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td>{portfolio.label} – {curve.label}</td>
                        {MC_COLS.map(c => <td key={c.metric}>{cellValue(curve, pctIdx, c.metric, pp)}</td>)}
                      </tr>
                    )
                  })
                )}
                {/* All-percentiles section for single curve */}
                {chartData.effectiveCurves.length === 1 && (() => {
                  const { portfolio, curve } = chartData.effectiveCurves[0]
                  const totalCols = 2 + MC_COLS.length
                  return (
                    <>
                      <tr className="mc-pct-separator">
                        <td colSpan={totalCols}>All percentiles – <strong>{portfolio.label} – {curve.label}</strong></td>
                      </tr>
                      {PERCENTILE_LIST.map((p, idx) => {
                        const pp = curve.percentilePaths.find(x => x.percentile === p)
                        if (!pp) return null
                        return (
                          <tr key={p} className={p === percentile ? 'mc-active-pct' : ''}>
                            <td />
                            <td style={{ color: PERCENTILE_COLORS[idx], fontWeight: p === 50 ? 'bold' : 'normal' }}>P{p}</td>
                            {MC_COLS.map(c => <td key={c.metric}>{cellValue(curve, idx, c.metric, pp)}</td>)}
                          </tr>
                        )
                      })}
                    </>
                  )
                })()}
              </tbody>
            </table>
          </div>

          {/* MC Chart */}
          <div className="backtest-chart-container">
            <button
              className={`chart-scale-toggle${logScale ? ' active' : ''}`}
              type="button"
              onClick={() => setLogScale(l => !l)}
            >
              Log
            </button>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.rows} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis
                  dataKey="x"
                  type="number"
                  domain={[0, chartData.targetDays]}
                  ticks={chartData.yearTicks}
                  tickFormatter={v => `Y${v / 252}`}
                  tick={{ fill: textColor, fontSize: 11 }}
                />
                <YAxis
                  scale={logScale ? 'log' : 'linear'}
                  domain={['auto', 'auto']}
                  allowDataOverflow={logScale}
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)}
                  width={72}
                />
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(0), v => `Year ${(Number(v) / 252).toFixed(1)}`)} />
                <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />
                {chartData.datasets.map(ds => (
                  <Line
                    key={ds.label}
                    dataKey={ds.label}
                    stroke={ds.color}
                    strokeWidth={ds.strokeWidth}
                    strokeDasharray={ds.strokeDasharray}
                    dot={false}
                    activeDot={{ r: 4 }}
                    connectNulls={false}
                    isAnimationActive={false}
                  />
                ))}
                <Brush
                  dataKey="x"
                  height={26}
                  stroke={gridColor}
                  fill={isDark ? '#1a1a1a' : '#f8f8f8'}
                  travellerWidth={6}
                  tickFormatter={v => `Y${Math.round(v / 252)}`}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  )
}
