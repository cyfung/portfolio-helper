// ── BacktestPage.tsx — Full React port of backtest runner ─────────────────────

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush, ReferenceDot,
} from 'recharts'
import type { LegendPayload } from 'recharts'
import {
  BacktestPageHeader, RunButton, SavedPortfolioBlocksSection, ScenarioSetupControls,
} from '@/components/backtest/CommonBacktestSections'
import ImportDependenciesDialog from '@/components/backtest/ImportDependenciesDialog'
import TickerMappingControl from '@/components/backtest/TickerMappingControl'
import type { SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { useChartTheme } from '@/lib/chartTheme'
import { useChartContainerWidth } from '@/hooks/useChartContainerWidth'
import { useTransientToast } from '@/hooks/useTransientToast'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import {
  applyImportDependencyPreview,
  buildImportDependencyPreview,
  hasImportDependencyPreview,
  withPortfolioExportDependencies,
  type ImportDependencyPreview,
} from '@/lib/configImportExport'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, BacktestResults, emptyBlock, blockStateToAPIPortfolio,
  configToBlockState, PALETTE, cashflowStateFromSettings,
  cashflowToPayload, configToBlockInputLabel, DEFAULT_CASHFLOW_FREQUENCY,
  normalizeBlockSpreadInputs, startingBalanceToPayload,
} from '@/types/backtest'
import { ACCENT_LIGHT, ACCENT_DARK, scaleDash } from '@/lib/colorScheme'
import {
  buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR,
} from '@/lib/chartData'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import { fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'
import { validateDateRange } from '@/lib/dateRange'
import {
  applyTickerMappingsToPortfolio,
  loadTickerMappingSettings,
  selectedTickerMappingSet as resolveSelectedTickerMappingSet,
  TICKER_MAPPINGS_CHANGED_EVENT,
  type TickerMappingSettings,
} from '@/lib/tickerMappings'

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

type ChartRow = Record<string, unknown>

interface RealPortfolioSummary {
  slug: string
  name?: string | null
}

interface PortfolioDataResponse {
  allPortfolios?: RealPortfolioSummary[]
}

interface StoredBacktestConfig {
  fromDate?: string | null
  toDate?: string | null
  portfolios?: Record<string, unknown>[]
}

interface PerformanceIngestResponse {
  written?: number
  error?: string
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

const ACTION_MARKERS: Record<string, { label: string; short: string; color: string; defaultVisible?: boolean }> = {
  SELL_HIGH:           { label: 'Sell high',           short: 'SH', color: '#d94841' },
  BUY_LOW:             { label: 'Buy low',             short: 'BL', color: '#2f9e44' },
  PORTFOLIO_REBALANCE: { label: 'Portfolio rebalance', short: 'RB', color: '#7950f2', defaultVisible: false },
  MARGIN_REBALANCE:    { label: 'Margin rebalance',    short: 'MR', color: '#0ca678', defaultVisible: false },
  DRAWDOWN_MR:          { label: 'Drawdown MR',         short: 'DD-MR', color: '#6741d9', defaultVisible: false },
  DRAWDOWN_MR_EXIT:     { label: 'Drawdown MR exit',    short: 'DD-X', color: '#868e96', defaultVisible: false },
}
const ACTION_MARKER_RENDER_LIMIT = 350
type ActionPointChartKey = 'main' | 'drawdown' | 'recover' | 'margin'
const DEFAULT_ACTION_POINT_CHART_VISIBILITY: Record<ActionPointChartKey, boolean> = {
  main: false,
  drawdown: true,
  recover: false,
  margin: false,
}
const DEFAULT_FORCE_ACTION_POINT_CHART_DOTS: Record<ActionPointChartKey, boolean> = {
  main: false,
  drawdown: false,
  recover: false,
  margin: false,
}

function visibleActionPointGroups(
  actionPoints: { date: string; type: string }[] | undefined,
  visibleTypes: Set<string>,
  labels: string[],
) {
  if (!actionPoints?.length || visibleTypes.size === 0) return { markers: [], denseGroups: [] }
  const rowIndexByDate = new Map(labels.map((date, i) => [date, i]))
  const seen = new Set<string>()
  const points: { date: string; type: string; rowIndex: number }[] = []
  for (const point of actionPoints) {
    if (!visibleTypes.has(point.type)) continue
    const rowIndex = rowIndexByDate.get(point.date)
    if (rowIndex == null) continue
    const key = `${point.date}-${point.type}`
    if (seen.has(key)) continue
    seen.add(key)
    points.push({ date: point.date, type: point.type, rowIndex })
  }
  const byType = new Map<string, { date: string; type: string; rowIndex: number }[]>()
  for (const point of points) {
    const group = byType.get(point.type) ?? []
    group.push(point)
    byType.set(point.type, group)
  }
  const markers: { date: string; type: string; rowIndex: number }[] = []
  const denseGroups: { type: string; points: { date: string; type: string; rowIndex: number }[] }[] = []
  for (const [type, group] of byType) {
    if (group.length > ACTION_MARKER_RENDER_LIMIT) denseGroups.push({ type, points: group })
    else markers.push(...group)
  }
  return { markers, denseGroups }
}

function actionPointCount(actionPoints: { type: string }[] | undefined, type: string) {
  return actionPoints?.filter(point => point.type === type).length ?? 0
}

function averageMarginUtilization(points: { value: number }[] | undefined) {
  const values = points?.map(p => p.value).filter(Number.isFinite) ?? []
  return values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : null
}

function averageFinite(values: (number | null | undefined)[] | undefined) {
  const finiteValues = values?.filter((value): value is number => Number.isFinite(value)) ?? []
  return finiteValues.length > 0 ? finiteValues.reduce((sum, value) => sum + value, 0) / finiteValues.length : null
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
  marginUtilSeries: number[]
  navScaleFactor: number
}

interface BacktestViewState {
  results: BacktestResults | null
  realData: RealPortfolioData | null
  selected: Set<string>
  submittedPortfolios: SubmittedBacktestPortfolio[]
}

interface SubmittedBacktestPortfolio {
  includeNoMargin?: boolean
  marginStrategies?: { marginRatio?: number | null }[]
}

function realPortfolioDataFromResponse(d: Partial<RealPortfolioData>, navScaleFactor: number): RealPortfolioData {
  return {
    dates:            d.dates            ?? [],
    twrSeries:        d.twrSeries        ?? [],
    mwrSeries:        d.mwrSeries        ?? null,
    positionSeries:   d.positionSeries   ?? null,
    navSeries:        d.navSeries        ?? [],
    marginUtilSeries: d.marginUtilSeries ?? [],
    navScaleFactor,
  }
}

function withoutRealCurveKeys(selected: Set<string>) {
  return new Set([...selected].filter(key => !key.startsWith('real-')))
}

// ── Legend canvas line preview ─────────────────────────────────────────────────

function LegendLine({ color, strokeWidth, strokeDasharray }: { color: string; strokeWidth: number; strokeDasharray?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, 28, 10)
    ctx.strokeStyle = color
    ctx.lineWidth = strokeWidth
    ctx.setLineDash(strokeDasharray ? strokeDasharray.split(' ').map(Number) : [])
    ctx.beginPath()
    ctx.moveTo(2, 5)
    ctx.lineTo(26, 5)
    ctx.stroke()
  }, [color, strokeWidth, strokeDasharray])
  return <canvas ref={ref} width={28} height={10} style={{ display: 'inline-block', verticalAlign: 'middle' }} />
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function BacktestPage() {
  const [blocks, setBlocks]           = useState<BlockState[]>([0, 1, 2].map(emptyBlock))
  const [fromDate, setFromDate]       = useState('')
  const [toDate, setToDate]           = useState('')
  const [startingBalance, setStartingBalance]     = useState('10000')
  const [cashflowAmount, setCashflowAmount]       = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState(DEFAULT_CASHFLOW_FREQUENCY)
  const [tickerMappingSettings, setTickerMappingSettings] = useState<TickerMappingSettings>(() => loadTickerMappingSettings())
  const [importCode, setImportCode]               = useState('')
  const [configError, setConfigError] = useState('')
  const [pendingImport, setPendingImport] = useState<{ config: StoredBacktestConfig; preview: ImportDependencyPreview } | null>(null)
  const [importDependencyApplying, setImportDependencyApplying] = useState(false)
  const [importDependencyError, setImportDependencyError] = useState('')
  const [running, setRunning]         = useState(false)
  const [error, setError]             = useState('')
  const [viewState, setViewState]     = useState<BacktestViewState>({
    results: null,
    realData: null,
    selected: new Set(),
    submittedPortfolios: [],
  })
  const { results, realData, selected, submittedPortfolios } = viewState
  const [logScale, setLogScale]       = useState(false)
  const [scaleToNav, setScaleToNav]   = useState(true)
  const [visibleActionPointTypes, setVisibleActionPointTypes] = useState<Set<string>>(
    () => new Set(Object.entries(ACTION_MARKERS).filter(([, marker]) => marker.defaultVisible !== false).map(([type]) => type)),
  )
  const [actionPointChartVisibility, setActionPointChartVisibility] = useState<Record<ActionPointChartKey, boolean>>(
    () => ({ ...DEFAULT_ACTION_POINT_CHART_VISIBILITY }),
  )
  const [forceActionPointChartDots, setForceActionPointChartDots] = useState<Record<ActionPointChartKey, boolean>>(
    () => ({ ...DEFAULT_FORCE_ACTION_POINT_CHART_DOTS }),
  )
  const { toast: importToast, showToast: showImportToast } = useTransientToast()

  // Real portfolio overlay
  const [realPortfolios, setRealPortfolios] = useState<{ slug: string; name: string }[]>([])
  const [realSlug, setRealSlug]             = useState(() => localStorage.getItem('backtest-real-slug') ?? '')
  const [realIngesting, setRealIngesting]   = useState(false)
  const [realFetchNotice, setRealFetchNotice] = useState('')
  const realIngestingRef = useRef(false)
  const appConfig = usePortfolioStore(s => s.appConfig)
  const appConfigReady = !!appConfig
  const privacyNavScaleFactor = useMemo(() => {
    const pct = parseFloat(appConfig?.privacyScalePct ?? '')
    return appConfig?.privacyScaleEnabled && pct > 0 ? pct / 100 : 1
  }, [appConfig?.privacyScaleEnabled, appConfig?.privacyScalePct])
  const dateRangeError = validateDateRange(fromDate, toDate)
  const selectedTickerMappingSet = useMemo(
    () => resolveSelectedTickerMappingSet(tickerMappingSettings),
    [tickerMappingSettings],
  )

  useEffect(() => {
    const refreshTickerMappings = () => setTickerMappingSettings(loadTickerMappingSettings())
    window.addEventListener(TICKER_MAPPINGS_CHANGED_EVENT, refreshTickerMappings)
    return () => window.removeEventListener(TICKER_MAPPINGS_CHANGED_EVENT, refreshTickerMappings)
  }, [])

  const savedBarRef       = useRef<SavedPortfoliosBarRef>(null)
  const { chartWidth, chartContainerRef } = useChartContainerWidth()

  // Load portfolios for overlay selector
  useEffect(() => {
    fetch('/api/portfolio/data')
      .then(r => r.json())
      .then((d: PortfolioDataResponse) => {
        setRealPortfolios((d.allPortfolios ?? []).map(p => ({
          slug: p.slug,
          name: p.name || p.slug,
        })))
      })
      .catch(() => {})
  }, [])

  const fetchRealPortfolioData = useCallback(async (slug: string, signal?: AbortSignal) => {
    if (dateRangeError) throw new Error(dateRangeError)
    const params = [fromDate && `from=${fromDate}`, toDate && `to=${toDate}`].filter(Boolean).join('&')
    const r = await fetch(`/api/performance/chart/${slug}${params ? '?' + params : ''}`, { signal })
    if (!r.ok) throw new Error(`HTTP ${r.status}`)
    const d = await r.json()
    return realPortfolioDataFromResponse(d, privacyNavScaleFactor)
  }, [dateRangeError, fromDate, toDate, privacyNavScaleFactor])

  // Fetch real portfolio data when slug or date range changes
  useEffect(() => {
    if (!realSlug) {
      setViewState(prev => ({ ...prev, realData: null, selected: withoutRealCurveKeys(prev.selected) }))
      return
    }
    if (!appConfigReady) return
    if (dateRangeError) return
    const ac = new AbortController()
    fetchRealPortfolioData(realSlug, ac.signal)
      .then(realData => {
        if (ac.signal.aborted) return
        setViewState(prev => ({
          ...prev,
          realData,
        }))
      })
      .catch(() => {
        if (!ac.signal.aborted) setViewState(prev => ({ ...prev, realData: null }))
      })
    return () => ac.abort()
  }, [realSlug, appConfigReady, dateRangeError, fetchRealPortfolioData])

  // Persist real portfolio selection
  useEffect(() => {
    localStorage.setItem('backtest-real-slug', realSlug)
  }, [realSlug])

  async function handleFetchRealFromIbkr(slug: string) {
    if (!slug || realIngestingRef.current) return
    if (dateRangeError) {
      setError(dateRangeError)
      return
    }
    const portfolioName = realPortfolios.find(p => p.slug === slug)?.name ?? slug
    realIngestingRef.current = true
    setRealIngesting(true)
    setRealFetchNotice(`Fetching ${portfolioName} from IBKR...`)
    setError('')
    try {
      const r = await fetch(`/api/performance/ingest/${slug}`, { method: 'POST' })
      const d: PerformanceIngestResponse = await r.json()
      if (!r.ok) {
        setRealFetchNotice('')
        setError(`Fetch from IBKR failed for ${portfolioName}: ${d.error ?? `HTTP ${r.status}`}`)
        return
      }
      const newRealData = appConfigReady
        ? await fetchRealPortfolioData(slug)
        : null
      if (slug === realSlug) {
        setViewState(prev => ({
          ...prev,
          realData: newRealData ?? prev.realData,
        }))
      }
      setRealFetchNotice(`Fetched ${d.written} new snapshot(s).`)
    } catch (e: unknown) {
      setRealFetchNotice('')
      setError(`Fetch from IBKR failed for ${portfolioName}: ${errorMessage(e)}`)
    } finally {
      realIngestingRef.current = false
      setRealIngesting(false)
    }
  }

  // Restore settings on mount
  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: StoredBacktestConfig) => {
        if (!req.portfolios) return
        const portfolios = req.portfolios
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate)   setToDate(req.toDate)
        const cashflowState = cashflowStateFromSettings(req)
        if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
        if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
        if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
        setBlocks(prev => {
          const next = [...prev]
          portfolios.forEach((p, i) => {
            if (i < 3) next[i] = configToBlockState(p, configToBlockInputLabel(p, i))
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

  const selectedActionCurve = useMemo(() => {
    if (!results || selected.size !== 1) return null
    const key = [...selected][0]
    const [piText, ciText] = key.split('-')
    const pi = parseInt(piText, 10)
    const ci = parseInt(ciText, 10)
    if (!Number.isFinite(pi) || !Number.isFinite(ci)) return null
    const curve = results.portfolios[pi]?.curves[ci]
    if (!curve?.actionPoints?.length) return null
    return { dataKey: `p${pi}-c${ci}`, curve }
  }, [results, selected])

  const REAL_LABEL_KEY: Record<string, string> = {
    'Real - NAV':      'real-nav',
    'Real - TWR':      'real-twr',
    'Real - MWR':      'real-mwr',
    'Real - Position': 'real-pos',
  }

  const theme  = useChartTheme()
  const { isDark, gridColor, textColor } = theme

  const displayCurveLabel = useCallback((portfolioIndex: number, curveIndex: number, label: string) => {
    const submitted = submittedPortfolios[portfolioIndex]
    const noMarginOffset = submitted?.includeNoMargin === false ? 0 : 1
    const marginIndex = curveIndex - noMarginOffset
    const marginRatio = submitted?.marginStrategies?.[marginIndex]?.marginRatio
    if (marginIndex < 0 || marginRatio == null || !Number.isFinite(marginRatio) || /\d+(?:\.\d+)?%/.test(label)) {
      return label
    }

    const marginPct = marginRatio * 100
    const formattedMargin = `${Number.isInteger(marginPct) ? marginPct.toFixed(0) : marginPct.toFixed(2).replace(/\.?0+$/, '')}%`
    return label.replace(/^(Margin\s+\d+)/, `$1 ${formattedMargin}`)
  }, [submittedPortfolios])

  const displayResults = useMemo(() => {
    if (!results) return null
    return {
      ...results,
      portfolios: results.portfolios.map((portfolio, pi) => ({
        ...portfolio,
        curves: portfolio.curves.map((curve, ci) => ({
          ...curve,
          label: displayCurveLabel(pi, ci, curve.label),
        })),
      })),
    }
  }, [displayCurveLabel, results])

  // ── Computed chart data ───────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!displayResults) return null
    const labels        = buildCommonLabels(displayResults)
    const backtestStart = displayResults.portfolios[0]?.curves[0]?.points[0]?.value ?? 1
    const navDisplayFactor = realData ? privacyNavScaleFactor / realData.navScaleFactor : 1
    const realNavSeries = realData?.navSeries.map(v => v * navDisplayFactor) ?? []

    // Build date→index lookup for real portfolio data
    const realDateIndex: Map<string, number> = realData?.dates.length
      ? new Map(realData.dates.map((d, i) => [d, i]))
      : new Map()

    // NAV scale is only valid when the real portfolio existed at the FIRST backtest date.
    // If the portfolio started later, navStart = 0 → scaling disabled.
    const firstLabelRealIdx = realData?.dates.length ? realDateIndex.get(labels[0]) : undefined
    const navStart          = firstLabelRealIdx != null ? (realNavSeries[firstLabelRealIdx] ?? 0) : 0
    const shouldScaleToNav  = scaleToNav && navStart > 0 && !!realSlug

    // Backtest curves: when shouldScaleToNav, scale each so its first-date value = navStart
    const mainData = buildRechartsData(displayResults, labels, selected, pts => {
      if (shouldScaleToNav) {
        const startVal = pts[0]?.value ?? 1
        return pts.map(p => p.value * (navStart / startVal))
      }
      return pts.map(p => p.value)
    })

    // Per-curve scale factors for stats table End Value
    const curveScaleFactors = new Map<string, number>()
    displayResults.portfolios.forEach((portfolio, pi) => {
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
      refStart = mainData.rows[firstOverlapLabelIdx][mainData.datasets[0].dataKey] ?? backtestStart
    }

    // Normalisation bases: return series value at the first overlap date.
    // Dividing (1 + series[i]) by (1 + base) makes the line start at 1.0 at that date,
    // then multiply by refStart to put it on the correct Y axis value.
    const hasRealOverlap = realData != null && firstOverlapRealIdx >= 0
    const twrBase = hasRealOverlap ? (realData.twrSeries[firstOverlapRealIdx]        ?? 0) : 0
    const mwrBase = hasRealOverlap ? (realData.mwrSeries?.[firstOverlapRealIdx]      ?? 0) : 0
    const posBase = hasRealOverlap ? (realData.positionSeries?.[firstOverlapRealIdx] ?? 0) : 0

    // Inject real portfolio columns into chart rows
    if (realData?.dates.length) {
      for (const row of mainData.rows) {
        const ri = realDateIndex.get(row.x as string)
        if (ri == null) continue

        const nav = realNavSeries[ri]
        if (nav != null) row['Real - NAV'] = +nav.toFixed(4)

        const twr = realData.twrSeries[ri]
        if (twr != null) row['Real - TWR'] = +(refStart * (1 + twr) / (1 + twrBase)).toFixed(4)

        const mwr = realData.mwrSeries?.[ri]
        if (mwr != null) row['Real - MWR'] = +(refStart * (1 + mwr) / (1 + mwrBase)).toFixed(4)

        const pos = realData.positionSeries?.[ri]
        if (pos != null) row['Real - Position'] = +(refStart * (1 + pos) / (1 + posBase)).toFixed(4)
      }
    }

    // Stats computed from the overlap window (same period as what the chart shows)
    const od = hasRealOverlap ? realData.dates.slice(firstOverlapRealIdx)    : []
    const nv = firstOverlapRealIdx >= 0 ? realNavSeries.slice(firstOverlapRealIdx) : []
    const tv = hasRealOverlap
      ? realData.twrSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + twrBase)) : []
    const mv = realData?.mwrSeries && firstOverlapRealIdx >= 0
      ? realData.mwrSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + mwrBase)) : null
    const pv = realData?.positionSeries && firstOverlapRealIdx >= 0
      ? realData.positionSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + posBase)) : null
    const realAvgMargin = firstOverlapRealIdx >= 0
      ? averageFinite(realData?.marginUtilSeries.slice(firstOverlapRealIdx))
      : null

    const realStats = realData?.dates.length ? {
      nav: computeSeriesStats(od, nv),
      twr: computeSeriesStats(od, tv),
      mwr: mv ? computeSeriesStats(od, mv) : null,
      pos: pv ? computeSeriesStats(od, pv) : null,
      navAvgMargin: realAvgMargin,
    } : null

    const ddData  = buildRechartsData(displayResults, labels, selected, computeDrawdown)
    const rtrData = buildRechartsData(displayResults, labels, selected, computeRTR)

    // Inject real portfolio DD/RTR series
    if (realData?.dates.length) {
      const ac = isDark ? ACCENT_DARK : ACCENT_LIGHT

      const injectDDRTR = (vals: number[], dateOffset: number, key: string, color: string, strokeDasharray?: string) => {
        let ddPeak = -Infinity
        const ddVals = vals.map(v => { if (v > ddPeak) ddPeak = v; return ddPeak > 0 ? (v / ddPeak) - 1 : null })
        let rtrPeak = -Infinity
        const rtrVals = vals.map(v => { if (v > rtrPeak) rtrPeak = v; return v > 0 ? rtrPeak / v : null })
        for (const row of ddData.rows) {
          const ri = realDateIndex.get(row.x as string)
          if (ri == null || ri < dateOffset) continue
          const dd = ddVals[ri - dateOffset]
          if (dd != null) row[key] = +dd.toFixed(6)
        }
        for (const row of rtrData.rows) {
          const ri = realDateIndex.get(row.x as string)
          if (ri == null || ri < dateOffset) continue
          const rtr = rtrVals[ri - dateOffset]
          if (rtr != null) row[key] = +rtr.toFixed(6)
        }
        const dataset = { dataKey: key, label: key, color, strokeWidth: 2, ...(strokeDasharray ? { strokeDasharray } : {}) }
        ddData.datasets.push(dataset)
        rtrData.datasets.push({ ...dataset })
      }

      if (realNavSeries.length)
        injectDDRTR(realNavSeries, 0, 'Real - NAV', ac[0])
      if (firstOverlapRealIdx >= 0 && realData.twrSeries.length)
        injectDDRTR(
          realData.twrSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + twrBase)),
          firstOverlapRealIdx, 'Real - TWR', ac[1], '8 4',
        )
      if (firstOverlapRealIdx >= 0 && realData.mwrSeries != null)
        injectDDRTR(
          realData.mwrSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + mwrBase)),
          firstOverlapRealIdx, 'Real - MWR', ac[2], '4 3',
        )
      if (firstOverlapRealIdx >= 0 && realData.positionSeries != null)
        injectDDRTR(
          realData.positionSeries.slice(firstOverlapRealIdx).map(v => refStart * (1 + v) / (1 + posBase)),
          firstOverlapRealIdx, 'Real - Position', ac[3],
        )
    }

    const marginData = buildRechartsData(displayResults, labels, selected, pts => pts.map(p => p.value), c => c.marginPoints)

    return {
      labels,
      mainData,
      ddData,
      rtrData,
      marginData,
      realStats,
      curveScaleFactors,
      navStart,
      shouldScaleToNav,
    }
  }, [displayResults, selected, realData, scaleToNav, realSlug, isDark, privacyNavScaleFactor])
  const selectedActionPointGroups = useMemo(() => (
    visibleActionPointGroups(selectedActionCurve?.curve.actionPoints, visibleActionPointTypes, chartData?.labels ?? [])
  ), [chartData?.labels, selectedActionCurve, visibleActionPointTypes])

  // ── Run ───────────────────────────────────────────────────────────────────

  async function handleRun() {
    setError('')
    if (dateRangeError) {
      setError(dateRangeError)
      return
    }
    const runBlocks = blocks.map(normalizeBlockSpreadInputs)
    if (runBlocks.some((block, i) => block !== blocks[i])) setBlocks(runBlocks)
    const settingsPortfolios = runBlocks.map((b, i) => blockStateToAPIPortfolio(b, i))
    let portfolios
    try {
      const latestSavedPortfolios = await fetchSavedPortfolios()
      portfolios = runBlocks
        .map((b, i) => resolvedBlockStateToAPIPortfolio(b, i, latestSavedPortfolios))
        .map(p => applyTickerMappingsToPortfolio(p, selectedTickerMappingSet))
        .filter(p => p.tickers.length > 0)
    } catch (e: unknown) {
      setError(errorMessage(e) || 'Unable to resolve saved portfolio references.')
      return
    }
    if (portfolios.length === 0) {
      setError('Add at least one ticker with a positive weight to any portfolio block.')
      return
    }
    if (portfolios.some(p => !p.includeNoMargin && p.marginStrategies.length === 0 && (p.rebalanceStrategies?.length ?? 0) === 0)) {
      setError('Each portfolio must have Unlevered enabled or at least one margin or rebalance strategy row.')
      return
    }
    setRunning(true)
    try {
      const params = [fromDate && `from=${fromDate}`, toDate && `to=${toDate}`].filter(Boolean).join('&')
      const [backtestRes, realRes] = await Promise.all([
        fetch('/api/backtest/run', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            fromDate: fromDate || null,
            toDate: toDate || null,
            startingBalance: startingBalanceToPayload(startingBalance),
            portfolios,
            settingsPortfolios,
            cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
          }),
        }),
        realSlug
          ? fetch(`/api/performance/chart/${realSlug}${params ? '?' + params : ''}`)
          : Promise.resolve(null),
      ])
      const data: BacktestResults = await backtestRes.json()
      if (!backtestRes.ok || data.error) { setError(data.error || `Server error ${backtestRes.status}`); return }

      let newRealData: RealPortfolioData | null = realSlug ? realData : null
      if (realRes) {
        try {
          const d = await realRes.json()
          newRealData = realPortfolioDataFromResponse(d, privacyNavScaleFactor)
        } catch { newRealData = null }
      }

      const defaultKeys = data.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))
      if (newRealData?.twrSeries?.length) defaultKeys.push('real-twr')
      setViewState({
        results: data,
        realData: newRealData,
        selected: new Set(defaultKeys),
        submittedPortfolios: portfolios,
      })
    } catch (e: unknown) {
      setError('Request failed: ' + errorMessage(e))
    } finally {
      setRunning(false)
    }
  }

  // ── Import / Export ───────────────────────────────────────────────────────

  async function handleExport() {
    const exportBlocks = blocks.map(normalizeBlockSpreadInputs)
    if (exportBlocks.some((block, i) => block !== blocks[i])) setBlocks(exportBlocks)
    const portfolios = exportBlocks.map((b, i) => blockStateToAPIPortfolio(b, i))
    const code = await compressToCode(await withPortfolioExportDependencies({
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: startingBalanceToPayload(startingBalance),
      portfolios,
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
    }, portfolios))
    setImportCode(code)
    try {
      await navigator.clipboard.writeText(code)
      showImportToast('Export code copied.')
    } catch {
      showImportToast('Export code generated.')
    }
  }

  function applyImportedConfig(req: StoredBacktestConfig) {
    if (req.fromDate) setFromDate(req.fromDate)
    if (req.toDate)   setToDate(req.toDate)
    const cashflowState = cashflowStateFromSettings(req)
    if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
    if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
    if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
    if (req.portfolios) {
      const portfolios = req.portfolios
      setBlocks(prev => {
        const next = [...prev]
        portfolios.forEach((p, i) => {
          if (i < 3) next[i] = configToBlockState(p, configToBlockInputLabel(p, i))
        })
        return next
      })
    }
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const req = await decompressFromCode(importCode.trim()) as StoredBacktestConfig
      const preview = await buildImportDependencyPreview(req as Record<string, unknown>)
      if (hasImportDependencyPreview(preview)) {
        setPendingImport({ config: req, preview })
        setImportDependencyError('')
        setConfigError('')
        return
      }
      applyImportedConfig(req)
      showImportToast('Import complete.')
      setConfigError('')
    } catch {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }

  async function confirmPendingImport(previewArg?: ImportDependencyPreview, configArg?: StoredBacktestConfig) {
    if (!pendingImport || importDependencyApplying) return
    const preview = previewArg ?? pendingImport.preview
    const config = configArg ?? pendingImport.config
    setImportDependencyApplying(true)
    setImportDependencyError('')
    try {
      await applyImportDependencyPreview(preview)
      refreshSaved()
      applyImportedConfig(config)
      showImportToast('Import complete.')
      setPendingImport(null)
      setConfigError('')
    } catch (e: unknown) {
      setImportDependencyError(errorMessage(e))
    } finally {
      setImportDependencyApplying(false)
    }
  }

  // ── Curve toggle ──────────────────────────────────────────────────────────

  function toggleCurve(key: string, checked: boolean) {
    setViewState(prev => {
      const selected = new Set(prev.selected)
      if (checked) selected.add(key)
      else selected.delete(key)
      return { ...prev, selected }
    })
  }

  function toggleAll(checked: boolean) {
    setViewState(prev => ({ ...prev, selected: checked ? new Set(allKeys) : new Set() }))
  }

  function toggleActionPointType(type: string, checked: boolean) {
    setVisibleActionPointTypes(prev => {
      const next = new Set(prev)
      if (checked) next.add(type)
      else next.delete(type)
      return next
    })
  }

  function toggleActionPointChart(chart: ActionPointChartKey, checked: boolean) {
    setActionPointChartVisibility(prev => ({ ...prev, [chart]: checked }))
  }

  function toggleForceActionPointChartDots(chart: ActionPointChartKey, checked: boolean) {
    setForceActionPointChartDots(prev => ({ ...prev, [chart]: checked }))
  }

  const updateBlock = useCallback((i: number, s: BlockState) =>
    setBlocks(prev => { const n = [...prev]; n[i] = s; return n }),
    [],
  )
  const refreshSaved = useCallback(() => {
    savedBarRef.current?.refresh()
  }, [])

  const numPoints      = chartData?.labels.length ?? 2
  const pixelsPerPoint = chartWidth / Math.max(numPoints - 1, 1)

  // ── Chart helpers ─────────────────────────────────────────────────────────

  const makeTooltip = (valueFmt: (v: number) => string, labelFmt?: (l: unknown) => string) =>
    makeRechartsTooltip(theme, valueFmt, labelFmt)

  const commonLineProps = {
    type:              'monotone' as const,
    dot:               false as const,
    activeDot:         { r: 4 },
    connectNulls:      false,
    isAnimationActive: false,
  }

  const renderActionDotControls = (chart: ActionPointChartKey) => {
    if (!selectedActionCurve) return null
    const dotsEnabled = actionPointChartVisibility[chart]
    const hasDenseGroups = selectedActionPointGroups.denseGroups.length > 0
    return (
      <div className="chart-dot-controls" aria-label="Action point display">
        <label>
          <input
            type="checkbox"
            checked={dotsEnabled}
            onChange={e => toggleActionPointChart(chart, e.target.checked)}
          />
          <span>Dots</span>
        </label>
        {dotsEnabled && hasDenseGroups && (
          <label title="Render dense action points as chart dots">
            <input
              type="checkbox"
              checked={forceActionPointChartDots[chart]}
              onChange={e => toggleForceActionPointChartDots(chart, e.target.checked)}
            />
            <span>Force dots</span>
          </label>
        )}
      </div>
    )
  }

  const renderActionMarkers = (rows: ChartRow[], chart: ActionPointChartKey, yAxisId?: string) => {
    if (!selectedActionCurve || !actionPointChartVisibility[chart]) return null
    const points = forceActionPointChartDots[chart]
      ? selectedActionPointGroups.markers.concat(selectedActionPointGroups.denseGroups.flatMap(group => group.points))
      : selectedActionPointGroups.markers
    return points.map((point, i) => {
      const marker = ACTION_MARKERS[point.type]
      if (!marker) return null
      const row = rows[point.rowIndex]
      const y = row?.[selectedActionCurve.dataKey]
      if (typeof y !== 'number' || !Number.isFinite(y)) return null

      const duplicateKey = `${point.date}-${point.type}`
      return (
        <ReferenceDot
          key={`${duplicateKey}-${i}`}
          {...(yAxisId ? { yAxisId } : {})}
          x={point.date}
          y={y}
          r={5}
          fill={marker.color}
          stroke={isDark ? '#111' : '#fff'}
          strokeWidth={2}
          ifOverflow="extendDomain"
          label={{ value: marker.short, position: 'top', fill: marker.color, fontSize: 10 }}
        />
      )
    })
  }

  const renderDenseActionStrips = (chart: ActionPointChartKey) => {
    if (
      !chartData?.labels.length ||
      !actionPointChartVisibility[chart] ||
      forceActionPointChartDots[chart] ||
      selectedActionPointGroups.denseGroups.length === 0
    ) return null
    const maxX = Math.max(chartData.labels.length - 1, 1)
    return (
      <div className="chart-action-density" aria-label="Dense action point timeline">
        {selectedActionPointGroups.denseGroups.map(group => {
          const marker = ACTION_MARKERS[group.type]
          if (!marker) return null
          const path = group.points.map(point => {
            const x = (point.rowIndex / maxX) * 1000
            return `M ${x.toFixed(2)} 0 V 10`
          }).join(' ')
          return (
            <div className="chart-action-density-row" key={group.type}>
              <span style={{ color: marker.color }}>{marker.short}</span>
              <svg viewBox="0 0 1000 10" preserveAspectRatio="none" aria-hidden="true">
                <path d={path} stroke={marker.color} strokeWidth="1.2" vectorEffect="non-scaling-stroke" />
              </svg>
              <span>{group.points.length}</span>
            </div>
          )
        })}
      </div>
    )
  }

  const shouldScaleToNav = chartData?.shouldScaleToNav ?? false
  const showNavScaleBtn  = !!realSlug && (chartData?.navStart ?? 0) > 0
  const showNavLine      = !!realSlug && !!(realData?.navSeries.length) && showLine('real-nav')
  // NAV uses a secondary right axis only when not scaling (values are in different dollar range)
  const navSecondAxis    = showNavLine && !shouldScaleToNav
  const chartRightMargin = navSecondAxis ? 80 : 16

  // Build per-label metadata for canvas legend rendering
  const legendMetaMap = useMemo(() => {
    const map = new Map<string, { color: string; strokeWidth: number; strokeDasharray?: string }>()
    ;[...(chartData?.mainData.datasets ?? []), ...(chartData?.ddData.datasets ?? []), ...(chartData?.rtrData.datasets ?? [])]
      .forEach(ds => { if (!map.has(ds.label)) map.set(ds.label, { color: ds.color, strokeWidth: ds.strokeWidth ?? 2, strokeDasharray: ds.strokeDasharray || undefined }) })
    // Standalone real series rendered separately — include their exact dash/width
    const ac = isDark ? ACCENT_DARK : ACCENT_LIGHT
    map.set('Real - NAV',      { color: ac[0], strokeWidth: 2 })
    map.set('Real - TWR',      { color: ac[1], strokeWidth: 2, strokeDasharray: '8 4' })
    map.set('Real - MWR',      { color: ac[2], strokeWidth: 2, strokeDasharray: '4 3' })
    map.set('Real - Position', { color: ac[3], strokeWidth: 2 })
    return map
  }, [chartData, isDark])

  const renderLegend = (props: { payload?: ReadonlyArray<LegendPayload> }) => {
    const { payload } = props
    if (!payload?.length) return null
    return (
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem 0.8rem', fontSize: '0.78em', color: textColor, padding: '4px 8px 0' }}>
        {payload.map((entry, i) => {
          const value = entry.value ?? ''
          const meta = legendMetaMap.get(value)
          const color = meta?.color ?? entry.color ?? textColor
          const sw    = meta?.strokeWidth ?? 2
          const dash  = meta?.strokeDasharray
          return (
            <span key={`${value}-${i}`} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
              <LegendLine color={color} strokeWidth={sw} strokeDasharray={dash} />
              <span>{value}</span>
            </span>
          )
        })}
      </div>
    )
  }

  // Stats table stat row renderer helper
  const realStatRow = (
    key: string,
    label: string,
    stats: SeriesStats | null | undefined,
    color: string,
    avgMargin?: number | null,
  ) => {
    if (!stats) return null
    return (
      <tr key={key}>
        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
        <td style={{ color }}>{label}</td>
        <td>{money(stats.endingValue)}</td>
        <td>{pct(stats.cagr)}</td>
        <td>{pct(-stats.maxDrawdown)}</td>
        <td>{dur(stats.longestDrawdownDays)}</td>
        <td>{pct(stats.annualVolatility)}</td>
        <td>{fmt2(stats.sharpe)}</td>
        <td>{pct(stats.ulcerIndex)}</td>
        <td>{fmt2(stats.upi)}</td>
        <td>{avgMargin == null ? '-' : pct(avgMargin)}</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
      </tr>
    )
  }

  return (
    <div className="container">
      <BacktestPageHeader active="/backtest" />
      <div className={`config-status config-status-${importToast.type}${importToast.msg ? ' visible' : ''}`}>
        {importToast.msg}
      </div>

      <div className="backtest-form-card">
        <ScenarioSetupControls
          idPrefix="backtest"
          fromLabel="From Date"
          fromInputId="from-date"
          fromDate={fromDate}
          toLabel="To Date"
          toInputId="to-date"
          toDate={toDate}
          importInputId="backtest-import-code"
          importCode={importCode}
          configError={configError}
          dateRangeError={dateRangeError}
          startingBalance={startingBalance}
          cashflowAmount={cashflowAmount}
          cashflowFrequency={cashflowFrequency}
          onFromDateChange={setFromDate}
          onToDateChange={setToDate}
          onImportCodeChange={setImportCode}
          onImport={handleImport}
          onExport={handleExport}
          onStartingBalanceChange={setStartingBalance}
          onCashflowAmountChange={setCashflowAmount}
          onCashflowFrequencyChange={setCashflowFrequency}
        />

        <TickerMappingControl
          idPrefix="backtest"
          value={tickerMappingSettings}
          onChange={setTickerMappingSettings}
          onExportCode={setImportCode}
          onToast={showImportToast}
        />

        {realPortfolios.length > 0 && (
          <div className="backtest-section" style={{ marginTop: '0.5rem' }}>
            <label htmlFor="real-portfolio-select">Real Portfolio Overlay</label>
            <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center', marginTop: '0.25rem' }}>
              <select
                id="real-portfolio-select"
                value={realSlug}
                onChange={e => {
                  setRealSlug(e.target.value)
                  setRealFetchNotice('')
                }}
                style={{ width: 'auto' }}
              >
                <option value="">— none —</option>
                {realPortfolios.map(p => (
                  <option key={p.slug} value={p.slug}>{p.name}</option>
                ))}
              </select>
              <button
                className="backtest-config-btn"
                type="button"
                style={{ fontSize: '0.8rem', padding: '0.25rem 0.7rem', whiteSpace: 'nowrap' }}
                onClick={() => handleFetchRealFromIbkr(realSlug)}
                disabled={!realSlug || realIngesting || !!dateRangeError}
              >
                {realIngesting ? <>Fetching…<span className="btn-spinner" /></> : 'Fetch from IBKR'}
              </button>
            </div>
            {realFetchNotice && (
              <div style={{ marginTop: '0.25rem', fontSize: '0.78rem', color: 'var(--color-text-tertiary)' }}>
                {realFetchNotice}
              </div>
            )}
          </div>
        )}

        <SavedPortfolioBlocksSection
          savedBarRef={savedBarRef}
          blocks={blocks}
          onBlockChange={updateBlock}
          onSavedRefresh={refreshSaved}
          showSavedStrategies
        />

        <RunButton label="Run Backtest" running={running} disabled={running || !!dateRangeError} onClick={handleRun} />
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {!!displayResults?.warnings?.length && (
        <div className="backtest-error">
          {displayResults.warnings.map((warning, i) => (
            <div key={i}>{warning}</div>
          ))}
        </div>
      )}

      {displayResults && chartData && (
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
                  <th title="Average margin utilization">Avg Margin</th>
                  <th title="# buy-low margin triggers">BL</th>
                  <th title="# sell-high margin triggers">SH</th>
                  <th title="# buy-dip action points">BD</th>
                  <th title="# sell-surge action points">SS</th>
                </tr>
              </thead>
              <tbody>
                {displayResults.portfolios.flatMap((portfolio, pi) =>
                  portfolio.curves.map((curve, ci) => {
                    const key    = `${pi}-${ci}`
                    const s      = curve.stats
                    const factor = chartData.curveScaleFactors.get(key) ?? 1
                    const avgMargin = averageMarginUtilization(curve.marginPoints)
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td style={{ color: PALETTE[pi % PALETTE.length][ci % PALETTE[pi % PALETTE.length].length] }}>{portfolio.label} – {curve.label}</td>
                        <td>{money(s.endingValue * factor)}</td>
                        <td>{pct(s.cagr)}</td>
                        <td>{pct(s.maxDrawdown)}</td>
                        <td>{dur(s.longestDrawdownDays)}</td>
                        <td>{pct(s.annualVolatility)}</td>
                        <td>{fmt2(s.sharpe)}</td>
                        <td>{pct(s.ulcerIndex)}</td>
                        <td>{fmt2(s.upi)}</td>
                        <td>{avgMargin == null ? '–' : pct(avgMargin)}</td>
                        <td>{s.marginLowerTriggers != null ? String(s.marginLowerTriggers) : String(actionPointCount(curve.actionPoints, 'BUY_LOW'))}</td>
                        <td>{s.marginUpperTriggers != null ? String(s.marginUpperTriggers) : String(actionPointCount(curve.actionPoints, 'SELL_HIGH'))}</td>
                        <td>{actionPointCount(curve.actionPoints, 'BUY_DIP')}</td>
                        <td>{actionPointCount(curve.actionPoints, 'SELL_SURGE')}</td>
                      </tr>
                    )
                  })
                )}
                {realSlug && chartData.realStats && (() => {
                  const rs = chartData.realStats!
                  const ac = isDark ? ACCENT_DARK : ACCENT_LIGHT
                  return (
                    <>
                      {realStatRow('real-nav', 'Real - NAV',      rs.nav, ac[0], rs.navAvgMargin)}
                      {realStatRow('real-twr', 'Real - TWR',      rs.twr, ac[1])}
                      {rs.mwr && realStatRow('real-mwr', 'Real - MWR',      rs.mwr, ac[2])}
                      {rs.pos && realStatRow('real-pos', 'Real - Position', rs.pos, ac[3])}
                    </>
                  )
                })()}
              </tbody>
            </table>
          </div>

          {/* Portfolio Value chart */}
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Portfolio Value</div>
            {renderActionDotControls('main')}
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.4rem', marginBottom: '0.25rem', flexWrap: 'wrap' }}>
            {selectedActionCurve && (
              <div className="chart-action-filter" aria-label="Action point type filters">
                <span>Points</span>
                {Object.entries(ACTION_MARKERS).map(([type, marker]) => (
                  <label key={type}>
                    <input
                      type="checkbox"
                      checked={visibleActionPointTypes.has(type)}
                      onChange={e => toggleActionPointType(type, e.target.checked)}
                    />
                    <span style={{ color: marker.color }}>{marker.short}</span>
                  </label>
                ))}
              </div>
            )}
            <button
              className={`chart-scale-toggle${logScale ? ' active' : ''}`}
              type="button"
              style={{ position: 'static' }}
              onClick={() => setLogScale(l => !l)}
            >
              Log
            </button>
            {showNavScaleBtn && (
              <button
                className={`chart-scale-toggle${scaleToNav ? ' active' : ''}`}
                type="button"
                style={{ position: 'static' }}
                onClick={() => setScaleToNav(s => !s)}
              >
                NAV Scale
              </button>
            )}
          </div>
          {renderDenseActionStrips('main')}
          <div className="backtest-chart-container" ref={chartContainerRef}>
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
                    domain={['auto', 'auto']}
                    tick={{ fill: textColor, fontSize: 11 }}
                    tickFormatter={v => '$' + Number(v).toFixed(0)}
                    width={72}
                    label={{ value: 'NAV', angle: 90, position: 'insideRight', fill: isDark ? ACCENT_DARK[0] : ACCENT_LIGHT[0], fontSize: 10, dy: -20 }}
                  />
                )}
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(2))} />
                <Legend content={renderLegend} />

                {/* Backtest portfolio curves */}
                {chartData.mainData.datasets.map(ds => (
                  <Line key={ds.dataKey} {...commonLineProps} yAxisId="main" dataKey={ds.dataKey} name={ds.label} stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                ))}

                {/* Real portfolio lines */}
                {realSlug && realData?.dates.length ? (() => {
                  const ac = isDark ? ACCENT_DARK : ACCENT_LIGHT
                  return (
                    <>
                      {showLine('real-nav') && (
                        <Line
                          yAxisId={shouldScaleToNav ? 'main' : 'nav-right'}
                          dataKey="Real - NAV"
                          stroke={ac[0]}
                          strokeWidth={2}
                          strokeDasharray={scaleDash('4 2', pixelsPerPoint)}
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
                          dataKey="Real - TWR"
                          stroke={ac[1]}
                          strokeWidth={2}
                          strokeDasharray={scaleDash('4 2', pixelsPerPoint)}
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
                          dataKey="Real - MWR"
                          stroke={ac[2]}
                          strokeWidth={2}
                          strokeDasharray={scaleDash('4 2', pixelsPerPoint)}
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
                          dataKey="Real - Position"
                          stroke={ac[3]}
                          strokeWidth={2}
                          strokeDasharray={scaleDash('4 2', pixelsPerPoint)}
                          dot={false}
                          activeDot={{ r: 4 }}
                          connectNulls={false}
                          isAnimationActive={false}
                          type="monotone"
                        />
                      )}
                    </>
                  )
                })() : null}

                {renderActionMarkers(chartData.mainData.rows, 'main', 'main')}
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
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Drawdown</div>
            {renderActionDotControls('drawdown')}
          </div>
          {renderDenseActionStrips('drawdown')}
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
                <Legend content={renderLegend} />
                {chartData.ddData.datasets
                  .filter(ds => { const k = REAL_LABEL_KEY[ds.label]; return !k || showLine(k) })
                  .map(ds => (
                    <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label} stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                  ))}
                {renderActionMarkers(chartData.ddData.rows, 'drawdown')}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* RTR chart */}
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Return Required to Recover</div>
            {renderActionDotControls('recover')}
          </div>
          {renderDenseActionStrips('recover')}
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
                <Legend content={renderLegend} />
                {chartData.rtrData.datasets
                  .filter(ds => { const k = REAL_LABEL_KEY[ds.label]; return !k || showLine(k) })
                  .map(ds => (
                    <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label} stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                  ))}
                {renderActionMarkers(chartData.rtrData.rows, 'recover')}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* Margin Utilization chart */}
          {chartData.marginData.datasets.length > 0 && (
            <>
              <div className="backtest-chart-heading">
                <div className="backtest-chart-title">Margin Utilization</div>
                {renderActionDotControls('margin')}
              </div>
              {renderDenseActionStrips('margin')}
              <div className="backtest-chart-container">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData.marginData.rows} syncId="backtest" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                    <XAxis
                      dataKey="x"
                      tick={{ fill: textColor, fontSize: 11 }}
                      interval={Math.max(1, Math.floor(chartData.labels.length / 8))}
                    />
                    <YAxis
                      domain={['auto', 'auto']}
                      tick={{ fill: textColor, fontSize: 11 }}
                      tickFormatter={v => (Number(v) * 100).toFixed(0) + '%'}
                      width={60}
                    />
                    <Tooltip content={makeTooltip(v => (v * 100).toFixed(2) + '%')} />
                    <Legend content={renderLegend} />
                    {chartData.marginData.datasets.map(ds => (
                      <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label} stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                    ))}
                    {renderActionMarkers(chartData.marginData.rows, 'margin')}
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </>
          )}
        </>
      )}

      {pendingImport && (
        <ImportDependenciesDialog
          preview={pendingImport.preview}
          config={pendingImport.config as Record<string, unknown>}
          applying={importDependencyApplying}
          error={importDependencyError}
          onCancel={() => setPendingImport(null)}
          onConfirm={(preview, config) => confirmPendingImport(preview, config as StoredBacktestConfig)}
        />
      )}
    </div>
  )
}
