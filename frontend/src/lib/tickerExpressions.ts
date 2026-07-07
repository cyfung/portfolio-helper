export interface WeightedTickerExpression {
  ticker: string
  weight: number
}

export interface TickerExpressionConfig {
  letf?: string
}

const RESOLVED_WEIGHT_EPSILON = 1e-10

export function isResolvedNonZeroWeight(weight: number) {
  return Number.isFinite(weight) && Math.abs(weight) > RESOLVED_WEIGHT_EPSILON
}

export function normalizeTickerExpression(value: string) {
  return value.trim().replace(/\s+/g, ' ').toUpperCase()
}

export function isModifierToken(token: string) {
  return /^(?:S|R|E|V|VOL)=/i.test(token)
}

export function tokenizeDefinition(raw: string) {
  return raw.trim().replace(/,/g, ' ').split(/\s+/).filter(Boolean)
}

function splitTopLevel(raw: string, separator: string) {
  const parts: string[] = []
  let depth = 0
  let start = 0

  for (let i = 0; i < raw.length; i += 1) {
    const ch = raw[i]
    if (ch === '(') depth += 1
    else if (ch === ')') depth = Math.max(0, depth - 1)
    else if (ch === separator && depth === 0) {
      parts.push(raw.slice(start, i).trim())
      start = i + 1
    }
  }

  parts.push(raw.slice(start).trim())
  return parts
}

export const TICKER_CHAIN_SEPARATOR = '|'

export function splitTickerChain(raw: string) {
  const parts: string[] = []
  let depth = 0
  let start = 0
  const trimmed = raw.trim()

  for (let i = 0; i < trimmed.length; i += 1) {
    const ch = trimmed[i]
    if (ch === '(') depth += 1
    else if (ch === ')') depth = Math.max(0, depth - 1)
    else if (ch === TICKER_CHAIN_SEPARATOR && depth === 0) {
      parts.push(trimmed.slice(start, i).trim())
      start = i + 1
    }
  }

  parts.push(trimmed.slice(start).trim())
  return parts.filter(Boolean)
}

export interface SwapExpression {
  from: string
  to: string
  factor: number
  legs?: WeightedTickerExpression[]
}

