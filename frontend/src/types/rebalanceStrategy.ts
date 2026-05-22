// ── types/rebalanceStrategy.ts ───────────────────────────────────────────────

import { newId } from './backtest'
import { DEFAULT_SPREAD_PERCENT, normalizeNumberInput, percentInputToFraction } from '@/lib/numberInputs'

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
  scope: string            // 'INDIVIDUAL_STOCK' | 'BASE_PORTFOLIO'
  allocStrategy: string    // MarginRebalanceMode value
  portfolioSource?: string // 'STRATEGY_GROSS' | 'STRATEGY_VALUE' | 'REFERENCE_PORTFOLIO'
  referenceTicker?: string
  triggers: (PriceMoveTriggerState & { id: string })[]
  execution: ExecutionMethodState
  limit: string
  limitPointIndex: string
  coolingOffDays?: string
  minAdjustmentPct?: string
}

export interface DipSurgeScopeState {
  basePortfolio: DipSurgeState | null
  individualStock: DipSurgeState | null
}

export type MarginRebalanceTradeDirection = 'BOTH' | 'BUY_ONLY' | 'SELL_ONLY'

export interface DrawdownMarginOverrideState {
  enabled: boolean
  portfolioSource: string
  referenceTicker?: string
  enterDrawdownPct: string
  exitDrawdownPct: string
  targetMargin: string
  rebalancePeriod: string
  rebalanceOnEnter: boolean
  allocStrategy: string
  buyAllocStrategy: string
  sellAllocStrategy: string
  tradeDirection: MarginRebalanceTradeDirection
}

export interface DrawdownMarginTriggerState {
  enabled: boolean
  portfolioSource: string
  referenceTicker?: string
  enterDrawdownPct: string
  exitDrawdownPct: string
  triggerPointIndex: string
  triggerMargin: string
  allocStrategy: string
  restorePointIndex: string
  restoreMargin: string
  tiers: DrawdownMarginTriggerTierState[]
}

export interface DrawdownMarginTriggerTierState {
  id: string
  enterDrawdownPct: string
  exitDrawdownPct: string
  triggerPointIndex: string
  triggerMargin: string
  allocStrategy: string
  restorePointIndex: string
  restoreMargin: string
}

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
  drawdownMarginOverride: DrawdownMarginOverrideState
  cashflowImmediateInvestPct: string   // default '100'
  cashflowScaling: string              // CashflowScaling value
  cashflowScalingPointIndex: string
  cashflowScalingMargin: string
  deviationMode: string                // 'ABSOLUTE' | 'RELATIVE'
  sellHighEnabled: boolean
  sellHighTriggerPointIndex: string
  sellHighTriggerMargin: string
  sellHighAllocStrategy: string
  sellHighRestorePointIndex: string    // index into marginPoints[] for restore target; default '2'
  sellHighRestoreMargin: string
  buyLowEnabled: boolean
  buyLowTriggerPointIndex: string
  buyLowTriggerMargin: string
  buyLowAllocStrategy: string
  buyLowRestorePointIndex: string      // index into marginPoints[] for restore target; default '2'
  buyLowRestoreMargin: string
  drawdownBuyOnLowMargin: DrawdownMarginTriggerState
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
  { value: 'BASE_PORTFOLIO',   label: 'Portfolio Trigger' },
]

export const PORTFOLIO_TRIGGER_SOURCE_OPTIONS = [
  { value: 'STRATEGY_GROSS',      label: 'Strategy Stock Gross Value' },
  { value: 'STRATEGY_VALUE',      label: 'Strategy Portfolio Value' },
  { value: 'REFERENCE_PORTFOLIO', label: 'Independent Reference' },
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
    portfolioSource: 'REFERENCE_PORTFOLIO',
    referenceTicker: '',
    triggers: [],
    execution: { method: 'ONCE' },
    limit: '',
    limitPointIndex: '',
    coolingOffDays: '10',
    minAdjustmentPct: '0.5',
  }
}

function emptyDipSurgeScopes(): DipSurgeScopeState {
  return { basePortfolio: null, individualStock: null }
}

export function emptyDrawdownMarginOverride(): DrawdownMarginOverrideState {
  return {
    enabled: false,
    portfolioSource: 'REFERENCE_PORTFOLIO',
    referenceTicker: '',
    enterDrawdownPct: '10',
    exitDrawdownPct: '5',
    targetMargin: '95',
    rebalancePeriod: 'BI_MONTHLY',
    rebalanceOnEnter: true,
    allocStrategy: 'PROPORTIONAL',
    buyAllocStrategy: 'PROPORTIONAL',
    sellAllocStrategy: 'PROPORTIONAL',
    tradeDirection: 'BOTH',
  }
}

