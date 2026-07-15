// ── portfolioStore.ts — Port of globals.js mutable state ─────────────────────
import { create } from 'zustand'
import type {
  PortfolioData, PortfolioOption, StockData, CashData, PortfolioConfig, AppConfig,
  StockDisplayEvent, CashDisplayEvent, PortfolioTotalsEvent,
  IbkrDisplayEvent, GroupAllocEvent, PriceQuoteEvent, AllocMode, SseStatus,
} from '@/types/portfolio'
import { legacyVisibilityToDefaultModeId, normalizePortfolioColumnModes } from '@/lib/portfolioColumns'

interface PortfolioState {
  // ── Loaded portfolio data ─────────────────────────────────────────────────
  portfolioId: string
  portfolioName: string
  allPortfolios: PortfolioOption[]
  stocks: StockData[]
  cash: CashData[]
  config: PortfolioConfig
  appConfig: AppConfig | null

  // ── Live SSE data ──────────────────────────────────────────────────────────
  fxRates: Record<string, number>
  priceQuotes: Record<string, PriceQuoteEvent>
  lastStockDisplay: StockDisplayEvent | null
  lastCashDisplay: CashDisplayEvent | null
  lastPortfolioTotals: PortfolioTotalsEvent | null
  lastIbkrData: IbkrDisplayEvent | null
  lastGroupAllocData: GroupAllocEvent | null

  // ── UI state (some persisted to localStorage) ──────────────────────────────
  sseStatus: SseStatus
  currentDisplayCurrency: string
  moreInfoVisible: boolean
  rebalVisible: boolean
  groupViewActive: boolean
  editModeActive: boolean
  afterHoursGray: boolean
  showStockDisplayCurrency: boolean
  portfolioContentScale: number
  portfolioColumnModeId: string

  // ── Rebal/alloc targets ────────────────────────────────────────────────────
  rebalTargetUsd: number | null
  marginTargetPct: number | null
  marginTargetUsd: number | null
  allocAddMode: AllocMode
  allocReduceMode: AllocMode
  stockGroupBy: 'none' | 'ccy' | 'mainGroup'

  // ── Actions ───────────────────────────────────────────────────────────────
  loadPortfolioData: (data: PortfolioData) => void
  setFxRates: (rates: Record<string, number>) => void
  setPriceQuote: (event: PriceQuoteEvent) => void
  setStockDisplay: (event: StockDisplayEvent) => void
  setCashDisplay: (event: CashDisplayEvent) => void
  setPortfolioTotals: (event: PortfolioTotalsEvent) => void
  setIbkrData: (event: IbkrDisplayEvent) => void
  setGroupAllocData: (event: GroupAllocEvent) => void
  setSseStatus: (status: SseStatus) => void
  setDisplayCurrency: (currency: string) => void
  setMoreInfoVisible: (v: boolean) => void
  setRebalVisible: (v: boolean) => void
  setGroupViewActive: (v: boolean) => void
  setEditModeActive: (v: boolean) => void
  setPortfolioContentScale: (v: number) => void
  setPortfolioColumnModeId: (id: string) => void
  setRebalTargetUsd: (v: number | null) => void
  setMarginTargetPct: (v: number | null) => void
  setMarginTargetUsd: (v: number | null) => void
  setAllocAddMode: (mode: AllocMode) => void
  setAllocReduceMode: (mode: AllocMode) => void
  setStockGroupBy: (v: 'none' | 'ccy' | 'mainGroup') => void
  setStocks: (stocks: StockData[]) => void
  setCash: (cash: CashData[]) => void
  updateAppConfig: (patch: Partial<AppConfig>) => void
}

const LS_KEYS = {
  currency: 'portfolio-helper-display-currency',
  moreInfo: 'portfolio-helper-more-info-visible',
  rebal: 'portfolio-helper-rebal-visible',
  allocAdd: 'portfolio-helper-alloc-add-mode',
  allocReduce: 'portfolio-helper-alloc-reduce-mode',
  theme: 'portfolio-helper-theme',
  stockGroupBy: 'portfolio-helper-stock-group-by',
  contentScale: 'portfolio-helper-portfolio-content-scale',
  columnMode: 'portfolio-helper-portfolio-column-mode',
}

