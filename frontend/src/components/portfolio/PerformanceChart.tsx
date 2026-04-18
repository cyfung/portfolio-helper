import { useEffect, useMemo, useRef, useState } from 'react'
import IbkrConfigDialog from '@/components/portfolio/IbkrConfigDialog'
import {
  ComposedChart, Line, Area, XAxis, YAxis,
  CartesianGrid, Tooltip, Legend, ResponsiveContainer, Brush,
} from 'recharts'
import { useChartTheme } from '@/lib/chartTheme'
import type { SavedPortfolio } from '@/types/backtest'
import { blockStateToAPIPortfolio, configToBlockState } from '@/types/backtest'

interface Props {
  portfolioSlug: string
}

interface ChartData {
  dates: string[]
  twrSeries: number[]
  mwrSeries: number[] | null
  positionSeries: number[] | null
  navSeries: number[]
  marginUtilSeries: number[]
}

type ReturnMode = 'twr' | 'mwr' | 'position'
type Period = '1W' | '1M' | '3M' | '6M' | 'YTD' | '1Y' | '3Y' | 'All'

function todayStr() {
  return new Date().toISOString().slice(0, 10)
}

function periodFrom(p: Period, firstDate: string): string {
  const now = new Date()
  switch (p) {
    case '1W':  { const d = new Date(now); d.setDate(d.getDate() - 7);    return d.toISOString().slice(0, 10) }
    case '1M':  { const d = new Date(now); d.setMonth(d.getMonth() - 1);   return d.toISOString().slice(0, 10) }
    case '3M':  { const d = new Date(now); d.setMonth(d.getMonth() - 3);   return d.toISOString().slice(0, 10) }
    case '6M':  { const d = new Date(now); d.setMonth(d.getMonth() - 6);   return d.toISOString().slice(0, 10) }
    case 'YTD': return `${now.getFullYear()}-01-01`
    case '1Y':  { const d = new Date(now); d.setFullYear(d.getFullYear() - 1); return d.toISOString().slice(0, 10) }
    case '3Y':  { const d = new Date(now); d.setFullYear(d.getFullYear() - 3); return d.toISOString().slice(0, 10) }
    case 'All': return firstDate
    default:    return firstDate
  }
}

const PERIODS: Period[] = ['1W', '1M', '3M', '6M', 'YTD', '1Y', '3Y', 'All']
const BENCHMARK_COLORS = ['#e8c84a', '#c84aaa', '#4ac8e8', '#e84a4a']

