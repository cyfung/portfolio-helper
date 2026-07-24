import { useEffect, useMemo, useRef, useState } from 'react'
import IbkrConfigDialog from '@/components/portfolio/IbkrConfigDialog'
import {
  ComposedChart, Line, Area, XAxis, YAxis,
  CartesianGrid, Tooltip, Legend, ResponsiveContainer, Brush,
} from 'recharts'
import { X } from 'lucide-react'
import { useChartTheme } from '@/lib/chartTheme'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { useTransientToast } from '@/hooks/useTransientToast'
import { configToBlockState } from '@/types/backtest'
import { resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'
import { useSavedPortfolios } from '@/lib/savedPortfolioCache'

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
  const { toast, showToast: showInlineToast, clearToast } = useTransientToast()
  const [gaps, setGaps]                   = useState<{ from: string; to: string; days: number }[]>([])
  const xmlInputRef                       = useRef<HTMLInputElement>(null)

  const [showIbkrConfig, setShowIbkrConfig] = useState(false)

  const [period, setPeriod]               = useState<Period>('1Y')
  const [mode, setMode]                   = useState<ReturnMode>('twr')

  const [showNav, setShowNav]             = useState(true)
  const [showMargin, setShowMargin]       = useState(false)

  const { savedPortfolios } = useSavedPortfolios()
  const [selectedBenchmark, setSelectedBenchmark] = useState<string>('')
  const [benchmarkCache, setBenchmarkCache] = useState<Record<string, Record<string, number>>>({})
  const [benchmarkMarginKey, setBenchmarkMarginKey] = useState<string>('none')
  const [benchmarkLoading, setBenchmarkLoading]   = useState(false)

  const privacyScaleEnabled = usePortfolioStore(s => s.appConfig?.privacyScaleEnabled)
  const privacyScalePct = usePortfolioStore(s => s.appConfig?.privacyScalePct)

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
    fetch(`/api/performance/gaps/${portfolioSlug}`)
      .then(r => r.json())
      .then(setGaps)
      .catch(() => {})
  }, [portfolioSlug])

  // ── Fetch chart data when period changes ──────────────────────────────────
  useEffect(() => {
    if (!resolvedFrom || !resolvedTo) return
    const controller = new AbortController()
    setLoading(true)
    setError('')
    fetch(`/api/performance/chart/${portfolioSlug}?from=${resolvedFrom}&to=${resolvedTo}`, { signal: controller.signal })
      .then(r => r.json())
      .then((d: ChartData) => {
        setData(d)
        setMode(current => d.mwrSeries === null && current === 'mwr' ? 'twr' : current)
      })
      .catch(err => {
        if (err?.name !== 'AbortError') setError('Failed to load performance data.')
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [portfolioSlug, resolvedFrom, resolvedTo, privacyScaleEnabled, privacyScalePct])

  // ── Reset margin key + cache when benchmark changes ──────────────────────
  useEffect(() => { setBenchmarkMarginKey('none'); setBenchmarkCache({}) }, [selectedBenchmark])

  // ── Pre-fetch all margin options when benchmark or date range changes ─────
  useEffect(() => {
    if (!selectedBenchmark || !benchmarkBlock) { setBenchmarkCache({}); setBenchmarkLoading(false); return }
    const from = resolvedFrom; const to = resolvedTo
    if (!from || !to) return

    let portfolio: ReturnType<typeof resolvedBlockStateToAPIPortfolio>
    try {
      portfolio = resolvedBlockStateToAPIPortfolio(benchmarkBlock, 0, savedPortfolios)
    } catch (resolutionError) {
      setBenchmarkCache({})
      setBenchmarkLoading(false)
      setError(resolutionError instanceof Error ? resolutionError.message : 'Unable to resolve benchmark portfolio.')
      return
    }
    if (!portfolio.tickers.length) return

    const marginKeys: string[] = ['none', ...benchmarkBlock.margins.map((_, i) => String(i))]

    function buildFilteredPortfolio(key: string) {
      if (key === 'none') return { ...portfolio, marginStrategies: [], includeNoMargin: true }
      const idx = parseInt(key)
      return { ...portfolio, marginStrategies: [portfolio.marginStrategies[idx]], includeNoMargin: false }
    }

    async function fetchOne(key: string): Promise<[string, Record<string, number>]> {
      const r = await fetch('/api/backtest/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fromDate: from || null, toDate: to || null, portfolios: [buildFilteredPortfolio(key)], saveSettings: false }),
      })
      const result = await r.json()
      const curve = result?.portfolios?.[0]?.curves?.[0]
      if (!curve?.points?.length) return [key, {}]
      const pts: { date: string; value: number }[] = curve.points
      const base = pts[0].value
      const normalised: Record<string, number> = {}
      for (const pt of pts) normalised[pt.date] = pt.value / base - 1
      if (normalised[from] == null) normalised[from] = 0
      return [key, normalised]
    }

    let cancelled = false
    setBenchmarkLoading(true)
    setBenchmarkCache({})
    Promise.all(marginKeys.map(fetchOne))
      .then(entries => { if (!cancelled) setBenchmarkCache(Object.fromEntries(entries)) })
      .catch(() => { if (!cancelled) setBenchmarkCache({}) })
      .finally(() => { if (!cancelled) setBenchmarkLoading(false) })
    return () => { cancelled = true }
  }, [selectedBenchmark, benchmarkBlock, savedPortfolios, resolvedFrom, resolvedTo])

  // ── Import XML files ──────────────────────────────────────────────────────
  async function handleXmlImport(files: FileList) {
    setIngesting(true)
    clearToast()
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
    clearToast()
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
    showInlineToast(msg, isError ? 'error' : 'ok', isError ? 6000 : 3000)
  }

  // ── Build chart rows ──────────────────────────────────────────────────────
  const baseRows = useMemo((): Record<string, any>[] => {
    if (!data?.dates.length) return []
    return data.dates.map((date, i) => {
      const returnValue = mode === 'twr'
        ? data.twrSeries[i]
        : mode === 'mwr'
        ? (data.mwrSeries?.[i] ?? null)
        : (data.positionSeries?.[i] ?? null)
      return {
        date: date.slice(5),
        fullDate: date,
        return: returnValue != null ? +(returnValue * 100).toFixed(4) : null,
        nav:    +data.navSeries[i].toFixed(2),
        margin: +(data.marginUtilSeries[i] * 100).toFixed(2),
      }
    })
  }, [data, mode])

  const benchmarkData = benchmarkCache[benchmarkMarginKey] ?? {}

  const rows = useMemo(() => {
    if (!selectedBenchmark || !baseRows.length) return baseRows
    return baseRows.map(row => {
      const bval = benchmarkData[row.fullDate]
      return { ...row, benchmark: bval != null ? +(bval * 100).toFixed(4) : null }
    })
  }, [baseRows, benchmarkData, selectedBenchmark])

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
    isAnimationActive: true,
    animationDuration: 400,
    animationEasing: 'ease-out' as const,
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
            {ingesting ? <>Fetching…<span className="btn-spinner" /></> : 'Fetch from IBKR'}
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
                <option value="none">No Margin</option>
                {benchmarkBlock.margins.map((m, i) => (
                  <option key={i} value={String(i)}>{m.ratio}% Margin</option>
                ))}
              </select>
            )}
            {benchmarkLoading && <span className="btn-spinner" />}
          </div>
        )}
      </div>

      {/* ── Toast ──────────────────────────────────────────────────────── */}
      {toast.msg && (
        <div style={{
          padding: '0.4rem 0.8rem', borderRadius: 4, marginBottom: '0.5rem',
          background: toast.type === 'error' ? 'var(--color-error, #5c1a1a)' : 'var(--color-success-bg, #1a4a2a)',
          color: toast.type === 'error' ? '#ffaaaa' : '#aaffcc',
          fontSize: '0.83rem',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '0.75rem',
        }}>
          <span>{toast.type === 'error' ? '⚠' : '✓'} {toast.msg}</span>
          <button
            type="button"
            aria-label="Dismiss notification"
            title="Dismiss"
            onClick={clearToast}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '1.35rem',
              height: '1.35rem',
              padding: 0,
              border: 'none',
              borderRadius: 999,
              background: 'transparent',
              color: 'currentColor',
              cursor: 'pointer',
              flexShrink: 0,
            }}
          >
            <X aria-hidden="true" size={14} strokeWidth={2.25} />
          </button>
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
        <div className="flex-query-guide">
          <div className="flex-query-guide-header">
            <span className="flex-query-guide-badge performance">Performance query</span>
            <h2>No performance data yet</h2>
          </div>
          <div className="flex-query-steps">
            <div><strong>1.</strong> In IBKR, open <strong>Reports - Flex Queries</strong> and create a query.</div>
            <div><strong>2.</strong> Set <strong>Format</strong> to <strong>XML</strong>.</div>
            <div><strong>3.</strong> Set <strong>Date Range</strong> to <strong>Last 365 Days</strong> and <strong>Breakout by Day</strong> to <strong>Yes</strong>.</div>
            <div><strong>4.</strong> In <strong>IB Config</strong>, fill <strong>Flex Web Service Token</strong> and <strong>Performance Query ID</strong>, then click <strong>Fetch from IBKR</strong>.</div>
          </div>
          <div className="flex-query-table-wrap">
            <table className="flex-query-field-table">
              <thead>
              <tr>
                <th>Flex section</th>
                <th>Required fields</th>
                <th>Used for</th>
              </tr>
              </thead>
              <tbody>
              <tr>
                <td><code>Net Asset Value (NAV) in Base</code></td>
                <td><code>cash</code>, <code>total</code></td>
                <td>Daily cash and NAV series</td>
              </tr>
              <tr>
                <td><code>Open Positions</code></td>
                <td><code>symbol</code>, <code>positionValue</code></td>
                <td>Daily position values and change detection</td>
              </tr>
              <tr>
                <td><code>Cash Transactions</code></td>
                <td><code>fxRateToBase</code>, <code>amount</code>, <code>type</code></td>
                <td>External cash-flow and MWR calculations</td>
              </tr>
              </tbody>
            </table>
          </div>
          <div className="flex-query-note">For history beyond 365 days, run the query manually with a custom date range and use <strong>Import XML</strong>. Overlapping dates are deduplicated.</div>
        </div>
      )}
      {loading && <div style={{ textAlign: 'center', padding: '2rem', opacity: 0.5 }}>Loading…<span className="btn-spinner" /></div>}
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
                dataKey="fullDate"
                tick={{ fill: textColor, fontSize: 11 }}
                interval={Math.max(1, Math.floor(rows.length / 8))}
                tickFormatter={(v: string) => v.slice(5)}
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
                formatter={(value: any, name: any) => {
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
                  yAxisId="left"
                  dataKey="benchmark"
                  name={selectedBenchmark}
                  stroke={BENCHMARK_COLORS[0]}
                  strokeDasharray="6 3"
                  {...commonLine}
                />
              )}

              <Brush
                dataKey="fullDate"
                height={24}
                stroke={gridColor}
                fill={isDark ? '#1a1a1a' : '#f8f8f8'}
                travellerWidth={6}
                tickFormatter={(v: string) => v.slice(5)}
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
