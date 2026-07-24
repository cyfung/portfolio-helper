import { blockStateToAPIPortfolio, blockStateToSavedConfig } from '@/types/backtest'
import type { BlockConversionOptions, BlockState, SavedPortfolio } from '@/types/backtest'
import {
  expandSwapTickerRows,
  isResolvedNonZeroWeight,
} from '@/lib/tickerExpressions'
import {
  resolvePortfolioComposition,
  resolveRootPortfolioComposition,
  type PortfolioResolutionIssue,
  type ResolvedPortfolioComposition,
} from '@/lib/portfolioComposition'
import {
  getSavedPortfolios,
  SAVED_PORTFOLIOS_CHANGED_EVENT,
} from '@/lib/savedPortfolioCache'

export { SAVED_PORTFOLIOS_CHANGED_EVENT }

export interface ResolvedStockWeight {
  ticker: string
  weight: number
}

export const DUMMY_TICKER = 'DUMMY'

export function isPlaceholderTicker(ticker: string) {
  return ticker.trim().toUpperCase() === DUMMY_TICKER
}

export async function fetchSavedPortfolios(): Promise<SavedPortfolio[]> {
  return getSavedPortfolios()
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
  if (!Array.isArray(config?.rows)) throw new Error('Saved portfolio is missing tagged rows.')
  const savedConfigurations = new Map(
    [...savedByName].map(([name, value]) => [name, { rows: value?.rows ?? [] }]),
  )
  const resolution = resolvePortfolioComposition(config.rows, savedConfigurations, {
    referencePath: stack,
    stack,
  })
  assertResolved(resolution)
  return resolution.composition.map(position => ({
    ticker: position.instrument,
    weight: position.exposure,
  }))
}

function formatResolutionIssue(issue: PortfolioResolutionIssue) {
  const path = issue.referencePath?.length ? `${issue.referencePath.join(' → ')}: ` : ''
  return `${path}${issue.message}`
}

function assertResolved(resolution: ResolvedPortfolioComposition) {
  if (resolution.issues.length > 0) {
    throw new Error(resolution.issues.map(formatResolutionIssue).join('\n'))
  }
}

function savedConfigurationMap(savedPortfolios: SavedPortfolio[]) {
  return new Map(savedPortfolios.map(portfolio => [
    portfolio.name,
    { rows: savedPortfolioConfig(portfolio.config)?.rows ?? [] },
  ]))
}

export function blockStateResolution(
  block: BlockState,
  savedPortfolios: SavedPortfolio[],
): ResolvedPortfolioComposition {
  const rows = blockStateToSavedConfig(block).rows
  const rootName = block.label.trim()
  return resolvePortfolioComposition(rows, savedConfigurationMap(savedPortfolios), {
    referencePath: rootName ? [rootName] : [],
    stack: rootName ? [rootName] : [],
  })
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
  const rows = blockStateToSavedConfig(block).rows
  const resolution = options.normalize === false
    ? blockStateResolution(block, savedPortfolios)
    : resolveRootPortfolioComposition(rows, savedConfigurationMap(savedPortfolios), {
      rootName: block.label.trim() || undefined,
    })
  assertResolved(resolution)
  return resolution.composition
    .map(position => ({ ticker: position.instrument, weight: position.exposure }))
    .sort((a, b) => a.ticker.localeCompare(b.ticker))
}

export function blockStateToSettingsPortfolio(block: BlockState, idx: number) {
  const { tickers: _, ...settings } = blockStateToAPIPortfolio(block, idx)
  return { ...settings, rows: blockStateToSavedConfig(block).rows }
}

export function resolvedBlockStateToAPIPortfolio(
  block: BlockState,
  idx: number,
  savedPortfolios: SavedPortfolio[],
  options: BlockConversionOptions = {},
) {
  const apiPortfolio = blockStateToAPIPortfolio(block, idx, options)
  const tickers = resolveBlockState(block, savedPortfolios)

  return { ...apiPortfolio, tickers }
}