export default function PerformanceChart({ portfolioSlug }: Props) {
  const [snapshotDates, setSnapshotDates] = useState<string[]>([])
  const [data, setData]                   = useState<ChartData | null>(null)
  const [loading, setLoading]             = useState(false)
  const [ingesting, setIngesting]         = useState(false)
  const [error, setError]                 = useState('')
  const [toast, setToast]                 = useState('')
  const [gaps, setGaps]                   = useState<{ from: string; to: string; days: number }[]>([])
  const toastTimerRef                     = useRef<number | null>(null)
  const xmlInputRef                       = useRef<HTMLInputElement>(null)

  const [showIbkrConfig, setShowIbkrConfig] = useState(false)

  const [period, setPeriod]               = useState<Period>('1Y')
  const [mode, setMode]                   = useState<ReturnMode>('twr')

  const [showNav, setShowNav]             = useState(true)
  const [showMargin, setShowMargin]       = useState(false)

  const [savedPortfolios, setSavedPortfolios] = useState<SavedPortfolio[]>([])
  const [selectedBenchmark, setSelectedBenchmark] = useState<string>('')
  const [benchmarkData, setBenchmarkData] = useState<Record<string, number>>({})
  const [benchmarkMarginKey, setBenchmarkMarginKey] = useState<string>('__default__')

  const theme = useChartTheme()
  const { gridColor, textColor, isDark } = theme

  const benchmarkBlock = useMemo(() => {
    if (!selectedBenchmark) return null
    const sp = savedPortfolios.find(p => p.name === selectedBenchmark)
    if (!sp) return null
    return configToBlockState(sp.config?.portfolios?.[0] ?? sp.config, selectedBenchmark)
  }, [selectedBenchmark, savedPortfolios])

  const firstDate = snapshotDates[0] ?? ''
  const lastDate  = snapshotDates[snapshotDates.length - 1] ?? todayStr()

  const resolvedFrom = periodFrom(period, firstDate)
  const resolvedTo   = lastDate

  // ── Load snapshot dates on mount ─────────────────────────────────────────
  useEffect(() => {
    fetch(`/api/performance/snapshots/${portfolioSlug}`)
      .then(r => r.json())
      .then((d: { dates: string[] }) => setSnapshotDates(d.dates ?? []))
      .catch(() => {})
    fetch('/api/backtest/savedPortfolios')
      .then(r => r.json())
      .then((d: SavedPortfolio[]) => setSavedPortfolios(d ?? []))
      .catch(() => {})
    fetch(`/api/performance/gaps/${portfolioSlug}`)
      .then(r => r.json())
      .then(setGaps)
      .catch(() => {})
  }, [portfolioSlug])

  // ── Fetch chart data when period changes ──────────────────────────────────
  useEffect(() => {
    if (!resolvedFrom || !resolvedTo) return
    setLoading(true)
    setError('')
    fetch(`/api/performance/chart/${portfolioSlug}?from=${resolvedFrom}&to=${resolvedTo}`)
      .then(r => r.json())
      .then((d: ChartData) => {
        setData(d)
        if (d.mwrSeries === null && mode === 'mwr') setMode('twr')
      })
      .catch(() => setError('Failed to load performance data.'))
      .finally(() => setLoading(false))
  }, [portfolioSlug, resolvedFrom, resolvedTo])

  // ── Reset margin key when benchmark changes ───────────────────────────────
  useEffect(() => { setBenchmarkMarginKey('__default__') }, [selectedBenchmark])

  // ── Fetch benchmark data when selection changes ───────────────────────────
  useEffect(() => {
    if (!selectedBenchmark || !benchmarkBlock) { setBenchmarkData({}); return }
    const from = resolvedFrom; const to = resolvedTo
    if (!from || !to) return

    const portfolio = blockStateToAPIPortfolio(benchmarkBlock, 0)
    if (!portfolio.tickers.length) return

    let filteredPortfolio = portfolio
    if (benchmarkMarginKey === 'none') {
      filteredPortfolio = { ...portfolio, marginStrategies: [], includeNoMargin: true }
    } else if (benchmarkMarginKey !== '__default__') {
      const idx = parseInt(benchmarkMarginKey)
      filteredPortfolio = { ...portfolio, marginStrategies: [portfolio.marginStrategies[idx]], includeNoMargin: false }
    }

    setBenchmarkData({})
    fetch('/api/backtest/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fromDate: from || null, toDate: to || null, portfolios: [filteredPortfolio] }),
    })
      .then(r => r.json())
      .then(result => {
        const curve = result?.portfolios?.[0]?.curves?.[0]
        if (!curve?.points?.length) return
        const pts: { date: string; value: number }[] = curve.points
        const base = pts[0].value
        const normalised: Record<string, number> = {}
        for (const pt of pts) normalised[pt.date] = pt.value / base - 1
        setBenchmarkData(normalised)
      })
      .catch(() => {})
  }, [selectedBenchmark, benchmarkBlock, resolvedFrom, resolvedTo, benchmarkMarginKey])

  // ── Import XML files ──────────────────────────────────────────────────────
  async function handleXmlImport(files: FileList) {
    setIngesting(true)
    clearTimeout(toastTimerRef.current ?? undefined)
    try {
      let totalWritten = 0
      for (let i = 0; i < files.length; i++) {
        showToast(`Importing ${i + 1}/${files.length}…`)
        const xml = await files[i].text()
        const r = await fetch(`/api/performance/ingest-xml/${portfolioSlug}`, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: xml,
        })
        const d = await r.json()
        if (!r.ok) throw new Error(`${files[i].name}: ${d.error ?? `HTTP ${r.status}`}`)
        totalWritten += d.written
      }
      showToast(`Imported — ${totalWritten} new snapshot(s) written.`)
      fetch(`/api/performance/gaps/${portfolioSlug}`)
        .then(r => r.json())
        .then(setGaps)
        .catch(() => {})
      setSnapshotDates([])
      setData(null)
      fetch(`/api/performance/snapshots/${portfolioSlug}`)
        .then(r2 => r2.json())
        .then((sd: { dates: string[] }) => setSnapshotDates(sd.dates ?? []))
        .catch(() => {})
    } catch (e: any) {
      showToast(`Error: ${e.message}`, true)
    } finally {
      setIngesting(false)
      if (xmlInputRef.current) xmlInputRef.current.value = ''
    }
  }

  // ── Ingest from IBKR ──────────────────────────────────────────────────────
  async function handleIngest() {
    setIngesting(true)
    clearTimeout(toastTimerRef.current ?? undefined)
    try {
      const r = await fetch(`/api/performance/ingest/${portfolioSlug}`, { method: 'POST' })
      const d = await r.json()
      if (!r.ok) throw new Error(d.error ?? `HTTP ${r.status}`)
      showToast(`Fetched — ${d.written} new snapshot(s) written.`)
      setSnapshotDates([])
      setData(null)
      fetch(`/api/performance/snapshots/${portfolioSlug}`)
        .then(r2 => r2.json())
        .then((sd: { dates: string[] }) => setSnapshotDates(sd.dates ?? []))
        .catch(() => {})
    } catch (e: any) {
      showToast(`Error: ${e.message}`, true)
    } finally {
      setIngesting(false)
    }
  }

  function showToast(msg: string, isError = false) {
    setToast(isError ? `⚠ ${msg}` : `✓ ${msg}`)
    toastTimerRef.current = window.setTimeout(() => setToast(''), isError ? 6000 : 3000)
  }

  // ── Build chart rows ──────────────────────────────────────────────────────
  const rows = useMemo(() => {
    if (!data?.dates.length) return []
    return data.dates.map((date, i) => {
      const returnValue = mode === 'twr'
        ? data.twrSeries[i]
        : mode === 'mwr'
        ? (data.mwrSeries?.[i] ?? null)
        : (data.positionSeries?.[i] ?? null)

      const row: Record<string, any> = {
        date: date.slice(5),
        fullDate: date,
        return: returnValue != null ? +(returnValue * 100).toFixed(4) : null,
        nav:    +data.navSeries[i].toFixed(2),
        margin: +(data.marginUtilSeries[i] * 100).toFixed(2),
      }
      if (selectedBenchmark) {
        const bval = benchmarkData[date]
        row[`bm__${selectedBenchmark}`] = bval != null ? +(bval * 100).toFixed(4) : null
      }
      return row
    })
  }, [data, mode, benchmarkData, selectedBenchmark])

  const mwrDisabled = data?.mwrSeries == null

  const pctFmt = (v: number) => `${v >= 0 ? '+' : ''}${v.toFixed(2)}%`
  const navFmt = (v: number) => {
    if (v >= 1_000_000) return `$${(v / 1_000_000).toFixed(2)}M`
    if (v >= 1_000)     return `$${(v / 1_000).toFixed(1)}k`
    return `$${v.toFixed(0)}`
  }

  const commonLine = {
    dot: false as const,
    activeDot: { r: 3 },
    strokeWidth: 1.5,
    connectNulls: false,
    isAnimationActive: false,
    type: 'monotone' as const,
  }

  const hasData = rows.length > 0
  const isEmpty = snapshotDates.length === 0 && !loading

  const modeLabel = (m: ReturnMode) => m === 'twr' ? 'TWR' : m === 'mwr' ? 'MWR' : 'Position (ex-cash)'

  const activeModeStyle = {
    background: '#1a6bc7',
    color: '#fff',
    borderColor: '#1a6bc7',
  }

  return (
    <div style={{ padding: '1rem 0' }}>

      {/* ── Row 1: Period selector + action buttons ─────────────────────── */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', alignItems: 'center', marginBottom: '0.75rem' }}>

        {/* Period selector */}
        <div style={{ display: 'flex', gap: '0.25rem', flexWrap: 'wrap' }}>
          {PERIODS.map(p => (
            <button
              key={p}
              className="backtest-config-btn"
              style={{ minWidth: 40, padding: '0.25rem 0.5rem', fontSize: '0.8rem', ...(period === p ? activeModeStyle : {}) }}
              onClick={() => setPeriod(p)}
            >
              {p}
            </button>
          ))}
        </div>

        <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          {/* IB Config */}
          <button
            className="backtest-config-btn"
            style={{ fontSize: '0.8rem', padding: '0.25rem 0.7rem', whiteSpace: 'nowrap' }}
            onClick={() => setShowIbkrConfig(true)}
            disabled={ingesting}
          >
            IB Config
          </button>

          {/* Fetch from IBKR */}
          <button
            className="backtest-config-btn"
            style={{ fontSize: '0.8rem', padding: '0.25rem 0.7rem', whiteSpace: 'nowrap' }}
            onClick={handleIngest}
            disabled={ingesting}
          >
            {ingesting ? 'Fetching…' : 'Fetch from IBKR'}
          </button>

          {/* Import XML */}
          <input
            ref={xmlInputRef}
            type="file"
            accept=".xml"
            multiple
            style={{ display: 'none' }}
            onChange={e => { if (e.target.files?.length) handleXmlImport(e.target.files) }}
          />
          <button
            className="backtest-config-btn"
            style={{ fontSize: '0.8rem', padding: '0.25rem 0.7rem', whiteSpace: 'nowrap' }}
            onClick={() => xmlInputRef.current?.click()}
            disabled={ingesting}
          >
            Import XML
          </button>
        </div>
      </div>

      {gaps.length > 0 && (
        <div style={{ fontSize: '0.78rem', color: 'var(--color-warn, #e8a94a)', padding: '0.2rem 0.4rem', marginBottom: '0.4rem' }}>
          Data gaps: {gaps.map(g => `${g.from} → ${g.to}`).join(', ')}
        </div>
      )}

      {/* ── Row 2: Return mode + overlays + benchmark ───────────────────── */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', marginBottom: '0.75rem', fontSize: '0.82rem' }}>

        {/* Return mode buttons */}
        <div style={{ display: 'flex', gap: '0.25rem' }}>
          {(['twr', 'mwr', 'position'] as ReturnMode[]).map(m => (
            <button
              key={m}
              className="backtest-config-btn"
              style={{
                fontSize: '0.8rem',
                padding: '0.25rem 0.5rem',
                ...(mode === m ? activeModeStyle : {}),
              }}
              onClick={() => setMode(m)}
            >
              {modeLabel(m)}
            </button>
          ))}
        </div>

        <div style={{ width: 1, height: 16, background: gridColor, opacity: 0.4 }} />

        <label style={{ display: 'flex', gap: '0.3rem', alignItems: 'center', cursor: 'pointer' }}>
          <input type="checkbox" checked={showNav} onChange={e => setShowNav(e.target.checked)} />
          NAV
        </label>
        <label style={{ display: 'flex', gap: '0.3rem', alignItems: 'center', cursor: 'pointer' }}>
          <input type="checkbox" checked={showMargin} onChange={e => setShowMargin(e.target.checked)} />
          Margin
        </label>

        {savedPortfolios.length > 0 && (
          <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center' }}>
            <span style={{ opacity: 0.6 }}>Benchmark:</span>
            <select
              value={selectedBenchmark}
              onChange={e => setSelectedBenchmark(e.target.value)}
              style={{ fontSize: '0.8rem', padding: '0.2rem 0.4rem', background: isDark ? '#1a1a2e' : '#f0f0f0', color: textColor, border: `1px solid ${gridColor}`, borderRadius: 4 }}
            >
              <option value="">— none —</option>
              {savedPortfolios.map(sp => (
                <option key={sp.name} value={sp.name}>{sp.name}</option>
              ))}
            </select>
            {benchmarkBlock && benchmarkBlock.margins.length > 0 && (
              <select
                value={benchmarkMarginKey}
                onChange={e => setBenchmarkMarginKey(e.target.value)}
                style={{ fontSize: '0.8rem', padding: '0.2rem 0.4rem', background: isDark ? '#1a1a2e' : '#f0f0f0', color: textColor, border: `1px solid ${gridColor}`, borderRadius: 4 }}
              >
                <option value="__default__">Default</option>
                {benchmarkBlock.includeNoMargin && <option value="none">No Margin</option>}
                {benchmarkBlock.margins.map((m, i) => (
                  <option key={i} value={String(i)}>{m.ratio}% Margin</option>
                ))}
              </select>
            )}
          </div>
        )}
      </div>

      {/* ── Toast ──────────────────────────────────────────────────────── */}
      {toast && (
        <div style={{
          padding: '0.4rem 0.8rem', borderRadius: 4, marginBottom: '0.5rem',
          background: toast.startsWith('⚠') ? 'var(--color-error, #5c1a1a)' : 'var(--color-success-bg, #1a4a2a)',
          color: toast.startsWith('⚠') ? '#ffaaaa' : '#aaffcc',
          fontSize: '0.83rem',
        }}>
          {toast}
        </div>
      )}

      {/* ── Disclaimer ─────────────────────────────────────────────────── */}
      {mode === 'position' && (
        <div style={{ fontSize: '0.75rem', opacity: 0.55, marginBottom: '0.4rem' }}>
          Position Return (ex-cash): uses Yahoo Finance adjusted close prices. Assumes dividends reinvested. Excludes cash, margin, and interest.
        </div>
      )}

      {/* ── Chart ──────────────────────────────────────────────────────── */}
      {isEmpty && (
        <div style={{ padding: '2rem 1rem', fontSize: '0.85rem', lineHeight: 1.7 }}>
          <div style={{ fontWeight: 600, marginBottom: '0.75rem' }}>No data yet — set up a Flex Query in IBKR:</div>
          <ol style={{ paddingLeft: '1.4rem', margin: 0, display: 'flex', flexDirection: 'column', gap: '0.3rem' }}>
            <li>In IBKR, go to <strong>Reports → Flex Queries</strong> and create a new query.</li>
            <li>Enable these sections:
              <ul style={{ paddingLeft: '1.2rem', marginTop: '0.2rem' }}>
                <li><code>Net Asset Value (NAV) in Base</code> — fields: <code>cash</code>, <code>total</code></li>
                <li><code>Open Positions</code> — fields: <code>symbol</code>, <code>positionValue</code></li>
                <li><code>Cash Transactions</code> — fields: <code>fxRateToBase</code>, <code>amount</code>, <code>type</code></li>
              </ul>
            </li>
            <li>Set the output <strong>Format</strong> to <strong>XML</strong>.</li>
            <li>Set <strong>Date Range</strong> to <strong>Last 365 Days</strong> (or Last 30 Days); set <strong>Breakout by Day</strong> to <strong>Yes</strong>.</li>
            <li>For history beyond 365 days, run the query manually with a custom date range and use <strong>Import XML</strong> — overlapping dates are fine and will be deduplicated.</li>
            <li>Generate a <strong>Flex Query Token</strong> under <strong>Settings → Flex Web Service</strong>.</li>
            <li>Click <strong>IB Config</strong> above, enter the token + Query ID, then click <strong>Fetch from IBKR</strong>.</li>
          </ol>
        </div>
      )}
      {loading && <div style={{ textAlign: 'center', padding: '2rem', opacity: 0.5 }}>Loading…</div>}
      {error && <div style={{ color: '#e05c5c', padding: '0.5rem' }}>{error}</div>}

      {mode === 'mwr' && mwrDisabled && hasData && !loading && (
        <div style={{ textAlign: 'center', padding: '3rem', opacity: 0.5, fontSize: '0.9rem' }}>
          MWR not available — no external cash flows in this period.
        </div>
      )}

      {hasData && !loading && !(mode === 'mwr' && mwrDisabled) && (
        <div style={{ height: 380 }}>
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={rows} margin={{ top: 8, right: showMargin && showNav ? 112 : 56, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
              <XAxis
                dataKey="date"
                tick={{ fill: textColor, fontSize: 11 }}
                interval={Math.max(1, Math.floor(rows.length / 8))}
              />
              {/* Left: return % */}
              <YAxis
                yAxisId="left"
                tick={{ fill: textColor, fontSize: 11 }}
                tickFormatter={v => `${v >= 0 ? '+' : ''}${v.toFixed(1)}%`}
                width={60}
              />
              {/* Right: NAV (auto-scaled) */}
              {showNav && (
                <YAxis
                  yAxisId="right"
                  orientation="right"
                  domain={['auto', 'auto']}
                  tick={{ fill: '#1a8a5c', fontSize: 11 }}
                  tickFormatter={navFmt}
                  width={64}
                />
              )}
              {/* Far right: Margin % (independent scale) */}
              {showMargin && (
                <YAxis
                  yAxisId="margin"
                  orientation="right"
                  domain={['auto', 'auto']}
                  tick={{ fill: '#c75d1a', fontSize: 11 }}
                  tickFormatter={v => `${v.toFixed(0)}%`}
                  width={48}
                />
              )}

              <Tooltip
                contentStyle={{
                  background: isDark ? '#1e1e1e' : '#fff',
                  border: `1px solid ${gridColor}`,
                  fontSize: '0.78rem',
                }}
                formatter={(value: any, name: string) => {
                  if (name === 'NAV') return [navFmt(value), name]
                  if (typeof value === 'number') return [pctFmt(value), name]
                  return [value, name]
                }}
              />
              <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />

              {/* Primary return line */}
              <Line
                yAxisId="left"
                dataKey="return"
                name={mode === 'twr' ? 'TWR' : mode === 'mwr' ? 'MWR' : 'Position Return'}
                stroke="#1a6bc7"
                {...commonLine}
              />

              {/* NAV overlay */}
              {showNav && (
                <Line
                  yAxisId="right"
                  dataKey="nav"
                  name="NAV"
                  stroke="#1a8a5c"
                  strokeDasharray="4 2"
                  {...commonLine}
                />
              )}

              {/* Margin utilisation area */}
              {showMargin && (
                <Area
                  yAxisId="margin"
                  dataKey="margin"
                  name="Margin Util %"
                  stroke="#c75d1a"
                  fill="#c75d1a"
                  fillOpacity={0.15}
                  {...commonLine}
                />
              )}

              {/* Benchmark overlay */}
              {selectedBenchmark && (
                <Line
                  key={selectedBenchmark}
                  yAxisId="left"
                  dataKey={`bm__${selectedBenchmark}`}
                  name={selectedBenchmark}
                  stroke={BENCHMARK_COLORS[0]}
                  strokeDasharray="6 3"
                  {...commonLine}
                />
              )}

              <Brush
                dataKey="date"
                height={24}
                stroke={gridColor}
                fill={isDark ? '#1a1a1a' : '#f8f8f8'}
                travellerWidth={6}
              />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      )}
      {showIbkrConfig && (
        <IbkrConfigDialog
          portfolioSlug={portfolioSlug}
          onClose={() => setShowIbkrConfig(false)}
        />
      )}
    </div>
  )
}
