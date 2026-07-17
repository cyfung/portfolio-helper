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

export type TickerMappingStorage = 'server' | 'local'

export interface TickerMapping {
  id: string
  from: string
  to: string
  mode: 'prepend' | 'replaceAll'
  applyTo: 'expression' | 'ticker'
  isMappingRef?: boolean
  mappingRef?: string
}

export interface TickerMappingSet {
  id: string
  name: string
  mappings: TickerMapping[]
  storage?: TickerMappingStorage
  persistentId?: string
  updatedAt?: string
  sourceSavedSetId?: string
  sourceSavedSetName?: string
  sourceSavedSetHash?: string
  sourceSavedSetUpdatedAt?: string
  resolveWarnings?: string[]
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
const SERVER_SETTINGS_ENDPOINT = '/api/ticker-mapping/settings'
export const TICKER_MAPPINGS_CHANGED_EVENT = 'ticker-mappings-changed'
const ACTIVE_MAPPING_SET_DEFAULTS: TickerMappingSet[] = [
  { id: 'set-1', name: 'Mapping Set 1', mappings: [] },
]

export const DEFAULT_TICKER_MAPPING_SETTINGS: TickerMappingSettings = {
  selectedSetId: '',
  sets: ACTIVE_MAPPING_SET_DEFAULTS,
  savedSets: [],
}

let cachedTickerMappingSettings = DEFAULT_TICKER_MAPPING_SETTINGS
let cachedLegacyLocalTickerMappingSettings = DEFAULT_TICKER_MAPPING_SETTINGS
let hydrateTickerMappingSettingsPromise: Promise<TickerMappingSettings> | null = null
let localTickerMappingMutationVersion = 0

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

export function isTickerMappingRef(mapping: Partial<TickerMapping> | null | undefined) {
  return mapping?.isMappingRef === true || typeof mapping?.mappingRef === 'string'
}

export function tickerMappingRefName(mapping: Partial<TickerMapping>) {
  return normalizeTarget(String(mapping.mappingRef ?? mapping.from ?? ''))
}

function normalizeMapping(mapping: Partial<TickerMapping>): TickerMapping | null {
  const id = String(mapping.id || newMappingId())
  if (isTickerMappingRef(mapping)) {
    return {
      id,
      from: '',
      to: '',
      mode: 'prepend',
      applyTo: 'expression',
      isMappingRef: true,
      mappingRef: tickerMappingRefName(mapping),
    }
  }
  const from = normalizeSource(String(mapping.from ?? ''))
  const to = normalizeTarget(String(mapping.to ?? ''))
  const mode = mapping.mode === 'replaceAll' ? 'replaceAll' : 'prepend'
  const applyTo = mapping.applyTo === 'ticker' ? 'ticker' : 'expression'
  return { id, from, to, mode, applyTo }
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
    storage: raw.storage === 'local' || raw.storage === 'server' ? raw.storage : fallback?.storage,
    persistentId: typeof raw.persistentId === 'string' ? raw.persistentId : fallback?.persistentId,
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
  return cachedTickerMappingSettings
}

function sourceSetId(source: TickerMappingStorage, id: string) {
  return id.startsWith(`${source}:`) ? id : `${source}:${id}`
}

function persistedSetId(set: TickerMappingSet) {
  return set.persistentId ?? set.id.replace(/^(server|local):/, '')
}

function sourceSettings(settings: TickerMappingSettings, source: TickerMappingStorage): TickerMappingSettings {
  const savedSets = settings.savedSets.map(set => ({
    ...set,
    id: sourceSetId(source, persistedSetId(set)),
    persistentId: persistedSetId(set),
    storage: source,
  }))
  const selected = settings.savedSets.find(set => set.id === settings.selectedSetId)
  return {
    ...settings,
    selectedSetId: selected ? sourceSetId(source, persistedSetId(selected)) : '',
    savedSets,
  }
}

function combineServerAndLocalSettings(
  serverSettings: TickerMappingSettings,
  localSettings: TickerMappingSettings,
): TickerMappingSettings {
  const serverNames = new Set(serverSettings.savedSets.map(set => set.name.trim().toLowerCase()).filter(Boolean))
  const localOnlySets = localSettings.savedSets.filter(set => !serverNames.has(set.name.trim().toLowerCase()))
  return normalizeTickerMappingSettings({
    ...serverSettings,
    savedSets: [...serverSettings.savedSets, ...localOnlySets],
    selectedSetId: serverSettings.selectedSetId,
  })
}

function readLegacyTickerMappingSettings(): TickerMappingSettings | null {
  try {
    // Legacy localStorage is read only as an old mapping source. Do not write new
    // mappings here; server settings are the source of truth for new changes.
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? sourceSettings(normalizeTickerMappingSettings(JSON.parse(raw)), 'local') : null
  } catch {
    return null
  }
}

async function fetchTickerMappingSettingsFromServer(): Promise<TickerMappingSettings> {
  try {
    const res = await fetch(SERVER_SETTINGS_ENDPOINT)
    if (!res.ok) return DEFAULT_TICKER_MAPPING_SETTINGS
    return sourceSettings(normalizeTickerMappingSettings(await res.json()), 'server')
  } catch {
    return DEFAULT_TICKER_MAPPING_SETTINGS
  }
}

function stripSetForPersistence(set: TickerMappingSet): TickerMappingSet {
  const { storage: _storage, persistentId: _persistentId, ...rest } = set
  return {
    ...rest,
    id: persistedSetId(set),
    mappings: set.mappings.map(mapping => ({ ...mapping })),
  }
}

function serverSettingsForPersistence(settings: TickerMappingSettings): TickerMappingSettings {
  const selected = settings.savedSets.find(set => set.id === settings.selectedSetId)
  const savedSets = settings.savedSets
    .filter(set => set.storage !== 'local')
    .map(stripSetForPersistence)
  const selectedSetId = selected && selected.storage !== 'local'
    ? persistedSetId(selected)
    : ''
  return normalizeTickerMappingSettings({
    ...settings,
    selectedSetId,
    sets: settings.sets.map(stripSetForPersistence),
    savedSets,
  })
}

function localSettingsForPersistence(settings: TickerMappingSettings): TickerMappingSettings {
  const selected = settings.savedSets.find(set => set.id === settings.selectedSetId)
  const savedSets = settings.savedSets
    .filter(set => set.storage === 'local')
    .map(stripSetForPersistence)
  const selectedSetId = selected?.storage === 'local'
    ? persistedSetId(selected)
    : ''
  return normalizeTickerMappingSettings({
    ...DEFAULT_TICKER_MAPPING_SETTINGS,
    selectedSetId,
    savedSets,
  })
}

function sameJsonContent(a: unknown, b: unknown) {
  return stableStringify(a) === stableStringify(b)
}

function cleanupLegacyLocalTickerMappings(settings: TickerMappingSettings) {
  const nextLocalSettings = localSettingsForPersistence(settings)
  const currentLocalSettings = localSettingsForPersistence(cachedLegacyLocalTickerMappingSettings)
  if (sameJsonContent(nextLocalSettings, currentLocalSettings)) return

  try {
    // Cleanup only: remove/delete legacy local mappings that were replaced or
    // deleted. Never add newly saved mappings to this legacy localStorage key.
    if (nextLocalSettings.savedSets.length === 0) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, JSON.stringify(nextLocalSettings))
    cachedLegacyLocalTickerMappingSettings = sourceSettings(nextLocalSettings, 'local')
  } catch {}
}

