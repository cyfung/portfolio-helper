import {
  expandSwapTickerRows,
  formatSwapExpression,
  isModifierToken,
  normalizeTickerExpression,
  parseSwapExpression,
  splitTickerChain,
  TICKER_CHAIN_SEPARATOR,
  tokenizeDefinition,
} from '@/lib/tickerExpressions'

export interface TickerMapping {
  id: string
  from: string
  to: string
  mode: 'prepend' | 'replaceAll'
  applyTo: 'expression' | 'ticker'
}

export interface TickerMappingSet {
  id: string
  name: string
  mappings: TickerMapping[]
  updatedAt?: string
  sourceSavedSetId?: string
  sourceSavedSetName?: string
  sourceSavedSetHash?: string
  sourceSavedSetUpdatedAt?: string
}

export interface TickerMappingSettings {
  selectedSetId: string
  sets: TickerMappingSet[]
  savedSets: TickerMappingSet[]
}

export interface WeightedTicker {
  ticker: string
  weight: number
}

export interface TickerMappingResult<T> {
  value: T
  warnings: string[]
}

const STORAGE_KEY = 'ticker-mapping-settings'
export const TICKER_MAPPINGS_CHANGED_EVENT = 'ticker-mappings-changed'
const ACTIVE_MAPPING_SET_DEFAULTS: TickerMappingSet[] = [
  { id: 'set-1', name: 'Mapping Set 1', mappings: [] },
]

export const DEFAULT_TICKER_MAPPING_SETTINGS: TickerMappingSettings = {
  selectedSetId: '',
  sets: ACTIVE_MAPPING_SET_DEFAULTS,
  savedSets: [],
}

function newMappingId() {
  return `mapping-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function newMappingSetId() {
  return `set-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function normalizeSource(value: string) {
  return value.trim().toUpperCase()
}

function normalizeTarget(value: string) {
  return value.trim().replace(/\s+/g, ' ')
}

function normalizeMapping(mapping: Partial<TickerMapping>): TickerMapping | null {
  const from = normalizeSource(String(mapping.from ?? ''))
  const to = normalizeTarget(String(mapping.to ?? ''))
  const mode = mapping.mode === 'replaceAll' ? 'replaceAll' : 'prepend'
  const applyTo = mapping.applyTo === 'ticker' ? 'ticker' : 'expression'
  return { id: String(mapping.id || newMappingId()), from, to, mode, applyTo }
}

function normalizeSet(
  raw: Partial<TickerMappingSet>,
  idx: number,
  usedIds: Set<string>,
  fallback?: TickerMappingSet,
): TickerMappingSet {
  let id = String(raw.id || fallback?.id || '').trim()
  if (!id || usedIds.has(id)) id = newMappingSetId()
  usedIds.add(id)

  const mappings = Array.isArray(raw.mappings)
    ? raw.mappings.map(normalizeMapping).filter((m): m is TickerMapping => !!m)
    : []

  return {
    id,
    name: String(raw.name || fallback?.name || `Mapping Set ${idx + 1}`).trim() || fallback?.name || `Mapping Set ${idx + 1}`,
    mappings,
    updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : fallback?.updatedAt,
    sourceSavedSetId: typeof raw.sourceSavedSetId === 'string' ? raw.sourceSavedSetId : fallback?.sourceSavedSetId,
    sourceSavedSetName: typeof raw.sourceSavedSetName === 'string' ? raw.sourceSavedSetName : fallback?.sourceSavedSetName,
    sourceSavedSetHash: typeof raw.sourceSavedSetHash === 'string' ? raw.sourceSavedSetHash : fallback?.sourceSavedSetHash,
    sourceSavedSetUpdatedAt: typeof raw.sourceSavedSetUpdatedAt === 'string' ? raw.sourceSavedSetUpdatedAt : fallback?.sourceSavedSetUpdatedAt,
  }
}

export function normalizeTickerMappingSettings(raw: unknown): TickerMappingSettings {
  const obj = raw && typeof raw === 'object' ? raw as Partial<TickerMappingSettings> : {}
  const rawSets = Array.isArray(obj.sets) ? obj.sets : []
  const rawSavedSets = Array.isArray(obj.savedSets) ? obj.savedSets : []
  const usedIds = new Set<string>()
  const sets = ACTIVE_MAPPING_SET_DEFAULTS.map((fallback, idx) => (
    normalizeSet((rawSets[idx] as Partial<TickerMappingSet> | undefined) ?? {}, idx, usedIds, fallback)
  ))
  const savedSets = [...rawSavedSets, ...rawSets.slice(ACTIVE_MAPPING_SET_DEFAULTS.length)]
    .map((set, idx) => normalizeSet(set as Partial<TickerMappingSet>, idx, usedIds))
  const selectedSetId = savedSets.some(set => set.id === obj.selectedSetId) ? String(obj.selectedSetId) : ''
  return { selectedSetId, sets, savedSets }
}

export function loadTickerMappingSettings(): TickerMappingSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return normalizeTickerMappingSettings(raw ? JSON.parse(raw) : null)
  } catch {
    return DEFAULT_TICKER_MAPPING_SETTINGS
  }
}

