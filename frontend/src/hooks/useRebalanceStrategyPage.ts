import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import type { RebalanceStrategyBlockRef } from '@/components/rebalance/RebalanceStrategyBlock'
import type { SavedStrategiesBarRef } from '@/components/rebalance/SavedStrategiesBar'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { validateDateRange } from '@/lib/dateRange'
import { fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'
import { makeUniqueStrategyLabels } from '@/lib/rebalanceStrategyConfig'
import {
  BacktestResults,
  BlockState,
  DEFAULT_CASHFLOW_FREQUENCY,
  blockStateToAPIPortfolio,
  cashflowStateFromSettings,
  cashflowToPayload,
  configToBlockState,
  emptyBlock,
  normalizeBlockSpreadInputs,
  startingBalanceToPayload,
} from '@/types/backtest'
import {
  RebalStrategyState,
  drawdownMarginTriggerIssues,
  emptyStrategy,
  normalizeStrategySpreadInput,
  savedConfigToStrategyState,
  strategyStateToAPI,
} from '@/types/rebalanceStrategy'

type StrategyConfigLike = Record<string, unknown> & { label?: string }
type PageConfigLike = Record<string, unknown> & {
  fromDate?: string
  toDate?: string
  startingBalance?: unknown
  cashflow?: { amount?: unknown; frequency?: string }
  portfolios?: (Record<string, unknown> & { label?: string })[]
  portfolio?: Record<string, unknown> & { label?: string }
  portfolioState?: BlockState
  strategies?: StrategyConfigLike[]
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

export function useRebalanceStrategyPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [strategies, setStrategies] = useState<RebalStrategyState[]>([emptyStrategy(0), emptyStrategy(1)])
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [startingBalance, setStartingBalance] = useState('10000')
  const [cashflowAmount, setCashflowAmount] = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState(DEFAULT_CASHFLOW_FREQUENCY)
  const [includeActionDiagnostics, setIncludeActionDiagnostics] = useState(false)
  const [importCode, setImportCode] = useState('')
  const [configError, setConfigError] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const [results, setResults] = useState<BacktestResults | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const savedStrategiesBarRef = useRef<SavedStrategiesBarRef>(null)
  const strategyBlockRefs = useRef<(RebalanceStrategyBlockRef | null)[]>([])

  const dateRangeError = validateDateRange(fromDate, toDate)

  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: PageConfigLike) => {
        if (!req.portfolios) return
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate) setToDate(req.toDate)
        const cashflowState = cashflowStateFromSettings(req)
        if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
        if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
        if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
        if (req.portfolios[0]) setPortfolio(configToBlockState(req.portfolios[0], req.portfolios[0].label || ''))
      })
      .catch(() => {})
  }, [])

  const currentNormalizedStrategies = useCallback(() => {
    const currentStrategies = strategies
      .map((strategy, i) => strategyBlockRefs.current[i]?.getValue() ?? strategy)
      .map(normalizeStrategySpreadInput)
    strategyBlockRefs.current.forEach(ref => ref?.commit())
    return currentStrategies
  }, [strategies])

  const resolveRunInputs = useCallback(async () => {
    const runPortfolio = normalizeBlockSpreadInputs(portfolio)
    if (runPortfolio !== portfolio) setPortfolio(runPortfolio)

    const portfolioApi = resolvedBlockStateToAPIPortfolio(runPortfolio, 0, await fetchSavedPortfolios())
    if (portfolioApi.tickers.length === 0) {
      throw new Error('Add at least one ticker with a positive weight to the portfolio.')
    }

    const currentStrategies = currentNormalizedStrategies()
    const portfolioBlockStates = portfolioApi.rebalanceStrategies
      .map(s => savedConfigToStrategyState(s.config, s.name))
    const allStrategies = makeUniqueStrategyLabels([...portfolioBlockStates, ...currentStrategies], portfolioApi.label)
    const tierIssues = allStrategies.flatMap(strategy => [
      ...drawdownMarginTriggerIssues(strategy.drawdownBuyOnLowMargin, 'buy', `${strategy.label || 'Strategy'} BL on Drawdown`),
    ])
    if (tierIssues.length > 0) throw new Error(tierIssues[0])

    const runStrategies = allStrategies.slice(portfolioBlockStates.length)
    if (runStrategies.some((strategy, i) => strategy !== strategies[i] || strategy.label !== strategies[i]?.label)) {
      setStrategies(runStrategies)
    }

    return { portfolioApi, allStrategies }
  }, [currentNormalizedStrategies, portfolio, strategies])

  const handleRun = useCallback(async () => {
    setError('')
    if (dateRangeError) {
      setError(dateRangeError)
      return
    }

    let runInputs: Awaited<ReturnType<typeof resolveRunInputs>>
    try {
      runInputs = await resolveRunInputs()
    } catch (e: unknown) {
      setError(errorMessage(e, 'Unable to resolve saved portfolio references.'))
      return
    }

    setRunning(true)
    try {
      const res = await fetch('/api/rebalance-strategy/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromDate: fromDate || null,
          toDate: toDate || null,
          startingBalance: startingBalanceToPayload(startingBalance),
          portfolio: runInputs.portfolioApi,
          cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
          strategies: runInputs.allStrategies.map(strategy => strategyStateToAPI(strategy)),
          includeActionDiagnostics,
        }),
      })
      const data: BacktestResults = await res.json()
      if (!res.ok || data.error) {
        setError(data.error || `Server error ${res.status}`)
        return
      }
      setResults(data)
      setSelected(new Set(data.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))))
    } catch (e: unknown) {
      setError('Request failed: ' + errorMessage(e, 'Unknown error'))
    } finally {
      setRunning(false)
    }
  }, [
    cashflowAmount,
    cashflowFrequency,
    dateRangeError,
    fromDate,
    includeActionDiagnostics,
    resolveRunInputs,
    startingBalance,
    toDate,
  ])

  const handleExport = useCallback(async () => {
    const currentStrategies = currentNormalizedStrategies()
    if (currentStrategies.some((strategy, i) => strategy !== strategies[i])) setStrategies(currentStrategies)

    const exportPortfolio = normalizeBlockSpreadInputs(portfolio)
    if (exportPortfolio !== portfolio) setPortfolio(exportPortfolio)
    const code = await compressToCode({
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: startingBalanceToPayload(startingBalance),
      portfolio: blockStateToAPIPortfolio(exportPortfolio, 0),
      portfolioState: exportPortfolio,
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
      strategies: currentStrategies,
    })
    setImportCode(code)
    try { await navigator.clipboard.writeText(code) } catch {}
  }, [cashflowAmount, cashflowFrequency, currentNormalizedStrategies, fromDate, portfolio, startingBalance, strategies, toDate])

  const handleImport = useCallback(async () => {
    if (!importCode.trim()) return
    try {
      const req = await decompressFromCode(importCode.trim()) as PageConfigLike
      if (req.fromDate) setFromDate(req.fromDate)
      if (req.toDate) setToDate(req.toDate)
      const cashflowState = cashflowStateFromSettings(req)
      if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
      if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
      if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
      if (req.portfolioState) setPortfolio(req.portfolioState)
      else if (req.portfolio) setPortfolio(configToBlockState(req.portfolio, req.portfolio.label || ''))
      if (Array.isArray(req.strategies)) {
        setStrategies(req.strategies.slice(0, 2).map((s, i) => (
          savedConfigToStrategyState(s, s.label || `Strategy ${i + 1}`)
        )))
      }
      setConfigError('')
    } catch {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }, [importCode])

  const strategyHandlers = useMemo(
    () => [0, 1].map(i => (strategy: RebalStrategyState) =>
      setStrategies(prev => {
        const next = [...prev]
        next[i] = strategy
        return next
      })
    ),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])
  const refreshSavedStrategies = useCallback(() => savedStrategiesBarRef.current?.refresh(), [])

  return {
    portfolio,
    setPortfolio,
    strategies,
    fromDate,
    setFromDate,
    toDate,
    setToDate,
    startingBalance,
    setStartingBalance,
    cashflowAmount,
    setCashflowAmount,
    cashflowFrequency,
    setCashflowFrequency,
    includeActionDiagnostics,
    setIncludeActionDiagnostics,
    importCode,
    setImportCode,
    configError,
    running,
    error,
    results,
    selected,
    setSelected,
    savedBarRef,
    savedStrategiesBarRef,
    strategyBlockRefs,
    dateRangeError,
    handleRun,
    handleExport,
    handleImport,
    strategyHandlers,
    refreshSaved,
    refreshSavedStrategies,
  }
}
