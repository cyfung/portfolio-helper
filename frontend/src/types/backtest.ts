// ── types/backtest.ts — Shared types, constants, and helpers for backtest/MC ──

// ── Row state ─────────────────────────────────────────────────────────────────

import { omitStrategyEnabledFlag, savedConfigToStrategyState, strategyStateToAPI, strategyStateToSavedConfig } from './rebalanceStrategy'
import { DEFAULT_SPREAD_PERCENT, normalizeNumberInput, percentInputToFraction } from '@/lib/numberInputs'
import { allocOptionsFromHybridStrategies, DEFAULT_HYBRID_ALLOC_STRATEGIES } from '@/lib/allocStrategies'
import { parseSwapExpression } from '@/lib/tickerExpressions'
import {
  canonicalPortfolioConfiguration,
  convertPortfolioRowToLegacyTickerRow,
  parseInstrumentExpression,
  parseSwapInput,
  transformRowsBetweenSwapBarriers,
  type PortfolioRow,
  type ReferenceNormalizationMode,
} from '@/lib/portfolioComposition'

export interface HoldingEditorRow {
  id: string
  type: 'HOLDING'
  instrument: string
  allocation: string
}

export interface PortfolioReferenceEditorRow {
  id: string
  type: 'PORTFOLIO_REFERENCE'
  portfolioName: string
  allocation: string
  normalizationMode: ReferenceNormalizationMode
}

export interface SwapLegEditorRow {
  id: string
  instrument: string
  multiplier: string
}

export interface SwapEditorRow {
  id: string
  type: 'SWAP'
  source: string
  transferMode: 'AMOUNT' | 'ALL_REMAINING'
  transferAmount: string
  legs: SwapLegEditorRow[]
}

export type PortfolioEditorRow = HoldingEditorRow | PortfolioReferenceEditorRow | SwapEditorRow

export interface MarginRow {
  id: string
  ratio: string
  spread: string
  devUpper: string
  devLower: string
  modeUpper: string
  modeLower: string
}

export interface RebalanceStrategyRow {
  id: string
  name: string
  config: any
}

export interface BlockState {
  label: string
  tickers: PortfolioEditorRow[]
  rebalance: string
  margins: MarginRow[]
  rebalanceStrategies: RebalanceStrategyRow[]
  includeNoMargin: boolean
}

// ── Constants ─────────────────────────────────────────────────────────────────

export const PALETTE = [
  ['#1a6bc7', '#5599e8', '#99c3f5'],   // blues   (Portfolio 1)
  ['#c75d1a', '#e88a55', '#f5b899'],   // oranges (Portfolio 2)
  ['#1a8a5c', '#55b388', '#99d4bb'],   // greens  (Portfolio 3)
]

export const PERCENTILE_COLORS = ['#e05c5c', '#e0955c', '#d4c84a', '#4caf50', '#4aabcf', '#4a6fcf', '#7c4acf']
export const PERCENTILE_LIST = [5, 10, 25, 50, 75, 90, 95]

export const DEFAULT_CASHFLOW_FREQUENCY = 'MONTHLY'
export const DEFAULT_BETA_REFERENCE_TICKER = 'SPY'

export const CASHFLOW_FREQUENCY_OPTIONS = [
  { value: 'MONTHLY',   label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'YEARLY',    label: 'Yearly' },
]