export function emptyDrawdownMarginTriggerTier(direction: 'buy' | 'sell'): DrawdownMarginTriggerTierState {
  return {
    id: newId(),
    enterDrawdownPct: '10',
    exitDrawdownPct: '5',
    triggerPointIndex: direction === 'buy' ? '0' : '4',
    triggerMargin: '',
    allocStrategy: 'PROPORTIONAL',
    restorePointIndex: '2',
    restoreMargin: '',
  }
}

export function emptyDrawdownMarginTrigger(direction: 'buy' | 'sell'): DrawdownMarginTriggerState {
  const tier = emptyDrawdownMarginTriggerTier(direction)
  return {
    enabled: false,
    portfolioSource: 'REFERENCE_PORTFOLIO',
    referenceTicker: '',
    enterDrawdownPct: tier.enterDrawdownPct,
    exitDrawdownPct: tier.exitDrawdownPct,
    triggerPointIndex: tier.triggerPointIndex,
    triggerMargin: tier.triggerMargin,
    allocStrategy: tier.allocStrategy,
    restorePointIndex: tier.restorePointIndex,
    restoreMargin: tier.restoreMargin,
    tiers: [tier],
  }
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
    drawdownMarginOverride: emptyDrawdownMarginOverride(),
    cashflowImmediateInvestPct: '100',
    cashflowScaling: 'SCALED_BY_TARGET_MARGIN',
    cashflowScalingPointIndex: '3',
    cashflowScalingMargin: '',
    deviationMode: 'ABSOLUTE',
    sellHighEnabled: false,
    sellHighTriggerPointIndex: '4',
    sellHighTriggerMargin: '',
    sellHighAllocStrategy: 'PROPORTIONAL',
    sellHighRestorePointIndex: '2',
    sellHighRestoreMargin: '',
    buyLowEnabled: false,
    buyLowTriggerPointIndex: '0',
    buyLowTriggerMargin: '',
    buyLowAllocStrategy: 'PROPORTIONAL',
    buyLowRestorePointIndex: '2',
    buyLowRestoreMargin: '',
    drawdownBuyOnLowMargin: emptyDrawdownMarginTrigger('buy'),
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
  const portfolioSource = d.portfolioSource || 'REFERENCE_PORTFOLIO'
  const referenceTicker = (d.referenceTicker ?? '').trim().toUpperCase()
  return {
    scope: d.scope,
    allocStrategy: d.scope === 'BASE_PORTFOLIO' ? d.allocStrategy : null,
    portfolioSource: d.scope === 'BASE_PORTFOLIO' ? portfolioSource : null,
    referenceTicker: d.scope === 'BASE_PORTFOLIO' && portfolioSource === 'REFERENCE_PORTFOLIO' && referenceTicker ? referenceTicker : null,
    triggers: d.triggers.map(serializeTrigger),
    method: serializeExecution(d.execution),
    limit: (Number.isFinite(selectedLimit) ? selectedLimit : mid) / 100,
    coolingOffDays: Number.isFinite(coolingOffDays) ? Math.max(0, coolingOffDays) : 10,
    minAdjustmentPct: (Number.isFinite(minAdjustmentPct) ? Math.max(0, minAdjustmentPct) : 0.5) / 100,
  }
}

function serializeDipSurgeScopes(d: DipSurgeScopeState | DipSurgeState | null | undefined, marginPoints: number[]): object[] {
  const scopes = normalizeDipSurgeScopes(d)
  return [scopes.basePortfolio, scopes.individualStock]
    .filter((v): v is DipSurgeState => v !== null)
    .map(v => serializeDipSurge(v, marginPoints))
}

