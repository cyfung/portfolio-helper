// ── API response from GET /api/portfolio/data ─────────────────────────────────

export interface PortfolioOption {
  slug: string
  name: string
  seqOrder: number
}

export interface StockData {
  label: string
  amount: number
  targetWeight: number     // 0 if unset
  letf: string             // "mult,sym,mult,sym" format
  groups: string           // "mult name;mult name" format
}

export interface CashData {
  label: string
  currency: string         // "P" = portfolio reference
  amount: number           // for P entries: sign multiplier (+1 or -1)
  marginFlag: boolean
  portfolioRef?: string    // lowercase portfolio slug, for P entries
}

export interface PortfolioConfig {
  rebalTargetUsd: number
  marginTargetPct: number
  marginTargetUsd: number
  allocAddMode: AllocMode
  allocReduceMode: AllocMode
  virtualBalanceEnabled: boolean
  dividendCalcUpToDate: string
  dividendStartDate: string
}

export interface AppConfig {
  version: string
  showStockDisplayCurrency: boolean
  afterHoursGray: boolean
  displayCurrencies: string[]
  hasUpdate: boolean
  latestVersion: string | null
  downloadPhase: string
  isJpackageInstall: boolean
  autoUpdate: boolean
  privacyScalePct: string
  privacyScaleEnabled: boolean
}

export interface PortfolioData {
  portfolioId: string
  portfolioName: string
  allPortfolios: PortfolioOption[]
  stocks: StockData[]
  cash: CashData[]
  config: PortfolioConfig
  appConfig: AppConfig
}

// ── SSE event payloads (from SseHandler.kt) ──────────────────────────────────

export interface FxRatesEvent {
  type: 'fx-rates'
  rates: Record<string, number>
}

export interface StockDisplayItem {
  symbol: string
  markPrice: number | null
  closePrice: number | null
  dayChangeNative: number | null
  dayChangePct: number | null
  qty: number | null
  currency: string
  positionValueUsd: number | null
  isMarketClosed: boolean
  tradingPeriodEndMs: number | null
  estPriceNative: number | null
  lastNav: number | null
}

export interface StockDisplayEvent {
  type: 'stock-display'
  portfolioId: string
  stocks: StockDisplayItem[]
  stockGrossUsd: number
  stockGrossKnown: boolean
  dayChangeUsd: number
  prevDayUsd: number
}

export interface CashDisplayEntry {
  label: string
  currency: string
  rawCcyAmount: number       // face value in the entry's own currency
  baseUsd: number | null     // USD base for display-currency conversion; null = not ready
  isMarginEntry: boolean
  entryId: string
  portfolioRef?: string
  portfolioMultiplier?: number
}

export interface CashDisplayEvent {
  type: 'cash-display'
  portfolioId: string
  entries: CashDisplayEntry[]
  totalBaseUsd: number
  totalKnown: boolean
  marginBaseUsd: number
}

export interface PortfolioTotalsEvent {
  type: 'portfolio-totals'
  portfolioId: string
  stockGrossUsd: number
  stockGrossKnown: boolean
  cashTotalUsd: number
  cashKnown: boolean
  grandTotalUsd: number
  grandTotalKnown: boolean
  marginUsd: number
  dayChangeUsd: number
  prevDayUsd: number
}

export interface IbkrRateCurrency {
  currency: string
  displayRateText: string
  dailyInterestUsd: number
}

export interface IbkrDisplayEvent {
  type: 'ibkr-display'
  portfolioId: string
  perCurrency: IbkrRateCurrency[]
  lastFetch: number
  currentDailyUsd: number
  cheapestCcy: string | null
  cheapestDailyUsd: number
  savingsUsd: number
  label: string
}

export interface GroupAllocItem {
  symbol: string
  allocDollars: number
}

/** SSE event sent by RebalGaService — contains server-computed (GA) alloc for GROUP portfolios only */
export interface GroupAllocEvent {
  type: 'rebal-alloc'
  portfolioId: string
  stocks: GroupAllocItem[]
}

export type SseEvent =
  | FxRatesEvent
  | StockDisplayEvent
  | CashDisplayEvent
  | PortfolioTotalsEvent
  | IbkrDisplayEvent
  | GroupAllocEvent
  | { type: 'reload' }

export type AllocMode =
  | 'PROPORTIONAL'
  | 'CURRENT_WEIGHT'
  | 'UNDERVALUED_PRIORITY'
  | 'WATERFALL'

export type SseStatus = 'connecting' | 'live' | 'error'

// ── Computed display state ────────────────────────────────────────────────────

/** Per-stock computed display values (derived from SSE data + store state) */
export interface StockDisplayState {
  symbol: string
  qty: number
  markPrice: number | null
  closePrice: number | null
  lastNav: number | null
  estPriceNative: number | null
  dayChangeNative: number | null
  dayChangePct: number | null
  positionValueUsd: number | null
  currency: string
  isMarketClosed: boolean
  tradingPeriodEndMs: number | null
  targetWeight: number
  currentWeightPct: number | null
  rebalDollars: number | null
  rebalQty: number | null
  allocDollars: number | null
  allocQty: number | null
  letf: string
  groups: string
}

// ── Backup panel types ────────────────────────────────────────────────────────

export interface BackupEntry {
  id: number
  createdAt: number   // epoch ms (matches server JSON field name)
  label: string
}

// ── TWS snapshot ─────────────────────────────────────────────────────────────

export interface TwsSnapshotResponse {
  account: string
  positions: { symbol: string; qty: number }[]
  cashBalances: Record<string, number>
  accruedCash: Record<string, number>
}