function parseLegacySwapExpression(raw: string): SwapExpression | null {
  const trimmed = raw.trim()
  const match = /^SWAP\s*\(/i.exec(trimmed)
  if (!match || !trimmed.endsWith(')')) return null

  let depth = 0
  let closeIndex = -1
  for (let i = match[0].length - 1; i < trimmed.length; i += 1) {
    const ch = trimmed[i]
    if (ch === '(') depth += 1
    else if (ch === ')') {
      depth -= 1
      if (depth === 0) {
        closeIndex = i
        break
      }
    }
  }
  if (closeIndex !== trimmed.length - 1) return null

  const inner = trimmed.slice(match[0].length, closeIndex)
  const args = splitTopLevel(inner, ',')
  if ((args.length !== 2 && args.length !== 3) || !args[0] || !args[1]) return null
  const factor = args.length === 3 ? parseFloat(args[2]) : 1
  if (!Number.isFinite(factor)) return null
  return { from: args[0], to: args[1], factor }
}

function splitTopLevelPlus(raw: string) {
  const parts: string[] = []
  let depth = 0
  let start = 0

  for (let i = 0; i < raw.length; i += 1) {
    const ch = raw[i]
    if (ch === '(') depth += 1
    else if (ch === ')') depth = Math.max(0, depth - 1)
    else if (ch === '+' && depth === 0 && /\s/.test(raw[i - 1] ?? '') && /\s/.test(raw[i + 1] ?? '')) {
      parts.push(raw.slice(start, i).trim())
      start = i + 1
    }
  }

  parts.push(raw.slice(start).trim())
  return parts
}

function parseSwapLeg(raw: string): WeightedTickerExpression | null {
  const trimmed = raw.trim()
  if (!trimmed) return null

  const hashMatch = /^(.*?)\s*#\s*([+-]?(?:\d+(?:\.\d+)?|\.\d+))\s*$/.exec(trimmed)
  if (hashMatch) {
    const ticker = hashMatch[1].trim()
    const weight = parseFloat(hashMatch[2])
    return ticker && Number.isFinite(weight) ? { ticker, weight } : null
  }

  const bareMatch = /^(.*?)\s+([+-]?(?:\d+(?:\.\d+)?|\.\d+))\s*$/.exec(trimmed)
  if (bareMatch) {
    const ticker = bareMatch[1].trim()
    const weight = parseFloat(bareMatch[2])
    return ticker && Number.isFinite(weight) ? { ticker, weight } : null
  }

  return { ticker: trimmed, weight: 1 }
}

function parseShorthandSwapExpression(raw: string): SwapExpression | null {
  const trimmed = raw.trim()
  if (/^SWAP\s*\(/i.test(trimmed)) return null

  const parts = splitTopLevel(trimmed, '>')
  if (parts.length !== 2 || parts.some(part => !part)) return null

  const legs = splitTopLevelPlus(parts[1]).map(parseSwapLeg)
  if (legs.length === 0 || legs.some(leg => !leg)) return null

  const usableLegs = legs as WeightedTickerExpression[]
  return {
    from: parts[0],
    to: usableLegs[0].ticker,
    factor: usableLegs[0].weight,
    legs: usableLegs,
  }
}

export function parseSwapExpression(raw: string): SwapExpression | null {
  return parseLegacySwapExpression(raw) ?? parseShorthandSwapExpression(raw)
}

export function formatSwapExpression(from: string, to: string, factor = 1) {
  const normalizedFactor = Math.round(factor * 10000000000) / 10000000000
  return normalizedFactor === 1
    ? `SWAP(${normalizeTickerExpression(from)}, ${normalizeTickerExpression(to)})`
    : `SWAP(${normalizeTickerExpression(from)}, ${normalizeTickerExpression(to)}, ${normalizedFactor})`
}

export function parseLetfComponents(raw: string): WeightedTickerExpression[] {
  if (parseSwapExpression(raw)) return []
  const chain = splitTickerChain(raw)
  const currentExpression = chain.length > 1 ? chain[chain.length - 1] : raw
  const tokens = tokenizeDefinition(currentExpression)
  const components: WeightedTickerExpression[] = []

  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (isModifierToken(token)) {
      i += 1
      continue
    }

    const multiplier = parseFloat(token)
    if (Number.isFinite(multiplier) && i + 1 < tokens.length && !isModifierToken(tokens[i + 1])) {
      components.push({ ticker: normalizeTickerExpression(tokens[i + 1]), weight: multiplier })
      i += 2
    } else if (!Number.isFinite(multiplier)) {
      components.push({ ticker: normalizeTickerExpression(token), weight: 1 })
      i += 1
    } else {
      i += 1
    }
  }

  return components
}

export function expandSwapTickerRows(rows: WeightedTickerExpression[]): WeightedTickerExpression[] {
  function expand(ticker: string, weight: number): WeightedTickerExpression[] {
    const swap = parseSwapExpression(ticker)
    if (!swap) return [{ ticker: normalizeTickerExpression(ticker), weight }]
    const legs = swap.legs ?? [{ ticker: swap.to, weight: swap.factor }]
    const legWeightTotal = legs.reduce((sum, leg) => sum + leg.weight, 0)
    return [
      ...expand(swap.from, -weight),
      ...legs.flatMap(leg => expand(leg.ticker, weight * leg.weight)),
      { ticker: 'DUMMY', weight: weight * (2 - legWeightTotal) },
    ]
  }

  return rows.flatMap(row => expand(row.ticker, row.weight))
}

function addWeight(map: Map<string, number>, ticker: string, weight: number) {
  const key = normalizeTickerExpression(ticker)
  if (!key || !isResolvedNonZeroWeight(weight)) return
  map.set(key, (map.get(key) ?? 0) + weight)
}

export function expandLetfRows(
  rows: WeightedTickerExpression[],
  tickerConfigs: Record<string, TickerExpressionConfig>,
) {
  const weights = new Map<string, number>()
  let expanded = false

  for (const row of expandSwapTickerRows(rows)) {
    const ticker = normalizeTickerExpression(row.ticker)
    const letf = tickerConfigs[ticker]?.letf?.trim() || (ticker.includes(' ') ? ticker : '')
    const components = letf ? parseLetfComponents(letf) : []
    if (components.length === 0) {
      addWeight(weights, ticker, row.weight)
      continue
    }

    expanded = true
    for (const component of components) {
      addWeight(weights, component.ticker, row.weight * component.weight)
    }
  }

  return {
    expanded,
    rows: [...weights.entries()]
      .filter(([, weight]) => isResolvedNonZeroWeight(weight))
      .map(([ticker, weight]) => ({ ticker, weight }))
      .sort((a, b) => a.ticker.localeCompare(b.ticker)),
  }
}