function normalizeDipSurgeScopes(configValue: any): DipSurgeScopeState {
  const normalize = (
    item: any,
    scope: DipSurgeState['scope'],
    portfolioSource = item.portfolioSource ?? 'REFERENCE_PORTFOLIO',
  ): DipSurgeState => ({
    ...item,
    scope,
    portfolioSource: scope === 'BASE_PORTFOLIO' ? portfolioSource : 'REFERENCE_PORTFOLIO',
    referenceTicker: scope === 'BASE_PORTFOLIO' && portfolioSource === 'REFERENCE_PORTFOLIO' ? (item.referenceTicker ?? '') : '',
    minAdjustmentPct: item.minAdjustmentPct ?? '0.5',
  })
  const scopes = emptyDipSurgeScopes()
  if (configValue && !Array.isArray(configValue) && ('basePortfolio' in configValue || 'individualStock' in configValue || 'wholePortfolio' in configValue)) {
    scopes.basePortfolio = configValue.basePortfolio
      ? normalize(configValue.basePortfolio, 'BASE_PORTFOLIO')
      : (configValue.wholePortfolio ? normalize(configValue.wholePortfolio, 'BASE_PORTFOLIO', 'STRATEGY_GROSS') : null)
    scopes.individualStock = configValue.individualStock ? normalize(configValue.individualStock, 'INDIVIDUAL_STOCK') : null
    return scopes
  }
  const items = Array.isArray(configValue) ? configValue : (configValue ? [configValue] : [])
  for (const item of items) {
    if (!item) continue
    if (item.scope === 'BASE_PORTFOLIO') scopes.basePortfolio = normalize(item, 'BASE_PORTFOLIO')
    else if (item.scope === 'WHOLE_PORTFOLIO') scopes.basePortfolio = normalize(item, 'BASE_PORTFOLIO', 'STRATEGY_GROSS')
    else if ((item.scope ?? 'INDIVIDUAL_STOCK') === 'INDIVIDUAL_STOCK') scopes.individualStock = normalize(item, 'INDIVIDUAL_STOCK')
  }
  return scopes
}

function normalizeDrawdownMarginOverride(configValue: any): DrawdownMarginOverrideState {
  const base = emptyDrawdownMarginOverride()
  if (!configValue) return base
  const portfolioSource = configValue.portfolioSource ?? base.portfolioSource
  return {
    ...base,
    ...configValue,
    enabled: configValue.enabled ?? false,
    portfolioSource,
    referenceTicker: portfolioSource === 'REFERENCE_PORTFOLIO' ? (configValue.referenceTicker ?? '') : '',
    tradeDirection: configValue.tradeDirection ?? base.tradeDirection,
    allocStrategy: configValue.allocStrategy ?? base.allocStrategy,
    buyAllocStrategy: configValue.buyAllocStrategy ?? configValue.allocStrategy ?? base.buyAllocStrategy,
    sellAllocStrategy: configValue.sellAllocStrategy ?? configValue.allocStrategy ?? base.sellAllocStrategy,
    rebalancePeriod: configValue.rebalancePeriod ?? base.rebalancePeriod,
    rebalanceOnEnter: configValue.rebalanceOnEnter ?? true,
  }
}

function normalizeDrawdownMarginTrigger(configValue: any, direction: 'buy' | 'sell'): DrawdownMarginTriggerState {
  const base = emptyDrawdownMarginTrigger(direction)
  if (!configValue) return base
  const portfolioSource = configValue.portfolioSource ?? base.portfolioSource
  const legacyDrawdownPct = configValue.drawdownPct ?? configValue.enterDrawdownPct
  const legacyTier = {
    id: newId(),
    enterDrawdownPct: configValue.enterDrawdownPct ?? configValue.drawdownPct ?? base.enterDrawdownPct,
    exitDrawdownPct: configValue.exitDrawdownPct ?? legacyDrawdownPct ?? base.exitDrawdownPct,
    triggerPointIndex: configValue.triggerPointIndex ?? base.triggerPointIndex,
    triggerMargin: configValue.triggerMargin ?? '',
    allocStrategy: configValue.allocStrategy ?? base.allocStrategy,
    restorePointIndex: configValue.restorePointIndex ?? base.restorePointIndex,
    restoreMargin: configValue.restoreMargin ?? '',
  }
  const tiers = Array.isArray(configValue.tiers) && configValue.tiers.length > 0
    ? configValue.tiers.map((tier: any) => ({
      id: tier.id ?? newId(),
      enterDrawdownPct: tier.enterDrawdownPct ?? tier.drawdownPct ?? legacyTier.enterDrawdownPct,
      exitDrawdownPct: tier.exitDrawdownPct ?? tier.drawdownPct ?? legacyTier.exitDrawdownPct,
      triggerPointIndex: tier.triggerPointIndex ?? legacyTier.triggerPointIndex,
      triggerMargin: tier.triggerMargin ?? '',
      allocStrategy: tier.allocStrategy ?? legacyTier.allocStrategy,
      restorePointIndex: tier.restorePointIndex ?? legacyTier.restorePointIndex,
      restoreMargin: tier.restoreMargin ?? '',
    }))
    : [legacyTier]
  const firstTier = tiers[0] ?? legacyTier
  return {
    ...base,
    ...configValue,
    enabled: configValue.enabled ?? false,
    portfolioSource,
    referenceTicker: portfolioSource === 'REFERENCE_PORTFOLIO' ? (configValue.referenceTicker ?? '') : '',
    enterDrawdownPct: firstTier.enterDrawdownPct,
    exitDrawdownPct: firstTier.exitDrawdownPct,
    triggerPointIndex: firstTier.triggerPointIndex,
    triggerMargin: firstTier.triggerMargin,
    allocStrategy: firstTier.allocStrategy,
    restorePointIndex: firstTier.restorePointIndex,
    restoreMargin: firstTier.restoreMargin,
    tiers,
  }
}

