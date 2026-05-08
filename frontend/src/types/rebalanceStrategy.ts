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
  limitPointIndex: string
  coolingOffDays?: string
  minAdjustmentPct?: string
}

export interface DipSurgeScopeState {
  wholePortfolio: DipSurgeState | null
  individualStock: DipSurgeState | null
}

export type MarginRebalanceTradeDirection = 'BOTH' | 'BUY_ONLY' | 'SELL_ONLY'

export interface RebalStrategyState {
  label: string
  marginRatio: string
  marginSpread: string
  marginPoints: string[]
  portfolioRebalancePeriod: string
  portfolioRebalanceUseComfortZone: boolean
  marginRebalanceEnabled: boolean
  rebalancePeriod: string
  rebalanceAllocStrategy: string
  marginRebalanceTradeDirection: MarginRebalanceTradeDirection
  marginRebalanceRestoreMargin: string
  cashflowImmediateInvestPct: string   // default '100'
  cashflowScaling: string              // CashflowScaling value
  cashflowScalingPointIndex: string
  cashflowScalingMargin: string
  deviationMode: string                // 'ABSOLUTE' | 'RELATIVE'
  sellHighEnabled: boolean
  sellHighAllocStrategy: string
  sellHighRestorePointIndex: string    // index into marginPoints[] for restore target; default '2'
  sellHighRestoreMargin: string
  buyLowEnabled: boolean
  buyLowAllocStrategy: string
  buyLowRestorePointIndex: string      // index into marginPoints[] for restore target; default '2'
  buyLowRestoreMargin: string
  buyTheDip: DipSurgeScopeState
  sellOnSurge: DipSurgeScopeState
  useComfortZone: boolean
  comfortZoneLow: string
  comfortZoneHigh: string
  buyCooldownAfterSellHighDays: string
  sellCooldownAfterBuyLowDays: string
}

// ── Constants ─────────────────────────────────────────────────────────────────

export const REBALANCE_PERIOD_OVERRIDE_OPTIONS = [
  { value: 'INHERIT',        label: 'Inherit' },
  { value: 'NONE',           label: 'None' },
  { value: 'DAILY',          label: 'Daily' },
  { value: 'WEEKLY',         label: 'Weekly' },
  { value: 'BI_WEEKLY',      label: 'Bi-weekly' },
  { value: 'MONTHLY',        label: 'Monthly' },
  { value: 'BI_MONTHLY',     label: 'Bi-monthly' },
  { value: 'QUARTERLY',      label: 'Quarterly' },
  { value: 'EVERY_4_MONTHS', label: 'Every 4 Months' },
  { value: 'HALF_YEARLY',    label: 'Every Half Year' },
  { value: 'YEARLY',         label: 'Yearly' },
]

export const CASHFLOW_SCALING_OPTIONS = [
  { value: 'SCALED_BY_TARGET_MARGIN',  label: 'Scaled by Target Margin' },
  { value: 'SCALED_BY_CURRENT_MARGIN', label: 'Scaled by Current Margin' },
  { value: 'NO_SCALING',               label: 'No Scaling' },
]

export const MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS: { value: MarginRebalanceTradeDirection; label: string }[] = [
  { value: 'BOTH',      label: 'Both' },
  { value: 'BUY_ONLY',  label: 'Buy Only' },
  { value: 'SELL_ONLY', label: 'Sell Only' },
]

export const DIP_SURGE_SCOPE_OPTIONS = [
  { value: 'INDIVIDUAL_STOCK', label: 'Per Individual Stock' },
  { value: 'WHOLE_PORTFOLIO',  label: 'Whole Portfolio' },
]

export const PRICE_MOVE_TRIGGER_OPTIONS = [
  { value: 'VS_N_DAYS_AGO',  label: 'Drop vs N-day high' },
  { value: 'VS_RUNNING_AVG', label: 'Drop vs running avg (N days)' },
  { value: 'PEAK_DEVIATION', label: 'Drawdown from peak' },
]

export const EXECUTION_METHOD_OPTIONS = [
  { value: 'ONCE',        label: 'Buy Once' },
  { value: 'CONSECUTIVE', label: 'Consecutive Buy' },
  { value: 'STEPPED',     label: 'Averaging Down' },
]

// ── Factories ─────────────────────────────────────────────────────────────────

export function emptyDipSurge(scope: DipSurgeState['scope'] = 'INDIVIDUAL_STOCK'): DipSurgeState {
  return {
    scope,
    allocStrategy: 'PROPORTIONAL',
    triggers: [],
    execution: { method: 'ONCE' },
    limit: '',
    limitPointIndex: '',
    coolingOffDays: '10',
    minAdjustmentPct: '0.5',
  }
}

function emptyDipSurgeScopes(): DipSurgeScopeState {
  return { wholePortfolio: null, individualStock: null }
}

