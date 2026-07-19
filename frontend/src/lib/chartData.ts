// ── chartData.ts — Helpers to convert dataset arrays into Recharts row format ──

import { BacktestResults, BacktestCurve, PALETTE } from '@/types/backtest'
import { getGroupStrokeWidths } from '@/lib/colorScheme'
import { curveDataKey, curveDisplayLabel, curveSelectionKey } from '@/lib/curveNaming'

export interface RechartsDataset {
  dataKey: string
  label: string
  color: string
  strokeDasharray?: string
  strokeWidth?: number
}

export interface RechartsChartData {
  rows: Record<string, any>[]
  datasets: RechartsDataset[]
}

/** Build common date labels across all curves of all portfolios (intersection). */
export function buildCommonLabels(data: BacktestResults): string[] {
  const allCurves = data.portfolios.flatMap(p => p.curves)
  let common = new Set(allCurves[0].points.map(p => p.date))
  for (let i = 1; i < allCurves.length; i++) {
    const dates = new Set(allCurves[i].points.map(p => p.date))
    for (const d of [...common]) { if (!dates.has(d)) common.delete(d) }
  }
  return [...common].sort()
}

/** Convert datasets into Recharts row objects keyed by stable series id. */
export function buildRechartsData(
  data: BacktestResults,
  labels: string[],
  selected: Set<string>,
  valueFn: (pts: { date: string; value: number }[]) => (number | null)[],
  pointsSelector?: (curve: BacktestCurve) => { date: string; value: number }[] | undefined,
): RechartsChartData {
  const rows: Record<string, any>[] = labels.map(x => ({ x }))
  const datasets: RechartsDataset[] = []

  data.portfolios.forEach((portfolio, pi) => {
    const palette = PALETTE[pi % PALETTE.length]
    const widths  = getGroupStrokeWidths(portfolio.curves.length)
    portfolio.curves.forEach((curve, ci) => {
      if (selected.size > 0 && !selected.has(curveSelectionKey(pi, ci))) return
      const pts = pointsSelector ? pointsSelector(curve) : curve.points
      if (!pts) return
      const key = curveDataKey(pi, ci)
      const label = curveDisplayLabel(portfolio.label, curve.label)
      const vals = valueFn(pts)
      const byDate = new Map(pts.map((p, i) => [p.date, vals[i]]))
      labels.forEach((d, i) => { rows[i][key] = byDate.get(d) ?? undefined })
      datasets.push({
        dataKey: key,
        label,
        color: palette[ci % palette.length],
        strokeWidth: widths[ci] ?? 1.0,
      })
    })
  })

  return { rows, datasets }
}

export function computeDrawdown(pts: { date: string; value: number }[]): number[] {
  let peak = -Infinity
  return pts.map(p => { if (p.value > peak) peak = p.value; return (p.value / peak) - 1 })
}

export function computeRTR(pts: { date: string; value: number }[]): (number | null)[] {
  let peak = -Infinity
  return pts.map(p => { if (p.value > peak) peak = p.value; return p.value > 0 ? peak / p.value : null })
}
