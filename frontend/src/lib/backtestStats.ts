import type { BacktestCurve } from '@/types/backtest'

export interface SeriesStats {
  endingValue: number
  cagr: number
  maxDrawdown: number
  averageDrawdown?: number
  longestDrawdownDays: number
  annualVolatility: number
  sharpe: number
  sortino?: number
  calmar?: number
  beta?: number
  ulcerIndex: number
  upi: number
}

export interface CommonStatsRow {
  key: string
  label: string
  color: string
  stats: SeriesStats
  avgMargin?: number | null
  actionCounts?: Record<string, number>
  endingValueFactor?: number
}

export function averageFinite(values: (number | null | undefined)[] | undefined) {
  const finiteValues = values?.filter((value): value is number => Number.isFinite(value)) ?? []
  return finiteValues.length > 0 ? finiteValues.reduce((sum, value) => sum + value, 0) / finiteValues.length : null
}

export function averageMarginUtilization(points: { value: number }[] | undefined) {
  return averageFinite(points?.map(p => p.value))
}

export function actionPointCount(actionPoints: { type: string }[] | undefined, type: string) {
  return actionPoints?.filter(point => point.type === type).length ?? 0
}

export function actionCountsFromCurve(curve: Pick<BacktestCurve, 'actionPoints' | 'stats'>): Record<string, number> {
  const actionCounts: Record<string, number> = {}
  curve.actionPoints?.forEach(point => {
    actionCounts[point.type] = (actionCounts[point.type] ?? 0) + 1
  })
  if (curve.stats.marginLowerTriggers != null) actionCounts.BUY_LOW = curve.stats.marginLowerTriggers
  if (curve.stats.marginUpperTriggers != null) actionCounts.SELL_HIGH = curve.stats.marginUpperTriggers
  return actionCounts
}

export function computeSeriesStats(dates: string[], values: number[], benchmarkValues: number[] = []): SeriesStats | null {
  if (values.length < 2) return null
  const n = values.length
  const start = values[0]
  const end = values[n - 1]
  if (start <= 0) return null

  const years = (new Date(dates[n - 1]).getTime() - new Date(dates[0]).getTime()) / (365.25 * 86400000)
  if (years <= 0) return null
  const cagr = Math.pow(end / start, 1 / years) - 1

  const returns: number[] = []
  for (let i = 1; i < n; i++) {
    if (values[i - 1] > 0 && values[i] > 0) returns.push(values[i] / values[i - 1] - 1)
  }
  const mean = returns.reduce((a, b) => a + b, 0) / Math.max(returns.length, 1)
  const variance = returns.reduce((a, b) => a + (b - mean) ** 2, 0) / Math.max(returns.length - 1, 1)
  const annualVolatility = Math.sqrt(variance * 252)
  const sharpe = annualVolatility > 0 ? cagr / annualVolatility : 0
  const downsideMeanSquare = returns.reduce((sum, r) => sum + Math.min(0, r) ** 2, 0) / Math.max(returns.length, 1)
  const downsideDeviation = Math.sqrt(downsideMeanSquare * 252)
  const sortino = downsideDeviation > 0 ? cagr / downsideDeviation : 0

  let peak = start
  let maxDrawdown = 0
  let drawdownSum = 0
  let drawdownCount = 0
  let longestDrawdownDays = 0
  let ddStart: Date | null = null
  let sumSq = 0
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
    }
    const dd = peak > 0 ? Math.max(0, 1 - values[i] / peak) : 0
    if (dd > maxDrawdown) maxDrawdown = dd
    drawdownSum += dd
    drawdownCount++
    sumSq += dd * dd
  }
  if (ddStart) {
    const days = (new Date(dates[n - 1]).getTime() - ddStart.getTime()) / 86400000
    if (days > longestDrawdownDays) longestDrawdownDays = days
  }

  const ulcerIndex = Math.sqrt(sumSq / n)
  const upi = ulcerIndex > 0 ? cagr / ulcerIndex : 0
  const averageDrawdown = drawdownCount > 0 ? drawdownSum / drawdownCount : 0
  const calmar = maxDrawdown > 0 ? cagr / maxDrawdown : 0
  const beta = benchmarkValues.length >= 2 ? computeBeta(values, benchmarkValues) : undefined

  return { endingValue: end, cagr, maxDrawdown, averageDrawdown, longestDrawdownDays, annualVolatility, sharpe, sortino, calmar, beta, ulcerIndex, upi }
}

function computeBeta(values: number[], benchmarkValues: number[]) {
  if (benchmarkValues.length < 2) return 0
  const portfolioReturns: number[] = []
  const benchmarkReturns: number[] = []
  const lastIndex = Math.min(values.length, benchmarkValues.length) - 1
  for (let i = 1; i <= lastIndex; i++) {
    const prev = values[i - 1]
    const benchmarkPrev = benchmarkValues[i - 1]
    const benchmarkCur = benchmarkValues[i]
    if (prev <= 0 || benchmarkPrev <= 0) continue
    portfolioReturns.push(values[i] / prev - 1)
    benchmarkReturns.push(benchmarkCur / benchmarkPrev - 1)
  }
  if (portfolioReturns.length < 2) return 0
  const portfolioMean = averageFinite(portfolioReturns) ?? 0
  const benchmarkMean = averageFinite(benchmarkReturns) ?? 0
  let covariance = 0
  let variance = 0
  for (let i = 0; i < portfolioReturns.length; i++) {
    const benchmarkDelta = benchmarkReturns[i] - benchmarkMean
    covariance += (portfolioReturns[i] - portfolioMean) * benchmarkDelta
    variance += benchmarkDelta * benchmarkDelta
  }
  return variance > 0 ? covariance / variance : 0
}