async function saveTickerMappingSettingsToServer(settings: TickerMappingSettings) {
  const res = await fetch(SERVER_SETTINGS_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(serverSettingsForPersistence(settings)),
  })
  if (!res.ok) throw new Error(`Failed to save ticker mapping settings: HTTP ${res.status}`)
}

export async function hydrateTickerMappingSettings(): Promise<TickerMappingSettings> {
  if (hydrateTickerMappingSettingsPromise) return hydrateTickerMappingSettingsPromise
  const mutationVersionAtStart = localTickerMappingMutationVersion

  hydrateTickerMappingSettingsPromise = (async () => {
    const [serverSettings, legacySettings] = await Promise.all([
      fetchTickerMappingSettingsFromServer(),
      Promise.resolve(readLegacyTickerMappingSettings()),
    ])
    if (localTickerMappingMutationVersion !== mutationVersionAtStart) return cachedTickerMappingSettings
    cachedLegacyLocalTickerMappingSettings = legacySettings ?? DEFAULT_TICKER_MAPPING_SETTINGS
    cachedTickerMappingSettings = combineServerAndLocalSettings(serverSettings, cachedLegacyLocalTickerMappingSettings)
    notifyTickerMappingsChanged()
    return cachedTickerMappingSettings
  })().finally(() => {
    hydrateTickerMappingSettingsPromise = null
  })

  return hydrateTickerMappingSettingsPromise
}