export function saveTickerMappingSettings(settings: TickerMappingSettings) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(normalizeTickerMappingSettings(settings)))
}

export function notifyTickerMappingsChanged() {
  window.dispatchEvent(new Event(TICKER_MAPPINGS_CHANGED_EVENT))
}

export function mappingSetSummary(set: TickerMappingSet | null | undefined) {
  const count = usableTickerMappings(set?.mappings ?? []).length
  if (count === 0) return 'No mappings'
  return `${count} mapping${count === 1 ? '' : 's'}`
}

export function selectedTickerMappingSet(settings: TickerMappingSettings) {
  return settings.savedSets.find(set => set.id === settings.selectedSetId) ?? null
}

export function usableTickerMappings(mappings: TickerMapping[]) {
  return mappings.filter(mapping => mapping.from && mapping.to && !/\s/.test(mapping.from))
}

export function tickerMappingSetContent(set: Pick<TickerMappingSet, 'name' | 'mappings'>) {
  return {
    name: set.name.trim(),
    mappings: usableTickerMappings(set.mappings).map(mapping => ({
      from: mapping.from,
      to: mapping.to,
      mode: mapping.mode,
      applyTo: mapping.applyTo,
    })),
  }
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

export function tickerMappingSetHash(set: Pick<TickerMappingSet, 'name' | 'mappings'>) {
  const text = stableStringify(tickerMappingSetContent(set))
  let hash = 2166136261
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(36)
}

export function normalizeTickerMappingSets(value: unknown): TickerMappingSet[] {
  if (!Array.isArray(value)) return []
  const usedIds = new Set<string>()
  const byName = new Map<string, TickerMappingSet>()

  value.forEach((item, idx) => {
    const set = normalizeSet(item as Partial<TickerMappingSet>, idx, usedIds)
    const name = set.name.trim()
    if (!name || usableTickerMappings(set.mappings).length === 0) return
    byName.set(name.toLowerCase(), { ...set, name, mappings: usableTickerMappings(set.mappings) })
  })

  return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name))
}

export function exportableSavedTickerMappings(settings: TickerMappingSettings) {
  return normalizeTickerMappingSets(settings.savedSets)
}

export function mergeSavedTickerMappings(
  settings: TickerMappingSettings,
  importedSets: TickerMappingSet[],
): TickerMappingSettings {
  if (importedSets.length === 0) return normalizeTickerMappingSettings(settings)

  const importedByName = new Map(importedSets.map(set => [set.name.trim().toLowerCase(), set]))
  const retainedSavedSets = settings.savedSets.filter(set => !importedByName.has(set.name.trim().toLowerCase()))
  return normalizeTickerMappingSettings({
    ...settings,
    savedSets: [...retainedSavedSets, ...importedSets],
  })
}

