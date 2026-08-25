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

/**
 * Uniformly decimate a date-label series to at most `maxPoints` entries, always
 * keeping the first and last date. Recharts renders one SVG vertex (and, with
 * Brush, a second duplicate vertex in the overview strip) per label per visible
 * curve — on a multi-decade daily-resolution backtest this is the dominant cost
 * of both the first chart paint and every subsequent re-render (curve toggles,
 * tab switches), profiled at multiple seconds of blocked main thread on a
 * 6,846-point/5-curve run. Downsampling the shared label axis keeps every
 * series aligned on the same dates so rows/action-marker indices stay valid.
 */
export function downsampleLabels(labels: string[], maxPoints = 1500): string[] {
  if (labels.length <= maxPoints) return labels
  const stride = (labels.length - 1) / (maxPoints - 1)
  const out: string[] = []
  for (let i = 0; i < maxPoints; i++) {
    out.push(labels[Math.round(i * stride)])
  }
  return out
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
