// ── BacktestPage.tsx — Full React port of backtest runner ─────────────────────

import { useCallback, useEffect, useRef, useState } from 'react'
import Chart from 'chart.js/auto'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight } from '@/components/Layout'
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

// ── Chart helpers ─────────────────────────────────────────────────────────────

function buildCommonLabels(data: BacktestResults): string[] {
  let common = new Set(data.portfolios[0].curves[0].points.map(p => p.date))
  for (let i = 1; i < data.portfolios.length; i++) {
    const dates = new Set(data.portfolios[i].curves[0].points.map(p => p.date))
    for (const d of [...common]) { if (!dates.has(d)) common.delete(d) }
  }
  return [...common].sort()
}

function buildCurveDatasets(
  data: BacktestResults, labels: string[], selected: Set<string>,
  valueFn: (pts: { date: string; value: number }[]) => (number | null)[],
) {
  const datasets: any[] = []
  data.portfolios.forEach((portfolio, pi) => {
    const palette = PALETTE[pi % PALETTE.length]
    portfolio.curves.forEach((curve, ci) => {
      if (selected.size > 0 && !selected.has(`${pi}-${ci}`)) return
      const vals = valueFn(curve.points)
      const byDate = new Map(curve.points.map((p, i) => [p.date, vals[i]]))
      datasets.push({
        label: `${portfolio.label} \u2013 ${curve.label}`,
        data: labels.map(d => byDate.get(d) ?? null),
        spanGaps: false,
        borderColor: palette[ci % palette.length],
        backgroundColor: 'transparent',
        borderWidth: 1.5, pointRadius: 0, pointHoverRadius: 4,
      })
    })
  })
  return datasets
}

function computeDrawdown(pts: { date: string; value: number }[]): number[] {
  let peak = -Infinity
  return pts.map(p => { if (p.value > peak) peak = p.value; return (p.value / peak) - 1 })
}

function computeRTR(pts: { date: string; value: number }[]): (number | null)[] {
  let peak = -Infinity
  return pts.map(p => { if (p.value > peak) peak = p.value; return p.value > 0 ? peak / p.value : null })
}

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
  const mainCanvasRef     = useRef<HTMLCanvasElement>(null)
  const ddCanvasRef       = useRef<HTMLCanvasElement>(null)
  const rtrCanvasRef      = useRef<HTMLCanvasElement>(null)
  const mainChartRef      = useRef<Chart | null>(null)
  const ddChartRef        = useRef<Chart | null>(null)
  const rtrChartRef       = useRef<Chart | null>(null)

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

  // Re-render charts when results/logScale/selected changes
  useEffect(() => {
    if (!results) return
    const { gridColor, textColor } = getChartTheme()
    const labels = buildCommonLabels(results)

    // Destroy old
    mainChartRef.current?.destroy()
    ddChartRef.current?.destroy()
    rtrChartRef.current?.destroy()

    if (mainCanvasRef.current) {
      const datasets = buildCurveDatasets(results, labels, selected, pts => pts.map(p => p.value))
      mainChartRef.current = new Chart(mainCanvasRef.current.getContext('2d')!, {
        type: 'line', data: { labels, datasets },
        options: {
          animation: false, responsive: true, maintainAspectRatio: false,
          interaction: { mode: 'index', intersect: false },
          plugins: {
            title: { display: true, text: 'Portfolio Value', color: textColor, font: { size: 13 } },
            legend: { labels: { color: textColor } },
            tooltip: { mode: 'index', callbacks: { title: i => i[0]?.label || '', label: i => ` ${i.dataset.label}: $${(i.raw as number).toFixed(2)}` } },
          },
          scales: {
            x: { ticks: { color: textColor, maxTicksLimit: 10, maxRotation: 0 }, grid: { color: gridColor } },
            y: { type: logScale ? 'logarithmic' : 'linear', ticks: { color: textColor, callback: v => '$' + Number(v).toFixed(0) }, grid: { color: gridColor } },
          },
        },
      })
    }

    if (ddCanvasRef.current) {
      const datasets = buildCurveDatasets(results, labels, selected, computeDrawdown)
      ddChartRef.current = new Chart(ddCanvasRef.current.getContext('2d')!, {
        type: 'line', data: { labels, datasets },
        options: {
          animation: false, responsive: true, maintainAspectRatio: false,
          interaction: { mode: 'index', intersect: false },
          plugins: {
            title: { display: true, text: 'Drawdown', color: textColor, font: { size: 13 } },
            legend: { labels: { color: textColor } },
            tooltip: { mode: 'index', callbacks: { title: i => i[0]?.label || '', label: i => ` ${i.dataset.label}: ${((i.raw as number) * 100).toFixed(2)}%` } },
          },
          scales: {
            x: { ticks: { color: textColor, maxTicksLimit: 10, maxRotation: 0 }, grid: { color: gridColor } },
            y: { ticks: { color: textColor, callback: v => (Number(v) * 100).toFixed(1) + '%' }, grid: { color: gridColor } },
          },
        },
      })
    }

    if (rtrCanvasRef.current) {
      const datasets = buildCurveDatasets(results, labels, selected, computeRTR)
      rtrChartRef.current = new Chart(rtrCanvasRef.current.getContext('2d')!, {
        type: 'line', data: { labels, datasets },
        options: {
          animation: false, responsive: true, maintainAspectRatio: false,
          interaction: { mode: 'index', intersect: false },
          plugins: {
            title: { display: true, text: 'Return Required to Recover', color: textColor, font: { size: 13 } },
            legend: { labels: { color: textColor } },
            tooltip: { mode: 'index', callbacks: { title: i => i[0]?.label || '', label: i => ` ${i.dataset.label}: ${(i.raw as number).toFixed(2)}x` } },
          },
          scales: {
            x: { ticks: { color: textColor, maxTicksLimit: 10, maxRotation: 0 }, grid: { color: gridColor } },
            y: { ticks: { color: textColor, callback: v => Number(v).toFixed(2) + 'x' }, grid: { color: gridColor } },
          },
        },
      })
    }

    return () => {
      mainChartRef.current?.destroy(); mainChartRef.current = null
      ddChartRef.current?.destroy();   ddChartRef.current = null
      rtrChartRef.current?.destroy();  rtrChartRef.current = null
    }
  }, [results, logScale, selected])

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

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/backtest" /></div>
        <HeaderRight><ConfigButton /><ThemeToggle /></HeaderRight>
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

      {results && (
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

          {/* Main chart */}
          <div className="backtest-chart-container">
            <button
              className={`chart-scale-toggle${logScale ? ' active' : ''}`}
              type="button"
              onClick={() => setLogScale(l => !l)}
            >
              Log
            </button>
            <canvas ref={mainCanvasRef} />
          </div>

          {/* Drawdown chart */}
          <div className="backtest-chart-container">
            <canvas ref={ddCanvasRef} />
          </div>

          {/* RTR chart */}
          <div className="backtest-chart-container">
            <canvas ref={rtrCanvasRef} />
          </div>
        </>
      )}
    </div>
  )
}
