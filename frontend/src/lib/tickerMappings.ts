export interface TickerMapping {
  id: string
  from: string
  to: string
}

export interface TickerMappingSet {
  id: string
  name: string
  prependOnly: boolean
  mappings: TickerMapping[]
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
  { id: 'set-1', name: 'Mapping Set 1', prependOnly: true, mappings: [] },
  { id: 'set-2', name: 'Mapping Set 2', prependOnly: true, mappings: [] },
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
  return { id: String(mapping.id || newMappingId()), from, to }
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
  const prependOnly = typeof raw.prependOnly === 'boolean'
    ? raw.prependOnly
    : fallback?.prependOnly ?? true

  return {
    id,
    name: String(raw.name || fallback?.name || `Mapping Set ${idx + 1}`).trim() || fallback?.name || `Mapping Set ${idx + 1}`,
    prependOnly,
    mappings,
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
  const selectedSetId = [...sets, ...savedSets].some(set => set.id === obj.selectedSetId) ? String(obj.selectedSetId) : ''
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
  return [...settings.sets, ...settings.savedSets].find(set => set.id === settings.selectedSetId) ?? null
}

export function usableTickerMappings(mappings: TickerMapping[]) {
  return mappings.filter(mapping => mapping.from && mapping.to && !/\s/.test(mapping.from))
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

function isModifierToken(token: string) {
  return /^(?:S|R|E|V|VOL)=/i.test(token)
}

function tokenizeDefinition(raw: string) {
  return raw.trim().replace(/,/g, ' ').split(/\s+/).filter(Boolean)
}

function splitTickerChain(raw: string) {
  return raw.trim().split(/\s*>\s*/).map(segment => segment.trim()).filter(Boolean)
}

function hasModifierTokens(raw: string) {
  return tokenizeDefinition(raw).some(isModifierToken)
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

function letfModifierReplacementWarning(source: string, mapping: TickerMapping, mapped: string) {
  return `Ticker mapping warning: replacing ${mapping.from} inside LETF expression. Example: ${source} with ${mapping.from} -> ${mapping.to} becomes ${mapped}. Replacement modifiers apply to the whole generated LETF expression, and duplicated modifiers are resolved by the last modifier.`
}

function applySingleTickerMappingWithWarnings(ticker: string, mapping: TickerMapping): TickerMappingResult<string> {
  const rawTicker = ticker.trim()
  if (!rawTicker) return { value: '', warnings: [] }

  if (rawTicker.toUpperCase() === mapping.from && !rawTicker.includes(' ')) return { value: mapping.to, warnings: [] }
  if (!rawTicker.includes(' ')) return { value: rawTicker.toUpperCase(), warnings: [] }

  const tokens = tokenizeDefinition(rawTicker)
  const output: string[] = []
  let replacedComponent = false
  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (isModifierToken(token)) {
      output.push(token)
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
    warnings: replacedComponent && hasModifierTokens(mapping.to)
      ? [letfModifierReplacementWarning(rawTicker, mapping, mapped)]
      : [],
  }
}

function applySingleTickerMapping(ticker: string, mapping: TickerMapping) {
  return applySingleTickerMappingWithWarnings(ticker, mapping).value
}

function normalizeMappedTickerSegment(ticker: string) {
  return applySingleTickerMapping(ticker, { id: '', from: '__NO_TICKER_MAPPING__', to: '' })
}

export function mapTickerExpression(ticker: string, mappingSet: TickerMappingSet | null | undefined) {
  return mapTickerExpressionWithWarnings(ticker, mappingSet).value
}

export function mapTickerExpressionWithWarnings(
  ticker: string,
  mappingSet: TickerMappingSet | null | undefined,
): TickerMappingResult<string> {
  const mappings = usableTickerMappings(mappingSet?.mappings ?? [])
  if (mappings.length === 0) return { value: ticker.trim().toUpperCase(), warnings: [] }

  const warnings = new Set<string>()
  const value = mappings.reduce(
    (current, mapping) => {
      if (!mappingSet?.prependOnly) {
        const mapped = applySingleTickerMappingWithWarnings(current, mapping)
        mapped.warnings.forEach(warning => warnings.add(warning))
        return mapped.value
      }

      const chain = splitTickerChain(current)
      if (chain.length === 0) return ''

      const lastSegment = chain[chain.length - 1]
      const mapped = applySingleTickerMappingWithWarnings(lastSegment, mapping)
      const mappedLastSegment = mapped.value
      if (mappedLastSegment === normalizeMappedTickerSegment(lastSegment)) return current.trim()

      mapped.warnings.forEach(warning => warnings.add(warning))
      return `${chain.join(' > ')} > ${mappedLastSegment}`
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
  if (usableTickerMappings(mappingSet?.mappings ?? []).length === 0) return { value: rows, warnings: [] }
  const weights = new Map<string, number>()
  const warnings = new Set<string>()
  for (const row of rows) {
    const mapped = mapTickerExpressionWithWarnings(row.ticker, mappingSet)
    const mappedTicker = mapped.value
    mapped.warnings.forEach(warning => warnings.add(warning))
    if (!mappedTicker || row.weight <= 0) continue
    weights.set(mappedTicker, (weights.get(mappedTicker) ?? 0) + row.weight)
  }
  return {
    value: [...weights.entries()]
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