export function strategyStateToSavedConfig(s: RebalStrategyState): RebalStrategyState {
  return { ...s }
}

export function normalizeStrategySpreadInput(s: RebalStrategyState): RebalStrategyState {
  const marginSpread = normalizeNumberInput(s.marginSpread, DEFAULT_SPREAD_PERCENT, { min: 0 })
  return marginSpread === s.marginSpread ? s : { ...s, marginSpread }
}

function drawdownTriggerTiers(d: DrawdownMarginTriggerState | undefined, direction: 'buy' | 'sell') {
  const fallback = emptyDrawdownMarginTrigger(direction)
  const cfg = d ?? fallback
  return (cfg.tiers?.length ? cfg.tiers : [{
    id: 'legacy',
    enterDrawdownPct: cfg.enterDrawdownPct,
    exitDrawdownPct: cfg.exitDrawdownPct,
    triggerPointIndex: cfg.triggerPointIndex,
    triggerMargin: cfg.triggerMargin,
    allocStrategy: cfg.allocStrategy,
    restorePointIndex: cfg.restorePointIndex,
    restoreMargin: cfg.restoreMargin,
  }])
}

export function drawdownMarginTriggerIssues(
  d: DrawdownMarginTriggerState | undefined,
  direction: 'buy' | 'sell',
  label: string,
): string[] {
  if (!d?.enabled) return []
  const tiers = drawdownTriggerTiers(d, direction)
  if (tiers.length === 0) return [`${label}: add at least one tier.`]
  const issues: string[] = []
  let prevEnter: number | null = null
  let prevExit: number | null = null
  tiers.forEach((tier, i) => {
    const n = i + 1
    const enter = parseFloat(tier.enterDrawdownPct)
    const exit = parseFloat(tier.exitDrawdownPct)
    if (!Number.isFinite(enter) || enter < 0) issues.push(`${label} tier ${n}: enter DD must be 0 or higher.`)
    if (!Number.isFinite(exit)) issues.push(`${label} tier ${n}: exit DD must be a number.`)
    if (Number.isFinite(enter) && Number.isFinite(exit) && exit > enter) {
      issues.push(`${label} tier ${n}: exit DD cannot be deeper than enter DD.`)
    }
    if (prevEnter != null && Number.isFinite(enter) && enter <= prevEnter) {
      issues.push(`${label} tier ${n}: enter DD must be greater than the previous tier.`)
    }
    if (prevExit != null && Number.isFinite(exit) && exit < prevExit) {
      issues.push(`${label} tier ${n}: exit DD must be equal to or greater than the previous tier.`)
    }
    if (Number.isFinite(enter)) prevEnter = enter
    if (Number.isFinite(exit)) prevExit = exit
  })
  return issues
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
    drawdownMarginOverride: normalizeDrawdownMarginOverride(config.drawdownMarginOverride),
    sellHighTriggerPointIndex: config.sellHighTriggerPointIndex ?? '4',
    sellHighTriggerMargin: config.sellHighTriggerMargin ?? '',
    buyLowTriggerPointIndex: config.buyLowTriggerPointIndex ?? '0',
    buyLowTriggerMargin: config.buyLowTriggerMargin ?? '',
    drawdownBuyOnLowMargin: normalizeDrawdownMarginTrigger(config.drawdownBuyOnLowMargin, 'buy'),
    useComfortZone: config.useComfortZone ?? true,
    buyTheDip: normalizeDipSurgeScopes(config.buyTheDip),
    sellOnSurge: normalizeDipSurgeScopes(config.sellOnSurge),
    buyCooldownAfterSellHighDays: config.buyCooldownAfterSellHighDays ?? '10',
    sellCooldownAfterBuyLowDays: config.sellCooldownAfterBuyLowDays ?? '10',
  }
}

