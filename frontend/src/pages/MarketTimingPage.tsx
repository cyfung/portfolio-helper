import { useEffect, useMemo, useRef, useState } from 'react'
import { BacktestPageHeader } from '@/components/backtest/CommonBacktestSections'
import type { SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
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
import { validateDateRange } from '@/lib/dateRange'
import {
  blockStateToAPIPortfolio, configToBlockState, emptyBlock, type BlockState,
} from '@/types/backtest'
import type {
  InterestMode,
  MarketTimingResponse,
  ReferenceSource,
  UsCapePoint,
  WorldCapePoint,
} from '@/types/marketTiming'

const WORLD_CAPE_CSV_URL = `${import.meta.env.BASE_URL}data/world-cape-history.csv`
const US_CAPE_CSV_URL = `${import.meta.env.BASE_URL}data/us-cape-history.csv`
const DEFAULT_DRAWDOWN_CONFIGS = '5-0, 10-0, 15-0, 20-0, 25-0'

type MarketTimingImportConfig = {
  portfolio?: { label?: string } & Record<string, any>
  fromDate?: unknown
  toDate?: unknown
  drawdownConfigs?: unknown
  drawdownPcts?: unknown
  referenceSource?: unknown
  referenceTicker?: unknown
  interestMode?: unknown
  annualSpread?: unknown
  fixedAnnualRate?: unknown
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
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const dateRangeError = validateDateRange(fromDate, toDate)

  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate) setToDate(req.toDate)
        if (req.portfolios?.[0]) setPortfolio(configToBlockState(req.portfolios[0], req.portfolios[0].label || ''))
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
    const code = await compressToCode({
      fromDate,
      toDate,
      drawdownConfigs,
      referenceSource,
      referenceTicker,
      interestMode,
      annualSpread,
      fixedAnnualRate,
      portfolio: blockStateToAPIPortfolio(portfolio, 0),
    })
    setImportCode(code)
  }

  async function handleImport() {
    setConfigError('')
    try {
      const payload = await decompressFromCode(importCode.trim()) as MarketTimingImportConfig
      if (!payload?.portfolio) throw new Error('Invalid config')
      setFromDate(String(payload.fromDate ?? ''))
      setToDate(String(payload.toDate ?? ''))
      setDrawdownConfigs(String(payload.drawdownConfigs ?? payload.drawdownPcts ?? DEFAULT_DRAWDOWN_CONFIGS))
      setReferenceSource(payload.referenceSource === 'TICKER' ? 'TICKER' : 'PORTFOLIO')
      setReferenceTicker(String(payload.referenceTicker ?? 'VT'))
      setInterestMode(payload.interestMode === 'FIXED' ? 'FIXED' : 'SPREAD')
      setAnnualSpread(String(payload.annualSpread ?? '1.5'))
      setAnnualSpreadTouched(false)
      setFixedAnnualRate(String(payload.fixedAnnualRate ?? '5'))
      setPortfolio(configToBlockState(payload.portfolio, payload.portfolio.label || ''))
    } catch (e: any) {
      setConfigError(e?.message || 'Invalid config code')
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
      if (thresholds.length === 0) throw new Error('Enter at least one drawdown config')

      const savedPortfolios = await fetchSavedPortfolios()
      const apiPortfolio = resolvedBlockStateToAPIPortfolio(portfolio, 0, savedPortfolios)
      const runAnnualSpread = interestMode === 'SPREAD'
        ? normalizeNumberInput(annualSpread, DEFAULT_SPREAD_PERCENT, { min: 0 })
        : annualSpread
      if (runAnnualSpread !== annualSpread) setAnnualSpread(runAnnualSpread)

      const response = await fetch('/api/market-timing/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          saveSettings: false,
          fromDate,
          toDate,
          portfolio: { ...apiPortfolio, marginStrategies: [], rebalanceStrategies: [] },
          drawdownConfigs: thresholds,
          referenceSource,
          referenceTicker: referenceSource === 'TICKER' ? referenceTicker.trim().toUpperCase() : undefined,
          interestMode,
          annualSpread: interestMode === 'SPREAD'
            ? percentInputToFraction(runAnnualSpread, DEFAULT_SPREAD_PERCENT, { min: 0 })
            : undefined,
          fixedAnnualRate: interestMode === 'FIXED' ? (parseFloat(fixedAnnualRate) || 0) / 100 : undefined,
        }),
      })
      const data = await response.json()
      if (!response.ok || data.error) throw new Error(data.error || data.message || `HTTP ${response.status}`)
      setResults(data)
    } catch (e: any) {
      setError(e?.message || 'Run failed')
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
    </div>
  )
}
