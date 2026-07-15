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

interface ExpressionTerm {
  symbol: string
  weight: number
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

function applyFlexibleWeightRule(
  targetWeight: Record<string, number>,
  currentWeight: Record<string, number>,
  leftTerms: ExpressionTerm[],
  rightTerms: ExpressionTerm[],
) {
  const weightDev = Object.fromEntries(
    uniqueSymbols([...leftTerms, ...rightTerms]).map(symbol => [
      symbol,
      (currentWeight[symbol] ?? 0) - (targetWeight[symbol] ?? 0),
    ]),
  )
  const leftDev = expressionUnits(weightDev, leftTerms)
  const rightDev = expressionUnits(weightDev, rightTerms)

  if (leftDev > 0 && rightDev < 0) {
    const shift = Math.min(leftDev, -rightDev)
    applyExpressionShift(targetWeight, leftTerms, shift)
    applyExpressionShift(targetWeight, rightTerms, -shift)
  } else if (rightDev > 0 && leftDev < 0) {
    const shift = Math.min(rightDev, -leftDev)
    applyExpressionShift(targetWeight, rightTerms, shift)
    applyExpressionShift(targetWeight, leftTerms, -shift)
  }
}

function applyFlexibleRebalRule(
  rebalDollars: Record<string, number>,
  leftTerms: ExpressionTerm[],
  rightTerms: ExpressionTerm[],
) {
  const leftRebal = expressionUnits(rebalDollars, leftTerms)
  const rightRebal = expressionUnits(rebalDollars, rightTerms)

  if (leftRebal < 0 && rightRebal > 0) {
    const shift = Math.min(-leftRebal, rightRebal)
    applyExpressionShift(rebalDollars, leftTerms, shift)
    applyExpressionShift(rebalDollars, rightTerms, -shift)
  } else if (rightRebal < 0 && leftRebal > 0) {
    const shift = Math.min(-rightRebal, leftRebal)
    applyExpressionShift(rebalDollars, rightTerms, shift)
    applyExpressionShift(rebalDollars, leftTerms, -shift)
  }
}

export function computeFlexibleStockDisplay(
  stocks: FlexibleStockInput[],
  mappings: FlexibleWeightMapping[],
): FlexibleStockDisplay {
  const targetWeight = Object.fromEntries(stocks.map(stock => [stock.symbol, stock.targetWeight]))
  const currentWeight = Object.fromEntries(stocks.map(stock => [stock.symbol, stock.currentWeightPct]))
  const rebalDollars = Object.fromEntries(stocks.map(stock => [stock.symbol, stock.rebalDollars]))

  for (const mapping of mappings) {
    const leftTerms = parseFlexibleExpression(mapping.left)
    const rightTerms = parseFlexibleExpression(mapping.right)
    if (!leftTerms.length || !rightTerms.length) continue
    applyFlexibleWeightRule(targetWeight, currentWeight, leftTerms, rightTerms)
    applyFlexibleRebalRule(rebalDollars, leftTerms, rightTerms)
  }

  return { targetWeight, rebalDollars }
}
