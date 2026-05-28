// ── RebalanceStrategyPage.tsx ─────────────────────────────────────────────────

import { memo, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush, ReferenceDot,
} from 'recharts'
import {
  BacktestPageHeader, RunButton, ScenarioSetupControls,
} from '@/components/backtest/CommonBacktestSections'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import RebalanceStrategyBlock, { type RebalanceStrategyBlockRef } from '@/components/rebalance/RebalanceStrategyBlock'
import SavedStrategiesBar, { type SavedStrategiesBarRef } from '@/components/rebalance/SavedStrategiesBar'
import { useChartTheme } from '@/lib/chartTheme'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, BacktestResults, emptyBlock, blockStateToAPIPortfolio,
  configToBlockState, PALETTE, cashflowStateFromSettings,
  cashflowToPayload, DEFAULT_CASHFLOW_FREQUENCY, normalizeBlockSpreadInputs, startingBalanceToPayload,
} from '@/types/backtest'
import {
  buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR, type RechartsChartData,
} from '@/lib/chartData'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import {
  RebalStrategyState, emptyStrategy, strategyStateToAPI, savedConfigToStrategyState,
  normalizeStrategySpreadInput,
  drawdownMarginTriggerIssues,
} from '@/types/rebalanceStrategy'
import { fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'

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

function uniqueSavedJsonConfigName(name: string, takenNames: Set<string>) {
  if (!takenNames.has(labelKey(name))) return name

  const match = /^(.*?) \((\d+)\)$/.exec(name)
  const baseName = match?.[1] ?? name
  let counter = match?.[2] ? parseInt(match[2], 10) + 1 : 2
  let candidate = ''
  do {
    candidate = `${baseName} (${counter})`
    counter += 1
  } while (takenNames.has(labelKey(candidate)))
  return candidate
}

function makeUniqueStrategyLabels(strategies: RebalStrategyState[], portfolioLabel: string) {
  const taken = new Set<string>()
  if (portfolioLabel.trim()) taken.add(labelKey(portfolioLabel))

  return strategies.map((strategy, i) => {
    const base = strategy.label.trim() || `Strategy ${i + 1}`
    const label = uniqueSavedJsonConfigName(base, taken)
    taken.add(labelKey(label))
    return label === strategy.label ? strategy : { ...strategy, label }
  })
}

const ACTION_MARKERS: Record<string, { label: string; short: string; color: string; defaultVisible?: boolean }> = {
  SELL_HIGH:           { label: 'Sell high',          short: 'SH', color: '#d94841' },
  BUY_LOW:             { label: 'Buy low',            short: 'BL', color: '#2f9e44' },
  BUY_DIP:             { label: 'Buy dip',            short: 'BD', color: '#1971c2' },
  SELL_SURGE:          { label: 'Sell surge',         short: 'SS', color: '#e67700' },
  PORTFOLIO_REBALANCE: { label: 'Portfolio rebalance', short: 'RB', color: '#7950f2', defaultVisible: false },
  MARGIN_REBALANCE:    { label: 'Margin rebalance',    short: 'MR', color: '#0ca678', defaultVisible: false },
  VM_TIMING_MR:        { label: 'VM timing MR',        short: 'VM', color: '#0b7285', defaultVisible: false },
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

function averageMarginUtilization(points: { value: number }[] | undefined) {
  const values = points?.map(p => p.value).filter(Number.isFinite) ?? []
  return values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : null
}

const CHART_MARGIN = { top: 8, right: 16, bottom: 8, left: 8 }
const ACTIVE_DOT = { r: 4 }

function formatMoneyAxis(v: any) { return '$' + Number(v).toFixed(0) }
function formatMoneyTooltip(v: number) { return '$' + v.toFixed(2) }
function formatDrawdownAxis(v: any) { return (Number(v) * 100).toFixed(1) + '%' }
function formatPercentAxis(v: any) { return (Number(v) * 100).toFixed(0) + '%' }
function formatPercentTooltip(v: number) { return (v * 100).toFixed(2) + '%' }
function formatRecoverAxis(v: any) { return Number(v).toFixed(2) + 'x' }
function formatRecoverTooltip(v: number) { return v.toFixed(2) + 'x' }
function formatVmCapeAxis(v: any) { return Number(v).toFixed(0) }

type RebalanceChartKind = 'money' | 'drawdown' | 'recover' | 'margin'

const CHART_FORMATTERS: Record<RebalanceChartKind, {
  axis: (v: any) => string
  tooltip: (v: number) => string
  width: number
}> = {
  money: { axis: formatMoneyAxis, tooltip: formatMoneyTooltip, width: 72 },
  drawdown: { axis: formatDrawdownAxis, tooltip: formatPercentTooltip, width: 60 },
  recover: { axis: formatRecoverAxis, tooltip: formatRecoverTooltip, width: 60 },
  margin: { axis: formatPercentAxis, tooltip: formatPercentTooltip, width: 60 },
}

type CommonLineProps = {
  type: 'monotone'
  dot: false
  activeDot: { r: number }
  connectNulls: false
  isAnimationActive: false
}

type RebalanceLineChartProps = {
  chartData: RechartsChartData
  labelsLength: number
  gridColor: string
  textColor: string
  commonLineProps: CommonLineProps
  makeTooltip: (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) => ReactNode
  renderLegend: (props: any) => ReactNode
  renderActionMarkers: (rows: Record<string, any>[], chart: ActionPointChartKey) => ReactNode
  actionChart: ActionPointChartKey
  kind: RebalanceChartKind
  logScale?: boolean
  brushFill?: string
}

const RebalanceLineChart = memo(function RebalanceLineChart({
  chartData,
  labelsLength,
  gridColor,
  textColor,
  commonLineProps,
  makeTooltip,
  renderLegend,
  renderActionMarkers,
  actionChart,
  kind,
  logScale = false,
  brushFill,
}: RebalanceLineChartProps) {
  const format = CHART_FORMATTERS[kind]
  const isMoney = kind === 'money'

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={chartData.rows} syncId="rs-backtest" margin={CHART_MARGIN}>
        <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
        <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
          interval={Math.max(1, Math.floor(labelsLength / 8))} />
        <YAxis scale={isMoney && logScale ? 'log' : 'linear'} domain={['auto', 'auto']}
          allowDataOverflow={isMoney && logScale} tick={{ fill: textColor, fontSize: 11 }}
          tickFormatter={format.axis} width={format.width} />
        <Tooltip content={makeTooltip(format.tooltip)} />
        <Legend content={renderLegend} />
        {chartData.datasets.map(ds => (
          <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
            stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
        ))}
        {renderActionMarkers(chartData.rows, actionChart)}
        {brushFill && (
          <Brush dataKey="x" height={26} stroke={gridColor}
            fill={brushFill} travellerWidth={6} />
        )}
      </LineChart>
    </ResponsiveContainer>
  )
})

type VmTimingChartData = {
  rows: Record<string, any>[]
  datasets: {
    dataKey: string
    label: string
    color: string
    yAxisId: 'cape' | 'factor'
    strokeDasharray?: string
  }[]
}

const VmTimingLineChart = memo(function VmTimingLineChart({
  chartData,
  labelsLength,
  gridColor,
  textColor,
  commonLineProps,
  renderLegend,
}: {
  chartData: VmTimingChartData
  labelsLength: number
  gridColor: string
  textColor: string
  commonLineProps: CommonLineProps
  renderLegend: (props: any) => ReactNode
}) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={chartData.rows} syncId="rs-backtest" margin={CHART_MARGIN}>
        <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
        <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
          interval={Math.max(1, Math.floor(labelsLength / 8))} />
        <YAxis yAxisId="cape" domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
          tickFormatter={formatVmCapeAxis} width={54} />
        <YAxis yAxisId="factor" orientation="right" domain={[0, 1]} tick={{ fill: textColor, fontSize: 11 }}
          tickFormatter={formatPercentAxis} width={54} />
        <Tooltip
          formatter={(value: any, name: any, item: any) => {
            const n = Number(value)
            const dataKey = String(item?.dataKey ?? '')
            return [dataKey.includes('Factor') ? formatPercentTooltip(n) : n.toFixed(2), name]
          }}
        />
        <Legend content={renderLegend} />
        {chartData.datasets.map(ds => (
          <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
            yAxisId={ds.yAxisId} stroke={ds.color} strokeWidth={2} strokeDasharray={ds.strokeDasharray} />
        ))}
      </LineChart>
    </ResponsiveContainer>
  )
})

