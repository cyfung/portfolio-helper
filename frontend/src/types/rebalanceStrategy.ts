// ── types/rebalanceStrategy.ts ───────────────────────────────────────────────

import { newId } from './backtest'

// ── Discriminated unions matching Kotlin sealed classes ───────────────────────

export type PriceMoveTriggerState =
  | { type: 'VS_N_DAYS_AGO';  nDays: string; pct: string }
  | { type: 'VS_RUNNING_AVG'; nDays: string; pct: string }
  | { type: 'PEAK_DEVIATION'; pct: string }

export type ExecutionMethodState =
  | { method: 'ONCE' }
  | { method: 'CONSECUTIVE'; days: string }
  | { method: 'STEPPED';     portions: string; additionalPct: string }

// ── Form state ────────────────────────────────────────────────────────────────

export interface DipSurgeState {
  scope: string            // 'INDIVIDUAL_STOCK' | 'WHOLE_PORTFOLIO'
  allocStrategy: string    // MarginRebalanceMode value
  triggers: (PriceMoveTriggerState & { id: string })[]
  execution: ExecutionMethodState
  limit: string
}

export interface RebalStrategyState {
  label: string
  marginRatio: string
  marginSpread: string
  rebalancePeriod: string              // 'INHERIT' | 'NONE' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY'
  cashflowImmediateInvestPct: string   // default '100'
  cashflowScaling: string              // CashflowScaling value
  deviationMode: string                // 'ABSOLUTE' | 'RELATIVE'
  sellHighEnabled: boolean
  sellHighDeviationPct: string
  sellHighAllocStrategy: string
  buyLowEnabled: boolean
  buyLowDeviationPct: string
  buyLowAllocStrategy: string
  buyTheDip: DipSurgeState | null
  sellOnSurge: DipSurgeState | null
}

// ── Constants ─────────────────────────────────────────────────────────────────

export const REBALANCE_PERIOD_OVERRIDE_OPTIONS = [
  { value: 'INHERIT',   label: 'Inherit' },
  { value: 'NONE',      label: 'None' },
  { value: 'MONTHLY',   label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'YEARLY',    label: 'Yearly' },
]

export const CASHFLOW_SCALING_OPTIONS = [
  { value: 'SCALED_BY_TARGET_MARGIN',  label: 'Scaled by Target Margin' },
  { value: 'SCALED_BY_CURRENT_MARGIN', label: 'Scaled by Current Margin' },
  { value: 'NO_SCALING',               label: 'No Scaling' },
]

export const DIP_SURGE_SCOPE_OPTIONS = [
  { value: 'INDIVIDUAL_STOCK', label: 'Per Individual Stock' },
  { value: 'WHOLE_PORTFOLIO',  label: 'Whole Portfolio' },
]

export const PRICE_MOVE_TRIGGER_OPTIONS = [
  { value: 'VS_N_DAYS_AGO',  label: 'Drop vs N days ago' },
  { value: 'VS_RUNNING_AVG', label: 'Drop vs running avg (N days)' },
  { value: 'PEAK_DEVIATION', label: 'Drawdown from peak' },
]

export const EXECUTION_METHOD_OPTIONS = [
  { value: 'ONCE',        label: 'Buy Once' },
  { value: 'CONSECUTIVE', label: 'Consecutive Buy' },
  { value: 'STEPPED',     label: 'Averaging Down' },
]

// ── Factories ─────────────────────────────────────────────────────────────────

function emptyDipSurge(): DipSurgeState {
  return {
    scope: 'INDIVIDUAL_STOCK',
    allocStrategy: 'PROPORTIONAL',
    triggers: [],
    execution: { method: 'ONCE' },
    limit: '15',
  }
}

export function emptyStrategy(idx: number): RebalStrategyState {
  return {
    label: `Strategy ${idx + 1}`,
    marginRatio: '50',
    marginSpread: '1.5',
    rebalancePeriod: 'INHERIT',
    cashflowImmediateInvestPct: '100',
    cashflowScaling: 'SCALED_BY_TARGET_MARGIN',
    deviationMode: 'ABSOLUTE',
    sellHighEnabled: false,
    sellHighDeviationPct: '',
    sellHighAllocStrategy: 'PROPORTIONAL',
    buyLowEnabled: false,
    buyLowDeviationPct: '',
    buyLowAllocStrategy: 'PROPORTIONAL',
    buyTheDip: null,
    sellOnSurge: null,
  }
}

export function emptyTrigger(type: PriceMoveTriggerState['type']): PriceMoveTriggerState & { id: string } {
  const id = newId()
  if (type === 'PEAK_DEVIATION') return { id, type, pct: '10' }
  return { id, type, nDays: '20', pct: '5' }
}

// ── API serialisers ───────────────────────────────────────────────────────────

function serializeExecution(e: ExecutionMethodState): object {
  if (e.method === 'ONCE') return { method: 'ONCE' }
  if (e.method === 'CONSECUTIVE') return { method: 'CONSECUTIVE', days: parseInt(e.days) || 7 }
  return {
    method: 'STEPPED',
    portions: parseInt(e.portions) || 3,
    additionalPct: (parseFloat(e.additionalPct) || 5) / 100,
  }
}

function serializeTrigger(t: PriceMoveTriggerState): object {
  if (t.type === 'PEAK_DEVIATION') return { type: 'PEAK_DEVIATION', pct: (parseFloat(t.pct) || 10) / 100 }
  return {
    type: t.type,
    nDays: parseInt(t.nDays) || 20,
    pct: (parseFloat(t.pct) || 5) / 100,
  }
}

function serializeDipSurge(d: DipSurgeState): object {
  return {
    scope: d.scope,
    allocStrategy: d.scope === 'WHOLE_PORTFOLIO' ? d.allocStrategy : null,
    triggers: d.triggers.map(serializeTrigger),
    method: serializeExecution(d.execution),
    limit: (parseFloat(d.limit) || 15) / 100,
  }
}

export function strategyStateToAPI(s: RebalStrategyState, portfolioRebalance: string): object {
  const pct = (v: string, def = 0) => (parseFloat(v) || def) / 100
  const optPct = (v: string) => v.trim() ? pct(v) : null

  return {
    label: s.label.trim() || 'Strategy',
    marginRatio: pct(s.marginRatio, 50),
    marginSpread: pct(s.marginSpread, 1.5),
    rebalancePeriod: s.rebalancePeriod || 'INHERIT',
    cashflowImmediateInvestPct: pct(s.cashflowImmediateInvestPct, 100),
    cashflowScaling: s.cashflowScaling || 'SCALED_BY_TARGET_MARGIN',
    deviationMode: s.deviationMode || 'ABSOLUTE',
    sellOnHighMargin: s.sellHighEnabled
      ? { deviationPct: optPct(s.sellHighDeviationPct), allocStrategy: s.sellHighAllocStrategy || 'PROPORTIONAL' }
      : null,
    buyOnLowMargin: s.buyLowEnabled
      ? { deviationPct: optPct(s.buyLowDeviationPct), allocStrategy: s.buyLowAllocStrategy || 'PROPORTIONAL' }
      : null,
    buyTheDip: s.buyTheDip ? serializeDipSurge(s.buyTheDip) : null,
    sellOnSurge: s.sellOnSurge ? serializeDipSurge(s.sellOnSurge) : null,
  }
}
