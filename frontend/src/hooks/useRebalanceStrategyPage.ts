import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import type { RebalanceStrategyBlockRef } from '@/components/rebalance/RebalanceStrategyBlock'
import type { SavedStrategiesBarRef } from '@/components/rebalance/SavedStrategiesBar'
import { useSettingsAutosave } from '@/hooks/useSettingsAutosave'
import { useTransientToast } from '@/hooks/useTransientToast'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { validateDateRange } from '@/lib/dateRange'
import {
  applyImportDependencyPreview,
  buildImportDependencyPreview,
  hasImportDependencyPreview,
  withPortfolioExportDependencies,
  type ImportDependencyPreview,
} from '@/lib/configImportExport'
import { blockStateToSettingsPortfolio, fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'
import { makeUniqueStrategyLabels } from '@/lib/rebalanceStrategyConfig'
import {
  applyTickerMappingsToPortfolioWithWarnings,
  loadTickerMappingSettings,
  selectedTickerMappingSet as resolveSelectedTickerMappingSet,
  TICKER_MAPPINGS_CHANGED_EVENT,
  type TickerMappingSettings,
} from '@/lib/tickerMappings'
import {
  BacktestResults,
  BlockState,
  DEFAULT_CASHFLOW_FREQUENCY,
  blockStateToAPIPortfolio,
  cashflowStateFromSettings,
  cashflowToPayload,
  configToBlockInputLabel,
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
  strategyStateToSavedConfig,
} from '@/types/rebalanceStrategy'

type StrategyConfigLike = Record<string, unknown> & { label?: string }
type PageConfigLike = Record<string, unknown> & {
  fromDate?: string
  toDate?: string
  startingBalance?: unknown
  cashflow?: { amount?: unknown; frequency?: string }
  includeActionDiagnostics?: boolean
  portfolios?: (Record<string, unknown> & { label?: string })[]
  portfolio?: Record<string, unknown> & { label?: string }
  portfolioState?: BlockState
  strategies?: StrategyConfigLike[]
  strategyStates?: StrategyConfigLike[]
}

type RebalanceStrategyRunPayload = Record<string, unknown>

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function parseJsonResponse<T>(text: string, status: number): T {
  try {
    return JSON.parse(text) as T
  } catch {
    const preview = text.trim().replace(/\s+/g, ' ').slice(0, 240)
    throw new Error(
      preview
        ? `Server returned non-JSON response (${status}): ${preview}`
        : `Server returned empty response (${status})`,
    )
  }
}

function addResultWarnings(results: BacktestResults, warnings: string[]) {
  if (warnings.length === 0) return results
  return {
    ...results,
    warnings: [...new Set([...(results.warnings ?? []), ...warnings])],
  }
}

function restoreStrategyStates(req: PageConfigLike) {
  const savedStrategies = Array.isArray(req.strategyStates) ? req.strategyStates : req.strategies
  if (!Array.isArray(savedStrategies)) return null
  return savedStrategies.slice(0, 2).map((s, i) => (
    savedConfigToStrategyState(s, s.label || `Strategy ${i + 1}`)
  ))
}

export function useRebalanceStrategyPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [strategies, setStrategies] = useState<RebalStrategyState[]>([emptyStrategy(0), emptyStrategy(1)])
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [startingBalance, setStartingBalance] = useState('10000')
  const [cashflowAmount, setCashflowAmount] = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState(DEFAULT_CASHFLOW_FREQUENCY)
  const [tickerMappingSettings, setTickerMappingSettings] = useState<TickerMappingSettings>(() => loadTickerMappingSettings())
  const [includeActionDiagnostics, setIncludeActionDiagnostics] = useState(false)
  const [importCode, setImportCode] = useState('')
  const [configError, setConfigError] = useState('')
  const [settingsLoaded, setSettingsLoaded] = useState(false)
  const [pendingImport, setPendingImport] = useState<{ config: PageConfigLike; preview: ImportDependencyPreview } | null>(null)
  const [importDependencyApplying, setImportDependencyApplying] = useState(false)
  const [importDependencyError, setImportDependencyError] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const [results, setResults] = useState<BacktestResults | null>(null)
  const [zeroMarginInterestResults, setZeroMarginInterestResults] = useState<BacktestResults | null>(null)
  const [zeroMarginInterestRunning, setZeroMarginInterestRunning] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const { toast: importToast, showToast: showImportToast } = useTransientToast()
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const savedStrategiesBarRef = useRef<SavedStrategiesBarRef>(null)
  const strategyBlockRefs = useRef<(RebalanceStrategyBlockRef | null)[]>([])
  const lastRunPayloadRef = useRef<RebalanceStrategyRunPayload | null>(null)

  const dateRangeError = validateDateRange(fromDate, toDate)
  const selectedTickerMappingSet = useMemo(
    () => resolveSelectedTickerMappingSet(tickerMappingSettings),
    [tickerMappingSettings],
  )
  const buildSettingsPayload = useCallback((overrides?: {
    portfolio?: BlockState
    strategies?: RebalStrategyState[]
  }) => {
    const nextPortfolio = overrides?.portfolio ?? portfolio
    const nextStrategies = overrides?.strategies ?? strategies
    return {
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: startingBalanceToPayload(startingBalance),
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
      includeActionDiagnostics,
      settingsPortfolio: blockStateToSettingsPortfolio(nextPortfolio, 0),
      strategies: nextStrategies.map(strategy => strategyStateToAPI(strategy)),
      strategyStates: nextStrategies,
    }
  }, [
    cashflowAmount,
    cashflowFrequency,
    fromDate,
    includeActionDiagnostics,
    portfolio,
    startingBalance,
    strategies,
    toDate,
  ])
  const settingsPayload = useMemo(() => buildSettingsPayload(), [buildSettingsPayload])

  const settingsAutosave = useSettingsAutosave('/api/rebalance-strategy/settings', settingsPayload, settingsLoaded)

  useEffect(() => {
    const refreshTickerMappings = () => setTickerMappingSettings(loadTickerMappingSettings())
    window.addEventListener(TICKER_MAPPINGS_CHANGED_EVENT, refreshTickerMappings)
    return () => window.removeEventListener(TICKER_MAPPINGS_CHANGED_EVENT, refreshTickerMappings)
  }, [])

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
        if (typeof req.includeActionDiagnostics === 'boolean') setIncludeActionDiagnostics(req.includeActionDiagnostics)
        if (req.portfolios[0]) setPortfolio(configToBlockState(req.portfolios[0], configToBlockInputLabel(req.portfolios[0], 0)))
        const restoredStrategies = restoreStrategyStates(req)
        if (restoredStrategies) setStrategies(restoredStrategies)
      })
      .catch(() => {})
      .finally(() => setSettingsLoaded(true))
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

    const mappedPortfolio = applyTickerMappingsToPortfolioWithWarnings(
      resolvedBlockStateToAPIPortfolio(runPortfolio, 0, await fetchSavedPortfolios()),
      selectedTickerMappingSet,
    )
    const portfolioApi = mappedPortfolio.value
    const settingsPortfolio = blockStateToSettingsPortfolio(runPortfolio, 0)
    if (portfolioApi.tickers.length === 0) {
      throw new Error('Add at least one ticker with a positive net weight to the portfolio.')
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

    return { portfolioApi, settingsPortfolio, allStrategies, runStrategies, mappingWarnings: mappedPortfolio.warnings }
  }, [currentNormalizedStrategies, portfolio, selectedTickerMappingSet, strategies])

  const fetchRunResults = useCallback(async (payload: RebalanceStrategyRunPayload) => {
    const res = await fetch('/api/rebalance-strategy/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const data = parseJsonResponse<BacktestResults>(await res.text(), res.status)
    if (!res.ok || data.error) {
      throw new Error(data.error || `Server error ${res.status}`)
    }
    return data
  }, [])

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
      const payload: RebalanceStrategyRunPayload = {
        fromDate: fromDate || null,
        toDate: toDate || null,
        startingBalance: startingBalanceToPayload(startingBalance),
        portfolio: runInputs.portfolioApi,
        settingsPortfolio: runInputs.settingsPortfolio,
        cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
        strategies: runInputs.allStrategies.map(strategy => strategyStateToAPI(strategy)),
        strategyStates: runInputs.runStrategies,
        includeActionDiagnostics,
      }
      const data = addResultWarnings(await fetchRunResults(payload), runInputs.mappingWarnings)
      lastRunPayloadRef.current = payload
      setZeroMarginInterestResults(null)
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
    fetchRunResults,
    fromDate,
    includeActionDiagnostics,
    resolveRunInputs,
    startingBalance,
    toDate,
  ])

  const loadZeroMarginInterestResults = useCallback(async () => {
    if (zeroMarginInterestResults) return
    const payload = lastRunPayloadRef.current
    if (!payload) {
      setError('Run the strategy before enabling 0% margin interest on the chart.')
      return
    }

    setError('')
    setZeroMarginInterestRunning(true)
    try {
      const data = await fetchRunResults({
        ...payload,
        saveSettings: false,
        includeActionDiagnostics: false,
        zeroMarginInterest: true,
      })
      setZeroMarginInterestResults(data)
    } catch (e: unknown) {
      setError('Request failed: ' + errorMessage(e, 'Unknown error'))
    } finally {
      setZeroMarginInterestRunning(false)
    }
  }, [fetchRunResults, zeroMarginInterestResults])

  const handleExport = useCallback(async () => {
    const currentStrategies = currentNormalizedStrategies()
    if (currentStrategies.some((strategy, i) => strategy !== strategies[i])) setStrategies(currentStrategies)

    const exportPortfolio = normalizeBlockSpreadInputs(portfolio)
    if (exportPortfolio !== portfolio) setPortfolio(exportPortfolio)
    const portfolioConfig = blockStateToAPIPortfolio(exportPortfolio, 0)
    const savedStrategies = currentStrategies
      .map(strategy => ({ name: strategy.label.trim(), config: strategyStateToSavedConfig(strategy) }))
      .filter((strategy): strategy is { name: string; config: RebalStrategyState } => !!strategy.name)
    const code = await compressToCode(await withPortfolioExportDependencies({
      fromDate: fromDate || null,
      toDate: toDate || null,
      startingBalance: startingBalanceToPayload(startingBalance),
      portfolio: portfolioConfig,
      portfolioState: exportPortfolio,
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
      strategies: currentStrategies,
    }, [portfolioConfig], { savedStrategies }))
    setImportCode(code)
    try {
      await navigator.clipboard.writeText(code)
      showImportToast('Export code copied.')
    } catch {
      showImportToast('Export code generated.')
    }
  }, [cashflowAmount, cashflowFrequency, currentNormalizedStrategies, fromDate, portfolio, showImportToast, startingBalance, strategies, toDate])

  const applyImportedConfig = useCallback((req: PageConfigLike) => {
    if (req.fromDate) setFromDate(req.fromDate)
    if (req.toDate) setToDate(req.toDate)
    const cashflowState = cashflowStateFromSettings(req)
    if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
    if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
    if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
    if (req.portfolioState) setPortfolio(req.portfolioState)
    else if (req.portfolio) setPortfolio(configToBlockState(req.portfolio, configToBlockInputLabel(req.portfolio, 0)))
    const restoredStrategies = restoreStrategyStates(req)
    if (restoredStrategies) setStrategies(restoredStrategies)
  }, [])

  const handleImport = useCallback(async () => {
    if (!importCode.trim()) return
    try {
      const req = await decompressFromCode(importCode.trim()) as PageConfigLike
      const preview = await buildImportDependencyPreview(req as Record<string, unknown>)
      if (hasImportDependencyPreview(preview)) {
        setPendingImport({ config: req, preview })
        setImportDependencyError('')
        setConfigError('')
        return
      }
      applyImportedConfig(req)
      showImportToast('Import complete.')
      setConfigError('')
    } catch {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }, [applyImportedConfig, importCode, showImportToast])

  const confirmPendingImport = useCallback(async (previewArg?: ImportDependencyPreview, configArg?: PageConfigLike) => {
    if (!pendingImport || importDependencyApplying) return
    const preview = previewArg ?? pendingImport.preview
    const config = configArg ?? pendingImport.config
    setImportDependencyApplying(true)
    setImportDependencyError('')
    try {
      await applyImportDependencyPreview(preview)
      savedBarRef.current?.refresh()
      savedStrategiesBarRef.current?.refresh()
      applyImportedConfig(config)
      showImportToast('Import complete.')
      setPendingImport(null)
      setConfigError('')
    } catch (e: unknown) {
      setImportDependencyError(errorMessage(e, 'Unable to apply import dependencies.'))
    } finally {
      setImportDependencyApplying(false)
    }
  }, [applyImportedConfig, importDependencyApplying, pendingImport, showImportToast])

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
  const strategyCommitSaveHandlers = useMemo(
    () => [0, 1].map(i => (strategy: RebalStrategyState) => {
      const next = [...strategies]
      next[i] = strategy
      settingsAutosave.flush(buildSettingsPayload({ strategies: next }))
    }),
    [buildSettingsPayload, settingsAutosave, strategies],
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
    tickerMappingSettings,
    setTickerMappingSettings,
    includeActionDiagnostics,
    setIncludeActionDiagnostics,
    importCode,
    setImportCode,
    configError,
    pendingImport,
    setPendingImport,
    importDependencyApplying,
    importDependencyError,
    running,
    error,
    results,
    zeroMarginInterestResults,
    zeroMarginInterestRunning,
    selected,
    setSelected,
    importToast,
    showImportToast,
    savedBarRef,
    savedStrategiesBarRef,
    strategyBlockRefs,
    dateRangeError,
    handleRun,
    loadZeroMarginInterestResults,
    handleExport,
    handleImport,
    confirmPendingImport,
    strategyHandlers,
    strategyCommitSaveHandlers,
    refreshSaved,
    refreshSavedStrategies,
  }
}
