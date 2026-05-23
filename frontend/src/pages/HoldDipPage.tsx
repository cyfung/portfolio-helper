import { useEffect, useMemo, useRef, useState } from 'react'
import { Download } from 'lucide-react'
import {
  Brush, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { BacktestPageHeader, RunButton } from '@/components/backtest/CommonBacktestSections'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { useChartTheme } from '@/lib/chartTheme'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import { compressToCode, decompressFromCode } from '@/lib/compress'
import { fmt2, money, pct } from '@/lib/statsFormatters'
import { DEFAULT_SPREAD_PERCENT, isValidNumberInput, normalizeNumberInput, percentInputToFraction } from '@/lib/numberInputs'
import {
  blockStateToAPIPortfolio, cashflowStateFromSettings, configToBlockState, emptyBlock, PALETTE,
  startingBalanceToPayload, type BlockState,
} from '@/types/backtest'
import { fetchSavedPortfolios, resolvedBlockStateToAPIPortfolio } from '@/lib/portfolioRefs'

type InterestMode = 'SPREAD' | 'FIXED'
type ReferenceSource = 'PORTFOLIO' | 'TICKER'

interface HoldDipPoint {
  date: string
  value?: number | null
  triggerDate?: string | null
  daysToTrigger?: number | null
  referenceDrawdown?: number | null
}

interface HoldDipSummary {
  totalPoints: number
  triggeredPoints: number
  bestValue?: number | null
  worstValue?: number | null
  averageValue?: number | null
  medianValue?: number | null
  winRate?: number | null
  averageDaysToTrigger?: number | null
}

interface HoldDipResult {
  drawdownPct: number
  points: HoldDipPoint[]
  summary: HoldDipSummary
}

interface HoldDipResponse {
  referenceLabel: string
  referencePoints: { date: string; value: number }[]
  results: HoldDipResult[]
  error?: string
}

interface WorldCapePoint {
  date: string
  worldCape: number
  sourceMethod: string
}

interface UsCapePoint {
  date: string
  usCape: number
}

const WORLD_CAPE_CSV_URL = `${import.meta.env.BASE_URL}data/world-cape-history.csv`
const US_CAPE_CSV_URL = `${import.meta.env.BASE_URL}data/us-cape-history.csv`

function parseDrawdownPercents(value: string) {
  return value
    .split(/[,\s]+/)
    .map(s => parseFloat(s.trim()))
    .filter(Number.isFinite)
    .filter(v => v > 0 && v < 100)
    .map(v => v / 100)
}

function formatDays(days?: number | null) {
  if (days == null || !Number.isFinite(days)) return '-'
  if (days < 365) return `${Math.round(days)}d`
  return `${fmt2(days / 365.25)}y`
}

function splitCsvLine(line: string) {
  const fields: string[] = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        field += '"'
        i++
      } else {
        inQuotes = !inQuotes
      }
    } else if (ch === ',' && !inQuotes) {
      fields.push(field)
      field = ''
    } else {
      field += ch
    }
  }
  fields.push(field)
  return fields
}

function sourceMethodLabel(method: string) {
  if (method === 'US_SHILLER_PROXY') return 'US Shiller proxy'
  if (method.startsWith('SYNTHETIC_EP_BLEND')) return 'Synthetic world CAPE'
  if (method === 'SIBLIS_FREE_ANCHOR') return 'Siblis world CAPE'
  if (method === 'RA_CURRENT_REFERENCE') return 'RA current reference'
  return method
}

