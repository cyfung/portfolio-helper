// ── chartData.ts — Helpers to convert dataset arrays into Recharts row format ──

import { BacktestResults, PALETTE } from '@/types/backtest'
import { getGroupStrokeWidths } from '@/lib/colorScheme'

export interface RechartsDataset {
  label: string
  color: string
  strokeDasharray?: string
  strokeWidth?: number
}

export interface RechartsChartData {
  rows: Record<string, any>[]
  datasets: RechartsDataset[]
}

/** Build common date labels across all portfolios (intersection). */
export function buildCommonLabels(data: BacktestResults): string[] {
  let common = new Set(data.portfolios[0].curves[0].points.map(p => p.date))
  for (let i = 1; i < data.portfolios.length; i++) {
    const dates = new Set(data.portfolios[i].curves[0].points.map(p => p.date))
    for (const d of [...common]) { if (!dates.has(d)) common.delete(d) }
  }
  return [...common].sort()
}

/** Convert datasets into Recharts row objects keyed by label. */
export function buildRechartsData(
  data: BacktestResults,
  labels: string[],
  selected: Set<string>,
  valueFn: (pts: { date: string; value: number }[]) => (number | null)[],
): RechartsChartData {
  const rows: Record<string, any>[] = labels.map(x => ({ x }))
  const datasets: RechartsDataset[] = []

  data.portfolios.forEach((portfolio, pi) => {
    const palette = PALETTE[pi % PALETTE.length]
    const widths  = getGroupStrokeWidths(portfolio.curves.length)
    portfolio.curves.forEach((curve, ci) => {
      if (selected.size > 0 && !selected.has(`${pi}-${ci}`)) return
      const key = `${portfolio.label} \u2013 ${curve.label}`
      const vals = valueFn(curve.points)
      const byDate = new Map(curve.points.map((p, i) => [p.date, vals[i]]))
      labels.forEach((d, i) => { rows[i][key] = byDate.get(d) ?? undefined })
      datasets.push({
        label: key,
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
