// ── rebalance.ts — Port of display-worker.js computation logic ────────────────
// All functions are pure (no side effects) for use in React renders.

import type { AllocMode } from '@/types/portfolio'
import { DEFAULT_HYBRID_ALLOC_STRATEGIES, type HybridAllocStrategyConfig } from '@/lib/allocStrategies'

export interface StockForCompute {
  symbol: string
  qty: number
  targetWeight: number       // 0-100
  positionValueUsd: number   // current market value in USD
}

export interface ComputeResult {
  /** rebal column: how much $ to buy(+)/sell(-) to reach target allocation */
  rebalDollars: Record<string, number>
  rebalQty: Record<string, number>
  /** alloc column: how to deploy the rebal delta (new deposit or withdrawal) */
  allocDollars: Record<string, number>
  allocQty: Record<string, number>
  /** current weight % per stock */
  currentWeightPct: Record<string, number>
  /** margin-derived values */
  marginTargetUsd: number | null
  marginPct: number | null
}

// ── Core math ─────────────────────────────────────────────────────────────────

export function deriveMarginPct(rebalTotal: number, stockGross: number, marginUsd: number): number {
  const ec = stockGross + marginUsd
  if (ec <= 0) return 0
  const pct = (marginUsd - (rebalTotal - stockGross)) / ec * 100
  return pct >= 0 ? 0 : -pct
}

export function deriveRebalFromMarginPct(pct: number, stockGross: number, marginUsd: number): number {
  const ec = stockGross + marginUsd
  return (pct / 100) * ec + stockGross + marginUsd
}

export function getRebalTotal(
  rebalTargetUsd: number | null,
  marginTargetPct: number | null,
  stockGross: number,
  marginUsd: number,
  marginTargetUsd: number | null = null
): number {
  if (marginTargetPct !== null && marginTargetPct >= 0) {
    return deriveRebalFromMarginPct(marginTargetPct, stockGross, marginUsd)
  }
  if (rebalTargetUsd !== null && rebalTargetUsd >= 0) return rebalTargetUsd
  if (marginTargetUsd !== null && marginTargetUsd >= 0) return (stockGross + marginUsd) + marginTargetUsd
  return stockGross + Math.max(marginUsd, 0)
}

// ── Allocation strategies ─────────────────────────────────────────────────────

function computeWaterfall(
  eligible: StockForCompute[],
  totalStockValue: number,
  delta: number
): Record<string, number> {
  const alloc: Record<string, number> = {}
  for (const s of eligible) alloc[s.symbol] = 0

  const finalTotal = totalStockValue + delta
  if (finalTotal === 0 || delta === 0) return alloc
  const sign = delta >= 0 ? 1 : -1
  const deviation = (s: StockForCompute) => s.positionValueUsd / finalTotal - s.targetWeight / 100

  const sorted = [...eligible].sort((a, b) =>
    sign * (deviation(a) - deviation(b))
  )

  let remaining = Math.abs(delta)
  let groupLevel = sorted.length ? deviation(sorted[0]) : 0
  for (let i = 0; i < sorted.length && remaining > 0.01; i++) {
    const nextRawLevel = i + 1 < sorted.length ? deviation(sorted[i + 1]) : 0
    const nextLevel = delta >= 0 ? Math.min(nextRawLevel, 0) : Math.max(nextRawLevel, 0)
    const levelDistance = (nextLevel - groupLevel) * sign
    if (levelDistance <= 0) continue

    const groupSize = i + 1
    const costToLevel = levelDistance * finalTotal * groupSize
    if (remaining >= costToLevel) {
      const amountPerStock = (nextLevel - groupLevel) * finalTotal
      for (let j = 0; j <= i; j++) alloc[sorted[j].symbol] += amountPerStock
      remaining -= costToLevel
      groupLevel = nextLevel
    } else {
      const amountPerStock = remaining / groupSize * sign
      for (let j = 0; j <= i; j++) alloc[sorted[j].symbol] += amountPerStock
      remaining = 0
    }
  }

  if (remaining > 0.01) {
    applyProportionalSpillover(alloc, eligible, remaining, sign)
  }

  return alloc
}

