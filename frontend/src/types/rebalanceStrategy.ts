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
  momentumLookbackMonths?: string
  exitExtensionMonths?: string
  exitTargetMargin?: string
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

export type CapeSource = 'US' | 'WORLD'

export interface VmTimingMrState {
  enabled: boolean
  capeSource: CapeSource
  lowerMargin: string
  upperMargin: string
  momentumSource: string
  momentumReferenceTicker: string
  momentumLookbackMonths: string
  rebalancePeriod: string
  allocStrategy: string
}

export type DerivedTargetScaleFunction = 'SIGMOID' | 'ADAPTIVE_LOW_SIGMOID' | 'LINEAR' | 'STEP' | 'HYSTERESIS_STEP' | 'HYSTERESIS_STAIRS' | 'HYSTERESIS_STAIRS_MOMENTUM' | 'HYSTERESIS_STAIRS_REF_BL_RESET'
export type HysteresisStairsReferenceMode = 'RESET_REF' | 'BUY_LOW_INTENTION'
export type HysteresisStairsFallMode = 'DIRECT' | 'MOMENTUM'

export interface DerivedTargetStepState {
  id: string
  referenceMargin: string
  targetMargin: string
}

export interface DerivedTargetScaleState {
  function: DerivedTargetScaleFunction
  referenceLower: string
  referenceUpper: string
  targetLower: string
  targetUpper: string
  sigmoidSteepness: string
  stepBaseTarget: string
  momentumLookbackMonths: string
  hysteresisStairsReferenceMode: HysteresisStairsReferenceMode
  hysteresisStairsFallMode: HysteresisStairsFallMode
  steps: DerivedTargetStepState[]
}

export type DerivedMarginReferenceSource = 'BASE_STRATEGY' | 'STANDALONE_TICKER'
export type DerivedMarginReferenceMetric = 'MARGIN' | 'EQUITY_CUSHION' | 'MARGIN_COVERAGE'