function formatMultiplier(value: number) {
  return Number.isInteger(value) ? String(value) : String(Math.round(value * 10000000000) / 10000000000)
}

function scaleMappedDefinition(target: string, multiplier: number) {
  const tokens = tokenizeDefinition(target)
  if (tokens.length === 0) return []
  const output: string[] = []

  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (isModifierToken(token)) {
      output.push(token)
      i += 1
      continue
    }

    const componentMultiplier = parseFloat(token)
    if (Number.isFinite(componentMultiplier) && i + 1 < tokens.length && !isModifierToken(tokens[i + 1])) {
      output.push(formatMultiplier(componentMultiplier * multiplier), tokens[i + 1].toUpperCase())
      i += 2
      continue
    }

    if (!Number.isFinite(componentMultiplier)) {
      output.push(formatMultiplier(multiplier), token.toUpperCase())
    }
    i += 1
  }

  return output
}

function modifierKind(token: string) {
  const upper = token.toUpperCase()
  if (upper.startsWith('S=')) return 'S'
  if (upper.startsWith('R=')) return 'R'
  if (upper.startsWith('E=')) return 'E'
  if (upper.startsWith('V=') || upper.startsWith('VOL=')) return 'V'
  return null
}

function modifierKinds(raw: string) {
  const kinds = new Set<string>()
  tokenizeDefinition(raw).forEach(token => {
    const kind = modifierKind(token)
    if (kind) kinds.add(kind)
  })
  return kinds
}

function letfTokensContainMapping(tokens: string[], mapping: TickerMapping) {
  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (isModifierToken(token)) {
      i += 1
      continue
    }
    const multiplier = parseFloat(token)
    if (Number.isFinite(multiplier) && i + 1 < tokens.length && !isModifierToken(tokens[i + 1])) {
      if (tokens[i + 1].toUpperCase() === mapping.from) return true
      i += 2
      continue
    }
    if (!Number.isFinite(multiplier) && token.toUpperCase() === mapping.from) return true
    i += 1
  }
  return false
}

function applySingleTickerMappingWithWarnings(ticker: string, mapping: TickerMapping): TickerMappingResult<string> {
  const rawTicker = ticker.trim()
  if (!rawTicker) return { value: '', warnings: [] }

  if (rawTicker.toUpperCase() === mapping.from && !rawTicker.includes(' ')) return { value: mapping.to, warnings: [] }
  if (!rawTicker.includes(' ')) return { value: normalizeTickerExpression(rawTicker), warnings: [] }
  if (mapping.applyTo === 'ticker') return { value: normalizeMappedTickerSegment(rawTicker), warnings: [] }

  const tokens = tokenizeDefinition(rawTicker)
  const output: string[] = []
  const replacementModifiers = modifierKinds(mapping.to)
  const willReplaceAnyComponent = letfTokensContainMapping(tokens, mapping)
  const willReplaceComponent = replacementModifiers.size > 0 && willReplaceAnyComponent
  let replacedComponent = false
  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (isModifierToken(token)) {
      const kind = modifierKind(token)
      if (!willReplaceAnyComponent) output.push(token)
      else if (!willReplaceComponent || !kind || !replacementModifiers.has(kind)) output.push(token)
      i += 1
      continue
    }

    const multiplier = parseFloat(token)
    if (Number.isFinite(multiplier) && i + 1 < tokens.length && !isModifierToken(tokens[i + 1])) {
      const componentTicker = tokens[i + 1].toUpperCase()
      if (componentTicker === mapping.from) {
        output.push(...scaleMappedDefinition(mapping.to, multiplier))
        replacedComponent = true
      } else {
        output.push(formatMultiplier(multiplier), componentTicker)
      }
      i += 2
      continue
    }

    if (!Number.isFinite(multiplier)) {
      const componentTicker = token.toUpperCase()
      if (componentTicker === mapping.from) {
        output.push(...scaleMappedDefinition(mapping.to, 1))
        replacedComponent = true
      } else {
        output.push(componentTicker)
      }
    }
    i += 1
  }

  const mapped = output.join(' ')
  return {
    value: mapped,
    warnings: [],
  }
}

