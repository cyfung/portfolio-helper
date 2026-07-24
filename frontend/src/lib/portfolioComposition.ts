export type ReferenceNormalizationMode = 'NET_100' | 'PRESERVE'

declare const instrumentExpressionBrand: unique symbol
export type InstrumentExpression = string & { readonly [instrumentExpressionBrand]: true }

export interface HoldingAllocationRow {
  id: string
  type: 'HOLDING'
  instrument: InstrumentExpression
  allocation: number
}

export interface PortfolioReferenceAllocationRow {
  id: string
  type: 'PORTFOLIO_REFERENCE'
  portfolioName: string
  allocation: number
  normalizationMode: ReferenceNormalizationMode
}

export interface SwapLeg {
  instrument: InstrumentExpression
  multiplier: number
}

export type SwapTransfer =
  | { mode: 'AMOUNT'; amount: number }
  | { mode: 'ALL_REMAINING' }

export interface SwapRow {
  id: string
  type: 'SWAP'
  source: InstrumentExpression
  transfer: SwapTransfer
  legs: SwapLeg[]
}

export type PortfolioRow = HoldingAllocationRow | PortfolioReferenceAllocationRow | SwapRow

export interface PortfolioConfiguration {
  rows: PortfolioRow[]
}

export interface ResolvedInstrumentExposure {
  instrument: InstrumentExpression
  exposure: number
}

export type PortfolioResolutionIssueCode =
  | 'INVALID_ROW'
  | 'INVALID_INSTRUMENT'
  | 'INVALID_TRANSFER'
  | 'INVALID_LEGS'
  | 'UNSUPPORTED_REFERENCE'
  | 'MISSING_REFERENCE'
  | 'CIRCULAR_REFERENCE'
  | 'INVALID_NORMALIZED_CHILD'
  | 'INVALID_ROOT_NET'
  | 'LEGACY_DUMMY'
  | 'SOURCE_UNAVAILABLE'
  | 'INSUFFICIENT_SOURCE'

export interface PortfolioResolutionIssue {
  code: PortfolioResolutionIssueCode
  rowId: string
  referencePath?: string[]
  message: string
}

export interface ResolvedPortfolioComposition {
  composition: ResolvedInstrumentExposure[]
  net: number
  issues: PortfolioResolutionIssue[]
}

export interface SavedPortfolioConfiguration {
  rows: readonly unknown[]
}

export interface LegacyTickerPersistenceRow {
  ticker: string
  weight: number | '*'
  isPortfolioRef?: true
  portfolioRef?: string
}

const EPSILON = 1e-10
const MODIFIER = /^(?:S|R|E|V|VOL)=/i
const SIGNED_DECIMAL_SOURCE = String.raw`[+-]?(?:\d+(?:\.\d+)?|\.\d+)`
const PREFIX_MULTIPLIER = new RegExp(`^(${SIGNED_DECIMAL_SOURCE})\\s+(.+)$`)
const SUFFIX_MULTIPLIER = new RegExp(`^(.+?)\\s*#\\s*(${SIGNED_DECIMAL_SOURCE})$`)
const VALID_NUMERIC_MODIFIER = new RegExp(`^(?:S|E|V|VOL)=${SIGNED_DECIMAL_SOURCE}%?$`, 'i')
const VALID_REBALANCE_MODIFIER = /^R=(?:D|W|M|Q|Y)$/i

function hasSingleOuterGroup(value: string) {
  if (!value.startsWith('(') || !value.endsWith(')')) return false
  let depth = 0
  for (let index = 0; index < value.length; index += 1) {
    if (value[index] === '(') depth += 1
    else if (value[index] === ')') depth -= 1
    if (depth === 0 && index < value.length - 1) return false
  }
  return depth === 0
}

