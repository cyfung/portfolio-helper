import { SAVED_PORTFOLIOS_CHANGED_EVENT, fetchSavedPortfolios, resolveSavedPortfolioConfig, savedPortfolioConfig, savedPortfolioConfigMap } from '@/lib/portfolioRefs'
import type { SavedPortfolio } from '@/types/backtest'

export interface TickerConfigExport {
  symbol: string
  letf: string
  groups: string
}

export interface SavedStrategyExport {
  name: string
  config: any
}

export interface ImportDependencyPreview {
  savedPortfolios: {
    name: string
    config: any
    action: 'add' | 'replace'
  }[]
  tickerConfigs: {
    symbol: string
    current: TickerConfigExport
    next: TickerConfigExport
  }[]
  savedStrategies: {
    name: string
    config: any
    action: 'add' | 'replace'
  }[]
}

type ConfigPayload = Record<string, unknown>

function isPortfolioRef(row: any) {
  return row?.isPortfolioRef === true || row?.type === 'PORTFOLIO_REF' || !!row?.portfolioRef
}

function refName(row: any) {
  return String(row?.portfolioRef || row?.ticker || '').trim()
}

function stockName(row: any) {
  return String(row?.ticker || '').trim().toUpperCase()
}

function sortedByName<T extends { name: string }>(items: T[]) {
  return [...items].sort((a, b) => a.name.localeCompare(b.name))
}

function normalizeTickerConfig(config: Partial<TickerConfigExport> & { symbol?: unknown }): TickerConfigExport | null {
  const symbol = String(config.symbol ?? '').trim().toUpperCase()
  if (!symbol) return null
  return {
    symbol,
    letf: String(config.letf ?? '').trim(),
    groups: String(config.groups ?? '').trim(),
  }
}

function normalizeSavedPortfolios(value: unknown): SavedPortfolio[] {
  if (!Array.isArray(value)) return []
  const byName = new Map<string, SavedPortfolio>()
  value.forEach(item => {
    const name = String(item?.name ?? '').trim()
    if (!name || item?.config == null) return
    byName.set(name, { name, config: item.config })
  })
  return sortedByName([...byName.values()])
}

function normalizeSavedStrategies(value: unknown): SavedStrategyExport[] {
  if (!Array.isArray(value)) return []
  const byName = new Map<string, SavedStrategyExport>()
  value.forEach(item => {
    const name = String(item?.name ?? '').trim()
    if (!name || item?.config == null) return
    byName.set(name, { name, config: item.config })
  })
  return sortedByName([...byName.values()])
}

function parseLetfDefinition(raw: string) {
  const tokens = raw.trim().replace(/,/g, ' ').split(/\s+/).filter(Boolean)
  const components: { ticker: string; weight: number }[] = []

  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (/^(?:[SREV]|VOL)=/i.test(token)) {
      i += 1
      continue
    }

    const multiplier = parseFloat(token)
    if (Number.isFinite(multiplier) && i + 1 < tokens.length && !/^(?:[SREV]|VOL)=/i.test(tokens[i + 1])) {
      components.push({ ticker: tokens[i + 1].toUpperCase(), weight: multiplier })
      i += 2
    } else if (!Number.isFinite(multiplier)) {
      components.push({ ticker: token.toUpperCase(), weight: 1 })
      i += 1
    } else {
      i += 1
    }
  }

  return components
}

function collectPortfolioRefNames(configs: any[], savedPortfolios: SavedPortfolio[]) {
  const savedByName = savedPortfolioConfigMap(savedPortfolios)
  const names = new Set<string>()

  function visitConfig(config: any, stack: string[]) {
    for (const row of config?.tickers ?? []) {
      if (!isPortfolioRef(row)) continue
      const name = refName(row)
      if (!name || names.has(name)) continue
      names.add(name)
      if (stack.includes(name)) continue
      const child = savedByName.get(name)
      if (child) visitConfig(child, [...stack, name])
    }
  }

  configs.forEach(config => visitConfig(config, []))
  return names
}

