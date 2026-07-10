import { SAVED_PORTFOLIOS_CHANGED_EVENT, fetchSavedPortfolios, resolveSavedPortfolioConfig, savedPortfolioConfig, savedPortfolioConfigMap } from '@/lib/portfolioRefs'
import type { SavedPortfolio } from '@/types/backtest'
import {
  exportableSavedTickerMappings,
  loadTickerMappingSettings,
  mergeSavedTickerMappings,
  notifyTickerMappingsChanged,
  normalizeTickerMappingSets,
  saveTickerMappingSettings,
  isTickerMappingRef,
  tickerMappingRefName,
  type TickerMappingSet,
} from '@/lib/tickerMappings'
import { parseLetfComponents } from '@/lib/tickerExpressions'

export interface TickerConfigExport {
  symbol: string
  letf: string
  groups: string
}

export interface SavedStrategyExport {
  name: string
  config: any
}

export interface SavedTickerMappingExport {
  id: string
  name: string
  updatedAt?: string
  mappings: {
    id: string
    from: string
    to: string
    mode: 'prepend' | 'replaceAll'
    applyTo: 'expression' | 'ticker'
    isMappingRef?: boolean
    mappingRef?: string
  }[]
}

export type ImportDependencyAction = 'add' | 'replace'

export interface ImportDependencyPreview {
  savedPortfolios: {
    originalName: string
    name: string
    config: any
    action: ImportDependencyAction
    childNames: string[]
    parentNames: string[]
    referencedByImport: boolean
    enabled?: boolean
  }[]
  tickerConfigs: {
    symbol: string
    current: TickerConfigExport
    next: TickerConfigExport
    enabled?: boolean
  }[]
  savedStrategies: {
    originalName: string
    name: string
    config: any
    action: ImportDependencyAction
    enabled?: boolean
  }[]
  savedTickerMappings: {
    originalName: string
    name: string
    updatedAt?: string
    mappings: TickerMappingSet['mappings']
    action: ImportDependencyAction
    childNames: string[]
    parentNames: string[]
    enabled?: boolean
  }[]
  currentNames: {
    savedPortfolios: string[]
    savedStrategies: string[]
    savedTickerMappings: string[]
  }
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

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => `${JSON.stringify(key)}:${stableStringify(item)}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}

function sameJsonContent(a: unknown, b: unknown) {
  return stableStringify(a) === stableStringify(b)
}

function mappingContent(set: Pick<TickerMappingSet, 'mappings'>) {
  return {
    mappings: set.mappings.map(mapping => (
      isTickerMappingRef(mapping)
        ? { isMappingRef: true, mappingRef: tickerMappingRefName(mapping) }
        : { from: mapping.from, to: mapping.to, mode: mapping.mode, applyTo: mapping.applyTo }
    )),
  }
}

function cloneJson<T>(value: T): T {
  if (value == null) return value
  return JSON.parse(JSON.stringify(value))
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

function normalizeSavedTickerMappingsFromPayload(payload: ConfigPayload) {
  return normalizeTickerMappingSets(
    payload.savedTickerMappings ?? payload.savedMappings ?? payload.tickerMappingSets,
  )
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

function collectDirectPortfolioRefNames(configs: any[]) {
  const names = new Set<string>()
  configs.forEach(config => {
    for (const row of config?.tickers ?? []) {
      if (!isPortfolioRef(row)) continue
      const name = refName(row)
      if (name) names.add(name)
    }
  })
  return names
}

function extractPayloadPortfolioConfigs(payload: ConfigPayload) {
  const configs: any[] = []
  if (Array.isArray(payload.portfolios)) configs.push(...payload.portfolios)
  if (payload.portfolio && typeof payload.portfolio === 'object') configs.push(payload.portfolio)
  if (payload.portfolioState && typeof payload.portfolioState === 'object') configs.push(payload.portfolioState)
  return configs
}

function collectPortfolioChildNames(config: any, importedNames: Set<string>) {
  const names = new Set<string>()
  for (const row of config?.tickers ?? []) {
    if (!isPortfolioRef(row)) continue
    const name = refName(row)
    if (name && importedNames.has(name)) names.add(name)
  }
  return [...names].sort((a, b) => a.localeCompare(b))
}

function collectTickerMappingChildNames(set: TickerMappingSet, importedNames: Set<string>) {
  const names = new Set<string>()
  set.mappings.forEach(mapping => {
    if (!isTickerMappingRef(mapping)) return
    const name = tickerMappingRefName(mapping)
    if (name && importedNames.has(name)) names.add(name)
  })
  return [...names].sort((a, b) => a.localeCompare(b))
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
      parseLetfComponents(letf).forEach(component => {
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

export async function buildSavedTickerMappingsExportPayload(): Promise<{
  savedTickerMappings: SavedTickerMappingExport[]
}> {
  const savedTickerMappings = exportableSavedTickerMappings(loadTickerMappingSettings())
  return { savedTickerMappings }
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
  const importedSavedTickerMappings = normalizeSavedTickerMappingsFromPayload(payload)

  const [currentSavedPortfolios, currentSavedStrategies, currentTickerConfigs] = await Promise.all([
    importedSavedPortfolios.length > 0 ? fetchSavedPortfolios() : Promise.resolve([]),
    importedSavedStrategies.length > 0 ? fetchSavedStrategies() : Promise.resolve([]),
    Promise.all(importedTickerConfigs.map(config => fetchTickerConfig(config.symbol))),
  ])

  const currentPortfolioNames = new Set(currentSavedPortfolios.map(portfolio => portfolio.name))
  const currentStrategyNames = new Set(currentSavedStrategies.map(strategy => strategy.name))
  const currentTickerBySymbol = new Map(currentTickerConfigs.map(config => [config.symbol, config]))
  const currentPortfolioByName = new Map(currentSavedPortfolios.map(portfolio => [portfolio.name, savedPortfolioConfig(portfolio.config)]))
  const importedPortfolioNames = new Set(importedSavedPortfolios.map(portfolio => portfolio.name))
  const rootPortfolioRefNames = collectDirectPortfolioRefNames(extractPayloadPortfolioConfigs(payload))
  const portfolioChildrenByName = new Map(
    importedSavedPortfolios.map(portfolio => [
      portfolio.name,
      collectPortfolioChildNames(savedPortfolioConfig(portfolio.config), importedPortfolioNames),
    ]),
  )
  const portfolioParentsByName = new Map<string, string[]>()
  portfolioChildrenByName.forEach((children, parentName) => {
    children.forEach(childName => {
      portfolioParentsByName.set(childName, [...(portfolioParentsByName.get(childName) ?? []), parentName])
    })
  })
  const importedMappingNames = new Set(importedSavedTickerMappings.map(set => set.name))
  const mappingChildrenByName = new Map(
    importedSavedTickerMappings.map(set => [
      set.name,
      collectTickerMappingChildNames(set, importedMappingNames),
    ]),
  )
  const mappingParentsByName = new Map<string, string[]>()
  mappingChildrenByName.forEach((children, parentName) => {
    children.forEach(childName => {
      mappingParentsByName.set(childName, [...(mappingParentsByName.get(childName) ?? []), parentName])
    })
  })
  const currentMappingByName = new Map(
    normalizeTickerMappingSets(loadTickerMappingSettings().savedSets)
      .map(set => [set.name.trim().toLowerCase(), set]),
  )

  return {
    savedPortfolios: importedSavedPortfolios
      .map(portfolio => {
        const config = savedPortfolioConfig(portfolio.config)
        return {
          originalName: portfolio.name,
          name: portfolio.name,
          config,
          action: currentPortfolioNames.has(portfolio.name) ? 'replace' as const : 'add' as const,
          childNames: portfolioChildrenByName.get(portfolio.name) ?? [],
          parentNames: (portfolioParentsByName.get(portfolio.name) ?? []).sort((a, b) => a.localeCompare(b)),
          referencedByImport: rootPortfolioRefNames.has(portfolio.name),
        }
      })
      .filter(portfolio => (
        portfolio.action === 'add' ||
        !sameJsonContent(currentPortfolioByName.get(portfolio.name), portfolio.config)
      )),
    tickerConfigs: importedTickerConfigs
      .map(config => ({ symbol: config.symbol, current: currentTickerBySymbol.get(config.symbol) ?? { symbol: config.symbol, letf: '', groups: '' }, next: config }))
      .filter(row => !tickerConfigEquals(row.current, row.next)),
    savedStrategies: importedSavedStrategies.map(strategy => ({
      originalName: strategy.name,
      name: strategy.name,
      config: strategy.config,
      action: currentStrategyNames.has(strategy.name) ? 'replace' : 'add',
    })),
    savedTickerMappings: importedSavedTickerMappings
      .map(set => ({
        originalName: set.name,
        name: set.name,
        updatedAt: set.updatedAt,
        mappings: set.mappings,
        action: currentMappingByName.has(set.name.trim().toLowerCase()) ? 'replace' as const : 'add' as const,
        childNames: mappingChildrenByName.get(set.name) ?? [],
        parentNames: (mappingParentsByName.get(set.name) ?? []).sort((a, b) => a.localeCompare(b)),
      }))
      .filter(set => {
        if (set.action === 'add') return true
        const current = currentMappingByName.get(set.name.trim().toLowerCase())
        return !current || !sameJsonContent(mappingContent(current), mappingContent(set))
      }),
    currentNames: {
      savedPortfolios: [...currentPortfolioNames].sort((a, b) => a.localeCompare(b)),
      savedStrategies: [...currentStrategyNames].sort((a, b) => a.localeCompare(b)),
      savedTickerMappings: [...currentMappingByName.values()].map(set => set.name).sort((a, b) => a.localeCompare(b)),
    },
  }
}

export function hasImportDependencyPreview(preview: ImportDependencyPreview) {
  return (
    preview.savedPortfolios.length > 0 ||
    preview.tickerConfigs.length > 0 ||
    preview.savedStrategies.length > 0 ||
    preview.savedTickerMappings.length > 0
  )
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

export function renamePortfolioRefsInConfig<T>(config: T, renameMap: Map<string, string>): T {
  if (renameMap.size === 0 || config == null) return config

  function visit(value: any): any {
    if (Array.isArray(value)) return value.map(visit)
    if (!value || typeof value !== 'object') return value

    const next: Record<string, unknown> = {}
    Object.entries(value).forEach(([key, item]) => {
      next[key] = visit(item)
    })

    if (isPortfolioRef(value)) {
      const currentName = refName(value)
      const nextName = renameMap.get(currentName)
      if (nextName) {
        if ('portfolioRef' in value || value.isPortfolioRef === true || value.type === 'PORTFOLIO_REF') next.portfolioRef = nextName
        if ('ticker' in value || value.isPortfolioRef === true || value.type === 'PORTFOLIO_REF') next.ticker = nextName
      }
    }

    return next
  }

  return visit(config)
}

export function renameTickerMappingRefs<T extends { mappings: TickerMappingSet['mappings'] }>(
  set: T,
  renameMap: Map<string, string>,
): T {
  if (renameMap.size === 0) return set
  const renameByLowerName = new Map([...renameMap.entries()].map(([from, to]) => [from.trim().toLowerCase(), to]))
  return {
    ...set,
    mappings: set.mappings.map(mapping => {
      if (!isTickerMappingRef(mapping)) return mapping
      const currentName = tickerMappingRefName(mapping)
      const nextName = renameByLowerName.get(currentName.toLowerCase())
      return nextName ? { ...mapping, mappingRef: nextName } : mapping
    }),
  }
}

export function rewriteImportConfigPortfolioRefs<T extends ConfigPayload>(
  config: T,
  preview: ImportDependencyPreview,
): T {
  const renameMap = new Map(
    preview.savedPortfolios
      .filter(portfolio => portfolio.enabled !== false)
      .map(portfolio => [portfolio.originalName, portfolio.name.trim()] as const)
      .filter(([from, to]) => !!to && from !== to),
  )
  return renamePortfolioRefsInConfig(cloneJson(config), renameMap)
}

export async function applyImportDependencyPreview(preview: ImportDependencyPreview) {
  const enabledSavedPortfolios = preview.savedPortfolios
    .filter(portfolio => portfolio.enabled !== false && portfolio.name.trim())
  const portfolioRenameMap = new Map(
    enabledSavedPortfolios
      .map(portfolio => [portfolio.originalName, portfolio.name.trim()] as const)
      .filter(([from, to]) => from !== to),
  )

  await Promise.all(enabledSavedPortfolios.map(portfolio =>
    replaceSavedJsonConfig(
      '/api/backtest/savedPortfolios',
      portfolio.name.trim(),
      renamePortfolioRefsInConfig(portfolio.config, portfolioRenameMap),
    ),
  ))

  await Promise.all(preview.tickerConfigs.filter(row => row.enabled !== false).map(async row => {
    const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(row.next.symbol)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ letf: row.next.letf, groups: row.next.groups }),
    })
    if (!res.ok) throw new Error(`Failed to save ticker config for ${row.next.symbol}`)
  }))

  await Promise.all(preview.savedStrategies
    .filter(strategy => strategy.enabled !== false && strategy.name.trim())
    .map(strategy =>
      replaceSavedJsonConfig('/api/rebalance-strategy/savedStrategies', strategy.name.trim(), strategy.config),
  ))

  const enabledSavedTickerMappings = preview.savedTickerMappings
    .filter(set => set.enabled !== false && set.name.trim())
  const mappingRenameMap = new Map(
    enabledSavedTickerMappings
      .map(set => [set.originalName, set.name.trim()] as const)
      .filter(([from, to]) => from !== to),
  )

  if (enabledSavedTickerMappings.length > 0) {
    const currentSettings = loadTickerMappingSettings()
    saveTickerMappingSettings(mergeSavedTickerMappings(currentSettings, enabledSavedTickerMappings.map(set => ({
      id: '',
      name: set.name.trim(),
      updatedAt: set.updatedAt ?? new Date().toISOString(),
      mappings: renameTickerMappingRefs(set, mappingRenameMap).mappings,
    }))))
    notifyTickerMappingsChanged()
  }

  if (enabledSavedPortfolios.length > 0) {
    window.dispatchEvent(new Event(SAVED_PORTFOLIOS_CHANGED_EVENT))
  }
}