export default function HoldDipPage() {
  const [portfolio, setPortfolio] = useState<BlockState>(emptyBlock(0))
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [startingBalance, setStartingBalance] = useState('10000')
  const [drawdownPcts, setDrawdownPcts] = useState('5, 10, 15, 20, 25')
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
  const [results, setResults] = useState<HoldDipResponse | null>(null)
  const [worldCapePoints, setWorldCapePoints] = useState<WorldCapePoint[]>([])
  const [worldCapeError, setWorldCapeError] = useState('')
  const [usCapePoints, setUsCapePoints] = useState<UsCapePoint[]>([])
  const [usCapeError, setUsCapeError] = useState('')
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const theme = useChartTheme()

  useEffect(() => {
    fetch('/api/backtest/settings')
      .then(r => r.json())
      .then((req: any) => {
        if (req.fromDate) setFromDate(req.fromDate)
        if (req.toDate) setToDate(req.toDate)
        const cashflowState = cashflowStateFromSettings(req)
        if (cashflowState.startingBalance != null) setStartingBalance(cashflowState.startingBalance)
        if (req.portfolios?.[0]) setPortfolio(configToBlockState(req.portfolios[0], req.portfolios[0].label || ''))
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    fetch(WORLD_CAPE_CSV_URL)
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.text()
      })
      .then(text => {
        const rows = text
          .trim()
          .split(/\r?\n/)
          .slice(1)
          .map(splitCsvLine)
          .map(cols => ({
            date: cols[0],
            worldCape: Number(cols[1]),
            sourceMethod: cols[8],
          }))
          .filter(row => row.date && Number.isFinite(row.worldCape))
        setWorldCapePoints(rows)
        setWorldCapeError('')
      })
      .catch(() => setWorldCapeError('World CAPE CSV could not be loaded'))
  }, [])

  useEffect(() => {
    fetch(US_CAPE_CSV_URL)
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.text()
      })
      .then(text => {
        const rows = text
          .trim()
          .split(/\r?\n/)
          .slice(1)
          .map(splitCsvLine)
          .map(cols => ({
            date: cols[0],
            usCape: Number(cols[1]),
          }))
          .filter(row => row.date && Number.isFinite(row.usCape))
        setUsCapePoints(rows)
        setUsCapeError('')
      })
      .catch(() => setUsCapeError('US CAPE CSV could not be loaded'))
  }, [])

  function refreshSaved() {
    savedBarRef.current?.refresh()
  }

  function handleExport() {
    setConfigError('')
    const payload = {
      fromDate,
      toDate,
      startingBalance,
      drawdownPcts,
      referenceSource,
      referenceTicker,
      interestMode,
      annualSpread,
      fixedAnnualRate,
      portfolio: blockStateToAPIPortfolio(portfolio, 0),
    }
    setImportCode(compressToCode(payload))
  }

  function handleImport() {
    setConfigError('')
    try {
      const payload = decompressFromCode(importCode)
      if (!payload?.portfolio) throw new Error('Invalid config')
      setFromDate(payload.fromDate ?? '')
      setToDate(payload.toDate ?? '')
      setStartingBalance(String(payload.startingBalance ?? '10000'))
      setDrawdownPcts(String(payload.drawdownPcts ?? '5, 10, 15, 20, 25'))
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
    setRunning(true)
    setError('')
    setResults(null)
    try {
      const thresholds = parseDrawdownPercents(drawdownPcts)
      if (thresholds.length === 0) throw new Error('Enter at least one drawdown percent')
      const savedPortfolios = await fetchSavedPortfolios()
      const apiPortfolio = resolvedBlockStateToAPIPortfolio(portfolio, 0, savedPortfolios)
      const runAnnualSpread = interestMode === 'SPREAD'
        ? normalizeNumberInput(annualSpread, DEFAULT_SPREAD_PERCENT, { min: 0 })
        : annualSpread
      if (runAnnualSpread !== annualSpread) setAnnualSpread(runAnnualSpread)
      const body = {
        saveSettings: false,
        fromDate,
        toDate,
        startingBalance: startingBalanceToPayload(startingBalance),
        portfolio: { ...apiPortfolio, marginStrategies: [], rebalanceStrategies: [] },
        drawdownPcts: thresholds,
        referenceSource,
        referenceTicker: referenceSource === 'TICKER' ? referenceTicker.trim().toUpperCase() : undefined,
        interestMode,
        annualSpread: interestMode === 'SPREAD' ? percentInputToFraction(runAnnualSpread, DEFAULT_SPREAD_PERCENT, { min: 0 }) : undefined,
        fixedAnnualRate: interestMode === 'FIXED' ? (parseFloat(fixedAnnualRate) || 0) / 100 : undefined,
      }
      const res = await fetch('/api/hold-dip/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json()
      if (!res.ok || data.error) throw new Error(data.error || data.message || `HTTP ${res.status}`)
      setResults(data)
    } catch (e: any) {
      setError(e?.message || 'Run failed')
    } finally {
      setRunning(false)
    }
  }

  const chartData = useMemo(() => {
    if (!results?.results.length) return null
    const dates = results.results[0].points.map(p => p.date)
    const referenceByDate = new Map(results.referencePoints.map(p => [p.date, p.value]))
    const rows = dates.map((date, i) => {
      const row: Record<string, any> = { x: date }
      row.reference = referenceByDate.get(date)
      results.results.forEach((result, ri) => {
        const point = result.points[i]
        row[`dd${ri}`] = point?.value ?? undefined
        row[`dd${ri}Trigger`] = point?.triggerDate
        row[`dd${ri}Days`] = point?.daysToTrigger
        row[`dd${ri}RefDd`] = point?.referenceDrawdown
      })
      return row
    })
    return { rows }
  }, [results])

  const worldCapeChartData = useMemo(() => worldCapePoints.map(point => ({
    x: point.date,
    usProxyCape: point.sourceMethod === 'US_SHILLER_PROXY' ? point.worldCape : undefined,
    syntheticCape: point.sourceMethod.startsWith('SYNTHETIC_EP_BLEND') ? point.worldCape : undefined,
    siblisCape: point.sourceMethod === 'SIBLIS_FREE_ANCHOR' ? point.worldCape : undefined,
    currentReferenceCape: point.sourceMethod === 'RA_CURRENT_REFERENCE' ? point.worldCape : undefined,
    source: sourceMethodLabel(point.sourceMethod),
  })), [worldCapePoints])

  const worldCapeSummary = useMemo(() => {
    if (!worldCapePoints.length) return null
    const values = worldCapePoints.map(p => p.worldCape)
    const latest = worldCapePoints[worldCapePoints.length - 1]
    return {
      latest,
      startDate: worldCapePoints[0].date,
      endDate: latest.date,
      min: Math.min(...values),
      max: Math.max(...values),
      count: worldCapePoints.length,
    }
  }, [worldCapePoints])

  const usCapeChartData = useMemo(() => usCapePoints.map(point => ({
    x: point.date,
    usCape: point.usCape,
  })), [usCapePoints])

  const usCapeSummary = useMemo(() => {
    if (!usCapePoints.length) return null
    const values = usCapePoints.map(p => p.usCape)
    const latest = usCapePoints[usCapePoints.length - 1]
    return {
      latest,
      startDate: usCapePoints[0].date,
      endDate: latest.date,
      min: Math.min(...values),
      max: Math.max(...values),
      count: usCapePoints.length,
    }
  }, [usCapePoints])

  const tooltip = useMemo(() => makeRechartsTooltip(theme, (v: number) => money(v)), [theme])
  const capeTooltip = useMemo(() => makeRechartsTooltip(theme, (v: number) => fmt2(v)), [theme])

  return (
    <div className="container">
      <BacktestPageHeader active="/hold-dip" />

      <div className="backtest-form-card">
        <div className="backtest-section backtest-config-row">
          <DateFieldWithQuickSelect label="From Date" inputId="hold-dip-from-date" value={fromDate} onChange={setFromDate} />
          <DateFieldWithQuickSelect label="To Date" inputId="hold-dip-to-date" value={toDate} onChange={setToDate} />
          <div className="backtest-config-controls">
            <label htmlFor="hold-dip-import-code">Config Code</label>
            <div className="backtest-config-group">
              <input id="hold-dip-import-code" type="text" spellCheck={false} placeholder="Paste code..." value={importCode} onChange={e => setImportCode(e.target.value)} />
              <button className="backtest-config-btn" type="button" onClick={handleImport}>Import</button>
              <button className="backtest-config-btn" type="button" onClick={handleExport}>Export</button>
              {configError && <div className="backtest-config-error">{configError}</div>}
            </div>
          </div>
        </div>

        <div className="backtest-section backtest-config-row">
          <div>
            <label htmlFor="hold-dip-starting-balance">Notional</label>
            <input id="hold-dip-starting-balance" type="number" min="0" step="100" value={startingBalance} onChange={e => setStartingBalance(e.target.value)} />
          </div>
          <div>
            <label htmlFor="hold-dip-dd-pcts">Drawdown %</label>
            <input id="hold-dip-dd-pcts" type="text" value={drawdownPcts} onChange={e => setDrawdownPcts(e.target.value)} />
          </div>
          <div>
            <label htmlFor="hold-dip-reference-source">Reference</label>
            <select id="hold-dip-reference-source" value={referenceSource} onChange={e => setReferenceSource(e.target.value as ReferenceSource)}>
              <option value="PORTFOLIO">Portfolio</option>
              <option value="TICKER">Ticker</option>
            </select>
          </div>
          {referenceSource === 'TICKER' && (
            <div>
              <label htmlFor="hold-dip-reference-ticker">Ticker</label>
              <input id="hold-dip-reference-ticker" type="text" value={referenceTicker} onChange={e => setReferenceTicker(e.target.value)} />
            </div>
          )}
          <div>
            <label htmlFor="hold-dip-interest-mode">Interest</label>
            <select id="hold-dip-interest-mode" value={interestMode} onChange={e => setInterestMode(e.target.value as InterestMode)}>
              <option value="SPREAD">EFFR + spread</option>
              <option value="FIXED">Fixed rate</option>
            </select>
          </div>
          <div>
            <label htmlFor="hold-dip-interest-rate">{interestMode === 'SPREAD' ? 'Spread %' : 'Fixed %'}</label>
            <input
              id="hold-dip-interest-rate"
              type="number"
              step="0.05"
              value={interestMode === 'SPREAD' ? annualSpread : fixedAnnualRate}
              onChange={e => interestMode === 'SPREAD' ? setAnnualSpread(e.target.value) : setFixedAnnualRate(e.target.value)}
              onBlur={() => { if (interestMode === 'SPREAD') setAnnualSpreadTouched(true) }}
              className={interestMode === 'SPREAD' && annualSpreadTouched && !isValidNumberInput(annualSpread, { min: 0 }) ? 'input-error' : undefined}
              aria-invalid={interestMode === 'SPREAD' && annualSpreadTouched && !isValidNumberInput(annualSpread, { min: 0 })}
              title={interestMode === 'SPREAD' && annualSpreadTouched && !isValidNumberInput(annualSpread, { min: 0 }) ? 'Enter a valid non-negative spread percent' : undefined}
            />
          </div>
        </div>

        <SavedPortfoliosBar ref={savedBarRef} />
        <div className="portfolio-blocks">
          <PortfolioBlock idx={0} value={portfolio} onChange={setPortfolio} onSavedRefresh={refreshSaved} />
        </div>

        <RunButton label="Run Hold the Dip" running={running} disabled={running} onClick={handleRun} />
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {results && chartData && (
        <>
          <div className="stats-container">
            <table className="backtest-stats-table">
              <thead>
                <tr>
                  <th>DD</th>
                  <th>Triggered</th>
                  <th>Avg P/L</th>
                  <th>Median P/L</th>
                  <th>Best</th>
                  <th>Worst</th>
                  <th title="Wins divided by wins plus losses. Neutral zero P/L cases are excluded.">Win/Loss Rate</th>
                  <th>Avg Wait</th>
                </tr>
              </thead>
              <tbody>
                {results.results.map(result => (
                  <tr key={result.drawdownPct}>
                    <td>{pct(result.drawdownPct)}</td>
                    <td>{result.summary.triggeredPoints}/{result.summary.totalPoints}</td>
                    <td>{result.summary.averageValue == null ? '-' : money(result.summary.averageValue)}</td>
                    <td>{result.summary.medianValue == null ? '-' : money(result.summary.medianValue)}</td>
                    <td>{result.summary.bestValue == null ? '-' : money(result.summary.bestValue)}</td>
                    <td>{result.summary.worstValue == null ? '-' : money(result.summary.worstValue)}</td>
                    <td>{result.summary.winRate == null ? '-' : pct(result.summary.winRate)}</td>
                    <td>{formatDays(result.summary.averageDaysToTrigger)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Buy and Hold P/L Until Reference Drawdown</div>
          </div>
          <div className="backtest-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData.rows} syncId="hold-dip" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(chartData.rows.length / 8))} />
                <YAxis yAxisId="pnl" tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => '$' + Number(v).toFixed(0)} width={72} />
                <YAxis
                  yAxisId="reference"
                  orientation="right"
                  tick={{ fill: theme.textColor, fontSize: 11 }}
                  tickFormatter={v => '$' + Number(v).toFixed(0)}
                  width={72}
                />
                <Tooltip content={tooltip} />
                <Legend />
                <Line
                  yAxisId="reference"
                  dataKey="reference"
                  name={`Reference - ${results.referenceLabel || 'Portfolio'}`}
                  stroke={theme.textColor}
                  strokeWidth={1.8}
                  strokeDasharray="4 3"
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
                {results.results.map((result, i) => (
                  <Line
                    key={result.drawdownPct}
                    yAxisId="pnl"
                    dataKey={`dd${i}`}
                    name={`${pct(result.drawdownPct)} DD`}
                    stroke={PALETTE[i % PALETTE.length][0]}
                    strokeWidth={2}
                    dot={false}
                    activeDot={{ r: 4 }}
                    connectNulls={false}
                    isAnimationActive={false}
                    type="monotone"
                  />
                ))}
                <Brush dataKey="x" height={26} stroke={theme.gridColor} fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}

      {worldCapeError && <div className="backtest-error">{worldCapeError}</div>}

      {worldCapeSummary && worldCapeChartData.length > 0 && (
        <>
          <div className="backtest-chart-heading world-cape-heading">
            <div className="backtest-chart-title">World CAPE History</div>
            <a className="h-btn subtle world-cape-download" href={WORLD_CAPE_CSV_URL} download>
              <Download size={14} aria-hidden="true" />
              <span>CSV</span>
            </a>
          </div>
          <div className="world-cape-meta" aria-label="World CAPE dataset summary">
            <span>{worldCapeSummary.startDate} to {worldCapeSummary.endDate}</span>
            <span>{worldCapeSummary.count} observations</span>
            <span>Latest {fmt2(worldCapeSummary.latest.worldCape)} on {worldCapeSummary.latest.date}</span>
            <span>Range {fmt2(worldCapeSummary.min)}-{fmt2(worldCapeSummary.max)}</span>
          </div>
          <div className="backtest-chart-container world-cape-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={worldCapeChartData} syncId="world-cape" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(worldCapeChartData.length / 10))} />
                <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => Number(v).toFixed(0)} width={48} />
                <Tooltip content={capeTooltip} />
                <Legend />
                <Line
                  dataKey="usProxyCape"
                  name="US Shiller proxy"
                  stroke={theme.textColor}
                  strokeWidth={1.8}
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
                <Line
                  dataKey="syntheticCape"
                  name="Synthetic world CAPE"
                  stroke={PALETTE[0][0]}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
                <Line
                  dataKey="siblisCape"
                  name="Siblis world CAPE"
                  stroke={PALETTE[2][0]}
                  strokeWidth={2.4}
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
                <Line
                  dataKey="currentReferenceCape"
                  name="RA current reference"
                  stroke={PALETTE[4 % PALETTE.length][0]}
                  strokeWidth={0}
                  dot={{ r: 4, strokeWidth: 2 }}
                  activeDot={{ r: 5 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
                <Brush dataKey="x" height={26} stroke={theme.gridColor} fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}

      {usCapeError && <div className="backtest-error">{usCapeError}</div>}

      {usCapeSummary && usCapeChartData.length > 0 && (
        <>
          <div className="backtest-chart-heading world-cape-heading">
            <div className="backtest-chart-title">US CAPE History</div>
            <a className="h-btn subtle world-cape-download" href={US_CAPE_CSV_URL} download>
              <Download size={14} aria-hidden="true" />
              <span>CSV</span>
            </a>
          </div>
          <div className="world-cape-meta" aria-label="US CAPE dataset summary">
            <span>{usCapeSummary.startDate} to {usCapeSummary.endDate}</span>
            <span>{usCapeSummary.count} observations</span>
            <span>Latest {fmt2(usCapeSummary.latest.usCape)} on {usCapeSummary.latest.date}</span>
            <span>Range {fmt2(usCapeSummary.min)}-{fmt2(usCapeSummary.max)}</span>
          </div>
          <div className="backtest-chart-container world-cape-chart-container">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={usCapeChartData} syncId="us-cape" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(usCapeChartData.length / 10))} />
                <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => Number(v).toFixed(0)} width={48} />
                <Tooltip content={capeTooltip} />
                <Legend />
                <Line
                  dataKey="usCape"
                  name="US Shiller CAPE"
                  stroke={PALETTE[1 % PALETTE.length][0]}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
                <Brush dataKey="x" height={26} stroke={theme.gridColor} fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  )
}