function canonicalInstrumentSegment(value: string): string | null {
  const normalized = value.trim().replace(/\s+/g, ' ').toUpperCase()
  if (!normalized) return null
  const grouped = hasSingleOuterGroup(normalized)
  const inner = grouped ? normalized.slice(1, -1).trim() : normalized
  if (!inner || inner.includes('(') || inner.includes(')')) return null
  const tokens = inner.split(' ')
  const modifiers = tokens.filter(token => MODIFIER.test(token))
  if (modifiers.some(token => !VALID_NUMERIC_MODIFIER.test(token) && !VALID_REBALANCE_MODIFIER.test(token))) {
    return null
  }
  const expression = tokens.filter(token => !MODIFIER.test(token))
  if (expression.some(token => token.includes('='))) return null
  const validBase = expression.length === 1
    ? !Number.isFinite(Number(expression[0]))
    : expression.length >= 2 &&
      expression.length % 2 === 0 &&
      expression.every((token, index) =>
        index % 2 === 0 ? Number.isFinite(Number(token)) : !Number.isFinite(Number(token)))
  if (!validBase) return null
  const canonical = [...expression, ...modifiers.sort()].join(' ')
  return grouped ? `(${canonical})` : canonical
}

export function parseInstrumentExpression(value: string): InstrumentExpression | null {
  const trimmed = value.trim()
  if (!trimmed || !balancedParentheses(trimmed) || trimmed.includes('>') || /^SWAP\s*\(/i.test(trimmed)) return null
  const segments = splitTopLevel(trimmed, '|')
  if (segments.some(segment => !segment)) return null
  const canonical = segments.map(canonicalInstrumentSegment)
  return canonical.some(segment => segment == null)
    ? null
    : canonical.join(' | ') as InstrumentExpression
}

export function canonicalInstrumentExpression(value: string): string {
  return parseInstrumentExpression(value) ?? ''
}

function balancedParentheses(value: string) {
  let depth = 0
  for (const character of value) {
    if (character === '(') depth += 1
    if (character === ')') depth -= 1
    if (depth < 0) return false
  }
  return depth === 0
}

function splitTopLevel(value: string, separator: string) {
  const parts: string[] = []
  let depth = 0
  let start = 0
  for (let index = 0; index < value.length; index += 1) {
    if (value[index] === '(') depth += 1
    else if (value[index] === ')') depth -= 1
    else if (
      value[index] === separator &&
      depth === 0 &&
      (separator !== '+' || (/\s/.test(value[index - 1] ?? '') && /\s/.test(value[index + 1] ?? '')))
    ) {
      parts.push(value.slice(start, index).trim())
      start = index + 1
    }
  }
  parts.push(value.slice(start).trim())
  return parts
}

function parseSwapLeg(value: string): SwapLeg | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const prefix = PREFIX_MULTIPLIER.exec(trimmed)
  const suffix = SUFFIX_MULTIPLIER.exec(trimmed)
  if (prefix && suffix) return null

  const multiplier = prefix ? Number(prefix[1]) : suffix ? Number(suffix[2]) : 1
  const rawInstrument = (prefix ? prefix[2] : suffix ? suffix[1] : trimmed).trim()
  const grouped = rawInstrument.startsWith('(') && rawInstrument.endsWith(')')
  if (!grouped && rawInstrument.split(/\s+/).some(token => Number.isFinite(Number(token)))) return null
  const instrument = parseInstrumentExpression(rawInstrument)
  if (instrument == null || !Number.isFinite(multiplier) || multiplier === 0) return null
  return { instrument, multiplier }
}

export function parseSwapInput(value: string): { source: InstrumentExpression; legs: SwapLeg[]; formatted: string } | null {
  if (!balancedParentheses(value)) return null
  const parts = splitTopLevel(value, '>')
  if (parts.length !== 2) return null
  const rawSource = parts[0].trim()
  if (!hasSingleOuterGroup(rawSource) && Number.isFinite(Number(rawSource.split(/\s+/)[0]))) return null
  const source = parseInstrumentExpression(rawSource)
  const legs = splitTopLevel(parts[1], '+').map(parseSwapLeg)
  if (source == null || legs.length === 0 || legs.some(leg => leg == null)) return null
  const parsedLegs = legs as SwapLeg[]
  return {
    source,
    legs: parsedLegs,
    formatted: `${source} > ${parsedLegs.map(leg =>
      leg.multiplier === 1 ? leg.instrument : `${leg.multiplier} ${leg.instrument}`,
    ).join(' + ')}`,
  }
}

export function formatSwapRow(row: Pick<SwapRow, 'source' | 'legs'>): string {
  return `${row.source} > ${row.legs.map(leg =>
    leg.multiplier === 1 ? leg.instrument : `${leg.multiplier} ${leg.instrument}`,
  ).join(' + ')}`
}

