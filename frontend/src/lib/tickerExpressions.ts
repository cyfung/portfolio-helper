export interface WeightedTickerExpression {
  ticker: string
  weight: number
}

export interface TickerExpressionConfig {
  letf?: string
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

export function splitTickerChain(raw: string) {
  return splitTopLevel(raw.trim(), '>').filter(Boolean)
}

export interface SwapExpression {
  from: string
  to: string
  factor: number
}

export function parseSwapExpression(raw: string): SwapExpression | null {
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
    return [
      ...expand(swap.from, -weight),
      ...expand(swap.to, weight * swap.factor),
      { ticker: 'DUMMY', weight: weight * (2 - swap.factor) },
    ]
  }

  return rows.flatMap(row => expand(row.ticker, row.weight))
}

function addWeight(map: Map<string, number>, ticker: string, weight: number) {
  const key = normalizeTickerExpression(ticker)
  if (!key || weight === 0) return
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
      .map(([ticker, weight]) => ({ ticker, weight }))
      .sort((a, b) => a.ticker.localeCompare(b.ticker)),
  }
}
