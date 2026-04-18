import { useEffect, useMemo, useRef, useState } from 'react'
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
type Period = '1W' | '1M' | '3M' | '6M' | 'YTD' | '1Y' | '3Y' | 'All' | 'Custom'

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

const PERIODS: Period[] = ['1W', '1M', '3M', '6M', 'YTD', '1Y', '3Y', 'All', 'Custom']
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

  const [period, setPeriod]               = useState<Period>('1Y')
  const [customFrom, setCustomFrom]       = useState('')
  const [customTo, setCustomTo]           = useState(todayStr())
  const [mode, setMode]                   = useState<ReturnMode>('twr')

  const [showNav, setShowNav]             = useState(false)
  const [showMargin, setShowMargin]       = useState(false)

  const [savedPortfolios, setSavedPortfolios] = useState<SavedPortfolio[]>([])
  const [selectedBenchmarks, setSelectedBenchmarks] = useState<string[]>([])
  // benchmark name → { date → normalised value (0-based) }
  const [benchmarkData, setBenchmarkData] = useState<Record<string, Record<string, number>>>({})

  const theme = useChartTheme()
  const { gridColor, textColor, isDark } = theme

  const firstDate = snapshotDates[0] ?? ''
  const lastDate  = snapshotDates[snapshotDates.length - 1] ?? todayStr()

  const resolvedFrom = period === 'Custom' ? customFrom : periodFrom(period, firstDate)
  const resolvedTo   = period === 'Custom' ? customTo   : lastDate

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

  // ── Fetch benchmark data when selection changes ───────────────────────────
  useEffect(() => {
    if (selectedBenchmarks.length === 0) { setBenchmarkData({}); return }
    const from = resolvedFrom; const to = resolvedTo
    if (!from || !to) return

    const fetchBenchmark = async (name: string) => {
      const sp = savedPortfolios.find(p => p.name === name)
      if (!sp) return
      const block = configToBlockState(sp.config?.portfolios?.[0] ?? sp.config, name)
      const portfolio = blockStateToAPIPortfolio(block, 0)
      if (!portfolio.tickers.length) return
      try {
        const res = await fetch('/api/backtest/run', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ fromDate: from || null, toDate: to || null, portfolios: [portfolio] }),
        })
        const result = await res.json()
        const curve = result?.portfolios?.[0]?.curves?.[0]
        if (!curve?.points?.length) return
        // Normalise to 0% at start (divide by first value, subtract 1)
        const pts: { date: string; value: number }[] = curve.points
        const base = pts[0].value
        const normalised: Record<string, number> = {}
        for (const pt of pts) normalised[pt.date] = pt.value / base - 1
        setBenchmarkData(prev => ({ ...prev, [name]: normalised }))
      } catch (_) {}
    }

    setBenchmarkData({})
    for (const name of selectedBenchmarks) fetchBenchmark(name)
  }, [selectedBenchmarks, resolvedFrom, resolvedTo])

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
      // Refresh data
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

  function toggleBenchmark(name: string) {
    setSelectedBenchmarks(prev =>
      prev.includes(name) ? prev.filter(n => n !== name) : [...prev, name]
    )
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
        date: date.slice(5),   // MM-DD for display
        fullDate: date,
        return: returnValue != null ? +(returnValue * 100).toFixed(4) : null,
        nav:    +data.navSeries[i].toFixed(2),
        margin: +(data.marginUtilSeries[i] * 100).toFixed(2),
      }
      for (const [name, dateMap] of Object.entries(benchmarkData)) {
        const bval = dateMap[date]
        row[`bm__${name}`] = bval != null ? +(bval * 100).toFixed(4) : null
      }
      return row
    })
  }, [data, mode, benchmarkData])

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

  return (
    <div style={{ padding: '1rem 0' }}>

      {/* ── Controls ───────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', alignItems: 'center', marginBottom: '0.75rem' }}>

        {/* Period selector */}
        <div style={{ display: 'flex', gap: '0.25rem', flexWrap: 'wrap' }}>
          {PERIODS.map(p => (
            <button
              key={p}
              className={`backtest-config-btn${period === p ? ' active' : ''}`}
              style={{ minWidth: 40, padding: '0.25rem 0.5rem', fontSize: '0.8rem' }}
              onClick={() => setPeriod(p)}
            >
              {p}
            </button>
          ))}
        </div>

        {/* Custom date range */}
        {period === 'Custom' && (
          <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center', fontSize: '0.82rem' }}>
            <input type="date" value={customFrom} onChange={e => setCustomFrom(e.target.value)}
              style={{ fontSize: '0.82rem' }} />
            <span>→</span>
            <input type="date" value={customTo} onChange={e => setCustomTo(e.target.value)}
              style={{ fontSize: '0.82rem' }} />
          </div>
        )}

        <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          {/* Return mode */}
          <div style={{ display: 'flex', gap: '0.25rem' }}>
            {(['twr', 'mwr', 'position'] as ReturnMode[]).map(m => (
              <button
                key={m}
                className={`backtest-config-btn${mode === m ? ' active' : ''}`}
                style={{ fontSize: '0.8rem', padding: '0.25rem 0.5rem', opacity: m === 'mwr' && mwrDisabled ? 0.4 : 1 }}
                disabled={m === 'mwr' && mwrDisabled}
                title={m === 'mwr' && mwrDisabled ? 'No external cash flows in this period' : undefined}
                onClick={() => setMode(m)}
              >
                {m === 'twr' ? 'TWR' : m === 'mwr' ? 'MWR' : 'Position (ex-cash)'}
              </button>
            ))}
          </div>

          {/* Fetch from IBKR */}
          <button
            className="run-backtest-btn"
            style={{ fontSize: '0.8rem', padding: '0.3rem 0.8rem', whiteSpace: 'nowrap' }}
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
            className="run-backtest-btn"
            style={{ fontSize: '0.8rem', padding: '0.3rem 0.8rem', whiteSpace: 'nowrap' }}
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

      {/* Overlay toggles + benchmark */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', marginBottom: '0.75rem', fontSize: '0.82rem' }}>
        <label style={{ display: 'flex', gap: '0.3rem', alignItems: 'center', cursor: 'pointer' }}>
          <input type="checkbox" checked={showNav} onChange={e => setShowNav(e.target.checked)} />
          NAV (right axis)
        </label>
        <label style={{ display: 'flex', gap: '0.3rem', alignItems: 'center', cursor: 'pointer' }}>
          <input type="checkbox" checked={showMargin} onChange={e => setShowMargin(e.target.checked)} />
          Margin utilisation
        </label>

        {savedPortfolios.length > 0 && (
          <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center' }}>
            <span style={{ opacity: 0.6 }}>Benchmarks:</span>
            {savedPortfolios.slice(0, 4).map(sp => (
              <label key={sp.name} style={{ display: 'flex', gap: '0.25rem', alignItems: 'center', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={selectedBenchmarks.includes(sp.name)}
                  onChange={() => toggleBenchmark(sp.name)}
                />
                {sp.name}
              </label>
            ))}
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
        <div style={{ textAlign: 'center', padding: '3rem', opacity: 0.5, fontSize: '0.9rem' }}>
          No snapshots yet. Click "Fetch from IBKR" to import historical data.
        </div>
      )}
      {loading && <div style={{ textAlign: 'center', padding: '2rem', opacity: 0.5 }}>Loading…</div>}
      {error && <div style={{ color: '#e05c5c', padding: '0.5rem' }}>{error}</div>}

      {hasData && !loading && (
        <div style={{ height: 380 }}>
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={rows} margin={{ top: 8, right: 56, bottom: 8, left: 8 }}>
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
              {/* Right: NAV or margin util */}
              {(showNav || showMargin) && (
                <YAxis
                  yAxisId="right"
                  orientation="right"
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => showNav && !showMargin ? navFmt(v) : `${v.toFixed(1)}%`}
                  width={64}
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
                  yAxisId="right"
                  dataKey="margin"
                  name="Margin Util %"
                  stroke="#c75d1a"
                  fill="#c75d1a"
                  fillOpacity={0.15}
                  {...commonLine}
                />
              )}

              {/* Benchmark overlays */}
              {selectedBenchmarks.map((name, bi) => (
                <Line
                  key={name}
                  yAxisId="left"
                  dataKey={`bm__${name}`}
                  name={name}
                  stroke={BENCHMARK_COLORS[bi % BENCHMARK_COLORS.length]}
                  strokeDasharray="6 3"
                  {...commonLine}
                />
              ))}

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
    </div>
  )
}