export interface DerivedSubStrategyState {
  id: string
  label: string
  enabled: boolean
  marginReferenceSource: DerivedMarginReferenceSource
  marginReferenceTicker: string
  marginReferenceMetric: DerivedMarginReferenceMetric
  scale: DerivedTargetScaleState
  absoluteDeviationPct: string
  buyDeviationPct: string
  sellDeviationPct: string
  timeoutDays: string
  maxMargin: string
  allocStrategy?: string
  buyAllocStrategy: string
  sellAllocStrategy: string
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
  vmTimingMr: VmTimingMrState
  buyTheDip: DipSurgeScopeState
  sellOnSurge: DipSurgeScopeState
  useComfortZone: boolean
  comfortZoneLow: string
  comfortZoneHigh: string
  buyCooldownAfterSellHighDays: string
  sellCooldownAfterBuyLowDays: string
  derivedSubStrategies: DerivedSubStrategyState[]
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
    momentumLookbackMonths: '',
    exitExtensionMonths: '',
    exitTargetMargin: '',
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

export function emptyVmTimingMr(): VmTimingMrState {
  return {
    enabled: false,
    capeSource: 'WORLD',
    lowerMargin: '-50',
    upperMargin: '50',
    momentumSource: 'REFERENCE_PORTFOLIO',
    momentumReferenceTicker: '',
    momentumLookbackMonths: '12',
    rebalancePeriod: 'MONTHLY',
    allocStrategy: 'PROPORTIONAL',
  }
}

export function emptyDerivedTargetScale(): DerivedTargetScaleState {
  return {
    function: 'SIGMOID',
    referenceLower: '50',
    referenceUpper: '100',
    targetLower: '30',
    targetUpper: '100',
    sigmoidSteepness: '8',
    stepBaseTarget: '50',
    momentumLookbackMonths: '12',
    hysteresisStairsReferenceMode: 'RESET_REF',
    hysteresisStairsFallMode: 'DIRECT',
    steps: [emptyDerivedTargetStep(0)],
  }
}

export function emptyDerivedTargetStep(idx: number): DerivedTargetStepState {
  return {
    id: newId(),
    referenceMargin: String(60 + idx * 10),
    targetMargin: String(50 + idx * 10),
  }
}

export function emptyDerivedSubStrategy(idx: number): DerivedSubStrategyState {
  return {
    id: newId(),
    label: `Derived ${idx + 1}`,
    enabled: true,
    marginReferenceSource: 'BASE_STRATEGY',
    marginReferenceTicker: '',
    marginReferenceMetric: 'MARGIN',
    scale: emptyDerivedTargetScale(),
    absoluteDeviationPct: '5',
    buyDeviationPct: '5',
    sellDeviationPct: '5',
    timeoutDays: '10',
    maxMargin: '',
    allocStrategy: 'PROPORTIONAL',
    buyAllocStrategy: 'PROPORTIONAL',
    sellAllocStrategy: 'PROPORTIONAL',
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
    vmTimingMr: emptyVmTimingMr(),
    buyTheDip: emptyDipSurgeScopes(),
    sellOnSurge: emptyDipSurgeScopes(),
    useComfortZone: true,
    comfortZoneLow: '0',
    comfortZoneHigh: '0',
    buyCooldownAfterSellHighDays: '10',
    sellCooldownAfterBuyLowDays: '10',
    derivedSubStrategies: [],
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
  const exitTargetMargin = (() => {
    if (configValue.exitTargetMargin == null || String(configValue.exitTargetMargin).trim() === '') return ''
    const n = Number(configValue.exitTargetMargin)
    if (!Number.isFinite(n)) return String(configValue.exitTargetMargin)
    return String(Math.abs(n) <= 2 ? n * 100 : n)
  })()
  return {
    ...base,
    ...configValue,
    enabled: configValue.enabled ?? false,
    portfolioSource,
    referenceTicker: portfolioSource === 'REFERENCE_PORTFOLIO' ? (configValue.referenceTicker ?? '') : '',
    momentumLookbackMonths: configValue.momentumLookbackMonths == null ? '' : String(configValue.momentumLookbackMonths),
    exitExtensionMonths: configValue.exitExtensionMonths == null ? '' : String(configValue.exitExtensionMonths),
    exitTargetMargin,
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

function normalizeVmTimingMr(configValue: any): VmTimingMrState {
  const base = emptyVmTimingMr()
  if (!configValue) return base
  const marginText = (value: any, fallback: string) => {
    if (value == null) return fallback
    const n = Number(value)
    if (!Number.isFinite(n)) return String(value)
    return String(Math.abs(n) <= 2 ? n * 100 : n)
  }
  return {
    ...base,
    ...configValue,
    enabled: configValue.enabled ?? false,
    capeSource: configValue.capeSource === 'US' ? 'US' : 'WORLD',
    lowerMargin: marginText(configValue.lowerMargin, base.lowerMargin),
    upperMargin: marginText(configValue.upperMargin, base.upperMargin),
    momentumSource: configValue.momentumSource ?? configValue.portfolioSource ?? base.momentumSource,
    momentumReferenceTicker: configValue.momentumSource === 'REFERENCE_PORTFOLIO' || configValue.portfolioSource === 'REFERENCE_PORTFOLIO'
      ? (configValue.momentumReferenceTicker ?? configValue.referenceTicker ?? '')
      : '',
    momentumLookbackMonths: String(configValue.momentumLookbackMonths ?? base.momentumLookbackMonths),
    rebalancePeriod: configValue.rebalancePeriod === 'INHERIT' ? 'MONTHLY' : (configValue.rebalancePeriod ?? base.rebalancePeriod),
    allocStrategy: configValue.allocStrategy ?? base.allocStrategy,
  }
}

function normalizeDerivedSubStrategies(configValue: any): DerivedSubStrategyState[] {
  const items = Array.isArray(configValue) ? configValue : []
  return items.map((item: any, i: number) => ({
    ...emptyDerivedSubStrategy(i),
    ...item,
    id: item.id ?? newId(),
    enabled: item.enabled ?? true,
    scale: {
      ...emptyDerivedTargetScale(),
      ...(item.scale ?? {}),
      function: item.scale?.function === 'STEP'
        ? 'STEP'
        : (
          item.scale?.function === 'HYSTERESIS_STEP'
            ? 'HYSTERESIS_STEP'
            : item.scale?.function === 'HYSTERESIS_STAIRS' || item.scale?.function === 'HYSTERESIS_STAIRS_MOMENTUM'
            ? 'HYSTERESIS_STAIRS'
            : item.scale?.function === 'HYSTERESIS_STAIRS_REF_BL_RESET'
            ? 'HYSTERESIS_STAIRS_REF_BL_RESET'
            : item.scale?.function === 'LINEAR'
            ? 'LINEAR'
            : (item.scale?.function === 'ADAPTIVE_LOW_SIGMOID' ? 'ADAPTIVE_LOW_SIGMOID' : 'SIGMOID')
        ),
      steps: Array.isArray(item.scale?.steps) && item.scale.steps.length > 0
        ? item.scale.steps.map((step: any, stepIdx: number) => ({
          ...emptyDerivedTargetStep(stepIdx),
          ...step,
          id: step.id ?? newId(),
        }))
        : [emptyDerivedTargetStep(0)],
      hysteresisStairsReferenceMode: item.scale?.hysteresisStairsReferenceMode === 'BUY_LOW_INTENTION'
        ? 'BUY_LOW_INTENTION'
        : 'RESET_REF',
      hysteresisStairsFallMode: item.scale?.hysteresisStairsFallMode === 'MOMENTUM' || item.scale?.function === 'HYSTERESIS_STAIRS_MOMENTUM'
        ? 'MOMENTUM'
        : 'DIRECT',
    },
    absoluteDeviationPct: item.absoluteDeviationPct ?? '5',
    buyDeviationPct: item.buyDeviationPct ?? item.absoluteDeviationPct ?? '5',
    sellDeviationPct: item.sellDeviationPct ?? item.absoluteDeviationPct ?? '5',
    marginReferenceSource: item.marginReferenceSource === 'STANDALONE_TICKER' ? 'STANDALONE_TICKER' : 'BASE_STRATEGY',
    marginReferenceTicker: item.marginReferenceTicker ?? '',
    marginReferenceMetric: item.marginReferenceMetric === 'EQUITY_CUSHION'
      ? 'EQUITY_CUSHION'
      : (item.marginReferenceMetric === 'MARGIN_COVERAGE' ? 'MARGIN_COVERAGE' : 'MARGIN'),
    timeoutDays: item.timeoutDays ?? '10',
    maxMargin: item.maxMargin ?? '',
    allocStrategy: item.allocStrategy ?? 'PROPORTIONAL',
    buyAllocStrategy: item.buyAllocStrategy ?? item.allocStrategy ?? 'PROPORTIONAL',
    sellAllocStrategy: item.sellAllocStrategy ?? item.allocStrategy ?? 'PROPORTIONAL',
  }))
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
  const momentumLookbackMonths = parseInt(d.momentumLookbackMonths ?? '', 10)
  if ((d.momentumLookbackMonths ?? '').trim() !== '' && (!Number.isFinite(momentumLookbackMonths) || momentumLookbackMonths < 1)) {
    issues.push(`${label}: momentum months must be 1 or higher.`)
  }
  const exitExtensionMonths = parseInt(d.exitExtensionMonths ?? '', 10)
  if ((d.exitExtensionMonths ?? '').trim() !== '' && (!Number.isFinite(exitExtensionMonths) || exitExtensionMonths < 0)) {
    issues.push(`${label}: exit extension months must be 0 or higher.`)
  }
  const exitTargetMargin = parseFloat(d.exitTargetMargin ?? '')
  if ((d.exitTargetMargin ?? '').trim() !== '' && (!Number.isFinite(exitTargetMargin) || exitTargetMargin < 0)) {
    issues.push(`${label}: exit target margin must be 0 or higher.`)
  }
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
  const source = config ?? {}
  const base = emptyStrategy(0)
  return {
    ...base,
    ...source,
    label: name || source.label || base.label,
    portfolioRebalancePeriod: source.portfolioRebalancePeriod ?? base.portfolioRebalancePeriod,
    portfolioRebalanceUseComfortZone: source.portfolioRebalanceUseComfortZone ?? source.useComfortZone ?? base.portfolioRebalanceUseComfortZone,
    marginRebalanceEnabled: source.marginRebalanceEnabled ?? base.marginRebalanceEnabled,
    rebalancePeriod: source.rebalancePeriod === 'INHERIT' ? 'NONE' : (source.rebalancePeriod ?? base.rebalancePeriod),
    rebalanceAllocStrategy: source.rebalanceAllocStrategy ?? base.rebalanceAllocStrategy,
    marginRebalanceTradeDirection: source.marginRebalanceTradeDirection ?? base.marginRebalanceTradeDirection,
    marginRebalanceRestoreMargin: source.marginRebalanceRestoreMargin ?? base.marginRebalanceRestoreMargin,
    drawdownMarginOverride: normalizeDrawdownMarginOverride(source.drawdownMarginOverride),
    sellHighTriggerPointIndex: source.sellHighTriggerPointIndex ?? base.sellHighTriggerPointIndex,
    sellHighTriggerMargin: source.sellHighTriggerMargin ?? base.sellHighTriggerMargin,
    buyLowTriggerPointIndex: source.buyLowTriggerPointIndex ?? base.buyLowTriggerPointIndex,
    buyLowTriggerMargin: source.buyLowTriggerMargin ?? base.buyLowTriggerMargin,
    drawdownBuyOnLowMargin: normalizeDrawdownMarginTrigger(source.drawdownBuyOnLowMargin, 'buy'),
    vmTimingMr: normalizeVmTimingMr(source.vmTimingMr),
    useComfortZone: source.useComfortZone ?? base.useComfortZone,
    buyTheDip: normalizeDipSurgeScopes(source.buyTheDip),
    sellOnSurge: normalizeDipSurgeScopes(source.sellOnSurge),
    buyCooldownAfterSellHighDays: source.buyCooldownAfterSellHighDays ?? base.buyCooldownAfterSellHighDays,
    sellCooldownAfterBuyLowDays: source.sellCooldownAfterBuyLowDays ?? base.sellCooldownAfterBuyLowDays,
    derivedSubStrategies: normalizeDerivedSubStrategies(source.derivedSubStrategies),
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
  const vmTimingMr = s.vmTimingMr ?? emptyVmTimingMr()
  const vmMomentumReferenceTicker = (vmTimingMr.momentumReferenceTicker ?? '').trim().toUpperCase()
  const serializeDerivedSubStrategy = (d: DerivedSubStrategyState) => {
    const scale = d.scale ?? emptyDerivedTargetScale()
    const steepness = parseFloat(scale.sigmoidSteepness)
    const momentumLookbackMonths = parseInt(scale.momentumLookbackMonths ?? '', 10)
    const timeoutDays = parseInt(d.timeoutDays ?? '', 10)
    const serializeStep = (step: DerivedTargetStepState) => ({
      referenceMargin: pctAllowZero(step.referenceMargin, 60),
      targetMargin: pctAllowZero(step.targetMargin, 50),
    })
    return {
      label: d.label.trim() || 'Derived',
      enabled: d.enabled ?? true,
      marginReferenceSource: d.marginReferenceSource === 'STANDALONE_TICKER' ? 'STANDALONE_TICKER' : 'BASE_STRATEGY',
      marginReferenceTicker: d.marginReferenceSource === 'STANDALONE_TICKER'
        ? d.marginReferenceTicker.trim().toUpperCase()
        : null,
      marginReferenceMetric: d.marginReferenceMetric === 'EQUITY_CUSHION'
        ? 'EQUITY_CUSHION'
        : (d.marginReferenceMetric === 'MARGIN_COVERAGE' ? 'MARGIN_COVERAGE' : 'MARGIN'),
      scale: {
        function: scale.function || 'SIGMOID',
        referenceLower: pctAllowZero(scale.referenceLower, 50),
        referenceUpper: pctAllowZero(scale.referenceUpper, 100),
        targetLower: pctAllowZero(scale.targetLower, 30),
        targetUpper: pctAllowZero(scale.targetUpper, 100),
        sigmoidSteepness: Number.isFinite(steepness) ? steepness : 8,
        stepBaseTarget: pctAllowZero(scale.stepBaseTarget, 50),
        momentumLookbackMonths: Number.isFinite(momentumLookbackMonths) && momentumLookbackMonths > 0
          ? momentumLookbackMonths
          : 12,
        hysteresisStairsReferenceMode: scale.hysteresisStairsReferenceMode === 'BUY_LOW_INTENTION'
          ? 'BUY_LOW_INTENTION'
          : 'RESET_REF',
        hysteresisStairsFallMode: scale.hysteresisStairsFallMode === 'MOMENTUM'
          ? 'MOMENTUM'
          : 'DIRECT',
        steps: (scale.steps?.length ? scale.steps : [emptyDerivedTargetStep(0)]).map(serializeStep),
      },
      absoluteDeviationPct: pctAllowZero(d.absoluteDeviationPct, 5),
      buyDeviationPct: pctAllowZero(d.buyDeviationPct ?? d.absoluteDeviationPct, 5),
      sellDeviationPct: pctAllowZero(d.sellDeviationPct ?? d.absoluteDeviationPct, 5),
      timeoutDays: Number.isFinite(timeoutDays) ? Math.max(0, timeoutDays) : 10,
      maxMargin: customOrPointPct(d.maxMargin, undefined, 0, 4),
      allocStrategy: d.buyAllocStrategy || d.allocStrategy || 'PROPORTIONAL',
      buyAllocStrategy: d.buyAllocStrategy || d.allocStrategy || 'PROPORTIONAL',
      sellAllocStrategy: d.sellAllocStrategy || d.allocStrategy || 'PROPORTIONAL',
    }
  }
  const serializeDrawdownMarginTrigger = (
    d: DrawdownMarginTriggerState | undefined,
    direction: 'buy' | 'sell',
  ) => {
    const fallback = emptyDrawdownMarginTrigger(direction)
    const cfg = d ?? fallback
    if (!cfg.enabled) return null
    const portfolioSource = cfg.portfolioSource || 'REFERENCE_PORTFOLIO'
    const referenceTicker = (cfg.referenceTicker ?? '').trim().toUpperCase()
    const momentumLookbackMonths = parseInt(cfg.momentumLookbackMonths ?? '', 10)
    const exitExtensionMonths = parseInt(cfg.exitExtensionMonths ?? '', 10)
    const exitTargetMargin = parseFloat(cfg.exitTargetMargin ?? '')
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
      momentumLookbackMonths: direction === 'buy' && Number.isFinite(momentumLookbackMonths) && momentumLookbackMonths > 0
        ? momentumLookbackMonths
        : null,
      exitExtensionMonths: direction === 'buy' && Number.isFinite(exitExtensionMonths) && exitExtensionMonths > 0
        ? exitExtensionMonths
        : 0,
      exitTargetMargin: direction === 'buy' && Number.isFinite(exitTargetMargin)
        ? Math.max(0, exitTargetMargin) / 100
        : null,
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
    vmTimingMr: vmTimingMr.enabled
      ? {
        enabled: true,
        capeSource: vmTimingMr.capeSource === 'US' ? 'US' : 'WORLD',
        lowerMargin: pctAllowZero(vmTimingMr.lowerMargin, -50),
        upperMargin: pctAllowZero(vmTimingMr.upperMargin, 50),
        momentumSource: vmTimingMr.momentumSource || 'REFERENCE_PORTFOLIO',
        momentumReferenceTicker: vmTimingMr.momentumSource === 'REFERENCE_PORTFOLIO' && vmMomentumReferenceTicker ? vmMomentumReferenceTicker : null,
        momentumLookbackMonths: Math.max(1, parseInt(vmTimingMr.momentumLookbackMonths || '12', 10) || 12),
        rebalancePeriod: vmTimingMr.rebalancePeriod === 'INHERIT' ? 'MONTHLY' : (vmTimingMr.rebalancePeriod || 'MONTHLY'),
        allocStrategy: vmTimingMr.allocStrategy || 'PROPORTIONAL',
      }
      : null,
    buyTheDip: serializeDipSurgeScopes(s.buyTheDip, points),
    sellOnSurge: serializeDipSurgeScopes(s.sellOnSurge, points),
    useComfortZone: s.useComfortZone ?? true,
    comfortZoneLow:  lowComfort / 100,
    comfortZoneHigh: highComfort / 100,
    buyCooldownAfterSellHighDays: Number.isFinite(buyCooldownAfterSellHighDays) ? Math.max(0, buyCooldownAfterSellHighDays) : 10,
    sellCooldownAfterBuyLowDays: Number.isFinite(sellCooldownAfterBuyLowDays) ? Math.max(0, sellCooldownAfterBuyLowDays) : 10,
    derivedSubStrategies: (s.derivedSubStrategies ?? []).map(serializeDerivedSubStrategy),
  }
}