function collectDirectStockSymbols(config: any, symbols: Set<string>) {
  for (const row of config?.tickers ?? []) {
    if (isPortfolioRef(row)) continue
    const symbol = stockName(row)
    if (symbol) symbols.add(symbol)
  }
}

function collectRelatedTickerSymbols(configs: any[], savedPortfolios: SavedPortfolio[]) {
  const symbols = new Set<string>()
  const savedByName = savedPortfolioConfigMap(savedPortfolios)

  configs.forEach(config => {
    try {
      resolveSavedPortfolioConfig(config, savedByName)
        .forEach(row => symbols.add(row.ticker.toUpperCase()))
    } catch {
      collectDirectStockSymbols(config, symbols)
    }
  })

  return symbols
}

function collectSavedStrategiesFromPortfolios(configs: any[]) {
  const byName = new Map<string, SavedStrategyExport>()
  configs.forEach(config => {
    for (const row of config?.rebalanceStrategies ?? []) {
      const name = String(row?.name ?? row?.label ?? '').trim()
      const strategyConfig = row?.config
      if (!name || strategyConfig == null) continue
      byName.set(name, { name, config: strategyConfig })
    }
  })
  return sortedByName([...byName.values()])
}

async function fetchTickerConfig(symbol: string): Promise<TickerConfigExport> {
  const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(symbol)}`)
  if (!res.ok) throw new Error(`Failed to load ticker config for ${symbol}`)
  const data = await res.json()
  return {
    symbol,
    letf: String(data.letf ?? '').trim(),
    groups: String(data.groups ?? '').trim(),
  }
}

async function collectTickerConfigs(initialSymbols: Iterable<string>) {
  const queue = new Set([...initialSymbols].map(symbol => symbol.trim().toUpperCase()).filter(Boolean))
  const seen = new Set<string>()
  const configs = new Map<string, TickerConfigExport>()

  while (queue.size > 0) {
    const batch = [...queue].filter(symbol => !seen.has(symbol))
    queue.clear()
    if (batch.length === 0) break

    const entries = await Promise.all(batch.map(fetchTickerConfig))
    entries.forEach(config => {
      seen.add(config.symbol)
      if (config.letf || config.groups) configs.set(config.symbol, config)
      const letf = config.letf || (config.symbol.includes(' ') ? config.symbol : '')
      parseLetfDefinition(letf).forEach(component => {
        if (!seen.has(component.ticker)) queue.add(component.ticker)
      })
    })
  }

  return [...configs.values()].sort((a, b) => a.symbol.localeCompare(b.symbol))
}

export async function withPortfolioExportDependencies<T extends ConfigPayload>(
  payload: T,
  portfolioConfigs: any[],
  options: { savedStrategies?: SavedStrategyExport[] } = {},
): Promise<T & {
  savedPortfolios?: SavedPortfolio[]
  tickerConfigs?: TickerConfigExport[]
  savedStrategies?: SavedStrategyExport[]
}> {
  const savedPortfolios = await fetchSavedPortfolios()
  const relatedPortfolioNames = collectPortfolioRefNames(portfolioConfigs, savedPortfolios)
  const relatedPortfolios = sortedByName(
    savedPortfolios.filter(portfolio => relatedPortfolioNames.has(portfolio.name)),
  )
  const relatedTickerSymbols = collectRelatedTickerSymbols(portfolioConfigs, savedPortfolios)
  const tickerConfigs = await collectTickerConfigs(relatedTickerSymbols)
  const savedStrategies = normalizeSavedStrategies([
    ...collectSavedStrategiesFromPortfolios(portfolioConfigs),
    ...(options.savedStrategies ?? []),
  ])

  return {
    ...payload,
    ...(relatedPortfolios.length > 0 ? { savedPortfolios: relatedPortfolios } : {}),
    ...(tickerConfigs.length > 0 ? { tickerConfigs } : {}),
    ...(savedStrategies.length > 0 ? { savedStrategies } : {}),
  }
}

export async function fetchSavedStrategies(): Promise<SavedStrategyExport[]> {
  try {
    const res = await fetch('/api/rebalance-strategy/savedStrategies')
    if (!res.ok) return []
    return normalizeSavedStrategies(await res.json())
  } catch {
    return []
  }
}

function tickerConfigEquals(a: TickerConfigExport, b: TickerConfigExport) {
  return a.symbol === b.symbol && a.letf === b.letf && a.groups === b.groups
}

export async function buildImportDependencyPreview(payload: ConfigPayload): Promise<ImportDependencyPreview> {
  const importedSavedPortfolios = normalizeSavedPortfolios(payload.savedPortfolios)
  const importedTickerConfigs = Array.isArray(payload.tickerConfigs)
    ? payload.tickerConfigs.map(config => normalizeTickerConfig(config as Partial<TickerConfigExport>)).filter((v): v is TickerConfigExport => !!v)
    : []
  const importedSavedStrategies = normalizeSavedStrategies(payload.savedStrategies)

  const [currentSavedPortfolios, currentSavedStrategies, currentTickerConfigs] = await Promise.all([
    importedSavedPortfolios.length > 0 ? fetchSavedPortfolios() : Promise.resolve([]),
    importedSavedStrategies.length > 0 ? fetchSavedStrategies() : Promise.resolve([]),
    Promise.all(importedTickerConfigs.map(config => fetchTickerConfig(config.symbol))),
  ])

  const currentPortfolioNames = new Set(currentSavedPortfolios.map(portfolio => portfolio.name))
  const currentStrategyNames = new Set(currentSavedStrategies.map(strategy => strategy.name))
  const currentTickerBySymbol = new Map(currentTickerConfigs.map(config => [config.symbol, config]))

  return {
    savedPortfolios: importedSavedPortfolios.map(portfolio => ({
      name: portfolio.name,
      config: savedPortfolioConfig(portfolio.config),
      action: currentPortfolioNames.has(portfolio.name) ? 'replace' : 'add',
    })),
    tickerConfigs: importedTickerConfigs
      .map(config => ({ symbol: config.symbol, current: currentTickerBySymbol.get(config.symbol) ?? { symbol: config.symbol, letf: '', groups: '' }, next: config }))
      .filter(row => !tickerConfigEquals(row.current, row.next)),
    savedStrategies: importedSavedStrategies.map(strategy => ({
      name: strategy.name,
      config: strategy.config,
      action: currentStrategyNames.has(strategy.name) ? 'replace' : 'add',
    })),
  }
}

export function hasImportDependencyPreview(preview: ImportDependencyPreview) {
  return preview.savedPortfolios.length > 0 || preview.tickerConfigs.length > 0 || preview.savedStrategies.length > 0
}

async function replaceSavedJsonConfig(apiPath: string, name: string, config: any) {
  await fetch(`${apiPath}?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
  const res = await fetch(apiPath, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, config }),
  })
  if (!res.ok) throw new Error(`Failed to save ${name}`)
}

export async function applyImportDependencyPreview(preview: ImportDependencyPreview) {
  await Promise.all(preview.savedPortfolios.map(portfolio =>
    replaceSavedJsonConfig('/api/backtest/savedPortfolios', portfolio.name, portfolio.config),
  ))

  await Promise.all(preview.tickerConfigs.map(async row => {
    const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(row.next.symbol)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ letf: row.next.letf, groups: row.next.groups }),
    })
    if (!res.ok) throw new Error(`Failed to save ticker config for ${row.next.symbol}`)
  }))

  await Promise.all(preview.savedStrategies.map(strategy =>
    replaceSavedJsonConfig('/api/rebalance-strategy/savedStrategies', strategy.name, strategy.config),
  ))

  if (preview.savedPortfolios.length > 0) {
    window.dispatchEvent(new Event(SAVED_PORTFOLIOS_CHANGED_EVENT))
  }
}
