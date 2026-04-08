// ── rebalance.ts — Port of display-worker.js computation logic ────────────────
// All functions are pure (no side effects) for use in React renders.

import type { AllocMode } from '@/types/portfolio'

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
  if (marginTargetPct !== null && marginTargetPct > 0) {
    return deriveRebalFromMarginPct(marginTargetPct, stockGross, marginUsd)
  }
  if (rebalTargetUsd !== null && rebalTargetUsd > 0) return rebalTargetUsd
  if (marginTargetUsd !== null && marginTargetUsd > 0) return (stockGross + marginUsd) + marginTargetUsd
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
  const sign = delta >= 0 ? 1 : -1

  const currentDev: Record<string, number> = {}
  for (const s of eligible)
    currentDev[s.symbol] = (s.positionValueUsd / finalTotal) - (s.targetWeight / 100)

  const sorted = [...eligible].sort((a, b) => sign * (currentDev[a.symbol] - currentDev[b.symbol]))
  let remaining = Math.abs(delta)

  for (let i = 0; i < sorted.length && remaining > 0; i++) {
    const groupDev = currentDev[sorted[0].symbol]
    const nextDev = i + 1 < sorted.length ? currentDev[sorted[i + 1].symbol] : sign * Infinity
    const groupSize = i + 1
    const costToLevel = (nextDev - groupDev) * sign * finalTotal * groupSize

    if (remaining >= costToLevel) {
      for (let j = 0; j <= i; j++) {
        alloc[sorted[j].symbol] += (nextDev - groupDev) * finalTotal
        currentDev[sorted[j].symbol] = nextDev
      }
      remaining -= costToLevel
    } else {
      const perStock = remaining / groupSize
      for (let j = 0; j <= i; j++) alloc[sorted[j].symbol] += perStock * sign
      remaining = 0
    }
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

  let remaining = delta
  for (const s of sorted) {
    const targetVal = (s.targetWeight / 100) * finalTotal
    const needed = (targetVal - s.positionValueUsd) * sign
    if (needed <= 0) break
    const contribution = Math.min(needed, Math.abs(remaining)) * sign
    alloc[s.symbol] = contribution
    remaining -= contribution
    if (Math.abs(remaining) < 0.01) break
  }

  if (Math.abs(remaining) > 0.01) {
    applyProportionalSpillover(alloc, eligible, remaining, sign)
  }

  return alloc
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

export function computeDisplay(
  stocks: StockForCompute[],
  rebalTargetUsd: number | null,
  marginTargetPct: number | null,
  allocAddMode: AllocMode,
  allocReduceMode: AllocMode,
  stockGross: number,
  marginUsd: number,
  serverAllocDollars?: Record<string, number>  // from rebal-alloc SSE (waterfall)
): ComputeResult {
  const rebalDollars: Record<string, number> = {}
  const rebalQty: Record<string, number> = {}
  const allocDollars: Record<string, number> = {}
  const allocQty: Record<string, number> = {}
  const currentWeightPct: Record<string, number> = {}

  const portfolioTotal = stockGross + Math.max(marginUsd, 0)
  const rebalTotal = getRebalTotal(rebalTargetUsd, marginTargetPct, stockGross, marginUsd)
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

  // Alloc columns: how to deploy the delta using the chosen strategy
  if (hasTargetWeights && Math.abs(delta) > 0.01) {
    const sign = delta >= 0 ? 1 : -1
    const eligible = stocks.filter(s => s.targetWeight > 0)
    const mode = delta >= 0 ? allocAddMode : allocReduceMode

    let rawAlloc: Record<string, number> = {}

    if (mode === 'WATERFALL') {
      // Group portfolios: use GA result from server SSE; normal portfolios: compute client-side
      if (serverAllocDollars) rawAlloc = { ...serverAllocDollars }
      else rawAlloc = computeWaterfall(eligible, stockGross, delta)
    } else if (mode === 'UNDERVALUED_PRIORITY') {
      rawAlloc = computeUndervalueFirst(eligible, stockGross, delta)
    } else if (mode === 'CURRENT_WEIGHT') {
      rawAlloc = computeCurrentWeight(eligible, stockGross, delta)
    } else {
      // PROPORTIONAL (default)
      rawAlloc = computeProportional(eligible, delta)
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