function applySingleTickerMapping(ticker: string, mapping: TickerMapping) {
  return applySingleTickerMappingWithWarnings(ticker, mapping).value
}

function normalizeMappedTickerSegment(ticker: string) {
  return applySingleTickerMapping(ticker, { id: '', from: '__NO_TICKER_MAPPING__', to: '', mode: 'prepend', applyTo: 'expression' })
}

function formatSwapLeg(ticker: string, weight: number) {
  return weight === 1 ? ticker : `${ticker} #${formatMultiplier(weight)}`
}

function formatMappedSwapExpression(
  from: string,
  swap: NonNullable<ReturnType<typeof parseSwapExpression>>,
  mapSegment: (ticker: string) => string,
) {
  if (!swap.legs) return formatSwapExpression(mapSegment(from), mapSegment(swap.to), swap.factor)
  return `${mapSegment(from)} > ${swap.legs.map(leg => formatSwapLeg(mapSegment(leg.ticker), leg.weight)).join(' + ')}`
}

function normalizeMappedTickerExpression(ticker: string): string {
  const swap = parseSwapExpression(ticker)
  if (swap) {
    return formatMappedSwapExpression(swap.from, swap, normalizeMappedTickerExpression)
  }

  const chain = splitTickerChain(ticker)
  if (chain.length > 1) return chain.map(normalizeMappedTickerExpression).join(` ${TICKER_CHAIN_SEPARATOR} `)
  return normalizeMappedTickerSegment(ticker)
}

function mapTickerExpressionForSingleMapping(
  ticker: string,
  mapping: TickerMapping,
): TickerMappingResult<string> {
  const rawTicker = ticker.trim()
  if (!rawTicker) return { value: '', warnings: [] }

  const swap = parseSwapExpression(rawTicker)
  if (swap) {
    const from = mapTickerExpressionForSingleMapping(swap.from, mapping)
    if (swap.legs) {
      const mappedLegs = swap.legs.map(leg => ({
        ...leg,
        mapped: mapTickerExpressionForSingleMapping(leg.ticker, mapping),
      }))
      return {
        value: `${from.value} > ${mappedLegs.map(leg => formatSwapLeg(leg.mapped.value, leg.weight)).join(' + ')}`,
        warnings: [...from.warnings, ...mappedLegs.flatMap(leg => leg.mapped.warnings)],
      }
    }

    const to = mapTickerExpressionForSingleMapping(swap.to, mapping)
    return {
      value: formatSwapExpression(from.value, to.value, swap.factor),
      warnings: [...from.warnings, ...to.warnings],
    }
  }

  const chain = splitTickerChain(rawTicker)
  if (chain.length > 1) {
    if (mapping.mode !== 'replaceAll') {
      const lastSegment = chain[chain.length - 1]
      const mapped = applySingleTickerMappingWithWarnings(lastSegment, mapping)
      const normalizedLastSegment = normalizeMappedTickerSegment(lastSegment)
      if (mapped.value === normalizedLastSegment) {
        return { value: chain.map(normalizeMappedTickerExpression).join(` ${TICKER_CHAIN_SEPARATOR} `), warnings: [] }
      }
      return {
        value: `${chain.map(normalizeMappedTickerExpression).join(` ${TICKER_CHAIN_SEPARATOR} `)} ${TICKER_CHAIN_SEPARATOR} ${mapped.value}`,
        warnings: mapped.warnings,
      }
    }

    const warnings = new Set<string>()
    const mappedSegments = chain.map(segment => {
      const mapped = mapTickerExpressionForSingleMapping(segment, mapping)
      mapped.warnings.forEach(warning => warnings.add(warning))
      return mapped.value
    })
    return { value: mappedSegments.join(` ${TICKER_CHAIN_SEPARATOR} `), warnings: [...warnings] }
  }

  if (mapping.mode !== 'replaceAll') {
    const mapped = applySingleTickerMappingWithWarnings(rawTicker, mapping)
    const normalized = normalizeMappedTickerSegment(rawTicker)
    if (mapped.value === normalized) return { value: normalized, warnings: [] }
    return { value: `${normalized} ${TICKER_CHAIN_SEPARATOR} ${mapped.value}`, warnings: mapped.warnings }
  }

  return applySingleTickerMappingWithWarnings(rawTicker, mapping)
}