function computeUndervalueFirst(
  eligible: StockForCompute[],
  totalStockValue: number,
  delta: number
): Record<string, number> {
  const alloc: Record<string, number> = {}
  for (const s of eligible) alloc[s.symbol] = 0

  const finalTotal = totalStockValue + delta
  const sign = delta >= 0 ? 1 : -1

  const sorted = [...eligible].sort((a, b) =>
    sign * (
      (a.positionValueUsd / finalTotal - a.targetWeight / 100) -
      (b.positionValueUsd / finalTotal - b.targetWeight / 100)
    )
  )

  let remaining = Math.abs(delta)
  for (const s of sorted) {
    if (remaining <= 0.01) break
    const targetVal = (s.targetWeight / 100) * finalTotal
    const needed = (targetVal - s.positionValueUsd) * sign
    if (needed <= 0) break
    const contribution = Math.min(needed, remaining)
    alloc[s.symbol] = contribution * sign
    remaining -= contribution
  }

  if (remaining > 0.01) {
    applyProportionalSpillover(alloc, eligible, remaining, sign)
  }

  return alloc
}

function applyProportionalSpillover(
  alloc: Record<string, number>,
  eligible: StockForCompute[],
  remaining: number,
  sign: number
) {
  for (const s of eligible) {
    alloc[s.symbol] = (alloc[s.symbol] ?? 0) + (s.targetWeight / 100) * remaining * sign
  }
}

function computeProportional(
  eligible: StockForCompute[],
  delta: number
): Record<string, number> {
  const alloc: Record<string, number> = {}
  const totalWeight = eligible.reduce((s, e) => s + e.targetWeight, 0)
  if (totalWeight === 0) return alloc
  for (const s of eligible) {
    alloc[s.symbol] = (s.targetWeight / totalWeight) * delta
  }
  return alloc
}

function computeCurrentWeight(
  eligible: StockForCompute[],
  totalStockValue: number,
  delta: number
): Record<string, number> {
  const alloc: Record<string, number> = {}
  if (totalStockValue === 0) return computeProportional(eligible, delta)
  for (const s of eligible) {
    alloc[s.symbol] = (s.positionValueUsd / totalStockValue) * delta
  }
  return alloc
}

// ── Main compute ──────────────────────────────────────────────────────────────

function computeFullRebalance(
  eligible: StockForCompute[],
  totalStockValue: number,
  delta: number
): Record<string, number> {
  const alloc: Record<string, number> = {}
  const finalTotal = totalStockValue + delta
  const totalWeight = eligible.reduce((s, e) => s + e.targetWeight, 0)
  if (totalWeight === 0) return alloc
  for (const s of eligible) {
    alloc[s.symbol] = finalTotal * (s.targetWeight / totalWeight) - s.positionValueUsd
  }
  return alloc
}

function normalizeAllocMode(mode: string | null | undefined): string {
  return String(mode ?? 'PROPORTIONAL')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9_]+/g, '_') || 'PROPORTIONAL'
}

function hasFullRebalanceComponent(
  mode: string,
  hybridStrategies: HybridAllocStrategyConfig[],
): boolean {
  if (mode === 'FULL_REBALANCE') return true
  const hybrid = hybridStrategies.find(s => normalizeAllocMode(s.id) === mode)
  if (!hybrid) return false
  return [hybrid.first, hybrid.second].some(part => normalizeAllocMode(part) === 'FULL_REBALANCE')
}

function ratio(value: number | undefined): number {
  return Number.isFinite(value) && value! >= 0 ? value! : 1
}

function weightedAverageAllocs(
  a: Record<string, number>,
  b: Record<string, number>,
  eligible: StockForCompute[],
  aRatio: number | undefined,
  bRatio: number | undefined,
): Record<string, number> {
  const alloc: Record<string, number> = {}
  const rawA = ratio(aRatio)
  const rawB = ratio(bRatio)
  const total = rawA + rawB
  const safeA = total > 0 ? rawA : 1
  const safeB = total > 0 ? rawB : 1
  const safeTotal = safeA + safeB
  for (const s of eligible) {
    alloc[s.symbol] = ((a[s.symbol] ?? 0) * safeA + (b[s.symbol] ?? 0) * safeB) / safeTotal
  }
  return alloc
}

