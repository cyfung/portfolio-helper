export interface FlexibleWeightMapping {
  left: string
  right: string
}

export interface FlexibleStockInput {
  symbol: string
  currentWeightPct: number
  targetWeight: number
  rebalDollars: number
}

export interface FlexibleStockDisplay {
  targetWeight: Record<string, number>
  rebalDollars: Record<string, number>
}

export interface FlexibleWeightRuleLimitCheck {
  key: 'expandedExpressions' | 'expressionTerms'
  label: string
  current: number
  limit: number
  over: boolean
}

export interface FlexibleWeightRuleValidation {
  ok: boolean
  checks: FlexibleWeightRuleLimitCheck[]
}

interface ExpressionTerm {
  symbol: string
  weight: number
}

interface ParsedFlexibleRule {
  leftTerms: ExpressionTerm[]
  rightTerms: ExpressionTerm[]
  leftKey: string
  rightKey: string
}

const EPSILON = 1e-9
const MAX_EXPANDED_EXPRESSIONS = 100
const MAX_EXPRESSION_TERMS = 12
const MAX_RULE_APPLICATION_PASSES = 20

interface ExpansionStats {
  maxExpandedExpressions: number
  maxExpressionTerms: number
  expressionsExceeded: boolean
  termsExceeded: boolean
}

export function parseFlexibleWeightMappings(raw?: string | null): FlexibleWeightMapping[] {
  if (!raw?.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.map(item => {
      const obj = item && typeof item === 'object' ? item as Partial<FlexibleWeightMapping> : {}
      return {
        left: String(obj.left ?? '').trim(),
        right: String(obj.right ?? '').trim(),
      }
    }).filter(row => row.left && row.right)
  } catch {
    return []
  }
}

export function serializeFlexibleWeightMappings(rows: FlexibleWeightMapping[]): string {
  const clean = rows
    .map(row => ({ left: row.left.trim(), right: row.right.trim() }))
    .filter(row => row.left && row.right)
  return clean.length ? JSON.stringify(clean) : ''
}

export function parseFlexibleExpression(raw: string): ExpressionTerm[] {
  const tokens = raw.trim().split(/\s+/).filter(Boolean)
  const terms: ExpressionTerm[] = []
  for (let i = 0; i < tokens.length; i += 1) {
    const maybeWeight = parseFloat(tokens[i])
    if (Number.isFinite(maybeWeight) && i + 1 < tokens.length && !Number.isNaN(Number(tokens[i]))) {
      terms.push({ weight: Math.abs(maybeWeight), symbol: tokens[i + 1].trim().toUpperCase() })
      i += 1
    } else {
      terms.push({ weight: 1, symbol: tokens[i].trim().toUpperCase() })
    }
  }
  return terms.filter(term => term.symbol && term.weight > 0)
}

function normalizeWeight(value: number): number {
  return Math.round(value * 1e10) / 1e10
}

function normalizeTerms(terms: ExpressionTerm[]): ExpressionTerm[] {
  const weights = new Map<string, number>()
  for (const term of terms) {
    const symbol = term.symbol.trim().toUpperCase()
    const weight = normalizeWeight(Math.abs(term.weight))
    if (!symbol || weight <= EPSILON) continue
    weights.set(symbol, normalizeWeight((weights.get(symbol) ?? 0) + weight))
  }
  return [...weights.entries()]
    .map(([symbol, weight]) => ({ symbol, weight }))
    .filter(term => term.weight > EPSILON)
    .sort((a, b) => a.symbol.localeCompare(b.symbol))
}

function expressionKey(terms: ExpressionTerm[]): string {
  return normalizeTerms(terms)
    .map(term => `${term.symbol}:${term.weight}`)
    .join('|')
}

function scaleTerms(terms: ExpressionTerm[], scale: number): ExpressionTerm[] {
  return normalizeTerms(terms.map(term => ({ symbol: term.symbol, weight: term.weight * scale })))
}

function parseFlexibleRule(mapping: FlexibleWeightMapping): ParsedFlexibleRule | null {
  const leftTerms = normalizeTerms(parseFlexibleExpression(mapping.left))
  const rightTerms = normalizeTerms(parseFlexibleExpression(mapping.right))
  if (!leftTerms.length || !rightTerms.length) return null
  return {
    leftTerms,
    rightTerms,
    leftKey: expressionKey(leftTerms),
    rightKey: expressionKey(rightTerms),
  }
}