export function emptyStrategy(idx: number): RebalStrategyState {
  return {
    label: `Strategy ${idx + 1}`,
    marginRatio: '50',
    marginSpread: '1.5',
    marginPoints: ['40', '45', '50', '55', '60'],
    portfolioRebalancePeriod: 'INHERIT',
    portfolioRebalanceUseComfortZone: true,
    marginRebalanceEnabled: true,
    rebalancePeriod: 'NONE',
    rebalanceAllocStrategy: 'PROPORTIONAL',
    marginRebalanceTradeDirection: 'BOTH',
    marginRebalanceRestoreMargin: '',
    cashflowImmediateInvestPct: '100',
    cashflowScaling: 'SCALED_BY_TARGET_MARGIN',
    cashflowScalingPointIndex: '3',
    cashflowScalingMargin: '',
    deviationMode: 'ABSOLUTE',
    sellHighEnabled: false,
    sellHighAllocStrategy: 'PROPORTIONAL',
    sellHighRestorePointIndex: '2',
    sellHighRestoreMargin: '',
    buyLowEnabled: false,
    buyLowAllocStrategy: 'PROPORTIONAL',
    buyLowRestorePointIndex: '2',
    buyLowRestoreMargin: '',
    buyTheDip: emptyDipSurgeScopes(),
    sellOnSurge: emptyDipSurgeScopes(),
    useComfortZone: true,
    comfortZoneLow: '0',
    comfortZoneHigh: '0',
    buyCooldownAfterSellHighDays: '10',
    sellCooldownAfterBuyLowDays: '10',
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

function serializeDipSurge(d: DipSurgeState, marginPoints: number[]): object {
  const pointIdx = parseInt(d.limitPointIndex, 10)
  const selectedLimit = Number.isFinite(pointIdx) && marginPoints[pointIdx] != null
    ? marginPoints[pointIdx]
    : parseFloat(d.limit)
  const mid = marginPoints[2] ?? 50
  const coolingOffDays = parseInt(d.coolingOffDays ?? '', 10)
  const minAdjustmentPct = parseFloat(d.minAdjustmentPct ?? '')
  return {
    scope: d.scope,
    allocStrategy: d.scope === 'WHOLE_PORTFOLIO' ? d.allocStrategy : null,
    triggers: d.triggers.map(serializeTrigger),
    method: serializeExecution(d.execution),
    limit: (Number.isFinite(selectedLimit) ? selectedLimit : mid) / 100,
    coolingOffDays: Number.isFinite(coolingOffDays) ? Math.max(0, coolingOffDays) : 10,
    minAdjustmentPct: (Number.isFinite(minAdjustmentPct) ? Math.max(0, minAdjustmentPct) : 0.5) / 100,
  }
}

function serializeDipSurgeScopes(d: DipSurgeScopeState | DipSurgeState | null | undefined, marginPoints: number[]): object[] {
  const scopes = normalizeDipSurgeScopes(d)
  return [scopes.wholePortfolio, scopes.individualStock]
    .filter((v): v is DipSurgeState => v !== null)
    .map(v => serializeDipSurge(v, marginPoints))
}

function normalizeDipSurgeScopes(configValue: any): DipSurgeScopeState {
  const normalize = (item: any, scope: DipSurgeState['scope']): DipSurgeState => ({
    ...item,
    scope,
    minAdjustmentPct: item.minAdjustmentPct ?? '0.5',
  })
  const scopes = emptyDipSurgeScopes()
  if (configValue && !Array.isArray(configValue) && ('wholePortfolio' in configValue || 'individualStock' in configValue)) {
    scopes.wholePortfolio = configValue.wholePortfolio ? normalize(configValue.wholePortfolio, 'WHOLE_PORTFOLIO') : null
    scopes.individualStock = configValue.individualStock ? normalize(configValue.individualStock, 'INDIVIDUAL_STOCK') : null
    return scopes
  }
  const items = Array.isArray(configValue) ? configValue : (configValue ? [configValue] : [])
  for (const item of items) {
    if (!item) continue
    if ((item.scope ?? 'INDIVIDUAL_STOCK') === 'WHOLE_PORTFOLIO') scopes.wholePortfolio = normalize(item, 'WHOLE_PORTFOLIO')
    else scopes.individualStock = normalize(item, 'INDIVIDUAL_STOCK')
  }
  return scopes
}

export function strategyStateToSavedConfig(s: RebalStrategyState): RebalStrategyState {
  return { ...s }
}

export function savedConfigToStrategyState(config: any, name: string): RebalStrategyState {
  return {
    ...config,
    label: name,
    portfolioRebalancePeriod: config.portfolioRebalancePeriod ?? 'INHERIT',
    portfolioRebalanceUseComfortZone: config.portfolioRebalanceUseComfortZone ?? config.useComfortZone ?? true,
    marginRebalanceEnabled: config.marginRebalanceEnabled ?? true,
    rebalancePeriod: config.rebalancePeriod === 'INHERIT' ? 'NONE' : (config.rebalancePeriod ?? 'NONE'),
    rebalanceAllocStrategy: config.rebalanceAllocStrategy ?? 'PROPORTIONAL',
    marginRebalanceTradeDirection: config.marginRebalanceTradeDirection ?? 'BOTH',
    marginRebalanceRestoreMargin: config.marginRebalanceRestoreMargin ?? '',
    useComfortZone: config.useComfortZone ?? true,
    buyTheDip: normalizeDipSurgeScopes(config.buyTheDip),
    sellOnSurge: normalizeDipSurgeScopes(config.sellOnSurge),
    buyCooldownAfterSellHighDays: config.buyCooldownAfterSellHighDays ?? '10',
    sellCooldownAfterBuyLowDays: config.sellCooldownAfterBuyLowDays ?? '10',
  }
}

export function strategyStateToAPI(s: RebalStrategyState): object {
  const pct = (v: string, def = 0) => (parseFloat(v) || def) / 100
  const points = [...Array(5)].map((_, i) => parseFloat(s.marginPoints?.[i] ?? '') || [40, 45, 50, 55, 60][i])
  const margin = points[2]
  const low = points[0]
  const high = points[4]
  const customOrPointPct = (customValue: string | undefined, legacyPointIndex: string | undefined, offset = 0) => {
    const custom = parseFloat(customValue ?? '')
    if (Number.isFinite(custom)) return custom / 100
    const idx = parseInt(legacyPointIndex ?? '', 10)
    const pointIdx = Number.isFinite(idx) ? idx - offset : NaN
    return (Number.isFinite(pointIdx) && points[pointIdx] != null ? points[pointIdx] : margin) / 100
  }
  const cashflowIdx = parseInt(s.cashflowScalingPointIndex, 10)
  const cashflowScalingMargin = customOrPointPct(s.cashflowScalingMargin, cashflowIdx <= 0 ? '' : s.cashflowScalingPointIndex, 1)
  const buyCooldownAfterSellHighDays = parseInt(s.buyCooldownAfterSellHighDays ?? '', 10)
  const sellCooldownAfterBuyLowDays = parseInt(s.sellCooldownAfterBuyLowDays ?? '', 10)

  return {
    label: s.label.trim() || 'Strategy',
    marginRatio: margin / 100,
    marginSpread: pct(s.marginSpread, 1.5),
    portfolioRebalancePeriod: s.portfolioRebalancePeriod || 'INHERIT',
    portfolioRebalanceUseComfortZone: s.portfolioRebalanceUseComfortZone ?? true,
    marginRebalanceEnabled: s.marginRebalanceEnabled ?? true,
    rebalancePeriod: (s.marginRebalanceEnabled ?? true)
      ? (s.rebalancePeriod === 'INHERIT' ? 'NONE' : (s.rebalancePeriod || 'NONE'))
      : 'NONE',
    rebalanceAllocStrategy: s.rebalanceAllocStrategy || 'PROPORTIONAL',
    marginRebalanceTradeDirection: s.marginRebalanceTradeDirection || 'BOTH',
    marginRebalanceRestoreMargin: customOrPointPct(s.marginRebalanceRestoreMargin, undefined),
    cashflowImmediateInvestPct: pct(s.cashflowImmediateInvestPct, 100),
    cashflowScaling: s.cashflowScaling || 'SCALED_BY_TARGET_MARGIN',
    cashflowScalingMargin,
    deviationMode: 'ABSOLUTE',
    sellOnHighMargin: s.sellHighEnabled
      ? { deviationPct: high / 100, allocStrategy: s.sellHighAllocStrategy || 'PROPORTIONAL', targetMargin: customOrPointPct(s.sellHighRestoreMargin, s.sellHighRestorePointIndex) }
      : null,
    buyOnLowMargin: s.buyLowEnabled
      ? { deviationPct: low / 100, allocStrategy: s.buyLowAllocStrategy || 'PROPORTIONAL', targetMargin: customOrPointPct(s.buyLowRestoreMargin, s.buyLowRestorePointIndex) }
      : null,
    buyTheDip: serializeDipSurgeScopes(s.buyTheDip, points),
    sellOnSurge: serializeDipSurgeScopes(s.sellOnSurge, points),
    useComfortZone: s.useComfortZone ?? true,
    comfortZoneLow:  low / 100,
    comfortZoneHigh: high / 100,
    buyCooldownAfterSellHighDays: Number.isFinite(buyCooldownAfterSellHighDays) ? Math.max(0, buyCooldownAfterSellHighDays) : 10,
    sellCooldownAfterBuyLowDays: Number.isFinite(sellCooldownAfterBuyLowDays) ? Math.max(0, sellCooldownAfterBuyLowDays) : 10,
  }
}
