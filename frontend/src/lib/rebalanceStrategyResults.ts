import { useMemo } from 'react'
import { buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR } from '@/lib/chartData'
import { curveDisplayLabel, curveMetricDataKey, curveMetricLabel, curveSelectionKey } from '@/lib/curveNaming'
import { BacktestCurve, BacktestResults, PALETTE } from '@/types/backtest'

export type ActionMarker = {
  label: string
  short: string
  color: string
  defaultVisible?: boolean
}

export type VisibleActionPoint = {
  date: string
  type: string
  rowIndex: number
}

export type DenseActionPointGroup = {
  type: string
  points: VisibleActionPoint[]
}

export type RebalanceStatsRow = {
  key: string
  label: string
  color: string
  stats: BacktestCurve['stats']
  avgMargin: number | null
  actionCounts: Record<string, number>
}

export const ACTION_MARKERS: Record<string, ActionMarker> = {
  SELL_HIGH: { label: 'Sell high', short: 'SH', color: '#d94841' },
  BUY_LOW: { label: 'Buy low', short: 'BL', color: '#2f9e44' },
  BUY_DIP: { label: 'Buy dip', short: 'BD', color: '#1971c2' },
  SELL_SURGE: { label: 'Sell surge', short: 'SS', color: '#e67700' },
  PORTFOLIO_REBALANCE: { label: 'Portfolio rebalance', short: 'RB', color: '#7950f2', defaultVisible: false },
  MARGIN_REBALANCE: { label: 'Margin rebalance', short: 'MR', color: '#0ca678', defaultVisible: false },
  VM_TIMING_MR: { label: 'VM timing MR', short: 'VM', color: '#0b7285', defaultVisible: false },
  DRAWDOWN_MR: { label: 'Drawdown MR', short: 'DD-MR', color: '#6741d9', defaultVisible: false },
  DRAWDOWN_MR_EXIT: { label: 'Drawdown MR exit', short: 'DD-X', color: '#868e96', defaultVisible: false },
}

export const DEFAULT_ACTION_POINT_CHART_VISIBILITY = {
  main: false,
  drawdown: true,
  recover: false,
  margin: false,
  marginCushion: false,
  marginReciprocal: false,
}

export const DEFAULT_FORCE_ACTION_POINT_CHART_DOTS = {
  main: false,
  drawdown: false,
  recover: false,
  margin: false,
  marginCushion: false,
  marginReciprocal: false,
}

const ACTION_MARKER_RENDER_LIMIT = 350
const MARGIN_RECIPROCAL_EPSILON = 1e-6

export function visibleActionPointGroups(
  actionPoints: BacktestCurve['actionPoints'] | undefined,
  visibleTypes: Set<string>,
  labels: string[],
): { markers: VisibleActionPoint[]; denseGroups: DenseActionPointGroup[] } {
  if (!actionPoints?.length || visibleTypes.size === 0) return { markers: [], denseGroups: [] }

  const rowIndexByDate = new Map(labels.map((date, i) => [date, i]))
  const seen = new Set<string>()
  const points: VisibleActionPoint[] = []
  for (const point of actionPoints) {
    if (!visibleTypes.has(point.type)) continue
    const rowIndex = rowIndexByDate.get(point.date)
    if (rowIndex == null) continue
    const key = `${point.date}-${point.type}`
    if (seen.has(key)) continue
    seen.add(key)
    points.push({ date: point.date, type: point.type, rowIndex })
  }

  const byType = new Map<string, VisibleActionPoint[]>()
  for (const point of points) {
    const group = byType.get(point.type) ?? []
    group.push(point)
    byType.set(point.type, group)
  }

  const markers: VisibleActionPoint[] = []
  const denseGroups: DenseActionPointGroup[] = []
  for (const [type, group] of byType) {
    if (group.length > ACTION_MARKER_RENDER_LIMIT) denseGroups.push({ type, points: group })
    else markers.push(...group)
  }
  return { markers, denseGroups }
}

export function averageMarginUtilization(points: { value: number }[] | undefined) {
  const values = points?.map(p => p.value).filter(Number.isFinite) ?? []
  return values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : null
}

export function buildStatsRows(results: BacktestResults): RebalanceStatsRow[] {
  return results.portfolios.flatMap((portfolio, pi) =>
    portfolio.curves.map((curve, ci) => {
      const actionCounts: Record<string, number> = {}
      curve.actionPoints?.forEach(point => {
        actionCounts[point.type] = (actionCounts[point.type] ?? 0) + 1
      })
      return {
        key: curveSelectionKey(pi, ci),
        label: curveDisplayLabel(portfolio.label, curve.label),
        color: PALETTE[pi % PALETTE.length][ci % PALETTE[pi % PALETTE.length].length],
        stats: curve.stats,
        avgMargin: averageMarginUtilization(curve.marginPoints),
        actionCounts,
      }
    })
  )
}

export function useRebalanceChartData(results: BacktestResults, selected: Set<string>) {
  return useMemo(() => {
    const labels = buildCommonLabels(results)
    const mainData = buildRechartsData(results, labels, selected, pts => pts.map(p => p.value))
    const ddData = buildRechartsData(results, labels, selected, computeDrawdown)
    const rtrData = buildRechartsData(results, labels, selected, computeRTR)
    const marginData = buildRechartsData(results, labels, selected, pts => pts.map(p => p.value), c => c.marginPoints)
    const marginCushionData = buildRechartsData(
      results,
      labels,
      selected,
      pts => pts.map(p => 1 / (1 + Math.max(0, p.value))),
      c => c.marginPoints,
    )
    const marginReciprocalData = buildRechartsData(
      results,
      labels,
      selected,
      pts => pts.map(p => 1 / (Math.max(0, p.value) + MARGIN_RECIPROCAL_EPSILON)),
      c => c.marginPoints,
    )
    return { labels, mainData, ddData, rtrData, marginData, marginCushionData, marginReciprocalData }
  }, [results, selected])
}

export function useVmTimingChartData(
  results: BacktestResults,
  labels: string[],
  selected: Set<string>,
) {
  return useMemo(() => {
    if (labels.length === 0) return null

    const rows: Record<string, unknown>[] = labels.map(x => ({ x }))
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
        const key = curveSelectionKey(pi, ci)
        if (selected.size > 0 && !selected.has(key)) return
        if (!curve.vmTimingPoints?.length) return

        const baseColor = palette[ci % palette.length]
        const capeKey = curveMetricDataKey('vmCape', pi, ci)
        const factorKey = curveMetricDataKey('vmFactor', pi, ci)
        const byDate = new Map(curve.vmTimingPoints.map(point => [point.date, point]))
        labels.forEach((date, i) => {
          const point = byDate.get(date)
          if (!point) return
          rows[i][capeKey] = point.cape
          rows[i][factorKey] = point.valueFactor
        })

        datasets.push({ dataKey: capeKey, label: curveMetricLabel(portfolio.label, curve.label, 'CAPE'), color: baseColor, yAxisId: 'cape' })
        datasets.push({
          dataKey: factorKey,
          label: curveMetricLabel(portfolio.label, curve.label, 'Value factor'),
          color: baseColor,
          yAxisId: 'factor',
          strokeDasharray: '5 4',
        })
      })
    })

    return datasets.length > 0 ? { rows, datasets } : null
  }, [labels, results, selected])
}
