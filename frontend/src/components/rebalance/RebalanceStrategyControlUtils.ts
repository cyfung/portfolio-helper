import React from 'react'
import type { RebalStrategyState } from '@/types/rebalanceStrategy'

export const DEFAULT_POINTS = ['40', '45', '50', '55', '60']

export type OptionalStrategySectionKey =
  | 'marginRebalance'
  | 'drawdownMarginOverride'
  | 'vmTimingMr'
  | 'buyLow'
  | 'sellHigh'
  | 'drawdownBuyOnLowMargin'
  | 'buyTheDipPortfolio'
  | 'buyTheDipIndividual'
  | 'sellOnSurgePortfolio'
  | 'sellOnSurgeIndividual'

export const OPTIONAL_STRATEGY_SECTIONS: { key: OptionalStrategySectionKey; label: string }[] = [
  { key: 'marginRebalance', label: 'Margin Rebalance' },
  { key: 'drawdownMarginOverride', label: 'Drawdown MR Override' },
  { key: 'vmTimingMr', label: 'VM-timing-MR' },
  { key: 'buyLow', label: 'BL' },
  { key: 'sellHigh', label: 'SH' },
  { key: 'drawdownBuyOnLowMargin', label: 'BL on Drawdown' },
  { key: 'buyTheDipPortfolio', label: 'Buy the Dip - Portfolio Trigger' },
  { key: 'buyTheDipIndividual', label: 'Buy the Dip - Individual Stocks' },
  { key: 'sellOnSurgePortfolio', label: 'Sell on Surge - Portfolio Trigger' },
  { key: 'sellOnSurgeIndividual', label: 'Sell on Surge - Individual Stocks' },
]

export function isOptionalStrategySectionEnabled(s: RebalStrategyState, key: OptionalStrategySectionKey) {
  switch (key) {
    case 'marginRebalance':
      return s.marginRebalanceEnabled ?? true
    case 'drawdownMarginOverride':
      return (s.marginRebalanceEnabled ?? true) && (s.drawdownMarginOverride?.enabled ?? false)
    case 'vmTimingMr':
      return s.vmTimingMr?.enabled ?? false
    case 'buyLow':
      return s.buyLowEnabled
    case 'sellHigh':
      return s.sellHighEnabled
    case 'drawdownBuyOnLowMargin':
      return s.drawdownBuyOnLowMargin?.enabled ?? false
    case 'buyTheDipPortfolio':
      return s.buyTheDip?.basePortfolio != null
    case 'buyTheDipIndividual':
      return s.buyTheDip?.individualStock != null
    case 'sellOnSurgePortfolio':
      return s.sellOnSurge?.basePortfolio != null
    case 'sellOnSurgeIndividual':
      return s.sellOnSurge?.individualStock != null
  }
}

export function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}

export function parsePoint(v: string | undefined, fallback: number) {
  const n = parseInt(v ?? '', 10)
  return Number.isFinite(n) ? n : fallback
}

export function normalizeMarginPoints(points: string[] | undefined, max: number) {
  const sliderMax = Math.max(4, Math.floor(max))
  const values = DEFAULT_POINTS.map((def, i) => clamp(parsePoint(points?.[i], parseInt(def, 10)), 0, sliderMax))

  values[0] = clamp(values[0], 0, sliderMax - 4)
  values[1] = clamp(values[1], values[0] + 1, sliderMax - 3)
  values[2] = clamp(values[2], values[1] + 1, sliderMax - 2)
  values[3] = clamp(values[3], values[2] + 1, sliderMax - 1)
  values[4] = clamp(values[4], values[3] + 1, sliderMax)

  for (let i = 3; i >= 0; i -= 1) {
    values[i] = Math.min(values[i], values[i + 1] - 1)
  }
  for (let i = 1; i < values.length; i += 1) {
    values[i] = Math.max(values[i], values[i - 1] + 1)
  }

  return values
}

export function adjustMarginPoint(points: number[], index: number, value: number, max: number) {
  const sliderMax = Math.max(4, Math.floor(max))
  const next = [...points]
  const target = clamp(Math.round(value), index, sliderMax - (next.length - 1 - index))

  next[index] = target

  for (let i = index - 1; i >= 0; i -= 1) {
    if (next[i] >= next[i + 1]) next[i] = next[i + 1] - 1
  }
  for (let i = index + 1; i < next.length; i += 1) {
    if (next[i] <= next[i - 1]) next[i] = next[i - 1] + 1
  }

  return next.map(v => clamp(v, 0, sliderMax))
}

export function samePoints(a: string[], b: string[]) {
  return a.length === b.length && a.every((point, i) => point === b[i])
}

export function keepSectionOpen(e: React.SyntheticEvent<HTMLElement>) {
  e.preventDefault()
}

export function marginValueFromLegacyPoint(points: string[], index: string | undefined, offset = 0) {
  const pointIndex = parseInt(index ?? '', 10) - offset
  if (!Number.isFinite(pointIndex) || pointIndex === 2) return ''
  return points[pointIndex] ?? ''
}