export interface LegacyTickerRow {
  id?: string
  ticker?: string
  weight?: number | string
  isPortfolioRef?: boolean
  portfolioRef?: string
  type?: string
}

function parseLegacySwapCall(value: string) {
  const match = new RegExp(
    `^SWAP\\s*\\(\\s*(.+?)\\s*,\\s*(.+?)(?:\\s*,\\s*(${SIGNED_DECIMAL_SOURCE}))?\\s*\\)$`,
    'i',
  ).exec(value)
  if (!match) return null
  const multiplier = match[3] == null ? 1 : Number(match[3])
  const source = parseInstrumentExpression(match[1])
  const destination = parseInstrumentExpression(match[2])
  if (source == null || destination == null || !Number.isFinite(multiplier) || multiplier === 0) return null
  return {
    source,
    legs: [{ instrument: destination, multiplier }],
  }
}

export function convertLegacyTickerRow(row: LegacyTickerRow, fallbackId: string): PortfolioRow | null {
  const id = String(row.id ?? fallbackId)
  const rawWeight = String(row.weight ?? '').trim()
  const allocation = Number(rawWeight)
  const reference = row.isPortfolioRef === true || row.type === 'PORTFOLIO_REF' || row.portfolioRef != null
  if (reference) {
    const portfolioName = String(row.portfolioRef ?? row.ticker ?? '').trim()
    if (!portfolioName || !Number.isFinite(allocation) || Math.abs(allocation) <= EPSILON) return null
    return { id, type: 'PORTFOLIO_REFERENCE', portfolioName, allocation, normalizationMode: 'NET_100' }
  }

  const ticker = String(row.ticker ?? '').trim()
  const swap = parseSwapInput(ticker) ?? parseLegacySwapCall(ticker)
  if (swap) {
    const transfer: SwapTransfer = rawWeight === '*'
      ? { mode: 'ALL_REMAINING' }
      : { mode: 'AMOUNT', amount: allocation }
    if (transfer.mode === 'AMOUNT' && (!Number.isFinite(transfer.amount) || transfer.amount <= 0)) return null
    return { id, type: 'SWAP', source: swap.source, transfer, legs: swap.legs }
  }
  if (ticker.includes('>') || /^SWAP\s*\(/i.test(ticker)) return null

  const instrument = parseInstrumentExpression(ticker)
  if (instrument == null || !Number.isFinite(allocation) || Math.abs(allocation) <= EPSILON) return null
  return { id, type: 'HOLDING', instrument, allocation }
}

export function convertPortfolioRowToLegacyTickerRow(row: PortfolioRow): LegacyTickerPersistenceRow {
  if (row.type === 'HOLDING') return { ticker: row.instrument, weight: row.allocation }
  if (row.type === 'PORTFOLIO_REFERENCE') {
    return {
      ticker: row.portfolioName,
      weight: row.allocation,
      isPortfolioRef: true,
      portfolioRef: row.portfolioName,
    }
  }
  return {
    ticker: formatSwapRow(row),
    weight: row.transfer.mode === 'ALL_REMAINING' ? '*' : row.transfer.amount,
  }
}

function canonicalSwapTransfer(value: unknown): SwapTransfer | null {
  if (value == null || typeof value !== 'object') return null
  const transfer = value as Record<string, unknown>
  if (transfer.mode === 'ALL_REMAINING') return { mode: 'ALL_REMAINING' }
  return transfer.mode === 'AMOUNT' &&
    typeof transfer.amount === 'number' &&
    Number.isFinite(transfer.amount) &&
    transfer.amount > 0
    ? { mode: 'AMOUNT', amount: transfer.amount }
    : null
}

function canonicalSwapLegs(value: unknown): SwapLeg[] | null {
  if (!Array.isArray(value) || value.length === 0) return null
  const legs = value.map(item => {
    if (item == null || typeof item !== 'object') return null
    const leg = item as Record<string, unknown>
    const instrument = parseInstrumentExpression(String(leg.instrument ?? ''))
    const multiplier = leg.multiplier
    return instrument != null && typeof multiplier === 'number' && Number.isFinite(multiplier) && multiplier !== 0
      ? { instrument, multiplier }
      : null
  })
  return legs.some(leg => leg == null) ? null : legs as SwapLeg[]
}

export function canonicalPortfolioRow(value: unknown): PortfolioRow | null {
  if (value == null || typeof value !== 'object') return null
  const row = value as Record<string, unknown>
  if (typeof row.id !== 'string') return null
  if (row.type === 'HOLDING') {
    const instrument = parseInstrumentExpression(String(row.instrument ?? ''))
    const allocation = Number(row.allocation)
    return instrument != null && Number.isFinite(allocation) && Math.abs(allocation) > EPSILON
      ? { id: row.id, type: 'HOLDING', instrument, allocation }
      : null
  }
  if (row.type === 'PORTFOLIO_REFERENCE') {
    const portfolioName = typeof row.portfolioName === 'string' ? row.portfolioName.trim() : ''
    const allocation = Number(row.allocation)
    const normalizationMode = row.normalizationMode
    return portfolioName &&
      Number.isFinite(allocation) &&
      Math.abs(allocation) > EPSILON &&
      (normalizationMode === 'NET_100' || normalizationMode === 'PRESERVE')
      ? { id: row.id, type: 'PORTFOLIO_REFERENCE', portfolioName, allocation, normalizationMode }
      : null
  }
  if (row.type !== 'SWAP') return null
  const source = parseInstrumentExpression(String(row.source ?? ''))
  const transfer = canonicalSwapTransfer(row.transfer)
  const legs = canonicalSwapLegs(row.legs)
  return source != null && transfer != null && legs != null
    ? { id: row.id, type: 'SWAP', source, transfer, legs }
    : null
}

export function canonicalPortfolioConfiguration(configuration: { rows: readonly unknown[] }): PortfolioConfiguration | null {
  const rows = configuration.rows.map(canonicalPortfolioRow)
  return rows.some(row => row == null) ? null : { rows: rows as PortfolioRow[] }
}

function invalidResolutionIssue(value: unknown, rowId: string): PortfolioResolutionIssue {
  if (value == null || typeof value !== 'object') {
    return { code: 'INVALID_ROW', rowId, message: 'The portfolio row is invalid.' }
  }
  const row = value as Record<string, unknown>
  if (row.type === 'HOLDING' && parseInstrumentExpression(String(row.instrument ?? '')) == null) {
    return { code: 'INVALID_INSTRUMENT', rowId, message: 'The holding instrument expression is invalid.' }
  }
  if (row.type === 'SWAP') {
    if (parseInstrumentExpression(String(row.source ?? '')) == null) {
      return { code: 'INVALID_INSTRUMENT', rowId, message: 'The swap source instrument expression is invalid.' }
    }
    if (canonicalSwapTransfer(row.transfer) == null) {
      return { code: 'INVALID_TRANSFER', rowId, message: 'The swap transfer amount must be positive and finite.' }
    }
    if (canonicalSwapLegs(row.legs) == null) {
      return { code: 'INVALID_LEGS', rowId, message: 'The swap must have at least one valid non-zero leg.' }
    }
  }
  return { code: 'INVALID_ROW', rowId, message: 'The portfolio row is invalid.' }
}

export function resolvePortfolioComposition(rows: readonly unknown[]): ResolvedPortfolioComposition {
  return resolveRows(rows)
}

function resolveRows(
  rows: readonly unknown[],
  savedPortfolios?: ReadonlyMap<string, SavedPortfolioConfiguration>,
  referencePath?: string[],
  stack: string[] = [],
): ResolvedPortfolioComposition {
  const exposures = new Map<InstrumentExpression, number>()
  const issues: PortfolioResolutionIssue[] = []
  const withPath = (issue: PortfolioResolutionIssue): PortfolioResolutionIssue =>
    referencePath == null ? issue : { ...issue, referencePath }

  const addExposure = (instrument: InstrumentExpression, allocation: number) => {
    const next = (exposures.get(instrument) ?? 0) + allocation
    if (Math.abs(next) <= EPSILON) exposures.delete(instrument)
    else exposures.set(instrument, next)
  }

  rows.forEach((value, index) => {
    const rowId = value != null &&
      typeof value === 'object' &&
      typeof (value as Record<string, unknown>).id === 'string'
      ? (value as Record<string, unknown>).id as string
      : String(index)
    const row = canonicalPortfolioRow(value)
    if (row == null) {
      issues.push(withPath(invalidResolutionIssue(value, rowId)))
      return
    }
    if (row.type === 'HOLDING') {
      if (row.instrument === 'DUMMY') {
        issues.push(withPath({
          code: 'LEGACY_DUMMY',
          rowId: row.id,
          message: 'Legacy DUMMY holdings must be rewritten before resolution.',
        }))
        return
      }
      addExposure(row.instrument, row.allocation)
      return
    }
    if (row.type === 'PORTFOLIO_REFERENCE') {
      if (savedPortfolios != null) {
        const childPath = [...(referencePath ?? []), row.portfolioName]
        const child = savedPortfolios.get(row.portfolioName)
        if (child == null) {
          issues.push({
            code: 'MISSING_REFERENCE',
            rowId: row.id,
            referencePath: childPath,
            message: `Saved portfolio ${row.portfolioName} was not found.`,
          })
          return
        }
        if (stack.includes(row.portfolioName)) {
          issues.push({
            code: 'CIRCULAR_REFERENCE',
            rowId: row.id,
            referencePath: childPath,
            message: `Circular portfolio reference: ${childPath.join(' -> ')}.`,
          })
          return
        }

        const childResult = resolveRows(
          child.rows,
          savedPortfolios,
          childPath,
          [...stack, row.portfolioName],
        )
        issues.push(...childResult.issues)
        if (row.normalizationMode === 'NET_100' && childResult.net <= EPSILON) {
          issues.push({
            code: 'INVALID_NORMALIZED_CHILD',
            rowId: row.id,
            referencePath: childPath,
            message: `Normalized portfolio reference ${row.portfolioName} requires positive signed net exposure.`,
          })
          return
        }
        const scale = row.normalizationMode === 'NET_100'
          ? row.allocation / childResult.net
          : row.allocation / 100
        childResult.composition.forEach(position => addExposure(position.instrument, position.exposure * scale))
        return
      }
      issues.push({
        code: 'UNSUPPORTED_REFERENCE',
        rowId: row.id,
        message: `Portfolio reference ${row.portfolioName} cannot be resolved here.`,
      })
      return
    }

    const available = Math.max(exposures.get(row.source) ?? 0, 0)
    const amount = row.transfer.mode === 'ALL_REMAINING' ? available : row.transfer.amount
    if (available <= EPSILON) {
      issues.push(withPath({
        code: 'SOURCE_UNAVAILABLE',
        rowId: row.id,
        message: `No positive ${row.source} exposure is available to swap.`,
      }))
      return
    }
    if (amount - available > EPSILON) {
      issues.push(withPath({
        code: 'INSUFFICIENT_SOURCE',
        rowId: row.id,
        message: `Only ${available} of positive ${row.source} exposure is available to swap ${amount}.`,
      }))
      return
    }

    addExposure(row.source, -amount)
    row.legs.forEach(leg => addExposure(leg.instrument, amount * leg.multiplier))
  })

  const composition = [...exposures.entries()].map(([instrument, exposure]) => ({ instrument, exposure }))
  return {
    composition,
    net: composition.reduce((sum, position) => sum + position.exposure, 0),
    issues,
  }
}

export function resolveRootPortfolioComposition(
  rows: readonly unknown[],
  savedPortfolios: ReadonlyMap<string, SavedPortfolioConfiguration>,
  options: { rootName?: string } = {},
): ResolvedPortfolioComposition {
  const referencePath = options.rootName == null ? [] : [options.rootName]
  const resolved = resolveRows(rows, savedPortfolios, referencePath)
  if (resolved.net <= EPSILON) {
    return {
      ...resolved,
      issues: [
        ...resolved.issues,
        {
          code: 'INVALID_ROOT_NET',
          rowId: options.rootName ?? 'root',
          referencePath,
          message: 'Root portfolio requires positive signed net exposure.',
        },
      ],
    }
  }

  const scale = 100 / resolved.net
  return {
    composition: resolved.composition.map(position => ({
      ...position,
      exposure: position.exposure * scale,
    })),
    net: resolved.net,
    issues: resolved.issues,
  }
}
