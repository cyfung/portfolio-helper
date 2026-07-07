import { blockStateToAPIPortfolio } from '@/types/backtest'
import type { BlockState, SavedPortfolio } from '@/types/backtest'
import {
  expandSwapTickerRows,
  isResolvedNonZeroWeight,
  resolveSwapTickerRows,
  type WeightedOrWildcardTickerExpression,
} from '@/lib/tickerExpressions'

export const SAVED_PORTFOLIOS_CHANGED_EVENT = 'saved-portfolios-changed'

export interface ResolvedStockWeight {
  ticker: string
  weight: number
}

export const DUMMY_TICKER = 'DUMMY'

export function isPlaceholderTicker(ticker: string) {
  return ticker.trim().toUpperCase() === DUMMY_TICKER
}

export async function fetchSavedPortfolios(): Promise<SavedPortfolio[]> {
  try {
    const res = await fetch('/api/backtest/savedPortfolios')
    if (!res.ok) return []
    return await res.json()
  } catch (_) {
    return []
  }
}

function isPortfolioRef(row: any) {
  return row?.isPortfolioRef === true || row?.type === 'PORTFOLIO_REF' || !!row?.portfolioRef
}

function refName(row: any) {
  return String(row?.portfolioRef || row?.ticker || '').trim()
}

function rowWeight(row: any) {
  return parseFloat(String(row?.weight ?? '')) || 0
}

function rowResolveWeight(row: any): number | '*' {
  const raw = String(row?.weight ?? '').trim()
  if (raw === '*') return '*'
  return parseFloat(raw) || 0
}

function addWeight(map: Map<string, number>, ticker: string, weight: number) {
  const key = ticker.trim().toUpperCase()
  if (!key || !isResolvedNonZeroWeight(weight)) return
  map.set(key, (map.get(key) ?? 0) + weight)
}

function normalizeResolvedRows(rows: ResolvedStockWeight[]) {
  if (rows.length === 0) return rows
  const total = rows.reduce((sum, row) => sum + row.weight, 0)
  if (total <= 0) throw new Error('Portfolio net weight must be positive after merging signed rows.')
  let allocated = 0
  return rows.map((row, index) => {
    const weight = index === rows.length - 1 ? 100 - allocated : row.weight * 100 / total
    allocated += weight
    return { ...row, weight }
  })
}

export function stripPlaceholderAndNormalizeResolvedRows(rows: ResolvedStockWeight[]) {
  return normalizeResolvedRows(stripPlaceholderResolvedRows(rows))
}

export function stripPlaceholderResolvedRows(rows: ResolvedStockWeight[]) {
  return mergedNonZeroRows(expandSwapTickerRows(rows).filter(row => !isPlaceholderTicker(row.ticker)))
}

function mergedNonZeroRows(rows: ResolvedStockWeight[]) {
  const weights = new Map<string, number>()
  rows.forEach(row => addWeight(weights, row.ticker, row.weight))
  return [...weights.entries()]
    .filter(([, weight]) => isResolvedNonZeroWeight(weight))
    .map(([ticker, weight]) => ({ ticker, weight }))
    .sort((a, b) => a.ticker.localeCompare(b.ticker))
}

function scaledChildRows(parentWeight: number, childStocks: ResolvedStockWeight[]) {
  const childTotal = childStocks.reduce((sum, childStock) => sum + childStock.weight, 0)
  if (childTotal === 0) throw new Error('Portfolio reference net weight cannot be zero after merging signed rows.')
  const denominator = Math.abs(childTotal)
  const targetTotal = parentWeight * (childTotal < 0 ? -1 : 1)
  let allocated = 0

  return childStocks.map((childStock, index) => {
    const isLast = index === childStocks.length - 1
    const weight = isLast ? targetTotal - allocated : parentWeight * childStock.weight / denominator
    allocated += weight
    return { ...childStock, weight }
  })
}

export function savedPortfolioConfig(config: any) {
  return config?.portfolios?.[0] ?? config
}

export function savedPortfolioConfigMap(savedPortfolios: SavedPortfolio[]) {
  return new Map(savedPortfolios.map(p => [p.name, savedPortfolioConfig(p.config)]))
}

export function resolveSavedPortfolioConfig(
  config: any,
  savedByName: Map<string, any>,
  stack: string[] = [],
): ResolvedStockWeight[] {
  const rows: WeightedOrWildcardTickerExpression[] = []

  for (const row of config?.tickers ?? []) {
    const weight = rowResolveWeight(row)
    const portfolioRef = isPortfolioRef(row)
    const ticker = portfolioRef ? refName(row) : String(row.ticker || '')
    if (weight !== '*' && !isResolvedNonZeroWeight(weight)) continue

    if (portfolioRef) {
      if (weight === '*') throw new Error('Portfolio reference weight cannot be *.')
      const name = ticker
      const child = savedByName.get(name)
      if (!child) throw new Error(`Missing portfolio reference: ${name}`)
      if (stack.includes(name)) throw new Error(`Circular portfolio reference: ${[...stack, name].join(' -> ')}`)

      for (const childStock of scaledChildRows(weight, resolveSavedPortfolioConfig(child, savedByName, [...stack, name]))) {
        rows.push(childStock)
      }
    } else {
      rows.push({ ticker, weight })
    }
  }

  return mergedNonZeroRows(resolveSwapTickerRows(rows))
}

export function resolveBlockState(
  block: BlockState,
  savedPortfolios: SavedPortfolio[],
): ResolvedStockWeight[] {
  return resolveBlockStateRows(block, savedPortfolios, { normalize: true })
}

export function resolveBlockStateRows(
  block: BlockState,
  savedPortfolios: SavedPortfolio[],
  options: { normalize?: boolean } = {},
): ResolvedStockWeight[] {
  const savedByName = savedPortfolioConfigMap(savedPortfolios)
  const config = {
    tickers: block.tickers.map(row => row.isPortfolioRef
      ? { ticker: row.ticker, portfolioRef: row.ticker, isPortfolioRef: true, weight: row.weight }
      : { ticker: row.ticker, weight: row.weight }
    ),
  }
  const rows = resolveSavedPortfolioConfig(config, savedByName, block.label.trim() ? [block.label.trim()] : [])
  return options.normalize === false
    ? stripPlaceholderResolvedRows(rows)
    : stripPlaceholderAndNormalizeResolvedRows(rows)
}

export function blockStateToSettingsPortfolio(block: BlockState, idx: number) {
  const apiPortfolio = blockStateToAPIPortfolio(block, idx)
  const tickers = block.tickers
    .map(row => {
      const isRef = row.isPortfolioRef === true
      const ticker = isRef ? row.ticker.trim() : row.ticker.trim().toUpperCase()
      return { ticker, weight: rowResolveWeight(row), isRef }
    })
    .filter(row => row.ticker && (row.weight === '*' || row.weight !== 0))
    .map(row => row.isRef
      ? { ticker: row.ticker, weight: row.weight, isPortfolioRef: true, portfolioRef: row.ticker }
      : { ticker: row.ticker, weight: row.weight }
    )

  return { ...apiPortfolio, tickers }
}

export function resolvedBlockStateToAPIPortfolio(
  block: BlockState,
  idx: number,
  savedPortfolios: SavedPortfolio[],
) {
  const apiPortfolio = blockStateToAPIPortfolio(block, idx)
  const tickers = mergedNonZeroRows(resolveBlockState(block, savedPortfolios))

  return { ...apiPortfolio, tickers }
}
