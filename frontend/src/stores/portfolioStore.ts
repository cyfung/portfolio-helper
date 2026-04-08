// ── portfolioStore.ts — Port of globals.js mutable state ─────────────────────
import { create } from 'zustand'
import type {
  PortfolioData, PortfolioOption, StockData, CashData, PortfolioConfig, AppConfig,
  StockDisplayEvent, CashDisplayEvent, PortfolioTotalsEvent,
  IbkrDisplayEvent, GroupAllocEvent, AllocMode, SseStatus,
} from '@/types/portfolio'

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

  // ── Rebal/alloc targets ────────────────────────────────────────────────────
  rebalTargetUsd: number | null
  marginTargetPct: number | null
  marginTargetUsd: number | null
  allocAddMode: AllocMode
  allocReduceMode: AllocMode

  // ── Actions ───────────────────────────────────────────────────────────────
  loadPortfolioData: (data: PortfolioData) => void
  setFxRates: (rates: Record<string, number>) => void
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
  setRebalTargetUsd: (v: number | null) => void
  setMarginTargetPct: (v: number | null) => void
  setMarginTargetUsd: (v: number | null) => void
  setAllocAddMode: (mode: AllocMode) => void
  setAllocReduceMode: (mode: AllocMode) => void
}

const LS_KEYS = {
  currency: 'portfolio-helper-display-currency',
  moreInfo: 'portfolio-helper-more-info-visible',
  rebal: 'portfolio-helper-rebal-visible',
  allocAdd: 'portfolio-helper-alloc-add-mode',
  allocReduce: 'portfolio-helper-alloc-reduce-mode',
  theme: 'portfolio-helper-theme',
}

function lsGet(key: string): string | null {
  return localStorage.getItem(key) ?? localStorage.getItem(key.replace('portfolio-helper-', 'ib-viewer-'))
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

  rebalTargetUsd: null,
  marginTargetPct: null,
  marginTargetUsd: null,
  allocAddMode: (lsGet(LS_KEYS.allocAdd) as AllocMode | null) ?? 'PROPORTIONAL',
  allocReduceMode: (lsGet(LS_KEYS.allocReduce) as AllocMode | null) ?? 'PROPORTIONAL',

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
      // SSE data is NOT reset here — useSSE reconnects on portfolioId change,
      // which triggers fresh StateFlow emissions from the server immediately.
    })
  },

  setFxRates: (rates) => set(s => ({ fxRates: { ...s.fxRates, ...rates } })),

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
}))
