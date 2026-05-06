// ── RebalanceStrategyPage.tsx ─────────────────────────────────────────────────

import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush, ReferenceDot,
} from 'recharts'
import { Lock, SlidersHorizontal, Unlock } from 'lucide-react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import CashflowControls from '@/components/backtest/CashflowControls'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import RebalanceStrategyBlock, { type RebalanceStrategyBlockRef } from '@/components/rebalance/RebalanceStrategyBlock'
import SavedStrategiesBar, { type SavedStrategiesBarRef } from '@/components/rebalance/SavedStrategiesBar'
import { useChartTheme } from '@/lib/chartTheme'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import {
  BlockState, BacktestResults, emptyBlock, blockStateToAPIPortfolio,
  configToBlockState, PALETTE, cashflowStateFromSettings,
  cashflowToPayload, startingBalanceToPayload, REBALANCE_MARGIN_MODE_OPTIONS, REBALANCE_OPTIONS,
} from '@/types/backtest'
import {
  buildCommonLabels, buildRechartsData, computeDrawdown, computeRTR,
} from '@/lib/chartData'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import {
  RebalStrategyState, emptyStrategy, strategyStateToAPI, savedConfigToStrategyState,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS, type DipSurgeState, type ExecutionMethodState,
  type PriceMoveTriggerState,
} from '@/types/rebalanceStrategy'
import { resolvedBlockStateToAPIPortfolio, savedPortfolioConfig } from '@/lib/portfolioRefs'

type OptimizerMetric = 'cagr' | 'sharpe' | 'upi'
type OptimizerProgress = {
  running: boolean
  completed: number
  total: number
  generation: number
  generations: number
  bestScore: number | null
}

type OptimizerTestPortfolio = {
  name: string
  config: any
}

type OptimizerLockKey =
  | 'rebalancePeriod'
  | 'marginRebalance'
  | 'cashflow'
  | 'buyLow'
  | 'sellHigh'
  | 'buyDipWhole'
  | 'buyDipIndividual'
  | 'sellSurgeWhole'
  | 'sellSurgeIndividual'

type OptimizerLockMode = 'none' | 'enabled' | 'config'
type OptimizerLocks = Record<OptimizerLockKey, OptimizerLockMode>

const OPTIMIZER_LOCK_LABELS: { key: OptimizerLockKey; label: string }[] = [
  { key: 'rebalancePeriod', label: 'RP' },
  { key: 'marginRebalance', label: 'MR' },
  { key: 'cashflow', label: 'CF' },
  { key: 'buyLow', label: 'BL' },
  { key: 'sellHigh', label: 'SH' },
  { key: 'buyDipWhole', label: 'BD-W' },
  { key: 'buyDipIndividual', label: 'BD-I' },
  { key: 'sellSurgeWhole', label: 'SS-W' },
  { key: 'sellSurgeIndividual', label: 'SS-I' },
]

type StrategyGenome = {
  minMargin: number
  comfortLow: number
  maxMargin: number
  comfortHigh: number
  portfolioRebalance: string
  portfolioRebalanceUseComfortZone: boolean
  marginRebalanceEnabled: boolean
  rebalancePeriod: string
  allocStrategy: string
  tradeDirection: RebalStrategyState['marginRebalanceTradeDirection']
  marginRebalanceRestoreMargin: number
  cashflowImmediateInvestPct: number
  cashflowScalingMargin: number
  buyLowEnabled: boolean
  buyLowRestoreMargin: number
  sellHighEnabled: boolean
  sellHighRestoreMargin: number
  buyCooldownAfterSellHighDays: number
  sellCooldownAfterBuyLowDays: number
  useComfortZone: boolean
  buyDipWhole: DipSurgeGenome | null
  buyDipIndividual: DipSurgeGenome | null
  sellSurgeWhole: DipSurgeGenome | null
  sellSurgeIndividual: DipSurgeGenome | null
}

type DipSurgeTriggerGenome = {
  type: PriceMoveTriggerState['type']
  nDays: number
  pct: number
}

type DipSurgeGenome = {
  allocStrategy: string
  limit: number
  coolingOffDays: number
  executionMethod: ExecutionMethodState['method']
  consecutiveDays: number
  steppedPortions: number
  steppedAdditionalPctTenths: number
  triggers: DipSurgeTriggerGenome[]
}

// ── Legend line ───────────────────────────────────────────────────────────────

function LegendLine({ color, strokeWidth, strokeDasharray }: { color: string; strokeWidth: number; strokeDasharray?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current; if (!canvas) return
    const ctx = canvas.getContext('2d'); if (!ctx) return
    ctx.clearRect(0, 0, 28, 10)
    ctx.strokeStyle = color; ctx.lineWidth = strokeWidth
    ctx.setLineDash(strokeDasharray ? strokeDasharray.split(' ').map(Number) : [])
    ctx.beginPath(); ctx.moveTo(2, 5); ctx.lineTo(26, 5); ctx.stroke()
  }, [color, strokeWidth, strokeDasharray])
  return <canvas ref={ref} width={28} height={10} style={{ display: 'inline-block', verticalAlign: 'middle' }} />
}

function labelKey(label: string) {
  return label.trim().toLocaleLowerCase()
}

function makeUniqueStrategyLabels(strategies: RebalStrategyState[], portfolioLabel: string) {
  const taken = new Set<string>()
  if (portfolioLabel.trim()) taken.add(labelKey(portfolioLabel))

  return strategies.map((strategy, i) => {
    const base = strategy.label.trim() || `Strategy ${i + 1}`
    let label = base
    let suffix = 2
    while (taken.has(labelKey(label))) {
      label = `${base} (${suffix})`
      suffix += 1
    }
    taken.add(labelKey(label))
    return label === strategy.label ? strategy : { ...strategy, label }
  })
}

const ACTION_MARKERS: Record<string, { label: string; short: string; color: string }> = {
  SELL_HIGH:  { label: 'Sell high',  short: 'SH', color: '#d94841' },
  BUY_LOW:    { label: 'Buy low',    short: 'BL', color: '#2f9e44' },
  BUY_DIP:    { label: 'Buy dip',    short: 'BD', color: '#1971c2' },
  SELL_SURGE: { label: 'Sell surge', short: 'SS', color: '#e67700' },
}

const DEFAULT_OPTIMIZER_GENERATIONS = 20
const DEFAULT_OPTIMIZER_POPULATION = 12
const OPTIMIZER_ELITES = 4
const OPTIMIZER_MUTATION_RATE = 0.22
const OPTIMIZER_PERIODS = REBALANCE_PERIOD_OVERRIDE_OPTIONS
  .map(o => o.value)
  .filter(v => v !== 'INHERIT' && v !== 'NONE')
const OPTIMIZER_PORTFOLIO_REBALANCE_PERIODS = REBALANCE_OPTIONS.map(o => o.value)
const OPTIMIZER_ALLOC_STRATEGIES = REBALANCE_MARGIN_MODE_OPTIONS.map(o => o.value)
const OPTIMIZER_TRADE_DIRECTIONS: RebalStrategyState['marginRebalanceTradeDirection'][] = ['BOTH', 'BUY_ONLY', 'SELL_ONLY']
const OPTIMIZER_TRIGGER_TYPES: PriceMoveTriggerState['type'][] = ['VS_N_DAYS_AGO', 'VS_RUNNING_AVG', 'PEAK_DEVIATION']
const OPTIMIZER_EXECUTION_METHODS: ExecutionMethodState['method'][] = ['ONCE', 'CONSECUTIVE', 'STEPPED']

function clampNumber(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}

function clampInt(v: number, lo: number, hi: number) {
  return Math.round(clampNumber(v, lo, hi))
}

