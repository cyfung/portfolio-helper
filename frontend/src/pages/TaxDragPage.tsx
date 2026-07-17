import { useEffect, useState } from 'react'
import { ConfigButton, HeaderRight, PageNavTabs, PrivacyToggleButton, ThemeToggle } from '@/components/Layout'
import {
  loadTickerMappingSettings,
  notifyTickerMappingsChanged,
  saveTickerMappingSettings,
  type TickerMappingSet,
} from '@/lib/tickerMappings'

interface TaxDragAnnualResult {
  year: number
  fromDate: string
  toDate: string
  days: number
  startPrice: number
  endPrice: number
  dividend: number
  withheldTax: number
  priceReturn: number
  grossReturn: number
  afterTaxReturn: number
  grossEndingValue: number
  afterTaxEndingValue: number
}

interface TaxDragTickerResult {
  ticker: string
  startDate: string | null
  endDate: string | null
  days: number
  withholdingTaxPct: number
  cagrGross: number | null
  cagrAfterTax: number | null
  cagrDrag: number | null
  effectiveExpenseRatio: number | null
  backtestExpenseRatio: number | null
  endingValueGross: number | null
  endingValueAfterTax: number | null
  totalDividend: number
  totalWithheldTax: number
  annual: TaxDragAnnualResult[]
  commonPeriod?: boolean
  error: string | null
}

interface TaxDragResponse {
  results: TaxDragTickerResult[]
}

interface TaxDragInputs {
  taxPct: string
  tickers: string
  commonPeriodTickers: string
}

const TAX_DRAG_SETTINGS_ENDPOINT = '/api/tax-drag/settings'
const DEFAULT_TAX_PCT = '30'
const DEFAULT_TICKERS = 'SPY VTI VXUS'
const DEFAULT_COMMON_PERIOD_TICKERS = ''

function fmtPct(v: number | null | undefined, digits = 2) {
  return typeof v === 'number' && Number.isFinite(v) ? `${(v * 100).toFixed(digits)}%` : '-'
}

function fmtNum(v: number | null | undefined, digits = 2) {
  return typeof v === 'number' && Number.isFinite(v) ? v.toFixed(digits) : '-'
}

function fmtMoney(v: number | null | undefined) {
  return typeof v === 'number' && Number.isFinite(v)
    ? v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
    : '-'
}

function parseTickers(value: string): string[] {
  return value
    .split(/\s+/)
    .map(t => t.trim().toUpperCase())
    .filter(Boolean)
}

function newMappingId() {
  return `mapping-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function newMappingSetId() {
  return `set-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function formatExpenseModifier(value: number) {
  const pct = Math.round(value * 100 * 1000000) / 1000000
  return Object.is(pct, -0) ? '0' : String(pct)
}

function resultKey(result: TaxDragTickerResult, index: number) {
  return `${result.commonPeriod ? 'common' : 'main'}-${index}-${result.ticker}`
}

function defaultTaxDragInputs(): TaxDragInputs {
  return {
    taxPct: DEFAULT_TAX_PCT,
    tickers: DEFAULT_TICKERS,
    commonPeriodTickers: DEFAULT_COMMON_PERIOD_TICKERS,
  }
}

function normalizeTaxDragInputs(value: unknown): TaxDragInputs {
  const parsed = value && typeof value === 'object' ? value as Partial<TaxDragInputs> : null
  return {
    taxPct: typeof parsed?.taxPct === 'string' ? parsed.taxPct : DEFAULT_TAX_PCT,
    tickers: typeof parsed?.tickers === 'string' ? parsed.tickers : DEFAULT_TICKERS,
    commonPeriodTickers: typeof parsed?.commonPeriodTickers === 'string'
      ? parsed.commonPeriodTickers
      : DEFAULT_COMMON_PERIOD_TICKERS,
  }
}

async function fetchTaxDragInputsFromServer() {
  try {
    const res = await fetch(TAX_DRAG_SETTINGS_ENDPOINT)
    if (!res.ok) return defaultTaxDragInputs()
    return normalizeTaxDragInputs(await res.json())
  } catch {
    return defaultTaxDragInputs()
  }
}

async function saveTaxDragInputsToServer(inputs: TaxDragInputs) {
  const res = await fetch(TAX_DRAG_SETTINGS_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(normalizeTaxDragInputs(inputs)),
  })
  if (!res.ok) throw new Error(`Failed to save tax drag inputs: HTTP ${res.status}`)
}