function lsGet(key: string): string | null {
  return localStorage.getItem(key) ?? localStorage.getItem(key.replace('portfolio-helper-', 'ib-viewer-'))
}

function clampContentScale(value: number): number {
  if (!Number.isFinite(value)) return 1
  return Math.min(1.3, Math.max(0.7, value))
}

function readContentScale(): number {
  const raw = lsGet(LS_KEYS.contentScale)
  if (!raw) return 1
  return clampContentScale(parseFloat(raw))
}

function readInitialColumnMode(): string {
  const saved = lsGet(LS_KEYS.columnMode)
  if (saved) return saved
  return legacyVisibilityToDefaultModeId(lsGet(LS_KEYS.moreInfo) === 'true', lsGet(LS_KEYS.rebal) === 'true')
}

const DEFAULT_CONFIG: PortfolioConfig = {
  rebalTargetUsd: 0,
  marginTargetPct: 0,
  marginTargetUsd: 0,
  allocAddMode: 'PROPORTIONAL',
  allocReduceMode: 'PROPORTIONAL',
  virtualBalanceEnabled: false,
  dividendCalcUpToDate: '',
  dividendStartDate: '',
  flexibleWeightMappings: '',
}

export const usePortfolioStore = create<PortfolioState>((set, get) => ({
  // ── Initial state ──────────────────────────────────────────────────────────
  portfolioId: '',
  portfolioName: '',
  allPortfolios: [],
  stocks: [],
  cash: [],
  config: DEFAULT_CONFIG,
  appConfig: null,

  fxRates: { USD: 1.0 },
  priceQuotes: {},
  lastStockDisplay: null,
  lastCashDisplay: null,
  lastPortfolioTotals: null,
  lastIbkrData: null,
  lastGroupAllocData: null,

  sseStatus: 'connecting',
  currentDisplayCurrency: lsGet(LS_KEYS.currency) ?? 'USD',
  moreInfoVisible: lsGet(LS_KEYS.moreInfo) === 'true',
  rebalVisible: lsGet(LS_KEYS.rebal) === 'true',
  groupViewActive: false,
  editModeActive: false,
  afterHoursGray: true,
  showStockDisplayCurrency: false,
  portfolioContentScale: readContentScale(),
  portfolioColumnModeId: readInitialColumnMode(),

  rebalTargetUsd: null,
  marginTargetPct: null,
  marginTargetUsd: null,
  allocAddMode: (lsGet(LS_KEYS.allocAdd) as AllocMode | null) ?? 'PROPORTIONAL',
  allocReduceMode: (lsGet(LS_KEYS.allocReduce) as AllocMode | null) ?? 'PROPORTIONAL',
  stockGroupBy: (lsGet(LS_KEYS.stockGroupBy) as 'none' | 'ccy' | 'mainGroup' | null) ?? 'none',

  // ── Actions ────────────────────────────────────────────────────────────────
  loadPortfolioData: (data: PortfolioData) => {
    const { config, appConfig } = data
    const savedCurrency = lsGet(LS_KEYS.currency)
    const displayCurrency = (savedCurrency && appConfig.displayCurrencies.includes(savedCurrency))
      ? savedCurrency : appConfig.displayCurrencies[0] ?? 'USD'

    const rebalTarget = config.rebalTargetUsd > 0 ? config.rebalTargetUsd : null
    const marginTarget = config.marginTargetPct > 0 ? config.marginTargetPct : null
    const marginUsdTarget = config.marginTargetUsd > 0 ? config.marginTargetUsd : null

    // Alloc modes: prefer server-saved config over localStorage
    const allocAdd = config.allocAddMode as AllocMode
    const allocReduce = config.allocReduceMode as AllocMode
    const columnModes = normalizePortfolioColumnModes(appConfig.portfolioColumnModes)
    const currentColumnModeId = get().portfolioColumnModeId

    set({
      portfolioId: data.portfolioId,
      portfolioName: data.portfolioName,
      allPortfolios: data.allPortfolios,
      stocks: data.stocks,
      cash: data.cash,
      config,
      appConfig,
      currentDisplayCurrency: displayCurrency,
      afterHoursGray: appConfig.afterHoursGray,
      showStockDisplayCurrency: appConfig.showStockDisplayCurrency,
      rebalTargetUsd: rebalTarget,
      marginTargetPct: marginTarget,
      marginTargetUsd: marginUsdTarget,
      allocAddMode: allocAdd,
      allocReduceMode: allocReduce,
      portfolioColumnModeId: columnModes.some(mode => mode.id === currentColumnModeId)
        ? currentColumnModeId
        : columnModes[0].id,
      // SSE data is NOT reset here — useSSE reconnects on portfolioId change,
      // which triggers fresh StateFlow emissions from the server immediately.
    })
  },

  setFxRates: (rates) => set(s => ({ fxRates: { ...s.fxRates, ...rates } })),

  setPriceQuote: (event) => set(s => ({
    priceQuotes: {
      ...s.priceQuotes,
      [event.symbol.trim().toUpperCase()]: {
        ...event,
        symbol: event.symbol.trim().toUpperCase(),
      },
    },
  })),

  setStockDisplay: (event) => {
    if (event.portfolioId !== get().portfolioId) return
    set({ lastStockDisplay: event })
  },

  setCashDisplay: (event) => {
    if (event.portfolioId !== get().portfolioId) return
    set({ lastCashDisplay: event })
  },

  setPortfolioTotals: (event) => {
    if (event.portfolioId !== get().portfolioId) return
    set({ lastPortfolioTotals: event })
  },

  setIbkrData: (event) => {
    if (event.portfolioId !== get().portfolioId) return
    set({ lastIbkrData: event })
  },

  setGroupAllocData: (event) => {
    if (event.portfolioId !== get().portfolioId) return
    set({ lastGroupAllocData: event })
  },

  setSseStatus: (status) => set({ sseStatus: status }),

  setDisplayCurrency: (currency) => {
    localStorage.setItem(LS_KEYS.currency, currency)
    set({ currentDisplayCurrency: currency })
  },

  setMoreInfoVisible: (v) => {
    localStorage.setItem(LS_KEYS.moreInfo, String(v))
    set({ moreInfoVisible: v })
  },

  setRebalVisible: (v) => {
    localStorage.setItem(LS_KEYS.rebal, String(v))
    set({ rebalVisible: v })
  },

  setGroupViewActive: (v) => set({ groupViewActive: v }),
  setEditModeActive: (v) => set({ editModeActive: v }),
  setPortfolioContentScale: (v) => {
    const scale = clampContentScale(v)
    localStorage.setItem(LS_KEYS.contentScale, String(scale))
    set({ portfolioContentScale: scale })
  },
  setPortfolioColumnModeId: (id) => {
    localStorage.setItem(LS_KEYS.columnMode, id)
    set({ portfolioColumnModeId: id })
  },

  setRebalTargetUsd: (v) => set({ rebalTargetUsd: v, marginTargetPct: null, marginTargetUsd: null }),
  setMarginTargetPct: (v) => set({ marginTargetPct: v, rebalTargetUsd: null, marginTargetUsd: null }),
  setMarginTargetUsd: (v) => set({ marginTargetUsd: v, rebalTargetUsd: null, marginTargetPct: null }),

  setAllocAddMode: (mode) => {
    localStorage.setItem(LS_KEYS.allocAdd, mode)
    set({ allocAddMode: mode })
  },

  setAllocReduceMode: (mode) => {
    localStorage.setItem(LS_KEYS.allocReduce, mode)
    set({ allocReduceMode: mode })
  },

  setStockGroupBy: (v) => {
    localStorage.setItem(LS_KEYS.stockGroupBy, v)
    set({ stockGroupBy: v })
  },

  setStocks: (stocks) => set({ stocks }),
  setCash: (cash) => set({ cash }),

  updateAppConfig: (patch) => {
    const current = get().appConfig
    if (!current) return
    set({ appConfig: { ...current, ...patch } })
  },
}))