function parseMarginPoint(points: string[] | undefined, index: number, fallback: number) {
  const n = parseInt(points?.[index] ?? '', 10)
  return Number.isFinite(n) ? n : fallback
}

function randomInt(lo: number, hi: number) {
  return lo + Math.floor(Math.random() * (hi - lo + 1))
}

function randomChoice<T>(items: T[]) {
  return items[randomInt(0, items.length - 1)]
}

function metricLabel(metric: OptimizerMetric) {
  if (metric === 'cagr') return 'CAGR'
  if (metric === 'sharpe') return 'Sharpe'
  return 'UPI'
}

function triggerId() {
  return `ga-${Math.random().toString(36).slice(2, 10)}`
}

function nextLockMode(mode: OptimizerLockMode): OptimizerLockMode {
  if (mode === 'none') return 'enabled'
  if (mode === 'enabled') return 'config'
  return 'none'
}

function nextOptimizerLockMode(key: OptimizerLockKey, mode: OptimizerLockMode): OptimizerLockMode {
  return nextLockMode(mode)
}

function lockModeLabel(mode: OptimizerLockMode) {
  if (mode === 'enabled') return 'Enabled locked'
  if (mode === 'config') return 'Config locked'
  return 'Unlocked'
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function RebalanceStrategyPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [strategies, setStrategies] = useState<RebalStrategyState[]>([emptyStrategy(0), emptyStrategy(1)])
  const [fromDate, setFromDate]   = useState('')
  const [toDate, setToDate]       = useState('')
  const [startingBalance, setStartingBalance]     = useState('10000')
  const [cashflowAmount, setCashflowAmount]       = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState('NONE')
  const [importCode, setImportCode]               = useState('')
  const [configError, setConfigError] = useState('')
  const [running, setRunning]     = useState(false)
  const [error, setError]         = useState('')
  const [results, setResults]     = useState<BacktestResults | null>(null)
  const [selected, setSelected]   = useState<Set<string>>(new Set())
  const [logScale, setLogScale]   = useState(false)
  const [optimizerMetric, setOptimizerMetric] = useState<OptimizerMetric>('cagr')
  const [optimizerGenerations, setOptimizerGenerations] = useState(String(DEFAULT_OPTIMIZER_GENERATIONS))
  const [optimizerPopulation, setOptimizerPopulation] = useState(String(DEFAULT_OPTIMIZER_POPULATION))
  const [optimizerTestPortfolios, setOptimizerTestPortfolios] = useState<OptimizerTestPortfolio[]>([])
  const [optimizerPortfolioDragOver, setOptimizerPortfolioDragOver] = useState(false)
  const [optimizerLocks, setOptimizerLocks] = useState<OptimizerLocks>({
    rebalancePeriod: 'none',
    marginRebalance: 'none',
    cashflow: 'none',
    buyLow: 'none',
    sellHigh: 'enabled',
    buyDipWhole: 'none',
    buyDipIndividual: 'none',
    sellSurgeWhole: 'none',
    sellSurgeIndividual: 'none',
  })
  const [optimizerProgress, setOptimizerProgress] = useState<OptimizerProgress>({
    running: false,
    completed: 0,
    total: 0,
    generation: 0,
    generations: DEFAULT_OPTIMIZER_GENERATIONS,
    bestScore: null,
  })

  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const savedStrategiesBarRef = useRef<SavedStrategiesBarRef>(null)
  const strategyBlockRefs = useRef<(RebalanceStrategyBlockRef | null)[]>([])
  const [chartWidth, setChartWidth] = useState(1000)
  const chartObsRef = useRef<ResizeObserver | null>(null)
  const chartContainerRef = useCallback((node: HTMLDivElement | null) => {
    chartObsRef.current?.disconnect(); chartObsRef.current = null
    if (!node) return
    const obs = new ResizeObserver(entries => setChartWidth(entries[0].contentRect.width))
    obs.observe(node); chartObsRef.current = obs
  }, [])

  const theme = useChartTheme()
  const { gridColor, textColor } = theme

  // Restore the shared backtest portfolio cache, but only load portfolio slot 0.
  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (!req.portfolios) return
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate)   setToDate(req.toDate)
        const cashflowState = cashflowStateFromSettings(req)
        if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
        if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
        if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
        if (req.portfolios[0]) setPortfolio(configToBlockState(req.portfolios[0], req.portfolios[0].label || ''))
      })
      .catch(() => {})
  }, [])

  // ── Run ───────────────────────────────────────────────────────────────────

  async function loadSavedPortfolios() {
    try {
      const res = await fetch('/api/backtest/savedPortfolios')
      if (!res.ok) return []
      return await res.json()
    } catch (_) {
      return []
    }
  }

  async function handleRun() {
    setError('')
    let portfolioApi
    try {
      portfolioApi = resolvedBlockStateToAPIPortfolio(portfolio, 0, await loadSavedPortfolios())
    } catch (e: any) {
      setError(e.message || 'Unable to resolve saved portfolio references.')
      return
    }
    if (portfolioApi.tickers.length === 0) {
      setError('Add at least one ticker with a positive weight to the portfolio.'); return
    }
    const currentStrategies = strategies.map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    const runStrategies = makeUniqueStrategyLabels(currentStrategies, portfolioApi.label)
    if (runStrategies.some((s, i) => s.label !== strategies[i]?.label)) setStrategies(runStrategies)
    setRunning(true)
    try {
      const res = await fetch('/api/rebalance-strategy/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromDate: fromDate || null,
          toDate: toDate || null,
          startingBalance: startingBalanceToPayload(startingBalance),
          portfolio: portfolioApi,
          cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
          strategies: runStrategies.map(s => strategyStateToAPI(s)),
        }),
      })
      const data: BacktestResults = await res.json()
      if (!res.ok || data.error) { setError(data.error || `Server error ${res.status}`); return }
      setResults(data)
      setSelected(new Set(data.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))))
    } catch (e: any) {
      setError('Request failed: ' + e.message)
    } finally {
      setRunning(false)
    }
  }

  function optimizerBounds(strategy: RebalStrategyState) {
    const low = parseMarginPoint(strategy.marginPoints, 0, 40)
    const mid = parseMarginPoint(strategy.marginPoints, 2, 50)
    const restoreMax = parseMarginPoint(strategy.marginPoints, 3, 55)
    const high = parseMarginPoint(strategy.marginPoints, 4, 60)
    const lower = Math.min(low, high)
    const upper = Math.max(low, high)
    const center = clampInt(mid, lower + 2, upper - 2)
    return {
      low: lower,
      mid: center,
      restoreMax: clampInt(restoreMax, center + 1, upper),
      high: upper,
      canOptimize: upper - lower >= 4,
    }
  }

  function normalizeGenome(genome: StrategyGenome, bounds: ReturnType<typeof optimizerBounds>): StrategyGenome {
    const minMargin = clampInt(genome.minMargin, bounds.low, bounds.mid - 1)
    const maxMargin = clampInt(genome.maxMargin, bounds.mid + 1, bounds.high)
    const comfortHigh = clampInt(genome.comfortHigh, bounds.mid + 1, maxMargin)
    const restoreMax = Math.min(bounds.restoreMax, maxMargin)
    return {
      ...genome,
      minMargin,
      maxMargin,
      comfortLow: clampInt(genome.comfortLow, minMargin, bounds.mid - 1),
      comfortHigh,
      portfolioRebalance: OPTIMIZER_PORTFOLIO_REBALANCE_PERIODS.includes(genome.portfolioRebalance) ? genome.portfolioRebalance : 'YEARLY',
      cashflowImmediateInvestPct: clampInt(genome.cashflowImmediateInvestPct, 0, 100),
      cashflowScalingMargin: clampInt(genome.cashflowScalingMargin, minMargin, maxMargin),
      buyLowRestoreMargin: clampInt(genome.buyLowRestoreMargin, minMargin, restoreMax),
      sellHighRestoreMargin: clampInt(genome.sellHighRestoreMargin, minMargin, restoreMax),
      marginRebalanceRestoreMargin: clampInt(genome.marginRebalanceRestoreMargin, minMargin, restoreMax),
      buyCooldownAfterSellHighDays: clampInt(genome.buyCooldownAfterSellHighDays, 0, 60),
      sellCooldownAfterBuyLowDays: clampInt(genome.sellCooldownAfterBuyLowDays, 0, 60),
      rebalancePeriod: OPTIMIZER_PERIODS.includes(genome.rebalancePeriod) ? genome.rebalancePeriod : 'MONTHLY',
      allocStrategy: OPTIMIZER_ALLOC_STRATEGIES.includes(genome.allocStrategy) ? genome.allocStrategy : 'PROPORTIONAL',
      buyDipWhole: genome.buyDipWhole ? normalizeDipSurgeGenome(genome.buyDipWhole, minMargin, restoreMax) : null,
      buyDipIndividual: genome.buyDipIndividual ? normalizeDipSurgeGenome(genome.buyDipIndividual, minMargin, restoreMax) : null,
      sellSurgeWhole: genome.sellSurgeWhole ? normalizeDipSurgeGenome(genome.sellSurgeWhole, minMargin, maxMargin) : null,
      sellSurgeIndividual: genome.sellSurgeIndividual ? normalizeDipSurgeGenome(genome.sellSurgeIndividual, minMargin, maxMargin) : null,
    }
  }

  function normalizeDipSurgeGenome(genome: DipSurgeGenome, minMargin: number, maxMargin: number): DipSurgeGenome {
    const triggers = genome.triggers.length > 0 ? genome.triggers : [randomDipSurgeTrigger()]
    return {
      ...genome,
      allocStrategy: OPTIMIZER_ALLOC_STRATEGIES.includes(genome.allocStrategy) ? genome.allocStrategy : 'PROPORTIONAL',
      limit: clampInt(genome.limit, minMargin, maxMargin),
      coolingOffDays: clampInt(genome.coolingOffDays, 0, 60),
      executionMethod: OPTIMIZER_EXECUTION_METHODS.includes(genome.executionMethod) ? genome.executionMethod : 'ONCE',
      consecutiveDays: clampInt(genome.consecutiveDays, 2, 3),
      steppedPortions: clampInt(genome.steppedPortions, 2, 8),
      steppedAdditionalPctTenths: clampInt(genome.steppedAdditionalPctTenths, 5, 200),
      triggers: triggers.slice(0, 1).map(t => ({
        type: OPTIMIZER_TRIGGER_TYPES.includes(t.type) ? t.type : 'PEAK_DEVIATION',
        nDays: clampInt(t.nDays, 5, 252),
        pct: clampInt(t.pct, 1, 50),
      })),
    }
  }

  function dipSurgeGenomeFromState(state: DipSurgeState | null, bounds: ReturnType<typeof optimizerBounds>) {
    if (!state) return null
    const execution = state.execution
    const limit = parseInt(state.limit || '', 10)
    return normalizeDipSurgeGenome({
      allocStrategy: state.allocStrategy || 'PROPORTIONAL',
      limit: Number.isFinite(limit) ? limit : bounds.mid,
      coolingOffDays: parseInt(state.coolingOffDays ?? '', 10) || 10,
      executionMethod: execution.method,
      consecutiveDays: execution.method === 'CONSECUTIVE' ? parseInt(execution.days, 10) || 3 : 3,
      steppedPortions: execution.method === 'STEPPED' ? parseInt(execution.portions, 10) || 3 : 3,
      steppedAdditionalPctTenths: execution.method === 'STEPPED' ? clampInt((parseFloat(execution.additionalPct) || 5) * 10, 5, 200) : 50,
      triggers: state.triggers.map(t => ({
        type: t.type,
        nDays: t.type === 'PEAK_DEVIATION' ? 20 : parseInt(t.nDays, 10) || 20,
        pct: parseInt(t.pct, 10) || 5,
      })),
    }, bounds.low, bounds.high)
  }

  function randomDipSurgeTrigger(): DipSurgeTriggerGenome {
    return {
      type: randomChoice(OPTIMIZER_TRIGGER_TYPES),
      nDays: randomInt(2, 52) * 5,
      pct: randomInt(2, 25),
    }
  }

  function randomDipSurgeGenome(minMargin: number, maxMargin: number): DipSurgeGenome {
    return normalizeDipSurgeGenome({
      allocStrategy: randomChoice(OPTIMIZER_ALLOC_STRATEGIES),
      limit: randomInt(minMargin, maxMargin),
      coolingOffDays: randomInt(0, 12) * 5,
      executionMethod: randomChoice(OPTIMIZER_EXECUTION_METHODS),
      consecutiveDays: randomInt(2, 3),
      steppedPortions: randomInt(2, 6),
      steppedAdditionalPctTenths: randomInt(5, 100),
      triggers: [randomDipSurgeTrigger()],
    }, minMargin, maxMargin)
  }

  function executionFromDipSurgeGenome(genome: DipSurgeGenome): ExecutionMethodState {
    if (genome.executionMethod === 'CONSECUTIVE') return { method: 'CONSECUTIVE', days: String(genome.consecutiveDays) }
    if (genome.executionMethod === 'STEPPED') {
      return {
        method: 'STEPPED',
        portions: String(genome.steppedPortions),
        additionalPct: (genome.steppedAdditionalPctTenths / 10).toFixed(1),
      }
    }
    return { method: 'ONCE' }
  }

  function dipSurgeStateFromGenome(scope: DipSurgeState['scope'], genome: DipSurgeGenome): DipSurgeState {
    return {
      scope,
      allocStrategy: genome.allocStrategy,
      limit: String(genome.limit),
      limitPointIndex: '',
      coolingOffDays: String(genome.coolingOffDays),
      execution: executionFromDipSurgeGenome(genome),
      triggers: genome.triggers.map(t => (
        t.type === 'PEAK_DEVIATION'
          ? { id: triggerId(), type: t.type, pct: String(t.pct) }
          : { id: triggerId(), type: t.type, nDays: String(t.nDays), pct: String(t.pct) }
      )),
    }
  }

  function applyEnabledLocks(genome: StrategyGenome, base: RebalStrategyState, baseGenome: StrategyGenome, locks: OptimizerLocks) {
    const next = { ...genome }
    if (locks.rebalancePeriod !== 'none') next.portfolioRebalance = baseGenome.portfolioRebalance
    if (locks.marginRebalance !== 'none') next.marginRebalanceEnabled = base.marginRebalanceEnabled ?? true
    if (locks.cashflow !== 'none') next.cashflowImmediateInvestPct = baseGenome.cashflowImmediateInvestPct
    if (locks.buyLow !== 'none') next.buyLowEnabled = base.buyLowEnabled
    if (locks.sellHigh !== 'none') next.sellHighEnabled = base.sellHighEnabled
    if (locks.buyDipWhole !== 'none') next.buyDipWhole = base.buyTheDip.wholePortfolio ? (next.buyDipWhole ?? baseGenome.buyDipWhole) : null
    if (locks.buyDipIndividual !== 'none') next.buyDipIndividual = base.buyTheDip.individualStock ? (next.buyDipIndividual ?? baseGenome.buyDipIndividual) : null
    if (locks.sellSurgeWhole !== 'none') next.sellSurgeWhole = base.sellOnSurge.wholePortfolio ? (next.sellSurgeWhole ?? baseGenome.sellSurgeWhole) : null
    if (locks.sellSurgeIndividual !== 'none') next.sellSurgeIndividual = base.sellOnSurge.individualStock ? (next.sellSurgeIndividual ?? baseGenome.sellSurgeIndividual) : null
    return next
  }

  function applySectionConfigLocks(strategy: RebalStrategyState, base: RebalStrategyState, locks: OptimizerLocks): RebalStrategyState {
    return {
      ...strategy,
      ...(locks.rebalancePeriod === 'config' ? {
        portfolioRebalancePeriod: base.portfolioRebalancePeriod ?? 'INHERIT',
        portfolioRebalanceUseComfortZone: base.portfolioRebalanceUseComfortZone ?? true,
      } : {}),
      ...(locks.marginRebalance === 'config' ? {
        marginRebalanceEnabled: base.marginRebalanceEnabled ?? true,
        rebalancePeriod: base.rebalancePeriod,
        rebalanceAllocStrategy: base.rebalanceAllocStrategy,
        marginRebalanceTradeDirection: base.marginRebalanceTradeDirection,
        marginRebalanceRestoreMargin: base.marginRebalanceRestoreMargin,
        useComfortZone: base.useComfortZone,
      } : {}),
      ...(locks.cashflow === 'config' ? {
        cashflowImmediateInvestPct: base.cashflowImmediateInvestPct,
        cashflowScaling: base.cashflowScaling,
        cashflowScalingPointIndex: base.cashflowScalingPointIndex,
        cashflowScalingMargin: base.cashflowScalingMargin,
      } : {}),
      ...(locks.buyLow === 'config' ? {
        buyLowEnabled: base.buyLowEnabled,
        buyLowAllocStrategy: base.buyLowAllocStrategy,
        buyLowRestorePointIndex: base.buyLowRestorePointIndex,
        buyLowRestoreMargin: base.buyLowRestoreMargin,
      } : {}),
      ...(locks.sellHigh === 'config' ? {
        sellHighEnabled: base.sellHighEnabled,
        sellHighAllocStrategy: base.sellHighAllocStrategy,
        sellHighRestorePointIndex: base.sellHighRestorePointIndex,
        sellHighRestoreMargin: base.sellHighRestoreMargin,
      } : {}),
      buyTheDip: {
        wholePortfolio: locks.buyDipWhole === 'config' ? base.buyTheDip.wholePortfolio : strategy.buyTheDip.wholePortfolio,
        individualStock: locks.buyDipIndividual === 'config' ? base.buyTheDip.individualStock : strategy.buyTheDip.individualStock,
      },
      sellOnSurge: {
        wholePortfolio: locks.sellSurgeWhole === 'config' ? base.sellOnSurge.wholePortfolio : strategy.sellOnSurge.wholePortfolio,
        individualStock: locks.sellSurgeIndividual === 'config' ? base.sellOnSurge.individualStock : strategy.sellOnSurge.individualStock,
      },
    }
  }

  function genomeFromStrategy(strategy: RebalStrategyState, bounds: ReturnType<typeof optimizerBounds>, basePortfolioRebalance: string): StrategyGenome {
    const points = strategy.marginPoints ?? []
    const minMargin = parseMarginPoint(points, 0, bounds.low)
    const maxMargin = parseMarginPoint(points, 4, bounds.high)
    const fallbackRestore = bounds.mid
    const parseWithin = (value: string | undefined, fallback: number) => {
      const n = parseInt(value ?? '', 10)
      return Number.isFinite(n) ? n : fallback
    }
    const portfolioRebalancePeriod = strategy.portfolioRebalancePeriod ?? 'INHERIT'
    const portfolioRebalance = portfolioRebalancePeriod === 'INHERIT'
      ? basePortfolioRebalance
      : portfolioRebalancePeriod
    return normalizeGenome({
      minMargin,
      comfortLow: parseMarginPoint(points, 1, Math.max(bounds.low, bounds.mid - 5)),
      maxMargin,
      comfortHigh: parseMarginPoint(points, 3, Math.min(bounds.high, bounds.mid + 5)),
      portfolioRebalance: OPTIMIZER_PORTFOLIO_REBALANCE_PERIODS.includes(portfolioRebalance) ? portfolioRebalance : 'YEARLY',
      portfolioRebalanceUseComfortZone: strategy.portfolioRebalanceUseComfortZone ?? true,
      marginRebalanceEnabled: strategy.marginRebalanceEnabled ?? true,
      rebalancePeriod: strategy.rebalancePeriod === 'NONE' ? 'MONTHLY' : strategy.rebalancePeriod,
      allocStrategy: strategy.rebalanceAllocStrategy || 'PROPORTIONAL',
      tradeDirection: strategy.marginRebalanceTradeDirection || 'BOTH',
      marginRebalanceRestoreMargin: parseWithin(strategy.marginRebalanceRestoreMargin, fallbackRestore),
      cashflowImmediateInvestPct: clampInt(parseFloat(strategy.cashflowImmediateInvestPct) || 100, 0, 100),
      cashflowScalingMargin: parseWithin(strategy.cashflowScalingMargin, fallbackRestore),
      buyLowEnabled: strategy.buyLowEnabled,
      buyLowRestoreMargin: parseWithin(strategy.buyLowRestoreMargin, fallbackRestore),
      sellHighEnabled: strategy.sellHighEnabled,
      sellHighRestoreMargin: parseWithin(strategy.sellHighRestoreMargin, fallbackRestore),
      buyCooldownAfterSellHighDays: clampInt(parseFloat(strategy.buyCooldownAfterSellHighDays ?? '') || 10, 0, 60),
      sellCooldownAfterBuyLowDays: clampInt(parseFloat(strategy.sellCooldownAfterBuyLowDays ?? '') || 10, 0, 60),
      useComfortZone: strategy.useComfortZone ?? true,
      buyDipWhole: dipSurgeGenomeFromState(strategy.buyTheDip.wholePortfolio, bounds),
      buyDipIndividual: dipSurgeGenomeFromState(strategy.buyTheDip.individualStock, bounds),
      sellSurgeWhole: dipSurgeGenomeFromState(strategy.sellOnSurge.wholePortfolio, bounds),
      sellSurgeIndividual: dipSurgeGenomeFromState(strategy.sellOnSurge.individualStock, bounds),
    }, bounds)
  }

  function randomGenome(bounds: ReturnType<typeof optimizerBounds>): StrategyGenome {
    const minMargin = randomInt(bounds.low, bounds.mid - 1)
    const maxMargin = randomInt(bounds.mid + 1, bounds.high)
    return normalizeGenome({
      minMargin,
      comfortLow: randomInt(minMargin, bounds.mid - 1),
      maxMargin,
      comfortHigh: randomInt(bounds.mid + 1, maxMargin),
      portfolioRebalance: randomChoice(OPTIMIZER_PORTFOLIO_REBALANCE_PERIODS),
      portfolioRebalanceUseComfortZone: Math.random() >= 0.25,
      marginRebalanceEnabled: Math.random() >= 0.15,
      rebalancePeriod: randomChoice(OPTIMIZER_PERIODS),
      allocStrategy: randomChoice(OPTIMIZER_ALLOC_STRATEGIES),
      tradeDirection: randomChoice(OPTIMIZER_TRADE_DIRECTIONS),
      marginRebalanceRestoreMargin: randomInt(minMargin, maxMargin),
      cashflowImmediateInvestPct: randomInt(0, 20) * 5,
      cashflowScalingMargin: randomInt(minMargin, maxMargin),
      buyLowEnabled: Math.random() >= 0.2,
      buyLowRestoreMargin: randomInt(minMargin, maxMargin),
      sellHighEnabled: Math.random() >= 0.2,
      sellHighRestoreMargin: randomInt(minMargin, maxMargin),
      buyCooldownAfterSellHighDays: randomInt(0, 12) * 5,
      sellCooldownAfterBuyLowDays: randomInt(0, 12) * 5,
      useComfortZone: Math.random() >= 0.25,
      buyDipWhole: Math.random() < 0.45 ? randomDipSurgeGenome(minMargin, maxMargin) : null,
      buyDipIndividual: Math.random() < 0.45 ? randomDipSurgeGenome(minMargin, maxMargin) : null,
      sellSurgeWhole: Math.random() < 0.45 ? randomDipSurgeGenome(minMargin, maxMargin) : null,
      sellSurgeIndividual: Math.random() < 0.45 ? randomDipSurgeGenome(minMargin, maxMargin) : null,
    }, bounds)
  }

  function strategyFromGenome(base: RebalStrategyState, genome: StrategyGenome, bounds: ReturnType<typeof optimizerBounds>): RebalStrategyState {
    const g = normalizeGenome(genome, bounds)
    return {
      ...base,
      marginRatio: String(bounds.mid),
      marginSpread: base.marginSpread,
      marginPoints: [g.minMargin, g.comfortLow, bounds.mid, g.comfortHigh, g.maxMargin].map(String),
      portfolioRebalancePeriod: g.portfolioRebalance,
      portfolioRebalanceUseComfortZone: g.portfolioRebalanceUseComfortZone,
      marginRebalanceEnabled: g.marginRebalanceEnabled,
      rebalancePeriod: g.rebalancePeriod,
      rebalanceAllocStrategy: g.allocStrategy,
      marginRebalanceTradeDirection: g.tradeDirection,
      marginRebalanceRestoreMargin: String(g.marginRebalanceRestoreMargin),
      cashflowImmediateInvestPct: String(g.cashflowImmediateInvestPct),
      cashflowScalingMargin: String(g.cashflowScalingMargin),
      cashflowScalingPointIndex: '',
      deviationMode: 'ABSOLUTE',
      buyLowEnabled: g.buyLowEnabled,
      buyLowAllocStrategy: g.allocStrategy,
      buyLowRestoreMargin: String(g.buyLowRestoreMargin),
      buyLowRestorePointIndex: '',
      sellHighEnabled: g.sellHighEnabled,
      sellHighAllocStrategy: g.allocStrategy,
      sellHighRestoreMargin: String(g.sellHighRestoreMargin),
      sellHighRestorePointIndex: '',
      useComfortZone: g.useComfortZone,
      comfortZoneLow: String(g.comfortLow),
      comfortZoneHigh: String(g.comfortHigh),
      buyCooldownAfterSellHighDays: String(g.buyCooldownAfterSellHighDays),
      sellCooldownAfterBuyLowDays: String(g.sellCooldownAfterBuyLowDays),
      buyTheDip: {
        wholePortfolio: g.buyDipWhole ? dipSurgeStateFromGenome('WHOLE_PORTFOLIO', g.buyDipWhole) : null,
        individualStock: g.buyDipIndividual ? dipSurgeStateFromGenome('INDIVIDUAL_STOCK', g.buyDipIndividual) : null,
      },
      sellOnSurge: {
        wholePortfolio: g.sellSurgeWhole ? dipSurgeStateFromGenome('WHOLE_PORTFOLIO', g.sellSurgeWhole) : null,
        individualStock: g.sellSurgeIndividual ? dipSurgeStateFromGenome('INDIVIDUAL_STOCK', g.sellSurgeIndividual) : null,
      },
    }
  }

  function crossoverGenome(a: StrategyGenome, b: StrategyGenome, bounds: ReturnType<typeof optimizerBounds>) {
    const child = { ...a }
    ;(Object.keys(child) as (keyof StrategyGenome)[]).forEach(key => {
      if (Math.random() < 0.5) (child[key] as any) = b[key]
    })
    return normalizeGenome(child, bounds)
  }

  function mutateGenome(genome: StrategyGenome, bounds: ReturnType<typeof optimizerBounds>) {
    const g = { ...genome }
    const maybe = (fn: () => void) => { if (Math.random() < OPTIMIZER_MUTATION_RATE) fn() }
    maybe(() => { g.minMargin += randomInt(-3, 3) })
    maybe(() => { g.maxMargin += randomInt(-3, 3) })
    maybe(() => { g.comfortLow += randomInt(-2, 2) })
    maybe(() => { g.comfortHigh += randomInt(-2, 2) })
    maybe(() => { g.portfolioRebalance = randomChoice(OPTIMIZER_PORTFOLIO_REBALANCE_PERIODS) })
    maybe(() => { g.portfolioRebalanceUseComfortZone = !g.portfolioRebalanceUseComfortZone })
    maybe(() => { g.marginRebalanceEnabled = !g.marginRebalanceEnabled })
    maybe(() => { g.rebalancePeriod = randomChoice(OPTIMIZER_PERIODS) })
    maybe(() => { g.allocStrategy = randomChoice(OPTIMIZER_ALLOC_STRATEGIES) })
    maybe(() => { g.tradeDirection = randomChoice(OPTIMIZER_TRADE_DIRECTIONS) })
    maybe(() => { g.marginRebalanceRestoreMargin += randomInt(-3, 3) })
    maybe(() => { g.cashflowImmediateInvestPct += randomChoice([-10, -5, 5, 10]) })
    maybe(() => { g.cashflowScalingMargin += randomInt(-3, 3) })
    maybe(() => { g.buyLowEnabled = !g.buyLowEnabled })
    maybe(() => { g.buyLowRestoreMargin += randomInt(-3, 3) })
    maybe(() => { g.sellHighEnabled = !g.sellHighEnabled })
    maybe(() => { g.sellHighRestoreMargin += randomInt(-3, 3) })
    maybe(() => { g.buyCooldownAfterSellHighDays += randomChoice([-10, -5, 5, 10]) })
    maybe(() => { g.sellCooldownAfterBuyLowDays += randomChoice([-10, -5, 5, 10]) })
    maybe(() => { g.useComfortZone = !g.useComfortZone })
    maybe(() => { g.buyDipWhole = g.buyDipWhole ? null : randomDipSurgeGenome(g.minMargin, g.maxMargin) })
    maybe(() => { g.buyDipIndividual = g.buyDipIndividual ? null : randomDipSurgeGenome(g.minMargin, g.maxMargin) })
    maybe(() => { if (g.buyDipWhole) g.buyDipWhole = mutateDipSurgeGenome(g.buyDipWhole, g.minMargin, g.maxMargin) })
    maybe(() => { if (g.buyDipIndividual) g.buyDipIndividual = mutateDipSurgeGenome(g.buyDipIndividual, g.minMargin, g.maxMargin) })
    maybe(() => { g.sellSurgeWhole = g.sellSurgeWhole ? null : randomDipSurgeGenome(g.minMargin, g.maxMargin) })
    maybe(() => { g.sellSurgeIndividual = g.sellSurgeIndividual ? null : randomDipSurgeGenome(g.minMargin, g.maxMargin) })
    maybe(() => { if (g.sellSurgeWhole) g.sellSurgeWhole = mutateDipSurgeGenome(g.sellSurgeWhole, g.minMargin, g.maxMargin) })
    maybe(() => { if (g.sellSurgeIndividual) g.sellSurgeIndividual = mutateDipSurgeGenome(g.sellSurgeIndividual, g.minMargin, g.maxMargin) })
    return normalizeGenome(g, bounds)
  }

  function mutateDipSurgeGenome(genome: DipSurgeGenome, minMargin: number, maxMargin: number) {
    const g = { ...genome, triggers: genome.triggers.map(t => ({ ...t })) }
    const maybe = (fn: () => void) => { if (Math.random() < OPTIMIZER_MUTATION_RATE) fn() }
    maybe(() => { g.allocStrategy = randomChoice(OPTIMIZER_ALLOC_STRATEGIES) })
    maybe(() => { g.limit += randomInt(-3, 3) })
    maybe(() => { g.coolingOffDays += randomChoice([-10, -5, 5, 10]) })
    maybe(() => { g.executionMethod = randomChoice(OPTIMIZER_EXECUTION_METHODS) })
    maybe(() => { g.consecutiveDays += randomInt(-3, 3) })
    maybe(() => { g.steppedPortions += randomChoice([-1, 1]) })
    maybe(() => { g.steppedAdditionalPctTenths += randomInt(-10, 10) })
    maybe(() => {
      if (g.triggers.length === 0) g.triggers.push(randomDipSurgeTrigger())
      const t = g.triggers[randomInt(0, g.triggers.length - 1)]
      t.type = randomChoice(OPTIMIZER_TRIGGER_TYPES)
    })
    maybe(() => {
      if (g.triggers.length === 0) g.triggers.push(randomDipSurgeTrigger())
      const t = g.triggers[randomInt(0, g.triggers.length - 1)]
      t.nDays += randomChoice([-20, -10, -5, 5, 10, 20])
    })
    maybe(() => {
      if (g.triggers.length === 0) g.triggers.push(randomDipSurgeTrigger())
      const t = g.triggers[randomInt(0, g.triggers.length - 1)]
      t.pct += randomChoice([-5, -2, -1, 1, 2, 5])
    })
    return normalizeDipSurgeGenome(g, minMargin, maxMargin)
  }

  async function scoreStrategyBatch(portfolios: any[], batch: RebalStrategyState[], portfolioRebalances: string[], metric: OptimizerMetric) {
    const res = await fetch('/api/rebalance-strategy/score-batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fromDate: fromDate || null,
        toDate: toDate || null,
        startingBalance: startingBalanceToPayload(startingBalance),
        portfolios,
        portfolioRebalanceStrategies: portfolioRebalances,
        cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
        metric,
        strategies: batch.map(strategy => strategyStateToAPI(strategy)),
      }),
    })
    const data: any = await res.json()
    if (!res.ok || data.error) throw new Error(data.error || `Server error ${res.status}`)
    if (!Array.isArray(data)) throw new Error('Unexpected optimizer response.')
    return data.map(score => Number.isFinite(score) ? score : -Infinity)
  }

  function handleOptimizerPortfolioDragOver(e: DragEvent) {
    if (!e.dataTransfer.types.includes('application/x-portfolio-chip')) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
    setOptimizerPortfolioDragOver(true)
  }

  function handleOptimizerPortfolioDrop(e: DragEvent) {
    e.preventDefault()
    setOptimizerPortfolioDragOver(false)
    if (!e.dataTransfer.types.includes('application/x-portfolio-chip')) return
    const raw = e.dataTransfer.getData('application/x-portfolio-chip')
    if (!raw) return
    const { name, config } = JSON.parse(raw)
    setOptimizerTestPortfolios(prev => (
      prev.some(p => p.name === name)
        ? prev
        : [...prev, { name, config }]
    ))
  }

  async function handleOptimizeStrategy1() {
    setError('')
    const currentStrategies = strategies.map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    const baseStrategy = optimizerLocks.sellHigh === 'enabled' ? { ...currentStrategies[0], sellHighEnabled: true } : currentStrategies[0]
    const bounds = optimizerBounds(baseStrategy)
    if (!bounds.canOptimize) {
      setError('Strategy 1 needs low and high margin points at least 4 percentage points apart.')
      return
    }

    const savedPortfolios = await loadSavedPortfolios()
    let portfolioApi
    try {
      portfolioApi = resolvedBlockStateToAPIPortfolio(portfolio, 0, savedPortfolios)
    } catch (e: any) {
      setError(e.message || 'Unable to resolve saved portfolio references.')
      return
    }
    if (portfolioApi.tickers.length === 0) {
      setError('Add at least one ticker with a positive weight to the portfolio.')
      return
    }

    let optimizerPortfolioApis
    try {
      optimizerPortfolioApis = [
        portfolioApi,
        ...optimizerTestPortfolios.map((testPortfolio, i) =>
          resolvedBlockStateToAPIPortfolio(
            configToBlockState(savedPortfolioConfig(testPortfolio.config), testPortfolio.name),
            i + 1,
            savedPortfolios,
          )
        ),
      ]
    } catch (e: any) {
      setError(e.message || 'Unable to resolve optimizer test portfolio references.')
      return
    }

    const generations = clampInt(parseInt(optimizerGenerations, 10) || DEFAULT_OPTIMIZER_GENERATIONS, 1, 100)
    const populationSize = clampInt(parseInt(optimizerPopulation, 10) || DEFAULT_OPTIMIZER_POPULATION, 4, 100)
    const eliteCount = Math.min(OPTIMIZER_ELITES, populationSize)
    const total = generations * populationSize
    setOptimizerProgress({
      running: true,
      completed: 0,
      total,
      generation: 1,
      generations,
      bestScore: null,
    })

    const baseGenome = genomeFromStrategy(baseStrategy, bounds, portfolio.rebalance)
    const lockGenome = (genome: StrategyGenome) =>
      applyEnabledLocks(normalizeGenome(genome, bounds), baseStrategy, baseGenome, optimizerLocks)
    let population = [
      baseGenome,
      ...Array.from({ length: populationSize - 1 }, () => lockGenome(randomGenome(bounds))),
    ]
    let best: { genome: StrategyGenome; score: number } | null = null
    let completed = 0

    try {
      for (let generation = 1; generation <= generations; generation += 1) {
        const candidates = population.map(genome =>
          applySectionConfigLocks(strategyFromGenome(baseStrategy, genome, bounds), baseStrategy, optimizerLocks)
        )
        const candidateRebalances = population.map(genome => normalizeGenome(genome, bounds).portfolioRebalance)
        const scores = await scoreStrategyBatch(optimizerPortfolioApis, candidates, candidateRebalances, optimizerMetric)
        const scored = population.map((genome, i) => ({ genome, score: scores[i] ?? -Infinity }))
        completed += population.length
        for (const { genome, score } of scored) {
          if (!best || score > best.score) best = { genome, score }
        }
        setOptimizerProgress({
          running: true,
          completed,
          total,
          generation,
          generations,
          bestScore: best?.score ?? null,
        })
        scored.sort((a, b) => b.score - a.score)
        const elites = scored.slice(0, eliteCount).map(item => item.genome)
        population = [...elites]
        while (population.length < populationSize) {
          const parentA = randomChoice(elites)
          const parentB = randomChoice(elites)
          population.push(lockGenome(mutateGenome(crossoverGenome(parentA, parentB, bounds), bounds)))
        }
      }

      if (!best) throw new Error('Optimizer did not produce a valid strategy.')
      const optimized = applySectionConfigLocks(strategyFromGenome(baseStrategy, lockGenome(best.genome), bounds), baseStrategy, optimizerLocks)
      setStrategies(prev => {
        const next = [...prev]
        next[0] = optimized
        return next
      })
      setOptimizerProgress(prev => ({ ...prev, running: false, completed: total, bestScore: best?.score ?? prev.bestScore }))
    } catch (e: any) {
      setError('Optimization failed: ' + (e.message || String(e)))
      setOptimizerProgress(prev => ({ ...prev, running: false }))
    }
  }

  async function handleExport() {
    const currentStrategies = strategies.map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    const code = await compressToCode({
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: startingBalanceToPayload(startingBalance),
      portfolio: blockStateToAPIPortfolio(portfolio, 0),
      portfolioState: portfolio,
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
      strategies: currentStrategies,
    })
    setImportCode(code)
    try { await navigator.clipboard.writeText(code) } catch (_) {}
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const req: any = await decompressFromCode(importCode.trim())
      if (req.fromDate) setFromDate(req.fromDate)
      if (req.toDate) setToDate(req.toDate)
      const cashflowState = cashflowStateFromSettings(req)
      if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
      if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
      if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
      if (req.portfolioState) setPortfolio(req.portfolioState)
      else if (req.portfolio) setPortfolio(configToBlockState(req.portfolio, req.portfolio.label || ''))
      if (Array.isArray(req.strategies)) {
        setStrategies(req.strategies.slice(0, 2).map((s: any, i: number) => savedConfigToStrategyState(s, s.label || `Strategy ${i + 1}`)))
      }
      setConfigError('')
    } catch (_) {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }

  // ── Chart data ────────────────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!results) return null
    const labels = buildCommonLabels(results)
    const mainData   = buildRechartsData(results, labels, selected, pts => pts.map(p => p.value))
    const ddData     = buildRechartsData(results, labels, selected, computeDrawdown)
    const rtrData    = buildRechartsData(results, labels, selected, computeRTR)
    const marginData = buildRechartsData(results, labels, selected, pts => pts.map(p => p.value), c => c.marginPoints)
    return { labels, mainData, ddData, rtrData, marginData }
  }, [results, selected])

  const allKeys    = results ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`)) : []
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0
  const showLine   = (key: string) => selected.size === 0 || selected.has(key)

  const selectedStrategyCurve = useMemo(() => {
    if (!results || selected.size !== 1) return null
    const key = [...selected][0]
    const [piText, ciText] = key.split('-')
    const pi = parseInt(piText, 10)
    const ci = parseInt(ciText, 10)
    if (!Number.isFinite(pi) || !Number.isFinite(ci) || pi === 0) return null
    const curve = results.portfolios[pi]?.curves[ci]
    if (!curve?.actionPoints?.length) return null
    return { dataKey: `p${pi}-c${ci}`, curve }
  }, [results, selected])

  function toggleCurve(key: string, checked: boolean) {
    setSelected(prev => { const s = new Set(prev); checked ? s.add(key) : s.delete(key); return s })
  }
  function toggleAll(checked: boolean) { setSelected(checked ? new Set(allKeys) : new Set()) }

  const strategyHandlers = useMemo(
    () => [0, 1].map(i => (s: RebalStrategyState) =>
      setStrategies(prev => { const n = [...prev]; n[i] = s; return n })
    ),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])
  const refreshSavedStrategies = useCallback(() => savedStrategiesBarRef.current?.refresh(), [])

  const numPoints      = chartData?.labels.length ?? 2
  const pixelsPerPoint = chartWidth / Math.max(numPoints - 1, 1)

  const makeTooltip = (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) =>
    makeRechartsTooltip(theme, valueFmt, labelFmt)

  const commonLineProps = {
    type: 'monotone' as const, dot: false as const,
    activeDot: { r: 4 }, connectNulls: false, isAnimationActive: false,
  }

  const renderActionMarkers = (rows: Record<string, any>[]) => {
    if (!selectedStrategyCurve) return null
    const seen = new Set<string>()
    return selectedStrategyCurve.curve.actionPoints?.map((point, i) => {
      const marker = ACTION_MARKERS[point.type]
      if (!marker) return null
      const row = rows.find(r => r.x === point.date)
      const y = row?.[selectedStrategyCurve.dataKey]
      if (typeof y !== 'number' || !Number.isFinite(y)) return null

      const duplicateKey = `${point.date}-${point.type}`
      if (seen.has(duplicateKey)) return null
      seen.add(duplicateKey)

      return (
        <ReferenceDot
          key={`${duplicateKey}-${i}`}
          x={point.date}
          y={y}
          r={5}
          fill={marker.color}
          stroke={theme.isDark ? '#111' : '#fff'}
          strokeWidth={2}
          ifOverflow="extendDomain"
          label={{ value: marker.short, position: 'top', fill: marker.color, fontSize: 10 }}
        />
      )
    })
  }

  const renderLegend = (props: any) => {
    const { payload } = props
    if (!payload?.length) return null
    return (
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem 0.8rem', fontSize: '0.78em', color: textColor, padding: '4px 8px 0' }}>
        {payload.map((entry: any, i: number) => (
          <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
            <LegendLine color={entry.color as string} strokeWidth={2} />
            <span>{entry.value}</span>
          </span>
        ))}
      </div>
    )
  }

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/rebalance-strategy" /></div>
        <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
      </div>

      <div className="backtest-form-card">
        <div className="backtest-section backtest-config-row">
          <DateFieldWithQuickSelect label="From Date" inputId="rs-from-date" value={fromDate} onChange={setFromDate} />
          <DateFieldWithQuickSelect label="To Date"   inputId="rs-to-date"   value={toDate}   onChange={setToDate} />

          <div className="backtest-config-controls">
            <label htmlFor="rs-import-code">Config Code</label>
            <div className="backtest-config-group">
              <input
                type="text" id="rs-import-code" placeholder="Paste code..." spellCheck={false}
                value={importCode} onChange={e => setImportCode(e.target.value)}
              />
              <button className="backtest-config-btn" onClick={handleImport}>Import</button>
              <button className="backtest-config-btn" onClick={handleExport}>Export</button>
              {configError && <div className="backtest-config-error">{configError}</div>}
            </div>
          </div>
        </div>

        <CashflowControls
          idPrefix="rs"
          startingBalance={startingBalance}
          cashflowAmount={cashflowAmount}
          cashflowFrequency={cashflowFrequency}
          onStartingBalanceChange={setStartingBalance}
          onCashflowAmountChange={setCashflowAmount}
          onCashflowFrequencyChange={setCashflowFrequency}
        />

        <SavedPortfoliosBar ref={savedBarRef} />
        <SavedStrategiesBar ref={savedStrategiesBarRef} />

        <div className="strategy-optimizer-panel">
          <div className="strategy-optimizer-controls">
            <label htmlFor="rs-optimizer-metric">Optimize Strategy 1 for</label>
            <select
              id="rs-optimizer-metric"
              value={optimizerMetric}
              disabled={optimizerProgress.running}
              onChange={e => setOptimizerMetric(e.target.value as OptimizerMetric)}
            >
              <option value="cagr">CAGR</option>
              <option value="sharpe">Sharpe Ratio</option>
              <option value="upi">UPI</option>
            </select>
            <label htmlFor="rs-optimizer-generations">Generations</label>
            <input
              id="rs-optimizer-generations"
              className="strategy-optimizer-number"
              type="number"
              min="1"
              max="100"
              step="1"
              value={optimizerGenerations}
              disabled={optimizerProgress.running}
              onChange={e => setOptimizerGenerations(e.target.value)}
              onBlur={() => setOptimizerGenerations(String(clampInt(parseInt(optimizerGenerations, 10) || DEFAULT_OPTIMIZER_GENERATIONS, 1, 100)))}
            />
            <label htmlFor="rs-optimizer-population">Genes / Gen</label>
            <input
              id="rs-optimizer-population"
              className="strategy-optimizer-number"
              type="number"
              min="4"
              max="100"
              step="1"
              value={optimizerPopulation}
              disabled={optimizerProgress.running}
              onChange={e => setOptimizerPopulation(e.target.value)}
              onBlur={() => setOptimizerPopulation(String(clampInt(parseInt(optimizerPopulation, 10) || DEFAULT_OPTIMIZER_POPULATION, 4, 100)))}
            />
            <button
              className="backtest-config-btn"
              type="button"
              onClick={handleOptimizeStrategy1}
              disabled={running || optimizerProgress.running}
            >
              {optimizerProgress.running ? <>Optimizing...<span className="btn-spinner" /></> : 'Optimize with GA'}
            </button>
          </div>
          <div className="strategy-optimizer-locks" aria-label="Optimizer section locks">
            {OPTIMIZER_LOCK_LABELS.map(item => {
              const mode = optimizerLocks[item.key]
              const Icon = mode === 'config' ? SlidersHorizontal : mode === 'enabled' ? Lock : Unlock
              return (
                <button
                  key={item.key}
                  type="button"
                  className={`strategy-optimizer-lock-btn ${mode}`}
                  disabled={optimizerProgress.running}
                  title={`${lockModeLabel(mode)}: ${item.label}`}
                  aria-label={`${item.label} optimizer lock: ${lockModeLabel(mode)}`}
                  onClick={() => setOptimizerLocks(prev => ({ ...prev, [item.key]: nextOptimizerLockMode(item.key, prev[item.key]) }))}
                >
                  <Icon size={13} strokeWidth={2} />
                  <span>{item.label}</span>
                </button>
              )
            })}
          </div>
          <div
            className={`strategy-optimizer-dropzone${optimizerPortfolioDragOver ? ' drag-over' : ''}`}
            onDragOver={handleOptimizerPortfolioDragOver}
            onDragLeave={() => setOptimizerPortfolioDragOver(false)}
            onDrop={handleOptimizerPortfolioDrop}
          >
            <span>Drop saved portfolios to include in optimizer average</span>
            {optimizerTestPortfolios.length > 0 && (
              <div className="strategy-optimizer-test-portfolios">
                {optimizerTestPortfolios.map(p => (
                  <button
                    key={p.name}
                    type="button"
                    className="strategy-optimizer-test-chip"
                    disabled={optimizerProgress.running}
                    onClick={() => setOptimizerTestPortfolios(prev => prev.filter(item => item.name !== p.name))}
                    title="Remove from optimizer average"
                  >
                    {p.name} x
                  </button>
                ))}
              </div>
            )}
          </div>
          {(optimizerProgress.running || optimizerProgress.completed > 0) && (
            <div className="strategy-optimizer-progress">
              <progress
                value={optimizerProgress.completed}
                max={Math.max(optimizerProgress.total, 1)}
                aria-label="Genetic algorithm optimization progress"
              />
              <span>
                {optimizerProgress.completed}/{optimizerProgress.total} evals
                {optimizerProgress.running ? `, gen ${optimizerProgress.generation}/${optimizerProgress.generations}` : ''}
                {` across ${optimizerTestPortfolios.length + 1} portfolio${optimizerTestPortfolios.length === 0 ? '' : 's'}`}
                {optimizerProgress.bestScore != null ? `, best ${metricLabel(optimizerMetric)} ${fmt2(optimizerProgress.bestScore)}` : ''}
              </span>
            </div>
          )}
        </div>

        {/* Portfolio + Strategy blocks side by side */}
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', marginTop: '0.75rem' }}>
          <PortfolioBlock idx={0} value={portfolio} onChange={setPortfolio} onSavedRefresh={refreshSaved} />
          {strategies.map((s, i) => (
            <RebalanceStrategyBlock
              key={i}
              ref={el => { strategyBlockRefs.current[i] = el }}
              idx={i}
              value={s}
              onChange={strategyHandlers[i]}
              onSavedRefresh={refreshSavedStrategies}
            />
          ))}
        </div>

        <button className="run-backtest-btn" type="button" onClick={handleRun} disabled={running || optimizerProgress.running}>
          {running ? <>Running…<span className="btn-spinner" /></> : 'Run Rebalance Strategy'}
        </button>
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {results && chartData && (
        <>
          {/* Stats table */}
          <div className="stats-container">
            <table className="backtest-stats-table">
              <thead>
                <tr>
                  <th>
                    <input
                      type="checkbox" checked={allChecked}
                      ref={el => { if (el) el.indeterminate = anyChecked && !allChecked }}
                      onChange={e => toggleAll(e.target.checked)}
                    />
                  </th>
                  <th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th>
                  <th title="Peak-to-recovery duration of the worst drawdown">Longest DD</th>
                  <th title="Annualised volatility of daily returns">Volatility</th>
                  <th>Sharpe</th>
                  <th title="Ulcer Index">Ulcer</th>
                  <th title="Ulcer Performance Index">UPI</th>
                </tr>
              </thead>
              <tbody>
                {results.portfolios.flatMap((portfolio, pi) =>
                  portfolio.curves.map((curve, ci) => {
                    const key = `${pi}-${ci}`
                    const s = curve.stats
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td style={{ color: PALETTE[pi % PALETTE.length][ci % PALETTE[pi % PALETTE.length].length] }}>
                          {portfolio.label} – {curve.label}
                        </td>
                        <td>{money(s.endingValue)}</td>
                        <td>{pct(s.cagr)}</td>
                        <td>{pct(s.maxDrawdown)}</td>
                        <td>{dur(s.longestDrawdownDays)}</td>
                        <td>{pct(s.annualVolatility)}</td>
                        <td>{fmt2(s.sharpe)}</td>
                        <td>{pct(s.ulcerIndex)}</td>
                        <td>{fmt2(s.upi)}</td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Portfolio Value + Margin Utilization chart */}
          <div className="backtest-chart-title">Portfolio Value</div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.25rem' }}>
            <button className={`chart-scale-toggle${logScale ? ' active' : ''}`} type="button"
              style={{ position: 'static' }} onClick={() => setLogScale(l => !l)}>Log</button>
          </div>
          <div className="backtest-chart-container" ref={chartContainerRef}>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.mainData.rows} syncId="rs-backtest"
                margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis scale={logScale ? 'log' : 'linear'} domain={['auto', 'auto']}
                  allowDataOverflow={logScale} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)} width={72} />
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(2))} />
                <Legend content={renderLegend} />
                {chartData.mainData.datasets.map(ds => (
                  <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                ))}
                {renderActionMarkers(chartData.mainData.rows)}
                <Brush dataKey="x" height={26} stroke={gridColor}
                  fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* Drawdown chart */}
          <div className="backtest-chart-title">Drawdown</div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.ddData.rows} syncId="rs-backtest"
                margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => (Number(v) * 100).toFixed(1) + '%'} width={60} />
                <Tooltip content={makeTooltip(v => (v * 100).toFixed(2) + '%')} />
                <Legend content={renderLegend} />
                {chartData.ddData.datasets.map(ds => (
                  <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                ))}
                {renderActionMarkers(chartData.ddData.rows)}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* RTR chart */}
          <div className="backtest-chart-title">Return Required to Recover</div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.rtrData.rows} syncId="rs-backtest"
                margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                <YAxis domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => Number(v).toFixed(2) + 'x'} width={60} />
                <Tooltip content={makeTooltip(v => v.toFixed(2) + 'x')} />
                <Legend content={renderLegend} />
                {chartData.rtrData.datasets.map(ds => (
                  <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                    stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
                ))}
                {renderActionMarkers(chartData.rtrData.rows)}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* Margin Utilization chart */}
          {chartData.marginData.datasets.length > 0 && (
            <>
              <div className="backtest-chart-title">Margin Utilization</div>
              <div className="backtest-chart-container">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData.marginData.rows} syncId="rs-backtest"
                    margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                    <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
                      interval={Math.max(1, Math.floor(chartData.labels.length / 8))} />
                    <YAxis domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
                      tickFormatter={v => (Number(v) * 100).toFixed(0) + '%'} width={60} />
                    <Tooltip content={makeTooltip(v => (v * 100).toFixed(2) + '%')} />
                    <Legend content={renderLegend} />
                    {chartData.marginData.datasets.map(ds => (
                      <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
                        stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} />
                    ))}
                    {renderActionMarkers(chartData.marginData.rows)}
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </>
          )}

        </>
      )}
    </div>
  )
}