export default function TaxDragPage() {
  const [taxDragInputsLoaded, setTaxDragInputsLoaded] = useState(false)
  const [taxPct, setTaxPct] = useState(DEFAULT_TAX_PCT)
  const [tickers, setTickers] = useState(DEFAULT_TICKERS)
  const [commonPeriodTickers, setCommonPeriodTickers] = useState(DEFAULT_COMMON_PERIOD_TICKERS)
  const [results, setResults] = useState<TaxDragTickerResult[]>([])
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [mappingStatus, setMappingStatus] = useState('')

  useEffect(() => {
    let cancelled = false
    fetchTaxDragInputsFromServer().then(inputs => {
      if (cancelled) return
      setTaxPct(inputs.taxPct)
      setTickers(inputs.tickers)
      setCommonPeriodTickers(inputs.commonPeriodTickers)
      setTaxDragInputsLoaded(true)
    })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (!taxDragInputsLoaded) return
    void saveTaxDragInputsToServer({ taxPct, tickers, commonPeriodTickers }).catch(() => {})
  }, [taxDragInputsLoaded, taxPct, tickers, commonPeriodTickers])

  async function calculate() {
    setError('')
    setMappingStatus('')
    setResults([])

    const withholdingTaxPct = parseFloat(taxPct)
    const parsedTickers = parseTickers(tickers)
    const parsedCommonPeriodTickers = parseTickers(commonPeriodTickers)

    if (!Number.isFinite(withholdingTaxPct) || withholdingTaxPct < 0 || withholdingTaxPct > 100) {
      setError('Enter a withholding tax between 0 and 100%.')
      return
    }
    if (parsedTickers.length === 0 && parsedCommonPeriodTickers.length === 0) {
      setError('Enter at least one ticker.')
      return
    }

    setLoading(true)
    try {
      const res = await fetch('/api/tax-drag/calculate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          withholdingTaxPct,
          tickers: parsedTickers,
          commonPeriodTickers: parsedCommonPeriodTickers,
        }),
      })
      if (!res.ok) {
        const text = await res.text()
        throw new Error(text || `HTTP ${res.status}`)
      }
      const data = await res.json() as TaxDragResponse
      setResults(data.results || [])
      setExpanded(Object.fromEntries((data.results || []).map((r, index) => [resultKey(r, index), true])))
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Tax drag calculation failed.')
    } finally {
      setLoading(false)
    }
  }

  function createTickerMapping() {
    setMappingStatus('')
    const ignoredCommonPeriodRows = results.filter(result => result.commonPeriod).length
    const mappingRows = results
      .filter(result => (
        !result.commonPeriod &&
        !result.error &&
        typeof result.backtestExpenseRatio === 'number' &&
        Number.isFinite(result.backtestExpenseRatio)
      ))
      .map(result => {
        const ticker = result.ticker.trim().toUpperCase()
        const expense = formatExpenseModifier(result.backtestExpenseRatio ?? 0)
        return {
          id: newMappingId(),
          from: ticker,
          to: `${ticker} E=${expense}`,
          mode: 'replaceAll' as const,
          applyTo: 'ticker' as const,
        }
      })

    if (mappingRows.length === 0) {
      setMappingStatus(
        ignoredCommonPeriodRows > 0
          ? 'No valid non-common-period Backtest E= values to map.'
          : 'No valid Backtest E= values to map.',
      )
      return
    }

    const settings = loadTickerMappingSettings()
    const name = `Tax Drag E=${taxPct.trim() || 'custom'}%`
    const existing = settings.savedSets.find(set => set.name.trim().toLowerCase() === name.toLowerCase())
    const savedSet: TickerMappingSet = {
      id: existing?.id ?? newMappingSetId(),
      name,
      mappings: mappingRows,
      updatedAt: new Date().toISOString(),
    }
    const nextSettings = {
      ...settings,
      selectedSetId: savedSet.id,
      savedSets: [
        ...settings.savedSets.filter(set => set.name.trim().toLowerCase() !== name.toLowerCase()),
        savedSet,
      ],
    }
    saveTickerMappingSettings(nextSettings)
    notifyTickerMappingsChanged()
    setMappingStatus(
      `Created ${name} with ${mappingRows.length} row${mappingRows.length === 1 ? '' : 's'}.` +
      (ignoredCommonPeriodRows > 0 ? ` Ignored ${ignoredCommonPeriodRows} common-period row${ignoredCommonPeriodRows === 1 ? '' : 's'}.` : ''),
    )
  }

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/tax-drag" />
        </div>
        <HeaderRight>
          <PrivacyToggleButton />
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      <div className="tax-drag-card">
        <div className="tax-drag-controls">
          <label>
            <span>Withholding Tax</span>
            <div className="tax-drag-percent-input">
              <input
                type="number"
                min={0}
                max={100}
                step="any"
                value={taxPct}
                onChange={e => setTaxPct(e.target.value)}
              />
              <span>%</span>
            </div>
          </label>
          <label className="tax-drag-tickers">
            <span>Tickers</span>
            <input
              type="text"
              value={tickers}
              onChange={e => setTickers(e.target.value)}
              placeholder="SPY VTI VXUS"
            />
          </label>
          <label className="tax-drag-tickers">
            <span>Common Period Tickers</span>
            <input
              type="text"
              value={commonPeriodTickers}
              onChange={e => setCommonPeriodTickers(e.target.value)}
              placeholder="SCHD VIG DGRO"
            />
          </label>
          <button className="calculate-btn tax-drag-run" type="button" onClick={calculate} disabled={loading}>
            {loading ? 'Calculating...' : 'Calculate'}
          </button>
        </div>

        <p className="cashflow-hint">
          Uses Yahoo raw close prices and Yahoo dividend events directly. Common-period tickers are recalculated over their overlapping Yahoo date range.
        </p>

        {error && <div className="backtest-error">{error}</div>}
      </div>

      {results.length > 0 && (
        <div className="tax-drag-results">
          <div className="tax-drag-result-actions">
            <button className="backtest-config-btn" type="button" onClick={createTickerMapping}>
              Create E= Mapping
            </button>
            {mappingStatus && <span className="tax-drag-mapping-status">{mappingStatus}</span>}
          </div>
          <table className="backtest-stats-table tax-drag-summary">
            <thead>
              <tr>
                <th>Ticker</th>
                <th>Range</th>
                <th>Days</th>
                <th>Gross CAGR</th>
                <th>After-tax CAGR</th>
                <th>Drag</th>
                <th>Effective ER</th>
                <th>Backtest E=</th>
                <th>Gross $1</th>
                <th>After-tax $1</th>
                <th>Dividends</th>
                <th>Tax Withheld</th>
              </tr>
            </thead>
            <tbody>
              {results.map((result, index) => {
                const key = resultKey(result, index)
                return (
                <tr
                  key={key}
                  className={[
                    result.error ? 'tax-drag-error-row' : '',
                    result.commonPeriod ? 'tax-drag-common-row' : '',
                  ].filter(Boolean).join(' ') || undefined}
                >
                  <td>
                    <button
                      type="button"
                      className="tax-drag-expand"
                      onClick={() => setExpanded(s => ({ ...s, [key]: !s[key] }))}
                      disabled={!!result.error}
                      aria-label={`${expanded[key] ? 'Collapse' : 'Expand'} ${result.ticker}`}
                    >
                      {expanded[key] ? '-' : '+'}
                    </button>
                    <span className="tax-drag-symbol">{result.ticker}</span>
                    {result.commonPeriod && <span className="tax-drag-period-badge">Common</span>}
                  </td>
                  <td>{result.error || `${result.startDate} to ${result.endDate}`}</td>
                  <td>{result.error ? '-' : result.days.toLocaleString()}</td>
                  <td>{fmtPct(result.cagrGross)}</td>
                  <td>{fmtPct(result.cagrAfterTax)}</td>
                  <td>{fmtPct(result.cagrDrag)}</td>
                  <td>{fmtPct(result.effectiveExpenseRatio)}</td>
                  <td>{fmtPct(result.backtestExpenseRatio)}</td>
                  <td>{fmtNum(result.endingValueGross, 4)}</td>
                  <td>{fmtNum(result.endingValueAfterTax, 4)}</td>
                  <td>{fmtMoney(result.totalDividend)}</td>
                  <td>{fmtMoney(result.totalWithheldTax)}</td>
                </tr>
              )})}
            </tbody>
          </table>

          {results.map((result, index) => ({ result, key: resultKey(result, index) }))
            .filter(row => !row.result.error && expanded[row.key])
            .map(({ result, key }) => (
            <section key={key} className={`tax-drag-annual-section${result.commonPeriod ? ' tax-drag-common-section' : ''}`}>
              <div className="backtest-chart-heading">
                <h2 className="backtest-chart-title">
                  {result.ticker} annual returns
                  {result.commonPeriod && <span className="tax-drag-period-badge">Common</span>}
                </h2>
                <span className="tax-drag-range">{result.startDate} to {result.endDate}</span>
              </div>
              <div className="tax-drag-table-wrap">
                <table className="backtest-stats-table tax-drag-annual">
                  <thead>
                    <tr>
                      <th>Year</th>
                      <th>Range</th>
                      <th>Days</th>
                      <th>Start</th>
                      <th>End</th>
                      <th>Dividend</th>
                      <th>Tax</th>
                      <th>Price Return</th>
                      <th>Gross Return</th>
                      <th>After-tax Return</th>
                      <th>Gross $1</th>
                      <th>After-tax $1</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.annual.map(row => (
                      <tr key={row.year}>
                        <td>{row.year}</td>
                        <td>{row.fromDate} to {row.toDate}</td>
                        <td>{row.days.toLocaleString()}</td>
                        <td>{fmtMoney(row.startPrice)}</td>
                        <td>{fmtMoney(row.endPrice)}</td>
                        <td>{fmtMoney(row.dividend)}</td>
                        <td>{fmtMoney(row.withheldTax)}</td>
                        <td>{fmtPct(row.priceReturn)}</td>
                        <td>{fmtPct(row.grossReturn)}</td>
                        <td>{fmtPct(row.afterTaxReturn)}</td>
                        <td>{fmtNum(row.grossEndingValue, 4)}</td>
                        <td>{fmtNum(row.afterTaxEndingValue, 4)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  )
}