// ── Component ─────────────────────────────────────────────────────────────────

export default function RebalanceStrategyPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [strategies, setStrategies] = useState<RebalStrategyState[]>([emptyStrategy(0), emptyStrategy(1)])
  const [fromDate, setFromDate]   = useState('')
  const [toDate, setToDate]       = useState('')
  const [startingBalance, setStartingBalance]     = useState('10000')
  const [cashflowAmount, setCashflowAmount]       = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState(DEFAULT_CASHFLOW_FREQUENCY)
  const [includeActionDiagnostics, setIncludeActionDiagnostics] = useState(false)
  const [importCode, setImportCode]               = useState('')
  const [configError, setConfigError] = useState('')
  const [running, setRunning]     = useState(false)
  const [error, setError]         = useState('')
  const [results, setResults]     = useState<BacktestResults | null>(null)
  const [selected, setSelected]   = useState<Set<string>>(new Set())
  const [logScale, setLogScale]   = useState(false)
  const [visibleActionPointTypes, setVisibleActionPointTypes] = useState<Set<string>>(
    () => new Set(Object.entries(ACTION_MARKERS).filter(([, marker]) => marker.defaultVisible !== false).map(([type]) => type)),
  )
  const [actionPointChartVisibility, setActionPointChartVisibility] = useState<Record<ActionPointChartKey, boolean>>(
    () => ({ ...DEFAULT_ACTION_POINT_CHART_VISIBILITY }),
  )
  const [forceActionPointChartDots, setForceActionPointChartDots] = useState<Record<ActionPointChartKey, boolean>>(
    () => ({ ...DEFAULT_FORCE_ACTION_POINT_CHART_DOTS }),
  )
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const savedStrategiesBarRef = useRef<SavedStrategiesBarRef>(null)
  const strategyBlockRefs = useRef<(RebalanceStrategyBlockRef | null)[]>([])

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
        const cashflowState = cashflowStateFromSettings(req)
        if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
        if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
        if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
        if (req.portfolios[0]) setPortfolio(configToBlockState(req.portfolios[0], req.portfolios[0].label || ''))
      })
      .catch(() => {})
  }, [])

  // ── Run ───────────────────────────────────────────────────────────────────

  async function handleRun() {
    setError('')
    const runPortfolio = normalizeBlockSpreadInputs(portfolio)
    if (runPortfolio !== portfolio) setPortfolio(runPortfolio)
    let portfolioApi
    try {
      portfolioApi = resolvedBlockStateToAPIPortfolio(runPortfolio, 0, await fetchSavedPortfolios())
    } catch (e: any) {
      setError(e.message || 'Unable to resolve saved portfolio references.')
      return
    }
    if (portfolioApi.tickers.length === 0) {
      setError('Add at least one ticker with a positive weight to the portfolio.'); return
    }
    const currentStrategies = strategies
      .map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
      .map(normalizeStrategySpreadInput)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    const portfolioBlockStates = (portfolioApi.rebalanceStrategies as any[]).map(s => savedConfigToStrategyState(s.config, s.name))
    const allStrategies = makeUniqueStrategyLabels([...portfolioBlockStates, ...currentStrategies], portfolioApi.label)
    const tierIssues = allStrategies.flatMap(strategy => [
      ...drawdownMarginTriggerIssues(strategy.drawdownBuyOnLowMargin, 'buy', `${strategy.label || 'Strategy'} BL on Drawdown`),
    ])
    if (tierIssues.length > 0) {
      setError(tierIssues[0])
      return
    }
    const runStrategies = allStrategies.slice(portfolioBlockStates.length)
    if (runStrategies.some((s, i) => s !== strategies[i] || s.label !== strategies[i]?.label)) setStrategies(runStrategies)
    setRunning(true)
    try {
      const res = await fetch('/api/rebalance-strategy/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromDate: fromDate || null,
          toDate: toDate || null,
          startingBalance: startingBalanceToPayload(startingBalance),
          portfolio: portfolioApi,
          cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
          strategies: allStrategies.map(s => strategyStateToAPI(s)),
          includeActionDiagnostics,
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
    const currentStrategies = strategies
      .map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
      .map(normalizeStrategySpreadInput)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    if (currentStrategies.some((s, i) => s !== strategies[i])) setStrategies(currentStrategies)
    const exportPortfolio = normalizeBlockSpreadInputs(portfolio)
    if (exportPortfolio !== portfolio) setPortfolio(exportPortfolio)
    const code = await compressToCode({
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: startingBalanceToPayload(startingBalance),
      portfolio: blockStateToAPIPortfolio(exportPortfolio, 0),
      portfolioState: exportPortfolio,
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
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
      const cashflowState = cashflowStateFromSettings(req)
      if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
      if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
      if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
      if (req.portfolioState) setPortfolio(req.portfolioState)
      else if (req.portfolio) setPortfolio(configToBlockState(req.portfolio, req.portfolio.label || ''))
      if (Array.isArray(req.strategies)) {
        setStrategies(req.strategies.slice(0, 2).map((s: any, i: number) => savedConfigToStrategyState(s, s.label || `Strategy ${i + 1}`)))
      }
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

  const allKeys = useMemo(
    () => results ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`)) : [],
    [results],
  )
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0

  const selectedStrategyCurve = useMemo(() => {
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
  const selectedActionPointGroups = useMemo(() => (
    visibleActionPointGroups(selectedStrategyCurve?.curve.actionPoints, visibleActionPointTypes, chartData?.labels ?? [])
  ), [chartData?.labels, selectedStrategyCurve, visibleActionPointTypes])
  const selectedActionDiagnostics = useMemo(() => (
    selectedStrategyCurve?.curve.actionPoints
      ?.filter(point => point.detail)
      .slice(0, 250) ?? []
  ), [selectedStrategyCurve])
  const vmTimingChartData = useMemo(() => {
    const labels = chartData?.labels ?? []
    if (!results || labels.length === 0) return null
    const rows: Record<string, any>[] = labels.map(x => ({ x }))
    const datasets: {
      dataKey: string
      label: string
      color: string
      yAxisId: 'cape' | 'factor'
      strokeDasharray?: string
    }[] = []

    results.portfolios.forEach((portfolio, pi) => {
      const palette = PALETTE[pi % PALETTE.length]
      portfolio.curves.forEach((curve, ci) => {
        const key = `${pi}-${ci}`
        if (selected.size > 0 && !selected.has(key)) return
        if (!curve.vmTimingPoints?.length) return
        const baseColor = palette[ci % palette.length]
        const capeKey = `vmCape${pi}-${ci}`
        const factorKey = `vmFactor${pi}-${ci}`
        const byDate = new Map(curve.vmTimingPoints.map(point => [point.date, point]))
        labels.forEach((date, i) => {
          const point = byDate.get(date)
          if (!point) return
          rows[i][capeKey] = point.cape
          rows[i][factorKey] = point.valueFactor
        })
        const label = `${portfolio.label} - ${curve.label}`
        datasets.push({ dataKey: capeKey, label: `${label} CAPE`, color: baseColor, yAxisId: 'cape' })
        datasets.push({ dataKey: factorKey, label: `${label} Value factor`, color: baseColor, yAxisId: 'factor', strokeDasharray: '5 4' })
      })
    })

    return datasets.length > 0 ? { rows, datasets } : null
  }, [chartData?.labels, results, selected])

  const statsRows = useMemo(() => (
    results?.portfolios.flatMap((portfolio, pi) =>
      portfolio.curves.map((curve, ci) => {
        const actionCounts: Record<string, number> = {}
        curve.actionPoints?.forEach(point => {
          actionCounts[point.type] = (actionCounts[point.type] ?? 0) + 1
        })
        return {
          key: `${pi}-${ci}`,
          label: `${portfolio.label} – ${curve.label}`,
          color: PALETTE[pi % PALETTE.length][ci % PALETTE[pi % PALETTE.length].length],
          stats: curve.stats,
          avgMargin: averageMarginUtilization(curve.marginPoints),
          actionCounts,
        }
      })
    ) ?? []
  ), [results])

  const toggleCurve = useCallback((key: string, checked: boolean) => {
    setSelected(prev => { const s = new Set(prev); checked ? s.add(key) : s.delete(key); return s })
  }, [])
  const toggleAll = useCallback((checked: boolean) => {
    setSelected(checked ? new Set(allKeys) : new Set())
  }, [allKeys])
  const toggleActionPointType = useCallback((type: string, checked: boolean) => {
    setVisibleActionPointTypes(prev => {
      const next = new Set(prev)
      checked ? next.add(type) : next.delete(type)
      return next
    })
  }, [])
  const toggleActionPointChart = useCallback((chart: ActionPointChartKey, checked: boolean) => {
    setActionPointChartVisibility(prev => ({ ...prev, [chart]: checked }))
  }, [])
  const toggleForceActionPointChartDots = useCallback((chart: ActionPointChartKey, checked: boolean) => {
    setForceActionPointChartDots(prev => ({ ...prev, [chart]: checked }))
  }, [])

  const strategyHandlers = useMemo(
    () => [0, 1].map(i => (s: RebalStrategyState) =>
      setStrategies(prev => { const n = [...prev]; n[i] = s; return n })
    ),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])
  const refreshSavedStrategies = useCallback(() => savedStrategiesBarRef.current?.refresh(), [])

  const makeTooltip = useCallback(
    (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) =>
      makeRechartsTooltip(theme, valueFmt, labelFmt),
    [theme],
  )

  const commonLineProps = useMemo<CommonLineProps>(() => ({
    type: 'monotone' as const, dot: false as const,
    activeDot: ACTIVE_DOT, connectNulls: false, isAnimationActive: false,
  }), [])

  const renderActionDotControls = useCallback((chart: ActionPointChartKey) => {
    if (!selectedStrategyCurve) return null
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
  }, [
    actionPointChartVisibility,
    forceActionPointChartDots,
    selectedActionPointGroups.denseGroups.length,
    selectedStrategyCurve,
    toggleActionPointChart,
    toggleForceActionPointChartDots,
  ])

  const renderActionMarkers = useCallback((rows: Record<string, any>[], chart: ActionPointChartKey) => {
    if (!selectedStrategyCurve || !actionPointChartVisibility[chart]) return null
    const points = forceActionPointChartDots[chart]
      ? selectedActionPointGroups.markers.concat(selectedActionPointGroups.denseGroups.flatMap(group => group.points))
      : selectedActionPointGroups.markers
    return points.map((point, i) => {
      const marker = ACTION_MARKERS[point.type]
      if (!marker) return null
      const row = rows[point.rowIndex]
      const y = row?.[selectedStrategyCurve.dataKey]
      if (typeof y !== 'number' || !Number.isFinite(y)) return null

      const duplicateKey = `${point.date}-${point.type}`
      return (
        <ReferenceDot
          key={`${duplicateKey}-${i}`}
          x={point.date}
          y={y}
          r={5}
          fill={marker.color}
          stroke={theme.isDark ? '#111' : '#fff'}
          strokeWidth={2}
          ifOverflow="extendDomain"
          label={{ value: marker.short, position: 'top', fill: marker.color, fontSize: 10 }}
        />
      )
    })
  }, [
    actionPointChartVisibility,
    forceActionPointChartDots,
    selectedActionPointGroups,
    selectedStrategyCurve,
    theme.isDark,
  ])

  const renderDenseActionStrips = useCallback((chart: ActionPointChartKey) => {
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
  }, [
    actionPointChartVisibility,
    chartData?.labels,
    forceActionPointChartDots,
    selectedActionPointGroups.denseGroups,
  ])

  const renderLegend = useCallback((props: any) => {
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
  }, [textColor])

  return (
    <div className="container">
      <BacktestPageHeader active="/rebalance-strategy" />

      <div className="backtest-form-card">
        <ScenarioSetupControls
          idPrefix="rs"
          fromLabel="From Date"
          fromInputId="rs-from-date"
          fromDate={fromDate}
          toLabel="To Date"
          toInputId="rs-to-date"
          toDate={toDate}
          importInputId="rs-import-code"
          importCode={importCode}
          configError={configError}
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

        <SavedPortfoliosBar ref={savedBarRef} />
        <SavedStrategiesBar ref={savedStrategiesBarRef} />

        <div className="strategy-options-panel">
          <label className="strategy-options-toggle" htmlFor="rs-action-diagnostics">
            <input
              id="rs-action-diagnostics"
              type="checkbox"
              checked={includeActionDiagnostics}
              disabled={running}
              onChange={e => setIncludeActionDiagnostics(e.target.checked)}
            />
            Action diagnostics
          </label>
        </div>

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
              onSavedRefresh={refreshSavedStrategies}
            />
          ))}
        </div>

        <RunButton
          label="Run Rebalance Strategy"
          running={running}
          disabled={running}
          onClick={handleRun}
        />
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
                  <th title="Average margin utilization">Avg Margin</th>
                  <th title="# buy-low action points">BL</th>
                  <th title="# sell-high action points">SH</th>
                  <th title="# buy-dip action points">BD</th>
                  <th title="# sell-surge action points">SS</th>
                  <th title="# VM timing margin rebalance action points">VM</th>
                </tr>
              </thead>
              <tbody>
                {statsRows.map(row => {
                    const s = row.stats
                    return (
                      <tr key={row.key}>
                        <td><input type="checkbox" checked={selected.has(row.key)} onChange={e => toggleCurve(row.key, e.target.checked)} /></td>
                        <td style={{ color: row.color }}>
                          {row.label}
                        </td>
                        <td>{money(s.endingValue)}</td>
                        <td>{pct(s.cagr)}</td>
                        <td>{pct(s.maxDrawdown)}</td>
                        <td>{dur(s.longestDrawdownDays)}</td>
                        <td>{pct(s.annualVolatility)}</td>
                        <td>{fmt2(s.sharpe)}</td>
                        <td>{pct(s.ulcerIndex)}</td>
                        <td>{fmt2(s.upi)}</td>
                        <td>{row.avgMargin == null ? '–' : pct(row.avgMargin)}</td>
                        <td>{row.actionCounts.BUY_LOW ?? 0}</td>
                        <td>{row.actionCounts.SELL_HIGH ?? 0}</td>
                        <td>{row.actionCounts.BUY_DIP ?? 0}</td>
                        <td>{row.actionCounts.SELL_SURGE ?? 0}</td>
                        <td>{row.actionCounts.VM_TIMING_MR ?? 0}</td>
                      </tr>
                    )
                  })}
              </tbody>
            </table>
          </div>

          {selectedActionDiagnostics.length > 0 && (
            <div className="stats-container">
              <table className="backtest-stats-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Type</th>
                    <th title="Zero-based trading date index in the backtest date set">Idx</th>
                    <th title="Trading dates since previous action for the same trigger key">Since Prev</th>
                    <th>Key</th>
                    <th>Dir</th>
                    <th>Trigger Value</th>
                    <th>Amount</th>
                    <th>Eligible</th>
                    <th>Margin Before</th>
                    <th>Margin After</th>
                    <th>Alloc</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedActionDiagnostics.map((point, i) => {
                    const d = point.detail!
                    return (
                      <tr key={`${point.date}-${point.type}-${i}`}>
                        <td>{point.date}</td>
                        <td>{point.type}</td>
                        <td>{d.tradingDayIndex ?? '-'}</td>
                        <td>{d.daysSincePrevious ?? '-'}</td>
                        <td>{d.key ?? '-'}</td>
                        <td>{d.direction ?? '-'}</td>
                        <td>{d.triggerValue == null ? '-' : fmt2(d.triggerValue)}</td>
                        <td>{d.amount == null ? '-' : money(d.amount)}</td>
                        <td>{d.eligibleAmount == null ? '-' : money(d.eligibleAmount)}</td>
                        <td>{d.marginBefore == null ? '-' : pct(d.marginBefore)}</td>
                        <td>{d.marginAfter == null ? '-' : pct(d.marginAfter)}</td>
                        <td>{d.allocStrategy ?? '-'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}

          {/* Portfolio Value + Margin Utilization chart */}
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Portfolio Value</div>
            {renderActionDotControls('main')}
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.4rem', marginBottom: '0.25rem', flexWrap: 'wrap' }}>
            {selectedStrategyCurve && (
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
            <button className={`chart-scale-toggle${logScale ? ' active' : ''}`} type="button"
              style={{ position: 'static' }} onClick={() => setLogScale(l => !l)}>Log</button>
          </div>
          {renderDenseActionStrips('main')}
          <div className="backtest-chart-container">
            <RebalanceLineChart
              chartData={chartData.mainData}
              labelsLength={chartData.labels.length}
              gridColor={gridColor}
              textColor={textColor}
              commonLineProps={commonLineProps}
              makeTooltip={makeTooltip}
              renderLegend={renderLegend}
              renderActionMarkers={renderActionMarkers}
              actionChart="main"
              kind="money"
              logScale={logScale}
              brushFill={theme.isDark ? '#1a1a1a' : '#f8f8f8'}
            />
          </div>

          {/* Drawdown chart */}
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Drawdown</div>
            {renderActionDotControls('drawdown')}
          </div>
          {renderDenseActionStrips('drawdown')}
          <div className="backtest-chart-container">
            <RebalanceLineChart
              chartData={chartData.ddData}
              labelsLength={chartData.labels.length}
              gridColor={gridColor}
              textColor={textColor}
              commonLineProps={commonLineProps}
              makeTooltip={makeTooltip}
              renderLegend={renderLegend}
              renderActionMarkers={renderActionMarkers}
              actionChart="drawdown"
              kind="drawdown"
            />
          </div>

          {/* RTR chart */}
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Return Required to Recover</div>
            {renderActionDotControls('recover')}
          </div>
          {renderDenseActionStrips('recover')}
          <div className="backtest-chart-container">
            <RebalanceLineChart
              chartData={chartData.rtrData}
              labelsLength={chartData.labels.length}
              gridColor={gridColor}
              textColor={textColor}
              commonLineProps={commonLineProps}
              makeTooltip={makeTooltip}
              renderLegend={renderLegend}
              renderActionMarkers={renderActionMarkers}
              actionChart="recover"
              kind="recover"
            />
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
                <RebalanceLineChart
                  chartData={chartData.marginData}
                  labelsLength={chartData.labels.length}
                  gridColor={gridColor}
                  textColor={textColor}
                  commonLineProps={commonLineProps}
                  makeTooltip={makeTooltip}
                  renderLegend={renderLegend}
                  renderActionMarkers={renderActionMarkers}
                  actionChart="margin"
                  kind="margin"
                />
              </div>
            </>
          )}

          {vmTimingChartData && (
            <>
              <div className="backtest-chart-heading">
                <div className="backtest-chart-title">VM Timing Debug</div>
              </div>
              <div className="backtest-chart-container">
                <VmTimingLineChart
                  chartData={vmTimingChartData}
                  labelsLength={chartData.labels.length}
                  gridColor={gridColor}
                  textColor={textColor}
                  commonLineProps={commonLineProps}
                  renderLegend={renderLegend}
                />
              </div>
            </>
          )}

        </>
      )}
    </div>
  )
}