export const REBALANCE_OPTIONS = [
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

export const MARGIN_MODE_OPTIONS = allocOptionsFromHybridStrategies(DEFAULT_HYBRID_ALLOC_STRATEGIES, true)

// ── ID generation ─────────────────────────────────────────────────────────────

export const REBALANCE_MARGIN_MODE_OPTIONS = MARGIN_MODE_OPTIONS.filter(o => o.value !== 'DAILY')

export interface CashflowPayload {
  amount: number
  frequency: string
}

export interface CashflowFormState {
  startingBalance: string
  cashflowAmount: string
  cashflowFrequency: string
  betaReferenceTicker: string
}

export type BlockConversionOptions = { strict?: boolean }

export function startingBalanceToPayload(value: string, options: BlockConversionOptions = {}): number {
  const trimmed = value.trim()
  if (!trimmed) return 10000
  const parsed = Number(trimmed)
  if (Number.isFinite(parsed) && parsed > 0) return parsed
  if (options.strict) throw new Error('Starting balance must be greater than 0.')
  return 10000
}

export function cashflowToPayload(amount: string, frequency: string): CashflowPayload | null {
  return amount && frequency !== 'NONE'
    ? { amount: parseFloat(amount), frequency }
    : null
}

export function cashflowStateFromSettings(req: any): Partial<CashflowFormState> {
  const frequency = req.cashflow?.frequency === 'NONE'
    ? DEFAULT_CASHFLOW_FREQUENCY
    : req.cashflow?.frequency

  return {
    ...(req.startingBalance != null ? { startingBalance: String(req.startingBalance) } : {}),
    cashflowAmount: req.cashflow?.amount != null ? String(req.cashflow.amount) : '0',
    ...(frequency ? { cashflowFrequency: frequency } : {}),
    betaReferenceTicker: typeof req.betaReferenceTicker === 'string'
      ? req.betaReferenceTicker
      : DEFAULT_BETA_REFERENCE_TICKER,
  }
}

let _nextId = 0
export function newId(): string { return String(++_nextId) }

// ── Block factory + helpers ───────────────────────────────────────────────────

export function emptyBlock(idx: number): BlockState {
  if (idx === 0) {
    return {
      label: '',
      tickers: [
        { id: newId(), type: 'HOLDING', instrument: 'VT', allocation: '60' },
        { id: newId(), type: 'HOLDING', instrument: 'KMLM', allocation: '40' },
      ],
      rebalance: 'YEARLY',
      margins: [],
      rebalanceStrategies: [],
      includeNoMargin: true,
    }
  }
  return { label: '', tickers: [], rebalance: 'YEARLY', margins: [], rebalanceStrategies: [], includeNoMargin: true }
}

/** Convert an API saved-portfolio config into BlockState (ratio → percentage). */
export function configToBlockState(config: any, name: string): BlockState {
  const r = (v: number) => String(Math.round(v * 10000) / 100)
  if (!Array.isArray(config?.rows)) throw new Error('Saved portfolio is missing tagged rows.')
  const persistedRows = canonicalPortfolioConfiguration({ rows: config.rows })?.rows
  if (!persistedRows) throw new Error('Saved portfolio contains invalid tagged rows.')
  return {
    label: name,
    tickers: persistedRows.map(row => {
      if (row.type === 'HOLDING') {
        return { id: row.id, type: row.type, instrument: row.instrument, allocation: String(row.allocation) }
      }
      if (row.type === 'PORTFOLIO_REFERENCE') {
        return {
          id: row.id,
          type: row.type,
          portfolioName: row.portfolioName,
          allocation: String(row.allocation),
          normalizationMode: row.normalizationMode,
        }
      }
      return {
        id: row.id,
        type: row.type,
        source: row.source,
        transferMode: row.transfer.mode,
        transferAmount: row.transfer.mode === 'AMOUNT' ? String(row.transfer.amount) : '',
        legs: row.legs.map((leg, index) => ({
          id: `${row.id}-leg-${index}`,
          instrument: leg.instrument,
          multiplier: String(leg.multiplier),
        })),
      }
    }),
    rebalance: config.rebalanceStrategy || 'YEARLY',
    margins: (config.marginStrategies || []).map((m: any) => ({
      id: newId(),
      ratio:    r(m.marginRatio ?? 0.5),
      spread:   r(m.marginSpread ?? 0.015),
      devUpper: r(m.marginDeviationUpper ?? 0.05),
      devLower: r(m.marginDeviationLower ?? 0.05),
      modeUpper: m.upperRebalanceMode || 'PROPORTIONAL',
      modeLower: m.lowerRebalanceMode || 'PROPORTIONAL',
    })),
    rebalanceStrategies: (config.rebalanceStrategies || []).map((s: any) => ({
      id: newId(),
      name: String(s.name || s.label || 'Strategy'),
      config: s.config ?? s,
    })),
    includeNoMargin: config.includeNoMargin !== false,
  }
}

export function configToBlockInputLabel(config: { inputLabel?: unknown; label?: unknown } | null | undefined, idx?: number): string {
  if (typeof config?.inputLabel === 'string') return config.inputLabel
  const label = typeof config?.label === 'string' ? config.label : ''
  return idx != null && label === `Portfolio ${idx + 1}` ? '' : label
}

export function normalizeBlockSpreadInputs(state: BlockState): BlockState {
  let changed = false
  const margins = state.margins.map(m => {
    const spread = normalizeNumberInput(m.spread, DEFAULT_SPREAD_PERCENT, { min: 0 })
    if (spread === m.spread) return m
    changed = true
    return { ...m, spread }
  })
  return changed ? { ...state, margins } : state
}

export function hasActiveRebalanceStrategyRows(strategies: any[] | null | undefined): boolean {
  return (strategies ?? []).some(strategy => {
    const baseEnabled = strategy?.baseEnabled !== false
    const derivedEnabled = Array.isArray(strategy?.derivedSubStrategies) &&
      strategy.derivedSubStrategies.some((derived: any) => derived?.enabled !== false)
    return baseEnabled || derivedEnabled
  })
}

type ApiTickerWeight = number | '*'

export function editorRowToPortfolioRow(row: PortfolioEditorRow): PortfolioRow | null {
  if (row.type === 'HOLDING') {
    const instrument = parseInstrumentExpression(row.instrument)
    const allocation = Number(row.allocation.trim())
    return instrument != null && Number.isFinite(allocation) && allocation !== 0
      ? { id: row.id, type: 'HOLDING', instrument, allocation }
      : null
  }
  if (row.type === 'PORTFOLIO_REFERENCE') {
    const portfolioName = row.portfolioName.trim()
    const allocation = Number(row.allocation.trim())
    return portfolioName && Number.isFinite(allocation) && allocation !== 0
      ? { id: row.id, type: 'PORTFOLIO_REFERENCE', portfolioName, allocation, normalizationMode: row.normalizationMode }
      : null
  }
  const source = parseInstrumentExpression(row.source)
  const amount = Number(row.transferAmount.trim())
  const legs = row.legs.map(leg => {
    const instrument = parseInstrumentExpression(leg.instrument)
    const multiplier = Number(leg.multiplier.trim())
    return instrument != null && Number.isFinite(multiplier) && multiplier !== 0
      ? { instrument, multiplier }
      : null
  })
  if (source == null || legs.length === 0 || legs.some(leg => leg == null)) return null
  if (row.transferMode === 'AMOUNT' && (!Number.isFinite(amount) || amount <= 0)) return null
  return {
    id: row.id,
    type: 'SWAP',
    source,
    transfer: row.transferMode === 'ALL_REMAINING' ? { mode: 'ALL_REMAINING' } : { mode: 'AMOUNT', amount },
    legs: legs as NonNullable<(typeof legs)[number]>[],
  }
}

export function holdingRowSwapCandidate(row: HoldingEditorRow) {
  return parseSwapInput(row.instrument)
}

export function convertHoldingEditorRowToSwap(row: HoldingEditorRow): SwapEditorRow | null {
  const swap = holdingRowSwapCandidate(row)
  if (!swap) return null
  return {
    id: row.id,
    type: 'SWAP',
    source: swap.source,
    transferMode: row.allocation.trim() === '*' ? 'ALL_REMAINING' : 'AMOUNT',
    transferAmount: row.allocation.trim() === '*' ? '' : row.allocation,
    legs: swap.legs.map((leg, index) => ({
      id: `${row.id}-leg-${index}`,
      instrument: leg.instrument,
      multiplier: String(leg.multiplier),
    })),
  }
}

export function invalidPortfolioEditorRowIds(state: Pick<BlockState, 'tickers'>): Set<string> {
  return new Set(state.tickers.filter(row =>
    editorRowToPortfolioRow(row) == null ||
    (row.type === 'HOLDING' && row.instrument.trim().toUpperCase() === 'DUMMY'),
  ).map(row => row.id))
}

export function portfolioEditorRowMergeKey(row: PortfolioEditorRow, index: number): string {
  const label = row.type === 'HOLDING'
    ? row.instrument.trim().toUpperCase()
    : row.type === 'PORTFOLIO_REFERENCE'
      ? row.portfolioName.trim()
      : row.source.trim().toUpperCase()
  if (row.type === 'SWAP') return `swap:${index}:${row.id}`
  if (!label) return `empty:${row.id}`
  return row.type === 'PORTFOLIO_REFERENCE'
    ? `${row.type}:${label}:${row.normalizationMode}`
    : `${row.type}:${label}`
}

function mergeAndSortRowsBetweenSwaps(rows: PortfolioEditorRow[]): PortfolioEditorRow[] {
  type MergeGroup = {
    row: HoldingEditorRow | PortfolioReferenceEditorRow
    allocation: number
    label: string
    firstIndex: number
  }
  const groups = new Map<string, MergeGroup>()

  rows.forEach((row, index) => {
    if (row.type === 'SWAP') return
    const key = portfolioEditorRowMergeKey(row, index)
    const label = row.type === 'HOLDING'
      ? row.instrument.trim().toUpperCase()
      : row.portfolioName.trim()
    const allocation = Number(row.allocation) || 0
    const existing = groups.get(key)
    if (existing) existing.allocation += allocation
    else groups.set(key, { row, allocation, label, firstIndex: index })
  })

  const formatAllocation = (allocation: number) =>
    String(Math.round(allocation * 10000000000) / 10000000000)
  return [...groups.values()]
    .sort((a, b) =>
      Number(a.row.type === 'PORTFOLIO_REFERENCE') - Number(b.row.type === 'PORTFOLIO_REFERENCE') ||
      a.label.localeCompare(b.label) ||
      a.firstIndex - b.firstIndex)
    .map(({ row, allocation, label }) => row.type === 'HOLDING'
      ? { ...row, instrument: label, allocation: formatAllocation(allocation) }
      : { ...row, portfolioName: label, allocation: formatAllocation(allocation) })
}

export function sortAndMergePortfolioEditorRows(rows: PortfolioEditorRow[]): PortfolioEditorRow[] {
  return transformRowsBetweenSwapBarriers(rows, mergeAndSortRowsBetweenSwaps)
}

function parseInputNumber(
  value: string | number | null | undefined,
  fallback: number,
  label: string,
  options: BlockConversionOptions = {},
) {
  const raw = String(value ?? '').trim()
  if (!raw) return fallback
  const parsed = Number(raw)
  if (Number.isFinite(parsed)) return parsed
  if (options.strict) throw new Error(`${label} is invalid.`)
  return fallback
}

function percentInputOrDefault(
  value: string | number | null | undefined,
  fallback: number,
  label: string,
  options: BlockConversionOptions = {},
) {
  return parseInputNumber(value, fallback, label, options) / 100
}

function isNonZeroApiWeight(weight: ApiTickerWeight) {
  return weight === '*' || weight !== 0
}

function hasOrderSensitiveTickerRows(rows: { ticker: string; weight: ApiTickerWeight }[]) {
  return rows.some(row => row.weight === '*' || parseSwapExpression(row.ticker))
}

function mergeAPITickerRows<T extends { ticker: string; weight: number; isPortfolioRef?: boolean; portfolioRef?: string }>(rows: T[]): T[] {
  const merged = new Map<string, T>()
  rows.forEach(row => {
    if (!row.ticker || row.weight === 0) return
    const key = row.isPortfolioRef ? `P:${row.portfolioRef || row.ticker}` : `T:${row.ticker}`
    const existing = merged.get(key)
    if (existing) existing.weight += row.weight
    else merged.set(key, { ...row })
  })
  return [...merged.values()]
    .filter(row => row.weight !== 0)
    .sort((a, b) => a.ticker.localeCompare(b.ticker))
}

function blockStateToAPITickers(state: BlockState) {
  const rows = state.tickers
    .map(editorRowToPortfolioRow)
    .filter((row): row is PortfolioRow => row != null)
    .map(convertPortfolioRowToLegacyTickerRow)
    .filter(t => t.ticker && isNonZeroApiWeight(t.weight))

  if (hasOrderSensitiveTickerRows(rows)) return rows
  return mergeAPITickerRows(rows as Array<{ ticker: string; weight: number; isPortfolioRef?: boolean; portfolioRef?: string }>)
}

function blockStateToAPILabel(state: BlockState, idx: number, tickers: ReturnType<typeof blockStateToAPITickers>) {
  const inputLabel = state.label.trim()
  if (inputLabel) return inputLabel
  return tickers.length === 1 ? tickers[0].ticker : `Portfolio ${idx + 1}`
}

/** Convert BlockState to the portfolio object expected by the run API. */
export function blockStateToAPIPortfolio(state: BlockState, idx: number, options: BlockConversionOptions = {}) {
  const tickers = blockStateToAPITickers(state)
  return {
    label: blockStateToAPILabel(state, idx, tickers),
    inputLabel: state.label.trim(),
    tickers,
    rebalanceStrategy: state.rebalance,
    marginStrategies: state.margins.map(m => ({
      marginRatio:          percentInputOrDefault(m.ratio, 0, 'Margin ratio', options),
      marginSpread:         percentInputToFraction(m.spread, DEFAULT_SPREAD_PERCENT, { min: 0 }),
      marginDeviationUpper: percentInputOrDefault(m.devUpper, 5, 'Upper margin deviation', options),
      marginDeviationLower: percentInputOrDefault(m.devLower, 5, 'Lower margin deviation', options),
      upperRebalanceMode: m.modeUpper,
      lowerRebalanceMode: m.modeLower,
    })),
    rebalanceStrategies: (state.rebalanceStrategies ?? []).map(s => {
      const strategy = savedConfigToStrategyState(s.config, s.name)
      return {
        name: s.name,
        config: strategyStateToSavedConfig(strategy),
        ...omitStrategyEnabledFlag(strategyStateToAPI(strategy)),
      }
    }),
    includeNoMargin: state.includeNoMargin,
  }
}

/** Convert BlockState to the config format used by /api/backtest/savedPortfolios. */
export function blockStateToSavedConfig(state: BlockState) {
  const rows = state.tickers
    .map(editorRowToPortfolioRow)
    .filter((row): row is PortfolioRow => row != null)
  return {
    rows,
    rebalanceStrategy: state.rebalance,
    marginStrategies: state.margins.map(m => ({
      marginRatio:          percentInputOrDefault(m.ratio, 0, 'Margin ratio'),
      marginSpread:         percentInputToFraction(m.spread, DEFAULT_SPREAD_PERCENT, { min: 0 }),
      marginDeviationUpper: percentInputOrDefault(m.devUpper, 5, 'Upper margin deviation'),
      marginDeviationLower: percentInputOrDefault(m.devLower, 5, 'Lower margin deviation'),
      upperRebalanceMode: m.modeUpper,
      lowerRebalanceMode: m.modeLower,
    })),
    rebalanceStrategies: (state.rebalanceStrategies ?? []).map(s => {
      const strategy = savedConfigToStrategyState(s.config, s.name)
      return {
        name: s.name,
        config: strategyStateToSavedConfig(strategy),
        ...omitStrategyEnabledFlag(strategyStateToAPI(strategy)),
      }
    }),
    includeNoMargin: state.includeNoMargin,
  }
}

// ── API response types ────────────────────────────────────────────────────────

export interface BacktestCurveStats {
  endingValue: number
  cagr: number
  maxDrawdown: number
  longestDrawdownDays: number
  annualVolatility: number
  sharpe: number
  sortino?: number
  averageDrawdown?: number
  calmar?: number
  beta?: number
  ulcerIndex: number
  upi: number
  marginUpperTriggers?: number | null
  marginLowerTriggers?: number | null
}

export interface BacktestCurve {
  label: string
  points: { date: string; value: number }[]
  stats: BacktestCurveStats
  marginPoints?: { date: string; value: number }[]
  vmTimingPoints?: { date: string; cape: number; valueFactor: number }[]
  actionPoints?: {
    date: string
    type: 'SELL_HIGH' | 'BUY_LOW' | 'BUY_DIP' | 'SELL_SURGE' | 'PORTFOLIO_REBALANCE' | 'MARGIN_REBALANCE' | 'VM_TIMING_MR' | 'DRAWDOWN_MR' | 'DRAWDOWN_MR_EXIT'
    detail?: {
      tradingDayIndex?: number | null
      key?: string | null
      direction?: string | null
      triggerValue?: number | null
      cooldownDays?: number | null
      daysSincePrevious?: number | null
      amount?: number | null
      eligibleAmount?: number | null
      minAdjustment?: number | null
      grossBefore?: number | null
      grossAfter?: number | null
      marginBefore?: number | null
      marginAfter?: number | null
      allocStrategy?: string | null
    } | null
  }[]
}

export interface BacktestPortfolioResult {
  label: string
  curves: BacktestCurve[]
}

export interface BacktestResults {
  portfolios: BacktestPortfolioResult[]
  warnings?: string[]
  error?: string
}

export interface McPercentilePath {
  percentile: number
  points: number[]
  endValue: number
  cagr: number
  maxDrawdown?: number
  sharpe?: number
  ulcerIndex?: number
  upi?: number
  annualVolatility?: number
  longestDrawdownDays?: number
  sortino?: number
  averageDrawdown?: number
  calmar?: number
  beta?: number
}

export interface McCurve {
  label: string
  percentilePaths: McPercentilePath[]
  maxDdPercentiles: number[]
  longestDrawdownPercentiles: number[]
  volatilityPercentiles: number[]
  sharpePercentiles: number[]
  sortinoPercentiles: number[]
  ulcerPercentiles: number[]
  upiPercentiles: number[]
  averageDrawdownPercentiles: number[]
  calmarPercentiles: number[]
  betaPercentiles: number[]
}

export interface McPortfolioResult {
  label: string
  curves: McCurve[]
}

export interface MonteCarloResults {
  simulatedYears: number
  numSimulations: number
  seed: number
  portfolios: McPortfolioResult[]
  warnings?: string[]
  error?: string
}

export interface SavedPortfolio {
  name: string
  config: any
}
