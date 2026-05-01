// ── types/backtest.ts — Shared types, constants, and helpers for backtest/MC ──

// ── Row state ─────────────────────────────────────────────────────────────────

export interface TickerRow {
  id: string
  ticker: string
  weight: string
  isPortfolioRef?: boolean
}

export interface MarginRow {
  id: string
  ratio: string
  spread: string
  devUpper: string
  devLower: string
  modeUpper: string
  modeLower: string
}

export interface BlockState {
  label: string
  tickers: TickerRow[]
  rebalance: string
  margins: MarginRow[]
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

export const CASHFLOW_FREQUENCY_OPTIONS = [
  { value: 'NONE',      label: 'None' },
  { value: 'MONTHLY',   label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'YEARLY',    label: 'Yearly' },
]

export const REBALANCE_OPTIONS = [
  { value: 'NONE', label: 'None' },
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'YEARLY', label: 'Yearly' },
]

export const MARGIN_MODE_OPTIONS = [
  { value: 'PROPORTIONAL', label: 'Target Weight' },
  { value: 'CURRENT_WEIGHT', label: 'Current Weight' },
  { value: 'FULL_REBALANCE', label: 'Full Rebal' },
  { value: 'UNDERVALUED_PRIORITY', label: 'Underval First' },
  { value: 'WATERFALL', label: 'Waterfall' },
  { value: 'DAILY', label: 'Daily' },
]

// ── ID generation ─────────────────────────────────────────────────────────────

let _nextId = 0
export function newId(): string { return String(++_nextId) }

// ── Block factory + helpers ───────────────────────────────────────────────────

export function emptyBlock(idx: number): BlockState {
  if (idx === 0) {
    return {
      label: '',
      tickers: [
        { id: newId(), ticker: 'VT', weight: '60' },
        { id: newId(), ticker: 'KMLM', weight: '40' },
      ],
      rebalance: 'YEARLY',
      margins: [],
      includeNoMargin: true,
    }
  }
  return { label: '', tickers: [], rebalance: 'YEARLY', margins: [], includeNoMargin: true }
}

/** Convert an API saved-portfolio config into BlockState (ratio → percentage). */
export function configToBlockState(config: any, name: string): BlockState {
  const r = (v: number) => String(Math.round(v * 10000) / 100)
  return {
    label: name,
    tickers: (config.tickers || []).map((t: any) => ({
      id: newId(),
      ticker: String(t.portfolioRef || t.ticker || ''),
      weight: String(t.weight ?? ''),
      isPortfolioRef: t.isPortfolioRef === true || t.type === 'PORTFOLIO_REF' || !!t.portfolioRef,
    })),
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
    includeNoMargin: config.includeNoMargin !== false,
  }
}

/** Convert BlockState to the portfolio object expected by the run API. */
export function blockStateToAPIPortfolio(state: BlockState, idx: number) {
  return {
    label: state.label.trim() || `Portfolio ${idx + 1}`,
    tickers: state.tickers
      .map(t => t.isPortfolioRef
        ? { ticker: t.ticker.trim(), weight: parseFloat(t.weight) || 0, isPortfolioRef: true, portfolioRef: t.ticker.trim() }
        : { ticker: t.ticker.trim().toUpperCase(), weight: parseFloat(t.weight) || 0 }
      )
      .filter(t => t.ticker && t.weight > 0),
    rebalanceStrategy: state.rebalance,
    marginStrategies: state.margins.map(m => ({
      marginRatio:          (parseFloat(m.ratio)    || 0)   / 100,
      marginSpread:         (parseFloat(m.spread)   || 1.5) / 100,
      marginDeviationUpper: (parseFloat(m.devUpper) || 5)   / 100,
      marginDeviationLower: (parseFloat(m.devLower) || 5)   / 100,
      upperRebalanceMode: m.modeUpper,
      lowerRebalanceMode: m.modeLower,
    })),
    includeNoMargin: state.includeNoMargin,
  }
}

/** Convert BlockState to the config format used by /api/backtest/savedPortfolios. */
export function blockStateToSavedConfig(state: BlockState) {
  return {
    tickers: state.tickers
      .map(t => t.isPortfolioRef
        ? { ticker: t.ticker.trim(), weight: parseFloat(t.weight) || 0, isPortfolioRef: true, portfolioRef: t.ticker.trim() }
        : { ticker: t.ticker.trim().toUpperCase(), weight: parseFloat(t.weight) || 0 }
      ),
    rebalanceStrategy: state.rebalance,
    marginStrategies: state.margins.map(m => ({
      marginRatio:          (parseFloat(m.ratio)    || 0)   / 100,
      marginSpread:         (parseFloat(m.spread)   || 1.5) / 100,
      marginDeviationUpper: (parseFloat(m.devUpper) || 5)   / 100,
      marginDeviationLower: (parseFloat(m.devLower) || 5)   / 100,
      upperRebalanceMode: m.modeUpper,
      lowerRebalanceMode: m.modeLower,
    })),
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
}

export interface BacktestPortfolioResult {
  label: string
  curves: BacktestCurve[]
}

export interface BacktestResults {
  portfolios: BacktestPortfolioResult[]
  error?: string
}

export interface McPercentilePath {
  percentile: number
  points: number[]
  endValue: number
  cagr: number
}

export interface McCurve {
  label: string
  percentilePaths: McPercentilePath[]
  maxDdPercentiles: number[]
  longestDrawdownPercentiles: number[]
  volatilityPercentiles: number[]
  sharpePercentiles: number[]
  ulcerPercentiles: number[]
  upiPercentiles: number[]
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
  error?: string
}

export interface SavedPortfolio {
  name: string
  config: any
}