export function mapTickerExpression(ticker: string, mappingSet: TickerMappingSet | null | undefined) {
  return mapTickerExpressionWithWarnings(ticker, mappingSet).value
}

export function mapTickerExpressionWithWarnings(
  ticker: string,
  mappingSet: TickerMappingSet | null | undefined,
): TickerMappingResult<string> {
  const mappings = usableTickerMappings(mappingSet?.mappings ?? [])
  if (mappings.length === 0) return { value: normalizeMappedTickerExpression(ticker), warnings: [] }

  const warnings = new Set<string>()
  const value = mappings.reduce(
    (current, mapping) => {
      const mapped = mapTickerExpressionForSingleMapping(current, mapping)
      mapped.warnings.forEach(warning => warnings.add(warning))
      return mapped.value
    },
    ticker.trim(),
  )
  return { value, warnings: [...warnings] }
}

export function applyTickerMappingsToRows(rows: WeightedTicker[], mappingSet: TickerMappingSet | null | undefined) {
  return applyTickerMappingsToRowsWithWarnings(rows, mappingSet).value
}

export function applyTickerMappingsToRowsWithWarnings(
  rows: WeightedTicker[],
  mappingSet: TickerMappingSet | null | undefined,
): TickerMappingResult<WeightedTicker[]> {
  const expandedRows = expandSwapTickerRows(rows)
    .filter(row => row.ticker.trim().toUpperCase() !== 'DUMMY')
  if (usableTickerMappings(mappingSet?.mappings ?? []).length === 0) return { value: expandedRows, warnings: [] }

  const weights = new Map<string, number>()
  const warnings = new Set<string>()
  for (const row of expandedRows) {
    const mapped = mapTickerExpressionWithWarnings(row.ticker, mappingSet)
    const mappedTicker = mapped.value
    mapped.warnings.forEach(warning => warnings.add(warning))
    if (!mappedTicker || row.weight === 0) continue
    weights.set(mappedTicker, (weights.get(mappedTicker) ?? 0) + row.weight)
  }

  return {
    value: [...weights.entries()]
      .filter(([, weight]) => weight !== 0)
      .map(([ticker, weight]) => ({ ticker, weight }))
      .sort((a, b) => a.ticker.localeCompare(b.ticker)),
    warnings: [...warnings],
  }
}

export function applyTickerMappingsToPortfolio<T extends { tickers: WeightedTicker[] }>(
  portfolio: T,
  mappingSet: TickerMappingSet | null | undefined,
): T {
  return applyTickerMappingsToPortfolioWithWarnings(portfolio, mappingSet).value
}

export function applyTickerMappingsToPortfolioWithWarnings<T extends { tickers: WeightedTicker[] }>(
  portfolio: T,
  mappingSet: TickerMappingSet | null | undefined,
): TickerMappingResult<T> {
  if (!mappingSet?.mappings.length) return { value: portfolio, warnings: [] }
  const mapped = applyTickerMappingsToRowsWithWarnings(portfolio.tickers, mappingSet)
  return { value: { ...portfolio, tickers: mapped.value }, warnings: mapped.warnings }
}