function uniqueSymbols(terms: ExpressionTerm[]): string[] {
  return [...new Set(terms.map(term => term.symbol))]
}

function expressionUnits(values: Record<string, number>, terms: ExpressionTerm[]): number {
  let sign = 0
  let units = Infinity
  for (const term of terms) {
    const value = values[term.symbol]
    if (value === undefined || value === 0) return 0
    const legUnits = value / term.weight
    const legSign = Math.sign(legUnits)
    if (sign === 0) {
      sign = legSign
    } else if (legSign !== sign) {
      return 0
    }
    units = Math.min(units, Math.abs(legUnits))
  }
  return sign * (Number.isFinite(units) ? units : 0)
}

function applyExpressionShift(values: Record<string, number>, terms: ExpressionTerm[], shiftUnits: number) {
  if (shiftUnits === 0) return
  for (const term of terms) {
    values[term.symbol] = (values[term.symbol] ?? 0) + shiftUnits * term.weight
  }
}

function getFlexibleWeightShift(
  targetWeight: Record<string, number>,
  currentWeight: Record<string, number>,
  leftTerms: ExpressionTerm[],
  rightTerms: ExpressionTerm[],
): number {
  const weightDev = Object.fromEntries(
    uniqueSymbols([...leftTerms, ...rightTerms]).map(symbol => [
      symbol,
      (currentWeight[symbol] ?? 0) - (targetWeight[symbol] ?? 0),
    ]),
  )
  const leftDev = expressionUnits(weightDev, leftTerms)
  const rightDev = expressionUnits(weightDev, rightTerms)

  if (leftDev > 0 && rightDev < 0) {
    return Math.min(leftDev, -rightDev)
  } else if (rightDev > 0 && leftDev < 0) {
    return -Math.min(rightDev, -leftDev)
  }
  return 0
}

function applyFlexibleWeightRule(
  targetWeight: Record<string, number>,
  currentWeight: Record<string, number>,
  leftTerms: ExpressionTerm[],
  rightTerms: ExpressionTerm[],
): boolean {
  const shift = getFlexibleWeightShift(targetWeight, currentWeight, leftTerms, rightTerms)
  if (Math.abs(shift) <= EPSILON) return false
  applyExpressionShift(targetWeight, leftTerms, shift)
  applyExpressionShift(targetWeight, rightTerms, -shift)
  return true
}

function getFlexibleRebalShift(
  rebalDollars: Record<string, number>,
  leftTerms: ExpressionTerm[],
  rightTerms: ExpressionTerm[],
): number {
  const leftRebal = expressionUnits(rebalDollars, leftTerms)
  const rightRebal = expressionUnits(rebalDollars, rightTerms)

  if (leftRebal < 0 && rightRebal > 0) {
    return Math.min(-leftRebal, rightRebal)
  } else if (rightRebal < 0 && leftRebal > 0) {
    return -Math.min(-rightRebal, leftRebal)
  }
  return 0
}

function applyFlexibleRebalRule(
  rebalDollars: Record<string, number>,
  leftTerms: ExpressionTerm[],
  rightTerms: ExpressionTerm[],
): boolean {
  const shift = getFlexibleRebalShift(rebalDollars, leftTerms, rightTerms)
  if (Math.abs(shift) <= EPSILON) return false
  applyExpressionShift(rebalDollars, leftTerms, shift)
  applyExpressionShift(rebalDollars, rightTerms, -shift)
  return true
}

function expressionWithinLimit(terms: ExpressionTerm[]): boolean {
  return terms.length > 0 && terms.length <= MAX_EXPRESSION_TERMS
}

function createExpansionStats(): ExpansionStats {
  return {
    maxExpandedExpressions: 0,
    maxExpressionTerms: 0,
    expressionsExceeded: false,
    termsExceeded: false,
  }
}

function recordExpansionStats(stats: ExpansionStats | undefined, terms: ExpressionTerm[]) {
  if (!stats) return
  stats.maxExpressionTerms = Math.max(stats.maxExpressionTerms, terms.length)
  if (terms.length > MAX_EXPRESSION_TERMS) stats.termsExceeded = true
}

