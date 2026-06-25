import { useEffect, useMemo, useRef, useState } from 'react'
import ImportDependenciesDialog from '@/components/backtest/ImportDependenciesDialog'
import { BacktestPageHeader } from '@/components/backtest/CommonBacktestSections'
import type { SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { useTransientToast } from '@/hooks/useTransientToast'
import { UsCapeHistoryChart, WorldCapeHistoryChart } from '@/components/marketTiming/CapeHistoryCharts'
import MarketTimingResultsCharts from '@/components/marketTiming/MarketTimingResultsCharts'
import MarketTimingSetupCard from '@/components/marketTiming/MarketTimingSetupCard'
import {
  makeCapeSummary,
  makeMarginComparisonChartData,
  makeMarketTimingChartData,
  makeMarketTimingLineStyles,
  makeReferenceDrawdownChartData,
  makeUsCapeChartData,
  makeWindowAverageChartData,
  makeWorldCapeChartData,
  parseDrawdownConfigs,
} from '@/lib/marketTiming/chartData'
import { parseUsCapeCsv, parseWorldCapeCsv } from '@/lib/marketTiming/capeCsv'
import { DEFAULT_SPREAD_PERCENT, normalizeNumberInput, percentInputToFraction } from '@/lib/numberInputs'
import { fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import {
  applyImportDependencyPreview,
  buildImportDependencyPreview,
  hasImportDependencyPreview,
  withPortfolioExportDependencies,
  type ImportDependencyPreview,
} from '@/lib/configImportExport'
import { validateDateRange } from '@/lib/dateRange'
import {
  blockStateToAPIPortfolio,
  configToBlockInputLabel,
  configToBlockState,
  emptyBlock,
  normalizeBlockSpreadInputs,
  type BlockState,
} from '@/types/backtest'
import type {
  DrawdownConfigInput,
  InterestMode,
  MarketTimingResponse,
  ReferenceSource,
  UsCapePoint,
  WorldCapePoint,
} from '@/types/marketTiming'

const WORLD_CAPE_CSV_URL = `${import.meta.env.BASE_URL}data/world-cape-history.csv`
const US_CAPE_CSV_URL = `${import.meta.env.BASE_URL}data/us-cape-history.csv`
const DEFAULT_DRAWDOWN_CONFIGS = '5-1, 10-1, 15-1, 20-1, 25-1'

type MarketTimingImportConfig = {
  portfolio?: { label?: string } & Record<string, unknown>
  portfolios?: ({ label?: string } & Record<string, unknown>)[]
  fromDate?: unknown
  toDate?: unknown
  drawdownConfigs?: unknown
  referenceSource?: unknown
  referenceTicker?: unknown
  interestMode?: unknown
  annualSpread?: unknown
  fixedAnnualRate?: unknown
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function compactNumber(value: number) {
  if (!Number.isFinite(value)) return ''
  return Number(value.toFixed(6)).toString()
}

function percentLikeToInput(value: unknown, fallback: string) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return compactNumber(Math.abs(value) <= 1 ? value * 100 : value)
  }
  if (typeof value === 'string') return value
  return fallback
}

function drawdownConfigToInput(config: DrawdownConfigInput) {
  const drawdownPct = Math.abs(config.drawdownPct) <= 1
    ? config.drawdownPct * 100
    : config.drawdownPct
  return `${compactNumber(drawdownPct)}-${Math.max(0, Math.floor(config.zeroWindowMonths || 0))}`
}

function drawdownConfigsToInput(value: unknown, fallback = DEFAULT_DRAWDOWN_CONFIGS) {
  if (typeof value === 'string') return value
  if (!Array.isArray(value)) return fallback

  const entries = value
    .map(item => {
      if (typeof item === 'number' && Number.isFinite(item)) {
        return drawdownConfigToInput({ drawdownPct: item, zeroWindowMonths: 0 })
      }
      if (!item || typeof item !== 'object') return null
      const config = item as Record<string, unknown>
      const drawdownPct = Number(config.drawdownPct)
      const zeroWindowMonths = Number(config.zeroWindowMonths ?? 0)
      if (!Number.isFinite(drawdownPct)) return null
      return drawdownConfigToInput({
        drawdownPct,
        zeroWindowMonths: Number.isFinite(zeroWindowMonths) ? zeroWindowMonths : 0,
      })
    })
    .filter((entry): entry is string => !!entry)

  return entries.length ? entries.join(', ') : fallback
}

async function fetchText(url: string) {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.text()
}

export default function MarketTimingPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [drawdownConfigs, setDrawdownConfigs] = useState(DEFAULT_DRAWDOWN_CONFIGS)
  const [referenceSource, setReferenceSource] = useState<ReferenceSource>('PORTFOLIO')
  const [referenceTicker, setReferenceTicker] = useState('VT')
  const [interestMode, setInterestMode] = useState<InterestMode>('SPREAD')
  const [annualSpread, setAnnualSpread] = useState('1.5')
  const [annualSpreadTouched, setAnnualSpreadTouched] = useState(false)
  const [fixedAnnualRate, setFixedAnnualRate] = useState('5')
  const [importCode, setImportCode] = useState('')
  const [configError, setConfigError] = useState('')
  const [pendingImport, setPendingImport] = useState<{ config: MarketTimingImportConfig; preview: ImportDependencyPreview } | null>(null)
  const [importDependencyApplying, setImportDependencyApplying] = useState(false)
  const [importDependencyError, setImportDependencyError] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const [results, setResults] = useState<MarketTimingResponse | null>(null)
  const [worldCapePoints, setWorldCapePoints] = useState<WorldCapePoint[]>([])
  const [worldCapeError, setWorldCapeError] = useState('')
  const [usCapePoints, setUsCapePoints] = useState<UsCapePoint[]>([])
  const [usCapeError, setUsCapeError] = useState('')
  const [normalizeWindowDayZero, setNormalizeWindowDayZero] = useState(true)
  const [marginComparisonResultIndex, setMarginComparisonResultIndex] = useState(0)
  const [marginComparisonBaseMargin, setMarginComparisonBaseMargin] = useState(0)
  const { toast: importToast, showToast: showImportToast } = useTransientToast()
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const dateRangeError = validateDateRange(fromDate, toDate)

  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: MarketTimingImportConfig) => {
        if (req.fromDate) setFromDate(String(req.fromDate))
        if (req.toDate) setToDate(String(req.toDate))
        if (req.drawdownConfigs != null) {
          setDrawdownConfigs(drawdownConfigsToInput(req.drawdownConfigs))
        }
        if (req.referenceSource != null) {
          setReferenceSource(req.referenceSource === 'TICKER' ? 'TICKER' : 'PORTFOLIO')
        }
        if (req.referenceTicker != null) setReferenceTicker(String(req.referenceTicker || 'VT'))
        if (req.interestMode != null) setInterestMode(req.interestMode === 'FIXED' ? 'FIXED' : 'SPREAD')
        if (req.annualSpread != null) {
          setAnnualSpread(percentLikeToInput(req.annualSpread, '1.5'))
          setAnnualSpreadTouched(false)
        }
        if (req.fixedAnnualRate != null) setFixedAnnualRate(percentLikeToInput(req.fixedAnnualRate, '5'))
        const cachedPortfolio = req.portfolios?.[0] ?? req.portfolio
        if (cachedPortfolio) setPortfolio(configToBlockState(cachedPortfolio, configToBlockInputLabel(cachedPortfolio, 0)))
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    fetchText(WORLD_CAPE_CSV_URL)
      .then(text => {
        setWorldCapePoints(parseWorldCapeCsv(text))
        setWorldCapeError('')
      })
      .catch(() => setWorldCapeError('World CAPE CSV could not be loaded'))
  }, [])

  useEffect(() => {
    fetchText(US_CAPE_CSV_URL)
      .then(text => {
        setUsCapePoints(parseUsCapeCsv(text))
        setUsCapeError('')
      })
      .catch(() => setUsCapeError('US CAPE CSV could not be loaded'))
  }, [])

  function refreshSaved() {
    savedBarRef.current?.refresh()
  }

  async function handleExport() {
    setConfigError('')
    const exportPortfolio = blockStateToAPIPortfolio(portfolio, 0)
    const code = await compressToCode(await withPortfolioExportDependencies({
      fromDate,
      toDate,
      drawdownConfigs,
      referenceSource,
      referenceTicker,
      interestMode,
      annualSpread,
      fixedAnnualRate,
      portfolio: exportPortfolio,
    }, [exportPortfolio]))
    setImportCode(code)
    try {
      await navigator.clipboard.writeText(code)
      showImportToast('Export code copied.')
    } catch {
      showImportToast('Export code generated.')
    }
  }

  function applyImportedConfig(payload: MarketTimingImportConfig) {
    setFromDate(String(payload.fromDate ?? ''))
    setToDate(String(payload.toDate ?? ''))
    setDrawdownConfigs(drawdownConfigsToInput(payload.drawdownConfigs))
    setReferenceSource(payload.referenceSource === 'TICKER' ? 'TICKER' : 'PORTFOLIO')
    setReferenceTicker(String(payload.referenceTicker ?? 'VT'))
    setInterestMode(payload.interestMode === 'FIXED' ? 'FIXED' : 'SPREAD')
    setAnnualSpread(percentLikeToInput(payload.annualSpread, '1.5'))
    setAnnualSpreadTouched(false)
    setFixedAnnualRate(percentLikeToInput(payload.fixedAnnualRate, '5'))
    if (payload.portfolio) setPortfolio(configToBlockState(payload.portfolio, configToBlockInputLabel(payload.portfolio, 0)))
  }

  async function handleImport() {
    setConfigError('')
    try {
      const payload = await decompressFromCode(importCode.trim()) as MarketTimingImportConfig
      if (!payload?.portfolio) {
        setConfigError('Invalid config')
        return
      }
      const preview = await buildImportDependencyPreview(payload as Record<string, unknown>)
      if (hasImportDependencyPreview(preview)) {
        setPendingImport({ config: payload, preview })
        setImportDependencyError('')
        return
      }
      applyImportedConfig(payload)
      showImportToast('Import complete.')
    } catch (e: unknown) {
      setConfigError(errorMessage(e, 'Invalid config code'))
    }
  }

  async function confirmPendingImport() {
    if (!pendingImport || importDependencyApplying) return
    setImportDependencyApplying(true)
    setImportDependencyError('')
    try {
      await applyImportDependencyPreview(pendingImport.preview)
      refreshSaved()
      applyImportedConfig(pendingImport.config)
      showImportToast('Import complete.')
      setPendingImport(null)
      setConfigError('')
    } catch (e: unknown) {
      setImportDependencyError(errorMessage(e, 'Unable to apply import dependencies'))
    } finally {
      setImportDependencyApplying(false)
    }
  }

  async function handleRun() {
    setError('')
    setResults(null)
    if (dateRangeError) {
      setError(dateRangeError)
      return
    }

    setRunning(true)
    try {
      const thresholds = parseDrawdownConfigs(drawdownConfigs)
      if (thresholds.length === 0) {
        setError('Enter at least one drawdown config')
        return
      }

      const runPortfolio = normalizeBlockSpreadInputs(portfolio)
      if (runPortfolio !== portfolio) setPortfolio(runPortfolio)
      const savedPortfolios = await fetchSavedPortfolios()
      const apiPortfolio = resolvedBlockStateToAPIPortfolio(runPortfolio, 0, savedPortfolios)
      const settingsPortfolio = blockStateToAPIPortfolio(runPortfolio, 0)
      const runAnnualSpread = interestMode === 'SPREAD'
        ? normalizeNumberInput(annualSpread, DEFAULT_SPREAD_PERCENT, { min: 0 })
        : annualSpread
      if (runAnnualSpread !== annualSpread) setAnnualSpread(runAnnualSpread)
      const runReferenceTicker = referenceTicker.trim().toUpperCase()
      const runFixedAnnualRate = (parseFloat(fixedAnnualRate) || 0) / 100

      const response = await fetch('/api/market-timing/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromDate: fromDate || null,
          toDate: toDate || null,
          portfolio: apiPortfolio,
          settingsPortfolio,
          drawdownConfigs: thresholds,
          referenceSource,
          referenceTicker: runReferenceTicker || 'VT',
          interestMode,
          annualSpread: percentInputToFraction(runAnnualSpread, DEFAULT_SPREAD_PERCENT, { min: 0 }),
          fixedAnnualRate: runFixedAnnualRate,
        }),
      })
      const data = await response.json()
      if (!response.ok || data.error) {
        setError(data.error || data.message || `HTTP ${response.status}`)
        return
      }
      setResults(data)
    } catch (e: unknown) {
      setError(errorMessage(e, 'Run failed'))
    } finally {
      setRunning(false)
    }
  }

  const chartData = useMemo(() => makeMarketTimingChartData(results), [results])
  const windowAverageChartData = useMemo(() => {
    if (!results?.results.length) return null
    return makeWindowAverageChartData(results.results, normalizeWindowDayZero)
  }, [results, normalizeWindowDayZero])
  const effectiveMarginComparisonIndex = results?.results.length
    ? Math.min(marginComparisonResultIndex, results.results.length - 1)
    : 0
  const marginComparisonResult = results?.results[effectiveMarginComparisonIndex]
  const marginComparisonChartData = useMemo(() => {
    if (!marginComparisonResult) return null
    return makeMarginComparisonChartData(
      marginComparisonResult,
      marginComparisonBaseMargin,
      normalizeWindowDayZero,
    )
  }, [marginComparisonResult, marginComparisonBaseMargin, normalizeWindowDayZero])
  const referenceDrawdownChartData = useMemo(() => makeReferenceDrawdownChartData(results), [results])
  const marketTimingLineStyles = useMemo(
    () => makeMarketTimingLineStyles(results?.results.length ?? 0),
    [results],
  )

  const worldCapeChartData = useMemo(() => makeWorldCapeChartData(worldCapePoints), [worldCapePoints])
  const worldCapeSummary = useMemo(
    () => makeCapeSummary(worldCapePoints, point => point.worldCape),
    [worldCapePoints],
  )
  const usCapeChartData = useMemo(() => makeUsCapeChartData(usCapePoints), [usCapePoints])
  const usCapeSummary = useMemo(
    () => makeCapeSummary(usCapePoints, point => point.usCape),
    [usCapePoints],
  )

  return (
    <div className="container">
      <BacktestPageHeader active="/market-timing" />
      <div className={`config-status config-status-${importToast.type}${importToast.msg ? ' visible' : ''}`}>
        {importToast.msg}
      </div>

      <MarketTimingSetupCard
        portfolio={portfolio}
        fromDate={fromDate}
        toDate={toDate}
        drawdownConfigs={drawdownConfigs}
        referenceSource={referenceSource}
        referenceTicker={referenceTicker}
        interestMode={interestMode}
        annualSpread={annualSpread}
        annualSpreadTouched={annualSpreadTouched}
        fixedAnnualRate={fixedAnnualRate}
        importCode={importCode}
        configError={configError}
        running={running}
        dateRangeError={dateRangeError}
        savedBarRef={savedBarRef}
        onPortfolioChange={setPortfolio}
        onFromDateChange={setFromDate}
        onToDateChange={setToDate}
        onDrawdownConfigsChange={setDrawdownConfigs}
        onReferenceSourceChange={setReferenceSource}
        onReferenceTickerChange={setReferenceTicker}
        onInterestModeChange={setInterestMode}
        onAnnualSpreadChange={setAnnualSpread}
        onAnnualSpreadTouched={() => setAnnualSpreadTouched(true)}
        onFixedAnnualRateChange={setFixedAnnualRate}
        onImportCodeChange={setImportCode}
        onImport={handleImport}
        onExport={handleExport}
        onRun={handleRun}
        onSavedRefresh={refreshSaved}
      />

      {error && <div className="backtest-error">{error}</div>}

      {results && chartData && (
        <MarketTimingResultsCharts
          results={results}
          chartData={chartData}
          windowAverageChartData={windowAverageChartData}
          marginComparisonChartData={marginComparisonChartData}
          referenceDrawdownChartData={referenceDrawdownChartData}
          marginComparisonResult={marginComparisonResult}
          effectiveMarginComparisonIndex={effectiveMarginComparisonIndex}
          marginComparisonBaseMargin={marginComparisonBaseMargin}
          normalizeWindowDayZero={normalizeWindowDayZero}
          lineStyles={marketTimingLineStyles}
          onNormalizeWindowDayZeroChange={setNormalizeWindowDayZero}
          onMarginComparisonIndexChange={setMarginComparisonResultIndex}
          onMarginComparisonBaseMarginChange={setMarginComparisonBaseMargin}
        />
      )}

      {worldCapeError && <div className="backtest-error">{worldCapeError}</div>}
      {worldCapeSummary && worldCapeChartData.length > 0 && (
        <WorldCapeHistoryChart
          csvUrl={WORLD_CAPE_CSV_URL}
          chartData={worldCapeChartData}
          summary={worldCapeSummary}
        />
      )}

      {usCapeError && <div className="backtest-error">{usCapeError}</div>}
      {usCapeSummary && usCapeChartData.length > 0 && (
        <UsCapeHistoryChart
          csvUrl={US_CAPE_CSV_URL}
          chartData={usCapeChartData}
          summary={usCapeSummary}
        />
      )}
      {pendingImport && (
        <ImportDependenciesDialog
          preview={pendingImport.preview}
          applying={importDependencyApplying}
          error={importDependencyError}
          onCancel={() => setPendingImport(null)}
          onConfirm={confirmPendingImport}
        />
      )}
    </div>
  )
}
