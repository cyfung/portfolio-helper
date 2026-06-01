import { fmt2, pct } from '@/lib/statsFormatters'
import { PALETTE } from '@/types/backtest'
import type {
  DrawdownConfigInput,
  MarketTimingPoint,
  MarketTimingResponse,
  MarketTimingResult,
  UsCapePoint,
  WorldCapePoint,
} from '@/types/marketTiming'

export const MAX_MARKET_TIMING_CHART_ROWS = 1_600
export const MAX_WINDOW_AVERAGE_CHART_ROWS = 1_200
export const MAX_CAPE_CHART_ROWS = 1_200
export const MARGIN_COMPARISON_LEVELS = Array.from({ length: 11 }, (_, i) => i * 10)

interface DateParts {
  year: number
  month: number
  day: number
}

interface WaitAdvantageWindow {
  anchorIndex: number
  leftBoundaryIndex: number
  rightBoundaryIndex: number
}

export function parseDrawdownConfigs(value: string): DrawdownConfigInput[] {
  return value
    .split(/[,\n;]+/)
    .map(s => s.trim())
    .filter(Boolean)
    .map(entry => {
      const match = entry.match(/^(\d+(?:\.\d+)?)\s*(?:[-:/]\s*(\d+(?:\.\d+)?))?$/)
      if (!match) return null
      const drawdown = parseFloat(match[1])
      const zeroWindow = match[2] == null ? 0 : Math.floor(parseFloat(match[2]))
      if (!Number.isFinite(drawdown) || drawdown <= 0 || drawdown >= 100) return null
      return {
        drawdownPct: drawdown / 100,
        zeroWindowMonths: Number.isFinite(zeroWindow) ? Math.max(0, zeroWindow) : 0,
      }
    })
    .filter((config): config is DrawdownConfigInput => config != null)
}

export function formatDays(days?: number | null) {
  if (days == null || !Number.isFinite(days)) return '-'
  if (days < 365) return `${Math.round(days)}d`
  return `${fmt2(days / 365.25)}y`
}

export function sourceMethodLabel(method: string) {
  if (method === 'US_SHILLER_PROXY') return 'US Shiller proxy'
  if (method.startsWith('SYNTHETIC_EP_BLEND')) return 'Synthetic world CAPE'
  if (method === 'SIBLIS_FREE_ANCHOR') return 'Siblis world CAPE'
  if (method === 'RA_CURRENT_REFERENCE') return 'RA current reference'
  return method
}

export function marketTimingResultLabel(result: MarketTimingResult) {
  return `${pct(result.drawdownPct)} DD - ${result.zeroWindowMonths ?? 0}m`
}

export function finiteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function parseIsoDateParts(value: string): DateParts | null {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!match) return null
  return {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
  }
}

function datePartsToUtcMs(parts: DateParts) {
  return Date.UTC(parts.year, parts.month - 1, parts.day)
}

function wholeCalendarMonthsBetween(earlier: DateParts, later: DateParts) {
  let months = (later.year - earlier.year) * 12 + later.month - earlier.month
  if (later.day < earlier.day) months -= 1
  return Math.max(0, months)
}

function monthOffsetFromAnchor(anchorDate: string, pointDate: string) {
  const anchor = parseIsoDateParts(anchorDate)
  const point = parseIsoDateParts(pointDate)
  if (!anchor || !point) return null
  const anchorMs = datePartsToUtcMs(anchor)
  const pointMs = datePartsToUtcMs(point)
  if (pointMs === anchorMs) return 0
  if (pointMs > anchorMs) return wholeCalendarMonthsBetween(anchor, point) + 1
  return -(wholeCalendarMonthsBetween(point, anchor) + 1)
}

export function downsampleChartRows<T extends Record<string, any>>(
  rows: T[],
  dataKeys: string[],
  maxRows: number,
  keepRow: (row: T, index: number) => boolean = () => false,
) {
  if (rows.length <= maxRows) return rows

  const keepIndexes = new Set<number>([0, rows.length - 1])
  rows.forEach((row, index) => {
    if (keepRow(row, index)) keepIndexes.add(index)
  })

  dataKeys.forEach(key => {
    for (let i = 1; i < rows.length; i++) {
      const previousDefined = finiteNumber(rows[i - 1][key])
      const currentDefined = finiteNumber(rows[i][key])
      if (previousDefined !== currentDefined) {
        keepIndexes.add(i - 1)
        keepIndexes.add(i)
      }
    }
  })

  const remainingBudget = maxRows - keepIndexes.size
  if (remainingBudget > 0) {
    if (remainingBudget === 1) {
      keepIndexes.add(Math.floor((rows.length - 1) / 2))
    } else {
      for (let i = 0; i < remainingBudget; i++) {
        keepIndexes.add(Math.round((i * (rows.length - 1)) / (remainingBudget - 1)))
      }
    }
  }

  return Array.from(keepIndexes)
    .sort((a, b) => a - b)
    .map(index => rows[index])
}