// Composite rules rewrite only when the full expression matches; single-term rules may rewrite one leg inside a larger expression.
function rewriteWholeExpression(terms: ExpressionTerm[], rules: ParsedFlexibleRule[]): ExpressionTerm[][] {
  const key = expressionKey(terms)
  const next: ExpressionTerm[][] = []
  for (const rule of rules) {
    if (key === rule.leftKey) next.push(rule.rightTerms)
    if (key === rule.rightKey) next.push(rule.leftTerms)
  }
  return next
}

function rewriteSingleTerms(terms: ExpressionTerm[], rules: ParsedFlexibleRule[]): ExpressionTerm[][] {
  const next: ExpressionTerm[][] = []
  for (let i = 0; i < terms.length; i += 1) {
    const term = terms[i]
    const rest = terms.filter((_, index) => index !== i)
    for (const rule of rules) {
      if (rule.leftTerms.length === 1 && rule.leftTerms[0].symbol === term.symbol) {
        next.push(normalizeTerms([
          ...rest,
          ...scaleTerms(rule.rightTerms, term.weight / rule.leftTerms[0].weight),
        ]))
      }
      if (rule.rightTerms.length === 1 && rule.rightTerms[0].symbol === term.symbol) {
        next.push(normalizeTerms([
          ...rest,
          ...scaleTerms(rule.leftTerms, term.weight / rule.rightTerms[0].weight),
        ]))
      }
    }
  }
  return next
}

function expandExpression(
  startTerms: ExpressionTerm[],
  rules: ParsedFlexibleRule[],
  stats?: ExpansionStats,
): ExpressionTerm[][] {
  const start = normalizeTerms(startTerms)
  const seen = new Map<string, ExpressionTerm[]>()
  const queue: ExpressionTerm[][] = [start]
  seen.set(expressionKey(start), start)
  recordExpansionStats(stats, start)
  if (stats) stats.maxExpandedExpressions = Math.max(stats.maxExpandedExpressions, seen.size)

  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    const current = queue[cursor]

    const candidates = [
      ...rewriteWholeExpression(current, rules),
      ...rewriteSingleTerms(current, rules),
    ]

    for (const candidate of candidates) {
      const normalized = normalizeTerms(candidate)
      recordExpansionStats(stats, normalized)
      if (!expressionWithinLimit(normalized)) continue
      const key = expressionKey(normalized)
      if (seen.has(key)) continue
      if (seen.size >= MAX_EXPANDED_EXPRESSIONS) {
        if (stats) stats.expressionsExceeded = true
        return [...seen.values()]
      }
      seen.set(key, normalized)
      queue.push(normalized)
      recordExpansionStats(stats, normalized)
      if (stats) stats.maxExpandedExpressions = Math.max(stats.maxExpandedExpressions, seen.size)
    }
  }

  return [...seen.values()]
}

function getExpandedExpression(
  terms: ExpressionTerm[],
  rules: ParsedFlexibleRule[],
  cache: Map<string, ExpressionTerm[][]>,
): ExpressionTerm[][] {
  const key = expressionKey(terms)
  const cached = cache.get(key)
  if (cached) return cached
  const expanded = expandExpression(terms, rules)
  cache.set(key, expanded)
  return expanded
}

function applyBestFlexibleWeightRule(
  targetWeight: Record<string, number>,
  currentWeight: Record<string, number>,
  leftCandidates: ExpressionTerm[][],
  rightCandidates: ExpressionTerm[][],
): boolean {
  let bestLeftTerms: ExpressionTerm[] | null = null
  let bestRightTerms: ExpressionTerm[] | null = null
  let bestShift = 0

  for (const leftTerms of leftCandidates) {
    const leftKey = expressionKey(leftTerms)
    for (const rightTerms of rightCandidates) {
      if (leftKey === expressionKey(rightTerms)) continue
      const shift = getFlexibleWeightShift(targetWeight, currentWeight, leftTerms, rightTerms)
      if (Math.abs(shift) > Math.abs(bestShift) + EPSILON) {
        bestLeftTerms = leftTerms
        bestRightTerms = rightTerms
        bestShift = shift
      }
    }
  }

  if (!bestLeftTerms || !bestRightTerms || Math.abs(bestShift) <= EPSILON) return false
  return applyFlexibleWeightRule(targetWeight, currentWeight, bestLeftTerms, bestRightTerms)
}

