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
  configToBlockState,
} from '@/types/backtest'
import {
  buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR,
} from '@/lib/chartData'
import { makeRechartsTooltip } from '@/lib/chartTooltip'

// ── Stats helper ──────────────────────────────────────────────────────────────

interface SeriesStats {
  endingValue: number
  cagr: number
  maxDrawdown: number
  longestDrawdownDays: number
  annualVolatility: number
  sharpe: number
  ulcerIndex: number
  upi: number
}

function computeSeriesStats(dates: string[], values: number[]): SeriesStats | null {
  if (values.length < 2) return null
  const n = values.length
  const start = values[0], end = values[n - 1]
  if (start <= 0) return null

  const years = (new Date(dates[n - 1]).getTime() - new Date(dates[0]).getTime()) / (365.25 * 86400000)
  if (years <= 0) return null
  const cagr = Math.pow(end / start, 1 / years) - 1

  const logReturns: number[] = []
  for (let i = 1; i < n; i++) {
    if (values[i - 1] > 0 && values[i] > 0) logReturns.push(Math.log(values[i] / values[i - 1]))
  }
  const mean = logReturns.reduce((a, b) => a + b, 0) / logReturns.length
  const variance = logReturns.reduce((a, b) => a + (b - mean) ** 2, 0) / Math.max(logReturns.length - 1, 1)
  const annualVolatility = Math.sqrt(variance * 252)
  const sharpe = annualVolatility > 0 ? cagr / annualVolatility : 0

  let peak = start, maxDrawdown = 0, longestDrawdownDays = 0
  let ddStart: Date | null = null
  for (let i = 0; i < n; i++) {
    if (values[i] >= peak) {
      if (ddStart) {
        const days = (new Date(dates[i]).getTime() - ddStart.getTime()) / 86400000
        if (days > longestDrawdownDays) longestDrawdownDays = days
      }
      peak = values[i]
      ddStart = null
    } else {
      if (!ddStart) ddStart = new Date(i > 0 ? dates[i - 1] : dates[0])
      const dd = values[i] / peak - 1
      if (dd < maxDrawdown) maxDrawdown = dd
    }
  }
  if (ddStart) {
    const days = (new Date(dates[n - 1]).getTime() - ddStart.getTime()) / 86400000
    if (days > longestDrawdownDays) longestDrawdownDays = days
  }

  peak = start
  let sumSq = 0
  for (const v of values) {
    if (v > peak) peak = v
    const dd = (v / peak - 1) * 100
    sumSq += dd * dd
  }
  const ulcerIndex = Math.sqrt(sumSq / n) / 100
  const upi = ulcerIndex > 0 ? cagr / ulcerIndex : 0

  return { endingValue: end, cagr, maxDrawdown, longestDrawdownDays, annualVolatility, sharpe, ulcerIndex, upi }
}

// ── Real portfolio data type ───────────────────────────────────────────────────

interface RealPortfolioData {
  dates: string[]
  twrSeries: number[]
  mwrSeries: number[] | null
  positionSeries: number[] | null
  navSeries: number[]
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function BacktestPage() {
  const [blocks, setBlocks]           = useState<BlockState[]>([0, 1, 2].map(emptyBlock))
  const [fromDate, setFromDate]       = useState('')
  const [toDate, setToDate]           = useState('')
  const [importCode, setImportCode]   = useState('')
  const [configError, setConfigError] = useState('')
  const [running, setRunning]         = useState(false)
  const [error, setError]             = useState('')
  const [results, setResults]         = useState<BacktestResults | null>(null)
  const [selected, setSelected]       = useState<Set<string>>(new Set())
  const [logScale, setLogScale]       = useState(false)
  const [scaleToNav, setScaleToNav]   = useState(true)

  // Real portfolio overlay
  const [realPortfolios, setRealPortfolios] = useState<{ slug: string; name: string }[]>([])
  const [realSlug, setRealSlug]             = useState('')
  const [realData, setRealData]             = useState<RealPortfolioData | null>(null)

  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)

  // Load portfolios for overlay selector
  useEffect(() => {
    fetch('/api/portfolio/data')
      .then(r => r.json())
      .then((d: any) => {
        setRealPortfolios((d.allPortfolios ?? []).map((p: any) => ({
          slug: p.slug,
          name: p.name || p.slug,
        })))
      })
      .catch(() => {})
  }, [])

