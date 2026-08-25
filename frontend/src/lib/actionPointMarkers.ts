// ── actionPointMarkers.ts — Shared action-point marker grouping for backtest-style charts ──
// Used by both BacktestPage and the Rebalance Strategy results view.

import type { BacktestCurve } from '@/types/backtest'

export type VisibleActionPoint = {
  date: string
  type: string
  rowIndex: number
}

export type DenseActionPointGroup = {
  type: string
  points: VisibleActionPoint[]
}

const ACTION_MARKER_RENDER_LIMIT = 350

/**
 * Index of the label nearest `date` in a sorted (possibly downsampled) label array.
 * ISO 'YYYY-MM-DD' strings compare correctly with plain string operators, so this
 * only needs Date.parse to break a tie between the two candidate neighbours.
 */
function nearestLabelIndex(labels: string[], date: string): number {
  const last = labels.length - 1
  if (date <= labels[0]) return 0
  if (date >= labels[last]) return last
  let lo = 0
  let hi = last
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (labels[mid] < date) lo = mid + 1
    else hi = mid
  }
  const before = labels[lo - 1]
  if (before == null) return lo
  const afterGap = Date.parse(labels[lo]) - Date.parse(date)
  const beforeGap = Date.parse(date) - Date.parse(before)
  return afterGap <= beforeGap ? lo : lo - 1
}

export function visibleActionPointGroups(
  actionPoints: BacktestCurve['actionPoints'] | undefined,
  visibleTypes: Set<string>,
  labels: string[],
): { markers: VisibleActionPoint[]; denseGroups: DenseActionPointGroup[] } {
  if (!actionPoints?.length || visibleTypes.size === 0 || labels.length === 0) return { markers: [], denseGroups: [] }

  // `labels` may be a downsampled subset (see downsampleLabels) that no longer contains
  // every action point's exact date — snap to the nearest surviving label instead of
  // dropping the marker, and dedupe on the snapped row so points that collapse onto the
  // same visible date don't render as stacked duplicate dots.
  const seen = new Set<string>()
  const points: VisibleActionPoint[] = []
  for (const point of actionPoints) {
    if (!visibleTypes.has(point.type)) continue
    const rowIndex = nearestLabelIndex(labels, point.date)
    const key = `${rowIndex}-${point.type}`
    if (seen.has(key)) continue
    seen.add(key)
    points.push({ date: labels[rowIndex], type: point.type, rowIndex })
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