function applyBestFlexibleRebalRule(
  rebalDollars: Record<string, number>,
  leftCandidates: ExpressionTerm[][],
  rightCandidates: ExpressionTerm[][],
): boolean {
  let bestLeftTerms: ExpressionTerm[] | null = null
  let bestRightTerms: ExpressionTerm[] | null = null
  let bestShift = 0

  for (const leftTerms of leftCandidates) {
    const leftKey = expressionKey(leftTerms)
    for (const rightTerms of rightCandidates) {
      if (leftKey === expressionKey(rightTerms)) continue
      const shift = getFlexibleRebalShift(rebalDollars, leftTerms, rightTerms)
      if (Math.abs(shift) > Math.abs(bestShift) + EPSILON) {
        bestLeftTerms = leftTerms
        bestRightTerms = rightTerms
        bestShift = shift
      }
    }
  }

  if (!bestLeftTerms || !bestRightTerms || Math.abs(bestShift) <= EPSILON) return false
  return applyFlexibleRebalRule(rebalDollars, bestLeftTerms, bestRightTerms)
}

export function validateFlexibleWeightMappings(mappings: FlexibleWeightMapping[]): FlexibleWeightRuleValidation {
  const parsedRules = mappings
    .map(parseFlexibleRule)
    .filter((rule): rule is ParsedFlexibleRule => rule !== null)
  const stats = createExpansionStats()
  const expandedKeys = new Set<string>()

  for (const rule of parsedRules) {
    for (const terms of [rule.leftTerms, rule.rightTerms]) {
      const key = expressionKey(terms)
      if (expandedKeys.has(key)) continue
      expandedKeys.add(key)
      expandExpression(terms, parsedRules, stats)
    }
  }

  const checks: FlexibleWeightRuleLimitCheck[] = [
    {
      key: 'expandedExpressions',
      label: 'Expanded expressions',
      current: stats.expressionsExceeded ? MAX_EXPANDED_EXPRESSIONS + 1 : stats.maxExpandedExpressions,
      limit: MAX_EXPANDED_EXPRESSIONS,
      over: stats.expressionsExceeded,
    },
    {
      key: 'expressionTerms',
      label: 'Expression terms',
      current: stats.termsExceeded ? Math.max(stats.maxExpressionTerms, MAX_EXPRESSION_TERMS + 1) : stats.maxExpressionTerms,
      limit: MAX_EXPRESSION_TERMS,
      over: stats.termsExceeded,
    },
  ]

  return {
    ok: checks.every(check => !check.over),
    checks,
  }
}

export function computeFlexibleStockDisplay(
  stocks: FlexibleStockInput[],
  mappings: FlexibleWeightMapping[],
): FlexibleStockDisplay {
  const targetWeight = Object.fromEntries(stocks.map(stock => [stock.symbol, stock.targetWeight]))
  const currentWeight = Object.fromEntries(stocks.map(stock => [stock.symbol, stock.currentWeightPct]))
  const rebalDollars = Object.fromEntries(stocks.map(stock => [stock.symbol, stock.rebalDollars]))
  const parsedRules = mappings
    .map(parseFlexibleRule)
    .filter((rule): rule is ParsedFlexibleRule => rule !== null)
  const expansionCache = new Map<string, ExpressionTerm[][]>()

  for (let pass = 0; pass < MAX_RULE_APPLICATION_PASSES; pass += 1) {
    let changed = false
    for (const rule of parsedRules) {
      const leftCandidates = getExpandedExpression(rule.leftTerms, parsedRules, expansionCache)
      const rightCandidates = getExpandedExpression(rule.rightTerms, parsedRules, expansionCache)
      changed = applyBestFlexibleWeightRule(targetWeight, currentWeight, leftCandidates, rightCandidates) || changed
      changed = applyBestFlexibleRebalRule(rebalDollars, leftCandidates, rightCandidates) || changed
    }
    if (!changed) break
  }

  return { targetWeight, rebalDollars }
}
