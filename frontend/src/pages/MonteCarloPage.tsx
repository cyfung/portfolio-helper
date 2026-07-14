// ── MonteCarloPage.tsx — Full React port of Monte Carlo simulation ────────────

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer, Brush,
} from 'recharts'
import {
  BacktestPageHeader, RunButton, SavedPortfolioBlocksSection, ScenarioSetupControls,
} from '@/components/backtest/CommonBacktestSections'
import ImportDependenciesDialog from '@/components/backtest/ImportDependenciesDialog'
import TickerMappingControl from '@/components/backtest/TickerMappingControl'
import type { SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { useChartContainerWidth } from '@/hooks/useChartContainerWidth'
import { useSettingsAutosave } from '@/hooks/useSettingsAutosave'
import { useTransientToast } from '@/hooks/useTransientToast'
import { getChartTheme } from '@/lib/chartTheme'
import { scaleDash } from '@/lib/colorScheme'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import {
  applyImportDependencyPreview,
  buildImportDependencyPreview,
  hasImportDependencyPreview,
  withPortfolioExportDependencies,
  type ImportDependencyPreview,
} from '@/lib/configImportExport'
import { pct, fmt2, money, dur } from '@/lib/statsFormatters'
import { validateDateRange } from '@/lib/dateRange'
import {
  applyTickerMappingsToPortfolioWithWarnings,
  loadTickerMappingSettings,
  selectedTickerMappingSet as resolveSelectedTickerMappingSet,
  TICKER_MAPPINGS_CHANGED_EVENT,
  type TickerMappingSettings,
} from '@/lib/tickerMappings'
import {
  BlockState, MonteCarloResults, McCurve, emptyBlock,
  blockStateToAPIPortfolio, configToBlockState,
  PERCENTILE_COLORS, PERCENTILE_LIST, PALETTE,
  cashflowStateFromSettings, cashflowToPayload, configToBlockInputLabel,
  DEFAULT_CASHFLOW_FREQUENCY, normalizeBlockSpreadInputs, startingBalanceToPayload,
} from '@/types/backtest'
import { blockStateToSettingsPortfolio, fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'

// ── Effective curves helper ───────────────────────────────────────────────────

function getEffectiveCurves(data: MonteCarloResults, selected: Set<string>) {
  const result: { portfolio: { label: string; curves: McCurve[] }; pi: number; curve: McCurve; ci: number }[] = []
  data.portfolios.forEach((portfolio, pi) => {
    portfolio.curves.forEach((curve, ci) => {
      if (selected.size === 0 || selected.has(`${pi}-${ci}`)) {
        result.push({ portfolio, pi, curve, ci })
      }
    })
  })
  return result
}

const MC_COLS = [
  { metric: 'END_VALUE', label: 'End Value' }, { metric: 'CAGR', label: 'CAGR' },
  { metric: 'MAX_DD', label: 'Max DD' }, { metric: 'LONGEST_DD', label: 'Longest DD' },
  { metric: 'ANN_VOL', label: 'Volatility' }, { metric: 'SHARPE', label: 'Sharpe' },
  { metric: 'ULCER_INDEX', label: 'Ulcer' }, { metric: 'UPI', label: 'UPI' },
]

interface McRunProgressDetail {
  label: string
  value: string | number
}

interface McRunProgress {
  phase: string
  phaseLabel: string
  action: string
  progressLabel: string
  completed: number
  total: number
  currentStep: number
  totalSteps: number
  details: McRunProgressDetail[]
  done?: boolean
}

function normalizeRunProgress(raw: any): McRunProgress {
  const completed = Number(raw?.completed ?? 0)
  const total = Number(raw?.total ?? 0)
  return {
    phase: String(raw?.phase ?? 'simulate'),
    phaseLabel: String(raw?.phaseLabel ?? 'Running simulations'),
    action: String(raw?.action ?? 'Computing simulation iterations'),
    progressLabel: String(raw?.progressLabel ?? 'Progress'),
    completed: Number.isFinite(completed) ? completed : 0,
    total: Number.isFinite(total) ? total : 0,
    currentStep: Number(raw?.currentStep ?? (total > 0 ? 4 : 0)) || 0,
    totalSteps: Number(raw?.totalSteps ?? 7) || 7,
    details: Array.isArray(raw?.details) ? raw.details : [],
    done: !!raw?.done,
  }
}

function progressValue(value: string | number) {
  return typeof value === 'number' ? value.toLocaleString() : value
}

function isActiveRunProgress(progress: McRunProgress) {
  return progress.phase !== 'idle' && !progress.done
}

function addResultWarnings(results: MonteCarloResults, warnings: string[]) {
  if (warnings.length === 0) return results
  return {
    ...results,
    warnings: [...new Set([...(results.warnings ?? []), ...warnings])],
  }
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function MonteCarloPage() {
  const [blocks, setBlocks]           = useState<BlockState[]>([0, 1, 2].map(emptyBlock))
  const [fromDate, setFromDate]       = useState('')
  const [toDate, setToDate]           = useState('')
  const [startingBalance, setStartingBalance]     = useState('10000')
  const [cashflowAmount, setCashflowAmount]       = useState('')
  const [cashflowFrequency, setCashflowFrequency] = useState(DEFAULT_CASHFLOW_FREQUENCY)
  const [minChunk, setMinChunk]       = useState('3')
  const [maxChunk, setMaxChunk]       = useState('8')
  const [simYears, setSimYears]       = useState('20')
  const [numSims, setNumSims]         = useState('500')
  const [importCode, setImportCode]   = useState('')
  const [configError, setConfigError] = useState('')
  const [tickerMappingSettings, setTickerMappingSettings] = useState<TickerMappingSettings>(() => loadTickerMappingSettings())
  const [settingsLoaded, setSettingsLoaded] = useState(false)
  const [pendingImport, setPendingImport] = useState<{ config: any; preview: ImportDependencyPreview } | null>(null)
  const [importDependencyApplying, setImportDependencyApplying] = useState(false)
  const [importDependencyError, setImportDependencyError] = useState('')
  const [running, setRunning]         = useState(false)
  const [runProgress, setRunProgress] = useState<McRunProgress | null>(null)
  const [error, setError]             = useState('')
  const [results, setResults]         = useState<MonteCarloResults | null>(null)
  const [lastSeed, setLastSeed]       = useState<number | null>(null)
  const [percentile, setPercentile]   = useState(50)
  const [selected, setSelected]       = useState<Set<string>>(new Set())
  const [logScale, setLogScale]       = useState(false)
  const { toast: importToast, showToast: showImportToast } = useTransientToast()

  const savedBarRef       = useRef<SavedPortfoliosBarRef>(null)
  const pollRef           = useRef<number | null>(null)
  const progressClearRef  = useRef<number | null>(null)
  const { chartWidth, chartContainerRef } = useChartContainerWidth()
  const dateRangeError = validateDateRange(fromDate, toDate)
  const selectedTickerMappingSet = useMemo(
    () => resolveSelectedTickerMappingSet(tickerMappingSettings),
    [tickerMappingSettings],
  )
  const settingsPayload = useMemo(() => ({
    fromDate: fromDate || null,
    toDate: toDate || null,
    minChunkYears: parseFloat(minChunk) || 3,
    maxChunkYears: parseFloat(maxChunk) || 8,
    simulatedYears: parseInt(simYears, 10) || 20,
    numSimulations: parseInt(numSims, 10) || 500,
    startingBalance: startingBalanceToPayload(startingBalance),
    cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
    settingsPortfolios: blocks.map((block, i) => blockStateToSettingsPortfolio(block, i)),
  }), [blocks, cashflowAmount, cashflowFrequency, fromDate, maxChunk, minChunk, numSims, simYears, startingBalance, toDate])

  useSettingsAutosave('/api/montecarlo/settings', settingsPayload, settingsLoaded)

  useEffect(() => {
    const refreshTickerMappings = () => setTickerMappingSettings(loadTickerMappingSettings())
    window.addEventListener(TICKER_MAPPINGS_CHANGED_EVENT, refreshTickerMappings)
    return () => window.removeEventListener(TICKER_MAPPINGS_CHANGED_EVENT, refreshTickerMappings)
  }, [])

  useEffect(() => () => {
    if (pollRef.current != null) window.clearInterval(pollRef.current)
    if (progressClearRef.current != null) window.clearTimeout(progressClearRef.current)
  }, [])

  useEffect(() => {
    let active = true
    fetch('/api/montecarlo/run-state')
      .then(r => r.json())
      .then(state => {
        if (!active) return
        const keepPolling = applyRunState(state, true)
        if (keepPolling) startRunStatePolling(true)
      })
      .catch(() => {})
    return () => { active = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run-state recovery is intentionally mount-only.
  }, [])

  // Restore settings on mount
  useEffect(() => {
    fetch('/api/montecarlo/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (!req || !Object.keys(req).length) return
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate)   setToDate(req.toDate)
        const cashflowState = cashflowStateFromSettings(req)
        if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
        if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
        if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
        if (req.minChunkYears  != null) setMinChunk(String(req.minChunkYears))
        if (req.maxChunkYears  != null) setMaxChunk(String(req.maxChunkYears))
        if (req.simulatedYears != null) setSimYears(String(req.simulatedYears))
        if (req.numSimulations != null) setNumSims(String(req.numSimulations))
        if (req.portfolios) {
          setBlocks(prev => {
            const next = [...prev]
            req.portfolios.forEach((p: any, i: number) => {
              if (i < 3) next[i] = configToBlockState(p, configToBlockInputLabel(p, i))
            })
            return next
          })
        }
      })
      .catch(() => {})
      .finally(() => setSettingsLoaded(true))
  }, [])

  // ── Computed chart data ───────────────────────────────────────────────────

  const chartData = useMemo(() => {
    if (!results) return null
    const targetDays = results.simulatedYears * 252
    const effectiveCurves = getEffectiveCurves(results, selected)
    const singleCurve = effectiveCurves.length === 1

    // Build rows: one object per day index
    const rows: Record<string, any>[] = Array.from({ length: targetDays + 1 }, (_, i) => ({ x: i }))

    interface McDataset { label: string; color: string; strokeDasharray?: string; strokeWidth: number }
    const datasets: McDataset[] = []

    if (singleCurve) {
      const { curve } = effectiveCurves[0]
      PERCENTILE_LIST.forEach((p, idx) => {
        const pp = curve.percentilePaths.find(x => x.percentile === p)
        if (!pp) return
        const key = `P${p}`
        pp.points.forEach((val, i) => { rows[i][key] = val })
        datasets.push({
          label: key,
          color: PERCENTILE_COLORS[idx],
          strokeDasharray: p !== 50 && idx < 3 ? '4 2' : undefined,
          strokeWidth: p === 50 ? 2.5 : 1.5,
        })
      })
    } else {
      effectiveCurves.forEach(({ portfolio, pi, curve, ci }) => {
        const palette = PALETTE[pi % PALETTE.length]
        const pp = curve.percentilePaths.find(x => x.percentile === percentile)
        if (!pp) return
        const key = `${portfolio.label} \u2013 ${curve.label}`
        pp.points.forEach((val, i) => { rows[i][key] = val })
        datasets.push({
          label: key,
          color: palette[ci % palette.length],
          strokeWidth: 1.5,
        })
      })
    }

    const yearTicks = Array.from({ length: results.simulatedYears + 1 }, (_, i) => i * 252)
    return { rows, datasets, yearTicks, targetDays, effectiveCurves }
  }, [results, percentile, selected])

  // ── Run ───────────────────────────────────────────────────────────────────

  function setLocalProgress(phaseLabel: string, action: string, details: McRunProgressDetail[] = [], currentStep = 1) {
    setRunProgress({
      phase: 'client',
      phaseLabel,
      action,
      progressLabel: 'Progress',
      completed: 0,
      total: 0,
      currentStep,
      totalSteps: 7,
      details,
    })
  }

  function clearRunProgressSoon() {
    if (progressClearRef.current != null) window.clearTimeout(progressClearRef.current)
    progressClearRef.current = window.setTimeout(() => {
      setRunProgress(null)
      progressClearRef.current = null
    }, 2500)
  }

  function stopRunStatePolling() {
    if (pollRef.current != null) window.clearInterval(pollRef.current)
    pollRef.current = null
  }

  function applyCachedResult(data: MonteCarloResults) {
    if (data.seed != null) setLastSeed(data.seed)
    setSelected(new Set())
    setResults(data)
  }

  function applyRunState(raw: any, restoreResult: boolean, keepIdle = false) {
    const progress = normalizeRunProgress(raw?.progress ?? raw)
    const result = raw?.result as MonteCarloResults | null | undefined
    const hasResult = !!result && Array.isArray(result.portfolios)

    if (progress.phase === 'idle' && !hasResult) {
      if (!keepIdle) {
        setRunProgress(null)
        setRunning(false)
      }
      return keepIdle
    }

    setRunProgress(progress)

    if (progress.phase === 'error') {
      setError(String(raw?.error || progress.action || 'Monte Carlo run failed'))
      setRunning(false)
      return false
    }

    if (hasResult && restoreResult) {
      applyCachedResult(result)
      setRunning(false)
      if (progress.done) clearRunProgressSoon()
      return false
    }

    const active = isActiveRunProgress(progress)
    setRunning(active)
    return active
  }

  function startRunStatePolling(restoreResult: boolean, keepIdle = false) {
    stopRunStatePolling()
    pollRef.current = window.setInterval(async () => {
      try {
        const r = await fetch('/api/montecarlo/run-state')
        const keepPolling = applyRunState(await r.json(), restoreResult, keepIdle)
        if (!keepPolling) stopRunStatePolling()
      } catch (_) {}
    }, 300)
  }

  async function doRun(seed: number | null = null) {
    setError('')
    if (progressClearRef.current != null) {
      window.clearTimeout(progressClearRef.current)
      progressClearRef.current = null
    }
    if (dateRangeError) {
      setError(dateRangeError)
      return
    }
    const runBlocks = blocks.map(normalizeBlockSpreadInputs)
    if (runBlocks.some((block, i) => block !== blocks[i])) setBlocks(runBlocks)
    const settingsPortfolios = runBlocks.map((b, i) => blockStateToSettingsPortfolio(b, i))
    const ns = parseInt(numSims, 10) || 500
    setRunning(true)
    setLocalProgress('Preparing request', 'Resolving saved portfolios and ticker mappings', [
      { label: 'Portfolio blocks', value: runBlocks.length },
      { label: 'Requested simulations', value: ns },
      { label: 'Simulated years', value: parseInt(simYears, 10) || 20 },
    ])
    let portfolios
    let mappingWarnings: string[]
    try {
      const savedPortfolios = await fetchSavedPortfolios()
      setLocalProgress('Preparing request', 'Applying ticker mappings and filtering empty blocks', [
        { label: 'Saved portfolios loaded', value: savedPortfolios.length },
        { label: 'Portfolio blocks', value: runBlocks.length },
      ])
      const mappedPortfolios = runBlocks
        .map((b, i) => resolvedBlockStateToAPIPortfolio(b, i, savedPortfolios))
        .map(p => applyTickerMappingsToPortfolioWithWarnings(p, selectedTickerMappingSet))
      mappingWarnings = mappedPortfolios.flatMap(mapped => mapped.warnings)
      portfolios = mappedPortfolios
        .map(mapped => mapped.value)
        .filter(p => p.tickers.length > 0)
    } catch (e: any) {
      setError(e.message || 'Unable to resolve saved portfolio references.')
      setRunning(false)
      setRunProgress(null)
      return
    }

    if (portfolios.length === 0) {
      setError('Add at least one portfolio block with a positive net weight.')
      setRunning(false)
      setRunProgress(null)
      return
    }
    if (portfolios.some(p => !p.includeNoMargin && p.marginStrategies.length === 0 && (p.rebalanceStrategies?.length ?? 0) === 0)) {
      setError('Each portfolio must have Unlevered enabled, at least one margin row, or at least one rebalance strategy.')
      setRunning(false)
      setRunProgress(null)
      return
    }

    setLocalProgress('Submitting request', 'Sending simulation request to the server', [
      { label: 'Runnable portfolios', value: portfolios.length },
      { label: 'Ticker rows', value: portfolios.reduce((sum, p) => sum + p.tickers.length, 0) },
      { label: 'Requested simulations', value: ns },
    ], 2)

    startRunStatePolling(false, true)

    const reqBody: any = {
      fromDate: fromDate || null,
      toDate: toDate || null,
      minChunkYears:  parseFloat(minChunk)  || 3,
      maxChunkYears:  parseFloat(maxChunk)  || 8,
      simulatedYears: parseInt(simYears, 10) || 20,
      numSimulations: ns,
      startingBalance: startingBalanceToPayload(startingBalance),
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
      portfolios,
      settingsPortfolios,
    }
    if (seed != null) reqBody.seed = seed

    try {
      const res = await fetch('/api/montecarlo/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(reqBody),
      })
      const data: MonteCarloResults = addResultWarnings(await res.json(), mappingWarnings)
      if (!res.ok || data.error) {
        setError(data.error || `Server error ${res.status}`)
        setRunProgress(null)
        return
      }
      if (data.seed != null) setLastSeed(data.seed)
      setSelected(new Set())
      setResults(data)
      setRunProgress({
        phase: 'complete',
        phaseLabel: 'Complete',
        action: 'Simulation results are ready',
        progressLabel: 'Simulations',
        completed: data.numSimulations,
        total: data.numSimulations,
        currentStep: 7,
        totalSteps: 7,
        done: true,
        details: [
          { label: 'Portfolios', value: data.portfolios.length },
          { label: 'Curves', value: data.portfolios.reduce((sum, p) => sum + p.curves.length, 0) },
          { label: 'Simulations', value: data.numSimulations },
          { label: 'Seed', value: data.seed },
        ],
      })
      clearRunProgressSoon()
    } catch (e: any) {
      setError('Request failed: ' + e.message)
      setRunProgress(null)
    } finally {
      stopRunStatePolling()
      setRunning(false)
    }
  }

  // ── Import / Export ───────────────────────────────────────────────────────

  async function handleExport() {
    const exportBlocks = blocks.map(normalizeBlockSpreadInputs)
    if (exportBlocks.some((block, i) => block !== blocks[i])) setBlocks(exportBlocks)
    const portfolios = exportBlocks.map((b, i) => blockStateToAPIPortfolio(b, i))
    const code = await compressToCode(await withPortfolioExportDependencies({
      fromDate: fromDate || null, toDate: toDate || null,
      minChunkYears: parseFloat(minChunk) || 3, maxChunkYears: parseFloat(maxChunk) || 8,
      simulatedYears: parseInt(simYears, 10) || 20, numSimulations: parseInt(numSims, 10) || 500,
      startingBalance: startingBalanceToPayload(startingBalance),
      cashflow: cashflowToPayload(cashflowAmount, cashflowFrequency),
      portfolios,
    }, portfolios))
    setImportCode(code)
    try {
      await navigator.clipboard.writeText(code)
      showImportToast('Export code copied.')
    } catch (_) {
      showImportToast('Export code generated.')
    }
  }

  function applyImportedConfig(req: any) {
    if (req.fromDate) setFromDate(req.fromDate)
    if (req.toDate)   setToDate(req.toDate)
    const cashflowState = cashflowStateFromSettings(req)
    if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
    if (cashflowState.cashflowAmount != null) setCashflowAmount(cashflowState.cashflowAmount)
    if (cashflowState.cashflowFrequency != null) setCashflowFrequency(cashflowState.cashflowFrequency)
    if (req.minChunkYears  != null) setMinChunk(String(req.minChunkYears))
    if (req.maxChunkYears  != null) setMaxChunk(String(req.maxChunkYears))
    if (req.simulatedYears != null) setSimYears(String(req.simulatedYears))
    if (req.numSimulations != null) setNumSims(String(req.numSimulations))
    if (req.portfolios) {
      setBlocks(prev => {
        const next = [...prev]
        req.portfolios.forEach((p: any, i: number) => {
          if (i < 3) next[i] = configToBlockState(p, configToBlockInputLabel(p, i))
        })
        return next
      })
    }
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const req: any = await decompressFromCode(importCode.trim())
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
    } catch (_) {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }

  async function confirmPendingImport(previewArg?: ImportDependencyPreview, configArg?: any) {
    if (!pendingImport || importDependencyApplying) return
    const preview = previewArg ?? pendingImport.preview
    const config = configArg ?? pendingImport.config
    setImportDependencyApplying(true)
    setImportDependencyError('')
    try {
      await applyImportDependencyPreview(preview)
      refreshSaved()
      applyImportedConfig(config)
      showImportToast('Import complete.')
      setPendingImport(null)
      setConfigError('')
    } catch (e: any) {
      setImportDependencyError(e?.message || String(e))
    } finally {
      setImportDependencyApplying(false)
    }
  }

  // ── Curve toggle ──────────────────────────────────────────────────────────

  const allKeys = results
    ? results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`))
    : []
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0

  function toggleCurve(key: string, checked: boolean) {
    setSelected(prev => { const s = new Set(prev); checked ? s.add(key) : s.delete(key); return s })
  }
  function toggleAll(checked: boolean) { setSelected(checked ? new Set(allKeys) : new Set()) }

  const updateBlock = useCallback((i: number, s: BlockState) =>
    setBlocks(prev => { const n = [...prev]; n[i] = s; return n }),
    [],
  )
  const refreshSaved = useCallback(() => savedBarRef.current?.refresh(), [])

  // ── Stats helpers ─────────────────────────────────────────────────────────

  function cellValue(curve: McCurve, pctIdx: number, metric: string, pp: any): string {
    switch (metric) {
      case 'END_VALUE':   return money(pp.endValue)
      case 'CAGR':        return pct(pp.cagr)
      case 'MAX_DD':      return pct(curve.maxDdPercentiles[pctIdx])
      case 'LONGEST_DD':  return dur(curve.longestDrawdownPercentiles[pctIdx])
      case 'ANN_VOL':     return pct(curve.volatilityPercentiles[pctIdx])
      case 'SHARPE':      return fmt2(curve.sharpePercentiles[pctIdx])
      case 'ULCER_INDEX': return pct(curve.ulcerPercentiles[pctIdx])
      case 'UPI':         return fmt2(curve.upiPercentiles[pctIdx])
      default:            return '–'
    }
  }

  // ── Theme ─────────────────────────────────────────────────────────────────

  const theme = getChartTheme()
  const { isDark, gridColor, textColor } = theme
  const makeTooltip = (valueFmt: (v: number) => string, labelFmt?: (l: any) => string) =>
    makeRechartsTooltip(theme, valueFmt, labelFmt)
  const runProgressPercent = runProgress && runProgress.total > 0
    ? Math.min(100, Math.max(0, (runProgress.completed / runProgress.total) * 100))
    : null
  const runProgressDetails = runProgress
    ? runProgress.details.filter(detail =>
        detail.label !== runProgress.progressLabel &&
        detail.value !== undefined &&
        detail.value !== null &&
        String(detail.value) !== '',
      )
    : []
  return (
    <div className="container">
      <BacktestPageHeader active="/montecarlo" />
      <div className={`config-status config-status-${importToast.type}${importToast.msg ? ' visible' : ''}`}>
        {importToast.msg}
      </div>

      <div className="backtest-form-card">
        <ScenarioSetupControls
          idPrefix="mc"
          fromLabel="From Date (pool)"
          fromInputId="mc-from-date"
          fromDate={fromDate}
          toLabel="To Date (pool)"
          toInputId="mc-to-date"
          toDate={toDate}
          importInputId="mc-import-code"
          importCode={importCode}
          configError={configError}
          dateRangeError={dateRangeError}
          startingBalance={startingBalance}
          cashflowAmount={cashflowAmount}
          cashflowFrequency={cashflowFrequency}
          onFromDateChange={setFromDate}
          onToDateChange={setToDate}
          onImportCodeChange={setImportCode}
          onImport={handleImport}
          onExport={handleExport}
          onStartingBalanceChange={setStartingBalance}
          onCashflowAmountChange={setCashflowAmount}
          onCashflowFrequencyChange={setCashflowFrequency}
        />

        <TickerMappingControl
          idPrefix="mc"
          value={tickerMappingSettings}
          onChange={setTickerMappingSettings}
          onExportCode={setImportCode}
          onToast={showImportToast}
        />

        <div className="backtest-section mc-params-grid">
          {[
            { label: 'Min Chunk Years', val: minChunk, set: setMinChunk, id: 'mc-min-chunk' },
            { label: 'Max Chunk Years', val: maxChunk, set: setMaxChunk, id: 'mc-max-chunk' },
            { label: 'Simulated Years', val: simYears, set: setSimYears, id: 'mc-sim-years' },
            { label: 'Simulations',     val: numSims,  set: setNumSims,  id: 'mc-num-sims'  },
          ].map(({ label, val, set, id }) => (
            <div key={id} className="backtest-date-field">
              <label htmlFor={id}>{label}</label>
              <input type="text" id={id} inputMode="decimal" value={val} onChange={e => set(e.target.value)} />
            </div>
          ))}
        </div>

        <SavedPortfolioBlocksSection
          savedBarRef={savedBarRef}
          blocks={blocks}
          onBlockChange={updateBlock}
          onSavedRefresh={refreshSaved}
          showSavedStrategies
        />

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <RunButton label="Run Simulation" running={running} disabled={running || !!dateRangeError} onClick={() => doRun(null)} />
          {lastSeed != null && (
            <button
              className="run-backtest-btn" type="button"
              style={{ opacity: 0.75 }} disabled={running || !!dateRangeError}
              onClick={() => doRun(lastSeed)}
            >
              Rerun (same seed)
            </button>
          )}
        </div>

        {runProgress && (
          <div className="mc-run-progress-slot">
            <div
              className={`mc-run-progress is-floating${runProgress.done ? ' done' : ''}`}
              role="status"
              aria-live="polite"
            >
              <div className="mc-run-progress-header">
                <div className="mc-run-progress-title">
                  <strong>{runProgress.phaseLabel}</strong>
                  <span>{runProgress.action}</span>
                </div>
                {runProgress.currentStep > 0 && runProgress.totalSteps > 0 && (
                  <span className="mc-run-progress-step">
                    Step {runProgress.currentStep}/{runProgress.totalSteps}
                  </span>
                )}
              </div>
              {runProgressPercent != null && (
                <div className="mc-run-progress-bar" aria-label={`${runProgress.phaseLabel} progress`}>
                  <div style={{ width: `${runProgressPercent}%` }} />
                </div>
              )}
              <div className="mc-run-progress-details">
                {runProgress.total > 0 && (
                  <span>
                    <span>{runProgress.progressLabel}</span>
                    <strong>{runProgress.completed.toLocaleString()} / {runProgress.total.toLocaleString()}</strong>
                  </span>
                )}
                {runProgressDetails.map((detail, i) => (
                  <span key={`${detail.label}-${i}`}>
                    <span>{detail.label}</span>
                    <strong>{progressValue(detail.value)}</strong>
                  </span>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {!!results?.warnings?.length && (
        <div className="backtest-error">
          {results.warnings.map((warning, i) => (
            <div key={i}>{warning}</div>
          ))}
        </div>
      )}

      {results && chartData && (
        <>
          <div style={{ opacity: 0.7, margin: '0.5rem 0 1rem', lineHeight: 1.5 }}>
            <p style={{ fontSize: 'var(--font-size-md)', margin: 0 }}>
              ⚠︎ Each metric is independently ranked across all simulations.
            </p>
            <p style={{ fontSize: '0.82em', margin: 0 }}>
              At P50, CAGR shows the median CAGR outcome, Max DD shows the median worst drawdown (ranked by drawdown), and so on.
            </p>
            <p style={{ fontSize: '0.82em', margin: 0 }}>
              The chart always shows the path at the selected percentile when simulations are ranked by CAGR.
            </p>
          </div>

          {/* Percentile tabs */}
          <div className="mc-percentile-tabs">
            {PERCENTILE_LIST.map(p => (
              <button
                key={p} type="button"
                className={`mc-pct-tab${p === percentile ? ' active' : ''}`}
                onClick={() => setPercentile(p)}
              >
                {p}th
              </button>
            ))}
          </div>

          {/* Stats table */}
          <div className="stats-container">
            <div className="mc-stats-header">
              Results at <strong>{percentile}th percentile</strong> ({results.numSimulations} simulations, {results.simulatedYears}yr)
            </div>
            <table className="backtest-stats-table">
              <thead>
                <tr>
                  <th>
                    <input
                      type="checkbox"
                      checked={allChecked}
                      ref={el => { if (el) el.indeterminate = anyChecked && !allChecked }}
                      onChange={e => toggleAll(e.target.checked)}
                    />
                  </th>
                  <th>Curve</th>
                  {MC_COLS.map(c => <th key={c.metric}>{c.label}</th>)}
                </tr>
              </thead>
              <tbody>
                {results.portfolios.flatMap((portfolio, pi) =>
                  portfolio.curves.map((curve, ci) => {
                    const key = `${pi}-${ci}`
                    const pctIdx = PERCENTILE_LIST.indexOf(percentile)
                    const pp = curve.percentilePaths.find(p => p.percentile === percentile)
                    if (!pp) return null
                    return (
                      <tr key={key}>
                        <td><input type="checkbox" checked={selected.has(key)} onChange={e => toggleCurve(key, e.target.checked)} /></td>
                        <td style={{ color: PALETTE[pi % PALETTE.length][ci % PALETTE[pi % PALETTE.length].length] }}>{portfolio.label} – {curve.label}</td>
                        {MC_COLS.map(c => <td key={c.metric}>{cellValue(curve, pctIdx, c.metric, pp)}</td>)}
                      </tr>
                    )
                  })
                )}
                {/* All-percentiles section for single curve */}
                {chartData.effectiveCurves.length === 1 && (() => {
                  const { portfolio, curve } = chartData.effectiveCurves[0]
                  const totalCols = 2 + MC_COLS.length
                  return (
                    <>
                      <tr className="mc-pct-separator">
                        <td colSpan={totalCols}>All percentiles – <strong>{portfolio.label} – {curve.label}</strong></td>
                      </tr>
                      {PERCENTILE_LIST.map((p, idx) => {
                        const pp = curve.percentilePaths.find(x => x.percentile === p)
                        if (!pp) return null
                        return (
                          <tr key={p} className={p === percentile ? 'mc-active-pct' : ''}>
                            <td />
                            <td style={{ color: PERCENTILE_COLORS[idx], fontWeight: p === 50 ? 'bold' : 'normal' }}>P{p}</td>
                            {MC_COLS.map(c => <td key={c.metric}>{cellValue(curve, idx, c.metric, pp)}</td>)}
                          </tr>
                        )
                      })}
                    </>
                  )
                })()}
              </tbody>
            </table>
          </div>

          {/* MC Chart */}
          <div className="backtest-chart-container" ref={chartContainerRef}>
            <button
              className={`chart-scale-toggle${logScale ? ' active' : ''}`}
              type="button"
              onClick={() => setLogScale(l => !l)}
            >
              Log
            </button>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.rows} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                <XAxis
                  dataKey="x"
                  type="number"
                  domain={[0, chartData.targetDays]}
                  ticks={chartData.yearTicks}
                  tickFormatter={v => `Y${v / 252}`}
                  tick={{ fill: textColor, fontSize: 11 }}
                />
                <YAxis
                  scale={logScale ? 'log' : 'linear'}
                  domain={['auto', 'auto']}
                  allowDataOverflow={logScale}
                  tick={{ fill: textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)}
                  width={72}
                />
                <Tooltip content={makeTooltip(v => '$' + v.toFixed(0), v => `Year ${(Number(v) / 252).toFixed(1)}`)} />
                <Legend wrapperStyle={{ color: textColor, fontSize: '0.78em' }} />
                {(() => {
                  const numPts = (chartData.targetDays ?? 0) + 1
                  const pxPt   = chartWidth / Math.max(numPts - 1, 1)
                  return chartData.datasets.map(ds => (
                  <Line
                    key={ds.label}
                    dataKey={ds.label}
                    stroke={ds.color}
                    strokeWidth={ds.strokeWidth}
                    strokeDasharray={scaleDash(ds.strokeDasharray, pxPt, 6)}
                    dot={false}
                    activeDot={{ r: 4 }}
                    connectNulls={false}
                    isAnimationActive={false}
                  />
                ))
                })()}
                <Brush
                  dataKey="x"
                  height={26}
                  stroke={gridColor}
                  fill={isDark ? '#1a1a1a' : '#f8f8f8'}
                  travellerWidth={6}
                  tickFormatter={v => `Y${Math.round(v / 252)}`}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
      {pendingImport && (
        <ImportDependenciesDialog
          preview={pendingImport.preview}
          config={pendingImport.config as Record<string, unknown>}
          applying={importDependencyApplying}
          error={importDependencyError}
          onCancel={() => setPendingImport(null)}
          onConfirm={confirmPendingImport}
        />
      )}
    </div>
  )
}