export function strategyStateToAPI(s: RebalStrategyState): object {
  const pct = (v: string, def = 0) => (parseFloat(v) || def) / 100
  const pctAllowZero = (v: string | undefined, def = 0) => {
    const parsed = parseFloat(v ?? '')
    return (Number.isFinite(parsed) ? parsed : def) / 100
  }
  const points = [...Array(5)].map((_, i) => parseFloat(s.marginPoints?.[i] ?? '') || [40, 45, 50, 55, 60][i])
  const margin = points[2]
  const lowComfort = points[1]
  const highComfort = points[3]
  const customOrPointPct = (customValue: string | undefined, legacyPointIndex: string | undefined, offset = 0, fallbackPointIndex = 2) => {
    const custom = parseFloat(customValue ?? '')
    if (Number.isFinite(custom)) return custom / 100
    const idx = parseInt(legacyPointIndex ?? '', 10)
    const pointIdx = Number.isFinite(idx) ? idx - offset : NaN
    return (Number.isFinite(pointIdx) && points[pointIdx] != null ? points[pointIdx] : (points[fallbackPointIndex] ?? margin)) / 100
  }
  const marginTriggerPct = (customValue: string | undefined, pointIndex: string | undefined, fallbackPointIndex: number) =>
    customOrPointPct(customValue, pointIndex, 0, fallbackPointIndex)
  const cashflowIdx = parseInt(s.cashflowScalingPointIndex, 10)
  const cashflowScalingMargin = customOrPointPct(s.cashflowScalingMargin, cashflowIdx <= 0 ? '' : s.cashflowScalingPointIndex, 1)
  const buyCooldownAfterSellHighDays = parseInt(s.buyCooldownAfterSellHighDays ?? '', 10)
  const sellCooldownAfterBuyLowDays = parseInt(s.sellCooldownAfterBuyLowDays ?? '', 10)
  const drawdownOverride = s.drawdownMarginOverride ?? emptyDrawdownMarginOverride()
  const drawdownReferenceTicker = (drawdownOverride.referenceTicker ?? '').trim().toUpperCase()
  const serializeDrawdownMarginTrigger = (
    d: DrawdownMarginTriggerState | undefined,
    direction: 'buy' | 'sell',
  ) => {
    const fallback = emptyDrawdownMarginTrigger(direction)
    const cfg = d ?? fallback
    if (!cfg.enabled) return null
    const portfolioSource = cfg.portfolioSource || 'REFERENCE_PORTFOLIO'
    const referenceTicker = (cfg.referenceTicker ?? '').trim().toUpperCase()
    const tiers = drawdownTriggerTiers(cfg, direction)
      .map(tier => ({
        enterDrawdownPct: pctAllowZero(tier.enterDrawdownPct, 10),
        exitDrawdownPct: pctAllowZero(tier.exitDrawdownPct, 5),
        triggerMargin: marginTriggerPct(tier.triggerMargin, tier.triggerPointIndex, direction === 'buy' ? 0 : 4),
        allocStrategy: tier.allocStrategy || 'PROPORTIONAL',
        targetMargin: customOrPointPct(tier.restoreMargin, tier.restorePointIndex, 0, 2),
      }))
      .sort((a, b) => a.enterDrawdownPct - b.enterDrawdownPct)
    const firstTier = tiers[0]
    return {
      enabled: true,
      portfolioSource,
      referenceTicker: portfolioSource === 'REFERENCE_PORTFOLIO' && referenceTicker ? referenceTicker : null,
      enterDrawdownPct: firstTier?.enterDrawdownPct ?? pctAllowZero(cfg.enterDrawdownPct, 10),
      exitDrawdownPct: firstTier?.exitDrawdownPct ?? pctAllowZero(cfg.exitDrawdownPct, 5),
      triggerMargin: firstTier?.triggerMargin ?? marginTriggerPct(cfg.triggerMargin, cfg.triggerPointIndex, direction === 'buy' ? 0 : 4),
      allocStrategy: firstTier?.allocStrategy ?? cfg.allocStrategy ?? 'PROPORTIONAL',
      targetMargin: firstTier?.targetMargin ?? customOrPointPct(cfg.restoreMargin, cfg.restorePointIndex, 0, 2),
      tiers,
    }
  }

  return {
    label: s.label.trim() || 'Strategy',
    marginRatio: margin / 100,
    marginSpread: percentInputToFraction(s.marginSpread, DEFAULT_SPREAD_PERCENT, { min: 0 }),
    portfolioRebalancePeriod: s.portfolioRebalancePeriod || 'INHERIT',
    portfolioRebalanceUseComfortZone: s.portfolioRebalanceUseComfortZone ?? true,
    marginRebalanceEnabled: s.marginRebalanceEnabled ?? true,
    rebalancePeriod: (s.marginRebalanceEnabled ?? true)
      ? (s.rebalancePeriod === 'INHERIT' ? 'NONE' : (s.rebalancePeriod || 'NONE'))
      : 'NONE',
    rebalanceAllocStrategy: s.rebalanceAllocStrategy || 'PROPORTIONAL',
    marginRebalanceTradeDirection: s.marginRebalanceTradeDirection || 'BOTH',
    marginRebalanceRestoreMargin: customOrPointPct(s.marginRebalanceRestoreMargin, undefined),
    drawdownMarginOverride: (s.marginRebalanceEnabled ?? true) && drawdownOverride.enabled
      ? {
        enabled: true,
        portfolioSource: drawdownOverride.portfolioSource || 'REFERENCE_PORTFOLIO',
        referenceTicker: drawdownOverride.portfolioSource === 'REFERENCE_PORTFOLIO' && drawdownReferenceTicker ? drawdownReferenceTicker : null,
        enterDrawdownPct: pctAllowZero(drawdownOverride.enterDrawdownPct, 10),
        exitDrawdownPct: pctAllowZero(drawdownOverride.exitDrawdownPct, 5),
        targetMargin: pctAllowZero(drawdownOverride.targetMargin, 95),
        rebalancePeriod: drawdownOverride.rebalancePeriod === 'INHERIT' ? 'NONE' : (drawdownOverride.rebalancePeriod || 'BI_MONTHLY'),
        rebalanceOnEnter: drawdownOverride.rebalanceOnEnter ?? true,
        allocStrategy: drawdownOverride.allocStrategy || s.rebalanceAllocStrategy || 'PROPORTIONAL',
        buyAllocStrategy: drawdownOverride.buyAllocStrategy || drawdownOverride.allocStrategy || s.rebalanceAllocStrategy || 'PROPORTIONAL',
        sellAllocStrategy: drawdownOverride.sellAllocStrategy || drawdownOverride.allocStrategy || s.rebalanceAllocStrategy || 'PROPORTIONAL',
        tradeDirection: drawdownOverride.tradeDirection || s.marginRebalanceTradeDirection || 'BOTH',
      }
      : null,
    cashflowImmediateInvestPct: pct(s.cashflowImmediateInvestPct, 100),
    cashflowScaling: s.cashflowScaling || 'SCALED_BY_TARGET_MARGIN',
    cashflowScalingMargin,
    deviationMode: 'ABSOLUTE',
    sellOnHighMargin: s.sellHighEnabled
      ? {
        deviationPct: marginTriggerPct(s.sellHighTriggerMargin, s.sellHighTriggerPointIndex, 4),
        allocStrategy: s.sellHighAllocStrategy || 'PROPORTIONAL',
        targetMargin: customOrPointPct(s.sellHighRestoreMargin, s.sellHighRestorePointIndex),
      }
      : null,
    buyOnLowMargin: s.buyLowEnabled
      ? {
        deviationPct: marginTriggerPct(s.buyLowTriggerMargin, s.buyLowTriggerPointIndex, 0),
        allocStrategy: s.buyLowAllocStrategy || 'PROPORTIONAL',
        targetMargin: customOrPointPct(s.buyLowRestoreMargin, s.buyLowRestorePointIndex),
      }
      : null,
    drawdownBuyOnLowMargin: serializeDrawdownMarginTrigger(s.drawdownBuyOnLowMargin, 'buy'),
    buyTheDip: serializeDipSurgeScopes(s.buyTheDip, points),
    sellOnSurge: serializeDipSurgeScopes(s.sellOnSurge, points),
    useComfortZone: s.useComfortZone ?? true,
    comfortZoneLow:  lowComfort / 100,
    comfortZoneHigh: highComfort / 100,
    buyCooldownAfterSellHighDays: Number.isFinite(buyCooldownAfterSellHighDays) ? Math.max(0, buyCooldownAfterSellHighDays) : 10,
    sellCooldownAfterBuyLowDays: Number.isFinite(sellCooldownAfterBuyLowDays) ? Math.max(0, sellCooldownAfterBuyLowDays) : 10,
  }
}