export function saveTickerMappingSettings(settings: TickerMappingSettings) {
  const normalized = normalizeTickerMappingSettings(settings)
  localTickerMappingMutationVersion += 1
  cachedTickerMappingSettings = normalized

  cleanupLegacyLocalTickerMappings(normalized)
  void saveTickerMappingSettingsToServer(normalized).catch(() => {})
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
  const selected = settings.savedSets.find(set => set.id === settings.selectedSetId)
  return selected ? resolveTickerMappingSet(selected, settings.savedSets) : null
}

export function usableTickerMappings(mappings: TickerMapping[]) {
  return mappings.filter(mapping => (
    isTickerMappingRef(mapping)
      ? !!tickerMappingRefName(mapping)
      : mapping.from && mapping.to && !/\s/.test(mapping.from)
  ))
}

export function tickerMappingSetContent(set: Pick<TickerMappingSet, 'name' | 'mappings'>) {
  return {
    name: set.name.trim(),
    mappings: usableTickerMappings(set.mappings).map(mapping => (
      isTickerMappingRef(mapping)
        ? { isMappingRef: true, mappingRef: tickerMappingRefName(mapping) }
        : {
            from: mapping.from,
            to: mapping.to,
            mode: mapping.mode,
            applyTo: mapping.applyTo,
          }
    )),
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
  return normalizeTickerMappingSets(settings.savedSets).map(stripSetForPersistence)
}

export function resolveTickerMappingSet(
  set: TickerMappingSet,
  savedSets: TickerMappingSet[],
): TickerMappingSet {
  const savedByName = new Map(
    savedSets
      .map(saved => [saved.name.trim().toLowerCase(), saved] as const)
      .filter(([name]) => !!name),
  )
  const warnings = new Set<string>()

  function expandMappings(mappings: TickerMapping[], stack: string[]): TickerMapping[] {
    return usableTickerMappings(mappings).flatMap(mapping => {
      if (!isTickerMappingRef(mapping)) return [mapping]

      const name = tickerMappingRefName(mapping)
      const key = name.toLowerCase()
      if (!key) return []
      if (stack.includes(key)) {
        warnings.add(`Circular ticker mapping reference skipped: ${[...stack, key].join(' -> ')}`)
        return []
      }

      const child = savedByName.get(key)
      if (!child) {
        warnings.add(`Missing ticker mapping reference: ${name}`)
        return []
      }
      return expandMappings(child.mappings, [...stack, key])
    })
  }

  const rootKey = set.name.trim().toLowerCase()
  return {
    ...set,
    mappings: expandMappings(set.mappings, rootKey ? [rootKey] : []),
    resolveWarnings: [...warnings],
  }
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
  const mappings = usableTickerMappings(mappingSet?.mappings ?? []).filter(mapping => !isTickerMappingRef(mapping))
  const warnings = new Set(mappingSet?.resolveWarnings ?? [])
  if (mappings.length === 0) return { value: normalizeMappedTickerExpression(ticker), warnings: [...warnings] }

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
  const initialWarnings = new Set(mappingSet?.resolveWarnings ?? [])
  if (usableTickerMappings(mappingSet?.mappings ?? []).filter(mapping => !isTickerMappingRef(mapping)).length === 0) {
    return { value: expandedRows, warnings: [...initialWarnings] }
  }

  const weights = new Map<string, number>()
  const warnings = new Set(initialWarnings)
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
  if (!mappingSet?.mappings.length) return { value: portfolio, warnings: mappingSet?.resolveWarnings ?? [] }
  const mapped = applyTickerMappingsToRowsWithWarnings(portfolio.tickers, mappingSet)
  return { value: { ...portfolio, tickers: mapped.value }, warnings: mapped.warnings }
}