function isZeroingWindowPoint(point: MarketTimingPoint) {
  return point.zeroingWindow === true || point.daysToTrigger === 0
}

function pushBestWindow(
  windows: WaitAdvantageWindow[],
  windowStartIndex: number | null,
  bestIndex: number | null,
  endExclusiveIndex: number,
) {
  if (windowStartIndex != null && bestIndex != null && endExclusiveIndex > windowStartIndex) {
    windows.push({
      anchorIndex: bestIndex,
      leftBoundaryIndex: windowStartIndex,
      rightBoundaryIndex: endExclusiveIndex - 1,
    })
  }
}

function findBackendWindowIds(points: MarketTimingPoint[]) {
  const windows: WaitAdvantageWindow[] = []
  let currentWindowId: number | null = null
  let windowStartIndex: number | null = null
  let bestIndex: number | null = null
  let bestValue = Number.POSITIVE_INFINITY

  function closeWindow(endExclusiveIndex: number) {
    pushBestWindow(windows, windowStartIndex, bestIndex, endExclusiveIndex)
    currentWindowId = null
    windowStartIndex = null
    bestIndex = null
    bestValue = Number.POSITIVE_INFINITY
  }

  points.forEach((point, index) => {
    const value = point.value
    const windowId = point.nonZeroWindowId
    if (!finiteNumber(value) || !finiteNumber(windowId)) {
      closeWindow(index)
      return
    }
    if (currentWindowId !== windowId) {
      closeWindow(index)
      currentWindowId = windowId
      windowStartIndex = index
    }
    if (value < bestValue) {
      bestValue = value
      bestIndex = index
    }
  })
  closeWindow(points.length)

  return windows
}

function findLegacyWaitAdvantageWindows(points: MarketTimingPoint[]) {
  const windows: WaitAdvantageWindow[] = []
  let windowStartIndex: number | null = null
  let bestIndex: number | null = null
  let bestValue = Number.POSITIVE_INFINITY

  function closeWindow(endExclusiveIndex: number) {
    pushBestWindow(windows, windowStartIndex, bestIndex, endExclusiveIndex)
    windowStartIndex = null
    bestIndex = null
    bestValue = Number.POSITIVE_INFINITY
  }

  points.forEach((point, index) => {
    const value = point.value
    if (!finiteNumber(value) || isZeroingWindowPoint(point)) {
      closeWindow(index)
      return
    }
    if (windowStartIndex == null) windowStartIndex = index
    if (value < bestValue) {
      bestValue = value
      bestIndex = index
    }
  })
  closeWindow(points.length)

  return windows
}

function findWaitAdvantageWindows(points: MarketTimingPoint[]): WaitAdvantageWindow[] {
  return points.some(point => finiteNumber(point.nonZeroWindowId))
    ? findBackendWindowIds(points)
    : findLegacyWaitAdvantageWindows(points)
}

function average(values: number[]) {
  return values.reduce((sum, value) => sum + value, 0) / values.length
}

function collectMonthlyWindowValues(
  points: MarketTimingPoint[],
  window: WaitAdvantageWindow,
  normalizeDayZero: boolean,
  getValue: (point: MarketTimingPoint) => number | undefined,
) {
  const anchorPoint = points[window.anchorIndex]
  if (!anchorPoint?.date) return []
  const anchorValue = getValue(anchorPoint)
  if (!finiteNumber(anchorValue)) return []

  const grouped = new Map<number, { sum: number; count: number }>()
  for (let pointIndex = window.leftBoundaryIndex; pointIndex <= window.rightBoundaryIndex; pointIndex++) {
    const point = points[pointIndex]
    if (!point?.date) continue
    const pointValue = getValue(point)
    if (!finiteNumber(pointValue)) continue
    const monthOffset = monthOffsetFromAnchor(anchorPoint.date, point.date)
    if (monthOffset == null) continue
    const value = normalizeDayZero ? pointValue - anchorValue : pointValue
    const current = grouped.get(monthOffset) ?? { sum: 0, count: 0 }
    current.sum += value
    current.count += 1
    grouped.set(monthOffset, current)
  }

  return Array.from(grouped.entries()).map(([monthOffset, item]) => ({
    monthOffset,
    value: item.sum / item.count,
  }))
}