  // Fetch real portfolio data when slug or date range changes
  useEffect(() => {
    if (!realSlug) { setRealData(null); return }
    const params = [fromDate && `from=${fromDate}`, toDate && `to=${toDate}`].filter(Boolean).join('&')
    fetch(`/api/performance/chart/${realSlug}${params ? '?' + params : ''}`)
      .then(r => r.json())
      .then((d: any) => setRealData({
        dates:          d.dates          ?? [],
        twrSeries:      d.twrSeries      ?? [],
        mwrSeries:      d.mwrSeries      ?? null,
        positionSeries: d.positionSeries ?? null,
        navSeries:      d.navSeries      ?? [],
      }))
      .catch(() => setRealData(null))
  }, [realSlug, fromDate, toDate])

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

  // ── Curve keys (for toggle / master checkbox) ────────────────────────────

  const backtestKeys = results
    ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))
    : []
  const realKeys: string[] = realSlug && realData ? [
    ...(realData.navSeries.length      ? ['real-nav'] : []),
    ...(realData.twrSeries.length      ? ['real-twr'] : []),
    ...(realData.mwrSeries    != null  ? ['real-mwr'] : []),
    ...(realData.positionSeries != null ? ['real-pos'] : []),
  ] : []
  const allKeys    = [...backtestKeys, ...realKeys]
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0

  // Empty selected = show all
  const showLine = (key: string) => selected.size === 0 || selected.has(key)

  // ── Computed chart data ───────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!results) return null
    const labels        = buildCommonLabels(results)
    const backtestStart = results.portfolios[0]?.curves[0]?.points[0]?.value ?? 1

    // Build date→index lookup for real portfolio data
    const realDateIndex: Map<string, number> = realData?.dates.length
      ? new Map(realData.dates.map((d, i) => [d, i]))
      : new Map()

    // NAV scale is only valid when the real portfolio existed at the FIRST backtest date.
    // If the portfolio started later, navStart = 0 → scaling disabled.
    const firstLabelRealIdx = realData?.dates.length ? realDateIndex.get(labels[0]) : undefined
    const navStart          = firstLabelRealIdx != null ? (realData!.navSeries[firstLabelRealIdx] ?? 0) : 0
    const shouldScaleToNav  = scaleToNav && navStart > 0 && !!realSlug

    // Backtest curves: when shouldScaleToNav, scale each so its first-date value = navStart
    const mainData = buildRechartsData(results, labels, selected, pts => {
      if (shouldScaleToNav) {
        const startVal = pts[0]?.value ?? 1
        return pts.map(p => p.value * (navStart / startVal))
      }
      return pts.map(p => p.value)
    })

    // Per-curve scale factors for stats table End Value
    const curveScaleFactors = new Map<string, number>()
    results.portfolios.forEach((portfolio, pi) => {
      portfolio.curves.forEach((curve, ci) => {
        const factor = shouldScaleToNav ? navStart / (curve.points[0]?.value ?? 1) : 1
        curveScaleFactors.set(`${pi}-${ci}`, factor)
      })
    })

    // Find first overlap date between backtest and real portfolio
    let firstOverlapLabelIdx = -1
    let firstOverlapRealIdx  = -1
    if (realData?.dates.length) {
      for (let i = 0; i < labels.length; i++) {
        const ri = realDateIndex.get(labels[i])
        if (ri != null) { firstOverlapLabelIdx = i; firstOverlapRealIdx = ri; break }
      }
    }

    // refStart: Y value that TWR/MWR/Position lines should start at their first visible point.
    // When scaled: navStart (backtest curves also start at navStart at t=0).
    // When not scaled: value of first visible backtest curve at first overlap date.
    let refStart = backtestStart
    if (shouldScaleToNav) {
      refStart = navStart
    } else if (firstOverlapLabelIdx >= 0 && mainData.datasets.length > 0) {
      refStart = mainData.rows[firstOverlapLabelIdx][mainData.datasets[0].label] ?? backtestStart
    }

    // Normalisation bases: return series value at the first overlap date.
    // Dividing (1 + series[i]) by (1 + base) makes the line start at 1.0 at that date,
    // then multiply by refStart to put it on the correct Y axis value.
    const twrBase = firstOverlapRealIdx >= 0 ? (realData!.twrSeries[firstOverlapRealIdx]        ?? 0) : 0
    const mwrBase = firstOverlapRealIdx >= 0 ? (realData!.mwrSeries?.[firstOverlapRealIdx]      ?? 0) : 0
    const posBase = firstOverlapRealIdx >= 0 ? (realData!.positionSeries?.[firstOverlapRealIdx] ?? 0) : 0

    // Inject real portfolio columns into chart rows
    if (realData?.dates.length) {
      for (const row of mainData.rows) {
        const ri = realDateIndex.get(row.x as string)
        if (ri == null) continue

        const nav = realData.navSeries[ri]
        if (nav != null) row['Real – NAV'] = +nav.toFixed(4)

        const twr = realData.twrSeries[ri]
        if (twr != null) row['Real – TWR'] = +(refStart * (1 + twr) / (1 + twrBase)).toFixed(4)

        const mwr = realData.mwrSeries?.[ri]
        if (mwr != null) row['Real – MWR'] = +(refStart * (1 + mwr) / (1 + mwrBase)).toFixed(4)

        const pos = realData.positionSeries?.[ri]
        if (pos != null) row['Real – Position'] = +(refStart * (1 + pos) / (1 + posBase)).toFixed(4)
      }
    }

    // Stats computed from the overlap window (same period as what the chart shows)
    const od = firstOverlapRealIdx >= 0 ? realData!.dates.slice(firstOverlapRealIdx)    : []
    const nv = firstOverlapRealIdx >= 0 ? realData!.navSeries.slice(firstOverlapRealIdx) : []
    const tv = firstOverlapRealIdx >= 0
      ? realData!.twrSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + twrBase)) : []
    const mv = realData?.mwrSeries && firstOverlapRealIdx >= 0
      ? realData.mwrSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + mwrBase)) : null
    const pv = realData?.positionSeries && firstOverlapRealIdx >= 0
      ? realData.positionSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + posBase)) : null

    const realStats = realData?.dates.length ? {
      nav: computeSeriesStats(od, nv),
      twr: computeSeriesStats(od, tv),
      mwr: mv ? computeSeriesStats(od, mv) : null,
      pos: pv ? computeSeriesStats(od, pv) : null,
    } : null

    return {
      labels,
      mainData,
      ddData:  buildRechartsData(results, labels, selected, computeDrawdown),
      rtrData: buildRechartsData(results, labels, selected, computeRTR),
      realStats,
      curveScaleFactors,
      navStart,
      shouldScaleToNav,
    }
  }, [results, selected, realData, scaleToNav, realSlug])

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
      const params = [fromDate && `from=${fromDate}`, toDate && `to=${toDate}`].filter(Boolean).join('&')
      const [backtestRes, realRes] = await Promise.all([
        fetch('/api/backtest/run', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ fromDate: fromDate || null, toDate: toDate || null, portfolios }),
        }),
        realSlug
          ? fetch(`/api/performance/chart/${realSlug}${params ? '?' + params : ''}`)
          : Promise.resolve(null),
      ])
      const data: BacktestResults = await backtestRes.json()
      if (!backtestRes.ok || data.error) { setError(data.error || `Server error ${backtestRes.status}`); return }

      let newRealData: RealPortfolioData | null = realData
      if (realRes) {
        try {
          const d = await realRes.json()
          newRealData = {
            dates:          d.dates          ?? [],
            twrSeries:      d.twrSeries      ?? [],
            mwrSeries:      d.mwrSeries      ?? null,
            positionSeries: d.positionSeries ?? null,
            navSeries:      d.navSeries      ?? [],
          }
        } catch { newRealData = null }
      }

      // Both set in same React 18 batch → single re-render, no delay
      setResults(data)
      setSelected(new Set())
      setRealData(newRealData)
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
    type:              'monotone' as const,
    dot:               false as const,
    activeDot:         { r: 4 },
    strokeWidth:       1.5,
    connectNulls:      false,
    isAnimationActive: false,
  }

  const shouldScaleToNav = chartData?.shouldScaleToNav ?? false
  const showNavScaleBtn  = !!realSlug && (chartData?.navStart ?? 0) > 0
  const showNavLine      = !!realSlug && !!(realData?.navSeries.length) && showLine('real-nav')
  // NAV uses a secondary right axis only when not scaling (values are in different dollar range)
  const navSecondAxis    = showNavLine && !shouldScaleToNav
  const chartRightMargin = navSecondAxis ? 80 : 16

  // Stats table stat row renderer helper
  const realStatRow = (
    key: string,
    label: string,
    stats: SeriesStats | null | undefined,
    color: string,
  ) => {
    if (!stats) return null
    return (
      <tr key={key}>
        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
        <td style={{ color }}>{label}</td>
        <td>{money(stats.endingValue)}</td>
        <td>{pct(stats.cagr)}</td>
        <td>{pct(stats.maxDrawdown)}</td>
        <td>{dur(stats.longestDrawdownDays)}</td>
        <td>{pct(stats.annualVolatility)}</td>
        <td>{fmt2(stats.sharpe)}</td>
        <td>{pct(stats.ulcerIndex)}</td>
        <td>{fmt2(stats.upi)}</td>
        <td>–</td>
        <td>–</td>
      </tr>
    )
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

        {realPortfolios.length > 0 && (
          <div className="backtest-section" style={{ marginTop: '0.5rem' }}>
            <label htmlFor="real-portfolio-select">Real Portfolio Overlay</label>
            <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center', marginTop: '0.25rem' }}>
              <select
                id="real-portfolio-select"
                value={realSlug}
                onChange={e => setRealSlug(e.target.value)}
              >
                <option value="">— none —</option>
                {realPortfolios.map(p => (
                  <option key={p.slug} value={p.slug}>{p.name}</option>
                ))}
              </select>
            </div>
          </div>
        )}

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
                    const key    = `${pi}-${ci}`
                    const s      = curve.stats
                    const factor = chartData.curveScaleFactors.get(key) ?? 1
                    const trig   = (v: number | null | undefined) => v == null ? '–' : String(v)
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td>{portfolio.label} – {curve.label}</td>
                        <td>{money(s.endingValue * factor)}</td>
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
                {realSlug && chartData.realStats && (() => {
                  const rs = chartData.realStats!
                  return (
                    <>
                      {realStatRow('real-nav', 'Real – NAV',      rs.nav, '#e8c84a')}
                      {realStatRow('real-twr', 'Real – TWR',      rs.twr, '#f09030')}
                      {rs.mwr && realStatRow('real-mwr', 'Real – MWR',      rs.mwr, '#e05050')}
                      {rs.pos && realStatRow('real-pos', 'Real – Position', rs.pos, '#50c050')}
                    </>
                  )
                })()}
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
            {showNavScaleBtn && (
              <button
                className={`chart-scale-toggle${scaleToNav ? ' active' : ''}`}
                type="button"
                style={{ right: 75 }}
                onClick={() => setScaleToNav(s => !s)}
              >
                NAV Scale
              </button>
            )}
            <ResponsiveContainer width="100%" height="100%">
              <LineChart
                data={chartData.mainData.rows}
                syncId="backtest"
                margin={{ top: 8, right: chartRightMargin, bottom: 8, left: 8 }}
              >
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis
                  dataKey="x"
                  tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))}
                />
                {/* Primary Y axis — backtest curves + TWR/MWR/Position */}
                <YAxis
                  yAxisId="main"
                  scale={logScale ? 'log' : 'linear'}
                  domain={['auto', 'auto']}
                  allowDataOverflow={logScale}
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)}
                  width={72}
                />
                {/* Secondary Y axis for NAV when not scaled (NAV keeps its own dollar scale) */}
                {navSecondAxis && (
                  <YAxis
                    yAxisId="nav-right"
                    orientation="right"
                    tick={{ fill: textColor, fontSize: 11 }}
                    tickFormatter={v => '$' + Number(v).toFixed(0)}
                    width={72}
                    label={{ value: 'NAV', angle: 90, position: 'insideRight', fill: '#e8c84a', fontSize: 10, dy: -20 }}
                  />
                )}
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(2))} />
                <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />

                {/* Backtest portfolio curves */}
                {chartData.mainData.datasets.map(ds => (
                  <Line key={ds.label} yAxisId="main" dataKey={ds.label} stroke={ds.color} {...commonLineProps} />
                ))}

                {/* Real portfolio lines */}
                {realSlug && realData?.dates.length ? (
                  <>
                    {showLine('real-nav') && (
                      <Line
                        yAxisId={shouldScaleToNav ? 'main' : 'nav-right'}
                        dataKey="Real – NAV"
                        stroke="#e8c84a"
                        strokeWidth={2}
                        dot={false}
                        activeDot={{ r: 4 }}
                        connectNulls={false}
                        isAnimationActive={false}
                        type="monotone"
                      />
                    )}
                    {showLine('real-twr') && (
                      <Line
                        yAxisId="main"
                        dataKey="Real – TWR"
                        stroke="#f09030"
                        strokeWidth={1.5}
                        strokeDasharray="6 3"
                        dot={false}
                        activeDot={{ r: 4 }}
                        connectNulls={false}
                        isAnimationActive={false}
                        type="monotone"
                      />
                    )}
                    {realData.mwrSeries != null && showLine('real-mwr') && (
                      <Line
                        yAxisId="main"
                        dataKey="Real – MWR"
                        stroke="#e05050"
                        strokeWidth={1.5}
                        strokeDasharray="4 2"
                        dot={false}
                        activeDot={{ r: 4 }}
                        connectNulls={false}
                        isAnimationActive={false}
                        type="monotone"
                      />
                    )}
                    {realData.positionSeries != null && showLine('real-pos') && (
                      <Line
                        yAxisId="main"
                        dataKey="Real – Position"
                        stroke="#50c050"
                        strokeWidth={1.5}
                        strokeDasharray="2 2"
                        dot={false}
                        activeDot={{ r: 4 }}
                        connectNulls={false}
                        isAnimationActive={false}
                        type="monotone"
                      />
                    )}
                  </>
                ) : null}

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
