import { blockStateToAPIPortfolio } from '@/types/backtest'
import type { BlockState, SavedPortfolio } from '@/types/backtest'

export interface ResolvedStockWeight {
  ticker: string
  weight: number
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

function addWeight(map: Map<string, number>, ticker: string, weight: number) {
  const key = ticker.trim().toUpperCase()
  if (!key || weight <= 0) return
  map.set(key, (map.get(key) ?? 0) + weight)
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
  const weights = new Map<string, number>()

  for (const row of config?.tickers ?? []) {
    const weight = rowWeight(row)
    if (weight <= 0) continue

    if (isPortfolioRef(row)) {
      const name = refName(row)
      const child = savedByName.get(name)
      if (!child) throw new Error(`Missing portfolio reference: ${name}`)
      if (stack.includes(name)) throw new Error(`Circular portfolio reference: ${[...stack, name].join(' -> ')}`)

      const childStocks = resolveSavedPortfolioConfig(child, savedByName, [...stack, name])
      for (const childStock of childStocks) {
        addWeight(weights, childStock.ticker, weight * childStock.weight / 100)
      }
    } else {
      addWeight(weights, String(row.ticker || ''), weight)
    }
  }

  return [...weights.entries()]
    .map(([ticker, weight]) => ({ ticker, weight }))
    .sort((a, b) => a.ticker.localeCompare(b.ticker))
}

export function resolveBlockState(
  block: BlockState,
  savedPortfolios: SavedPortfolio[],
): ResolvedStockWeight[] {
  const savedByName = savedPortfolioConfigMap(savedPortfolios)
  const config = {
    tickers: block.tickers.map(row => row.isPortfolioRef
      ? { ticker: row.ticker, portfolioRef: row.ticker, isPortfolioRef: true, weight: rowWeight(row) }
      : { ticker: row.ticker, weight: rowWeight(row) }
    ),
  }
  return resolveSavedPortfolioConfig(config, savedByName, block.label.trim() ? [block.label.trim()] : [])
}

export function resolvedBlockStateToAPIPortfolio(
  block: BlockState,
  idx: number,
  savedPortfolios: SavedPortfolio[],
) {
  const apiPortfolio = blockStateToAPIPortfolio(block, idx)
  const tickers = resolveBlockState(block, savedPortfolios)
    .map(row => ({ ticker: row.ticker, weight: row.weight }))
    .filter(row => row.ticker && row.weight > 0)

  return { ...apiPortfolio, tickers }
}