function windowMonthlyValues(
  points: MarketTimingPoint[],
  window: WaitAdvantageWindow,
  normalizeDayZero: boolean,
) {
  return collectMonthlyWindowValues(
    points,
    window,
    normalizeDayZero,
    point => finiteNumber(point.value) ? point.value : undefined,
  )
}

function totalMarginValue(point: MarketTimingPoint, margin: number) {
  if (!finiteNumber(point.basePortfolioReturn) || !finiteNumber(point.marginExcessReturn)) return undefined
  return point.basePortfolioReturn + (margin / 100) * point.marginExcessReturn
}

function marginTotalDifference(point: MarketTimingPoint, margin: number, baseMargin: number) {
  const baseTotal = totalMarginValue(point, baseMargin)
  const comparisonTotal = totalMarginValue(point, margin)
  if (!finiteNumber(baseTotal) || !finiteNumber(comparisonTotal)) return undefined
  if (baseTotal === 0) return undefined
  return comparisonTotal / baseTotal - 1
}

function windowMonthlyMarginValues(
  points: MarketTimingPoint[],
  window: WaitAdvantageWindow,
  margin: number,
  baseMargin: number,
  normalizeDayZero: boolean,
) {
  return collectMonthlyWindowValues(
    points,
    window,
    normalizeDayZero,
    point => marginTotalDifference(point, margin, baseMargin),
  )
}

export function makeMarketTimingChartData(results: MarketTimingResponse | null) {
  if (!results?.results.length) return null
  const dates = results.results[0].points.map(p => p.date)
  const referenceByDate = new Map(results.referencePoints.map(p => [p.date, p.value]))
  const dataKeys = ['reference', ...results.results.map((_, i) => `dd${i}`)]
  const rows = dates.map((date, i) => {
    const row: Record<string, any> = { x: date }
    row.reference = referenceByDate.get(date)
    results.results.forEach((result, ri) => {
      const point = result.points[i]
      row[`dd${ri}`] = point?.value ?? undefined
      row[`dd${ri}Trigger`] = point?.triggerDate
      row[`dd${ri}Days`] = point?.daysToTrigger
      row[`dd${ri}RefDd`] = point?.referenceDrawdown
    })
    return row
  })

  return {
    rows: downsampleChartRows(rows, dataKeys, MAX_MARKET_TIMING_CHART_ROWS),
  }
}

export function makeWindowAverageChartData(results: MarketTimingResult[], normalizeDayZero: boolean) {
  const windowGroups = results.map(result => findWaitAdvantageWindows(result.points))
  const monthlyGroups = windowGroups.map((windows, resultIndex) =>
    windows.map(window => windowMonthlyValues(results[resultIndex].points, window, normalizeDayZero))
  )
  const windowCounts: number[] = []
  let minOffset = 0
  let maxOffset = 0

  monthlyGroups.forEach((windows, resultIndex) => {
    windowCounts[resultIndex] = windows.length
    windows.forEach(monthlyValues => {
      monthlyValues.forEach(({ monthOffset }) => {
        minOffset = Math.min(minOffset, monthOffset)
        maxOffset = Math.max(maxOffset, monthOffset)
      })
    })
  })

  if (!windowCounts.some(count => count > 0)) return null

  const rows = Array.from({ length: maxOffset - minOffset + 1 }, (_, i) => {
    const offset = minOffset + i
    const row: Record<string, number | undefined> = { x: offset }
    monthlyGroups.forEach((windows, resultIndex) => {
      const values = windows
        .map(monthlyValues => monthlyValues.find(item => item.monthOffset === offset)?.value)
        .filter(finiteNumber)
      if (values.length > 0) row[`dd${resultIndex}`] = average(values)
    })
    return row
  })

  return {
    rows: downsampleChartRows(
      rows,
      results.map((_, i) => `dd${i}`),
      MAX_WINDOW_AVERAGE_CHART_ROWS,
      row => row.x === 0,
    ),
    windowCounts,
  }
}