export function computeDisplay(
  stocks: StockForCompute[],
  rebalTargetUsd: number | null,
  marginTargetPct: number | null,
  allocAddMode: AllocMode,
  allocReduceMode: AllocMode,
  stockGross: number,
  marginUsd: number,
  marginTargetUsd: number | null = null,
  serverAllocDollars?: Record<string, number>,  // from rebal-alloc SSE (waterfall)
  hybridStrategies: HybridAllocStrategyConfig[] = DEFAULT_HYBRID_ALLOC_STRATEGIES,
): ComputeResult {
  const rebalDollars: Record<string, number> = {}
  const rebalQty: Record<string, number> = {}
  const allocDollars: Record<string, number> = {}
  const allocQty: Record<string, number> = {}
  const currentWeightPct: Record<string, number> = {}

  const portfolioTotal = stockGross + Math.max(marginUsd, 0)
  const rebalTotal = getRebalTotal(rebalTargetUsd, marginTargetPct, stockGross, marginUsd, marginTargetUsd)
  const delta = rebalTotal - stockGross

  // Current weight %
  for (const s of stocks) {
    currentWeightPct[s.symbol] = portfolioTotal > 0
      ? (s.positionValueUsd / portfolioTotal) * 100
      : 0
  }

  // Rebal columns: (targetWeight% * rebalTotal) - currentValue
  const hasTargetWeights = stocks.some(s => s.targetWeight > 0)
  if (hasTargetWeights) {
    for (const s of stocks) {
      const targetVal = (s.targetWeight / 100) * rebalTotal
      const diff = targetVal - s.positionValueUsd
      rebalDollars[s.symbol] = diff
      rebalQty[s.symbol] = s.qty > 0 && s.positionValueUsd > 0
        ? diff / (s.positionValueUsd / s.qty)
        : 0
    }
  }

  // Alloc columns: how to deploy the delta using the chosen strategy.
  // Include existing zero-target positions for target-aware modes so reducing a
  // holding from a positive current weight to a 0% target produces a sell.
  const mode = normalizeAllocMode(delta >= 0 ? allocAddMode : allocReduceMode)
  if (hasTargetWeights && (Math.abs(delta) > 0.01 || hasFullRebalanceComponent(mode, hybridStrategies))) {
    const positiveTargetStocks = stocks.filter(s => s.targetWeight > 0)
    const targetAwareStocks = stocks.filter(s => s.targetWeight > 0 || Math.abs(s.positionValueUsd) > 0.01)
    const currentWeightStocks = delta < 0 ? targetAwareStocks : positiveTargetStocks

    let rawAlloc: Record<string, number> = {}

    const hybrid = hybridStrategies.find(s => normalizeAllocMode(s.id) === mode)
    const computeBase = (baseMode: string): Record<string, number> => {
      const normalizedBaseMode = normalizeAllocMode(baseMode)
      if (normalizedBaseMode === 'WATERFALL') return computeWaterfall(targetAwareStocks, stockGross, delta)
      if (normalizedBaseMode === 'FULL_REBALANCE') return computeFullRebalance(targetAwareStocks, stockGross, delta)
      if (normalizedBaseMode === 'UNDERVALUED_PRIORITY') return computeUndervalueFirst(targetAwareStocks, stockGross, delta)
      if (normalizedBaseMode === 'CURRENT_WEIGHT') return computeCurrentWeight(currentWeightStocks, stockGross, delta)
      return computeProportional(positiveTargetStocks, delta)
    }

    if (serverAllocDollars && (mode === 'WATERFALL' || hybrid)) {
      // Group portfolios: use the server GA/SSE result for the selected waterfall-based mode.
      rawAlloc = { ...serverAllocDollars }
    } else if (hybrid) {
      rawAlloc = weightedAverageAllocs(
        computeBase(hybrid.first),
        computeBase(hybrid.second),
        targetAwareStocks,
        hybrid.firstRatio,
        hybrid.secondRatio,
      )
    } else if (mode === 'WATERFALL') {
      rawAlloc = computeWaterfall(targetAwareStocks, stockGross, delta)
    } else if (mode === 'FULL_REBALANCE') {
      rawAlloc = computeFullRebalance(targetAwareStocks, stockGross, delta)
    } else if (mode === 'UNDERVALUED_PRIORITY') {
      rawAlloc = computeUndervalueFirst(targetAwareStocks, stockGross, delta)
    } else if (mode === 'CURRENT_WEIGHT') {
      rawAlloc = computeCurrentWeight(currentWeightStocks, stockGross, delta)
    } else {
      // PROPORTIONAL (default)
      rawAlloc = computeProportional(positiveTargetStocks, delta)
    }

    for (const s of stocks) {
      const dollars = rawAlloc[s.symbol] ?? 0
      allocDollars[s.symbol] = dollars
      allocQty[s.symbol] = s.qty > 0 && s.positionValueUsd > 0
        ? dollars / (s.positionValueUsd / s.qty)
        : 0
    }
  }

  // Margin derived values
  let marginTargetUsdVal: number | null = null
  let marginPctVal: number | null = null
  if (marginUsd < 0) {
    marginTargetUsdVal = marginUsd - (rebalTotal - stockGross)
    marginPctVal = deriveMarginPct(rebalTotal, stockGross, marginUsd)
  }

  return {
    rebalDollars, rebalQty,
    allocDollars, allocQty,
    currentWeightPct,
    marginTargetUsd: marginTargetUsdVal,
    marginPct: marginPctVal,
  }
}