export function makeMarginComparisonChartData(
  result: MarketTimingResult,
  baseMargin: number,
  normalizeDayZero: boolean,
) {
  const windows = findWaitAdvantageWindows(result.points)
  const monthlyGroups = windows.map(window => windowMonthlyValues(result.points, window, normalizeDayZero))
  const marginMonthlyGroups = MARGIN_COMPARISON_LEVELS.map(margin => ({
    margin,
    windows: windows.map(window =>
      windowMonthlyMarginValues(result.points, window, margin, baseMargin, normalizeDayZero)
    ),
  }))
  let minOffset = 0
  let maxOffset = 0

  monthlyGroups.forEach(monthlyValues => {
    monthlyValues.forEach(({ monthOffset }) => {
      minOffset = Math.min(minOffset, monthOffset)
      maxOffset = Math.max(maxOffset, monthOffset)
    })
  })

  if (windows.length === 0) return null

  const rows = Array.from({ length: maxOffset - minOffset + 1 }, (_, i) => {
    const offset = minOffset + i
    const row: Record<string, number | undefined> = { x: offset }
    marginMonthlyGroups.forEach(({ margin, windows }) => {
      const values = windows
        .map(monthlyValues => monthlyValues.find(item => item.monthOffset === offset)?.value)
        .filter(finiteNumber)
      if (values.length > 0) row[`m${margin}`] = average(values)
    })
    return row
  })

  return {
    rows: downsampleChartRows(
      rows,
      MARGIN_COMPARISON_LEVELS.map(margin => `m${margin}`),
      MAX_WINDOW_AVERAGE_CHART_ROWS,
      row => row.x === 0,
    ),
    windowCount: windows.length,
  }
}

export function makeReferenceDrawdownChartData(results: MarketTimingResponse | null) {
  if (!results?.referencePoints.length) return []
  let peak = Number.NEGATIVE_INFINITY
  const rows = results.referencePoints.map(point => {
    const value = point.value
    if (!Number.isFinite(value) || value <= 0) {
      return { x: point.date, referenceDrawdown: undefined }
    }
    peak = Math.max(peak, value)
    return {
      x: point.date,
      referenceDrawdown: peak > 0 ? value / peak - 1 : undefined,
    }
  })
  return downsampleChartRows(rows, ['referenceDrawdown'], MAX_MARKET_TIMING_CHART_ROWS)
}

export function makeWorldCapeChartData(points: WorldCapePoint[]) {
  const rows = points.map(point => ({
    x: point.date,
    usProxyCape: point.sourceMethod === 'US_SHILLER_PROXY' ? point.worldCape : undefined,
    syntheticCape: point.sourceMethod.startsWith('SYNTHETIC_EP_BLEND') ? point.worldCape : undefined,
    siblisCape: point.sourceMethod === 'SIBLIS_FREE_ANCHOR' ? point.worldCape : undefined,
    currentReferenceCape: point.sourceMethod === 'RA_CURRENT_REFERENCE' ? point.worldCape : undefined,
    source: sourceMethodLabel(point.sourceMethod),
  }))
  return downsampleChartRows(
    rows,
    ['usProxyCape', 'syntheticCape', 'siblisCape', 'currentReferenceCape'],
    MAX_CAPE_CHART_ROWS,
  )
}

export function makeUsCapeChartData(points: UsCapePoint[]) {
  const rows = points.map(point => ({
    x: point.date,
    usCape: point.usCape,
  }))
  return downsampleChartRows(rows, ['usCape'], MAX_CAPE_CHART_ROWS)
}

export function makeCapeSummary<T extends { date: string }>(
  points: T[],
  valueSelector: (point: T) => number,
) {
  if (!points.length) return null
  const values = points.map(valueSelector)
  const latest = points[points.length - 1]
  return {
    latest,
    startDate: points[0].date,
    endDate: latest.date,
    min: Math.min(...values),
    max: Math.max(...values),
    count: points.length,
  }
}

export function makeMarketTimingLineStyles(resultCount: number) {
  return Array.from({ length: resultCount }, (_, i) => {
    const groupIndex = i % PALETTE.length
    const variantIndex = Math.floor(i / PALETTE.length)
    const palette = PALETTE[groupIndex % PALETTE.length]
    return {
      stroke: palette[variantIndex % palette.length],
      strokeWidth: Math.max(1.4, 2.4 - variantIndex * 0.25),
    }
  })
}
