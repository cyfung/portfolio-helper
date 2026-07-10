import { useState } from 'react'
import { ConfigButton, HeaderRight, PageNavTabs, PrivacyToggleButton, ThemeToggle } from '@/components/Layout'

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
  error: string | null
}

interface TaxDragResponse {
  results: TaxDragTickerResult[]
}

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

export default function TaxDragPage() {
  const [taxPct, setTaxPct] = useState('30')
  const [tickers, setTickers] = useState('SPY VTI VXUS')
  const [results, setResults] = useState<TaxDragTickerResult[]>([])
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function calculate() {
    setError('')
    setResults([])

    const withholdingTaxPct = parseFloat(taxPct)
    const parsedTickers = parseTickers(tickers)

    if (!Number.isFinite(withholdingTaxPct) || withholdingTaxPct < 0 || withholdingTaxPct > 100) {
      setError('Enter a withholding tax between 0 and 100%.')
      return
    }
    if (parsedTickers.length === 0) {
      setError('Enter at least one ticker.')
      return
    }

    setLoading(true)
    try {
      const res = await fetch('/api/tax-drag/calculate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ withholdingTaxPct, tickers: parsedTickers }),
      })
      if (!res.ok) {
        const text = await res.text()
        throw new Error(text || `HTTP ${res.status}`)
      }
      const data = await res.json() as TaxDragResponse
      setResults(data.results || [])
      setExpanded(Object.fromEntries((data.results || []).map(r => [r.ticker, true])))
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Tax drag calculation failed.')
    } finally {
      setLoading(false)
    }
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
          <button className="calculate-btn tax-drag-run" type="button" onClick={calculate} disabled={loading}>
            {loading ? 'Calculating...' : 'Calculate'}
          </button>
        </div>

        <p className="cashflow-hint">
          Uses Yahoo raw close prices and Yahoo dividend events directly. Backtest E= is calibrated to the backtest modifier's 252-trading-day daily haircut.
        </p>

        {error && <div className="backtest-error">{error}</div>}
      </div>

      {results.length > 0 && (
        <div className="tax-drag-results">
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
              {results.map(result => (
                <tr key={result.ticker} className={result.error ? 'tax-drag-error-row' : undefined}>
                  <td>
                    <button
                      type="button"
                      className="tax-drag-expand"
                      onClick={() => setExpanded(s => ({ ...s, [result.ticker]: !s[result.ticker] }))}
                      disabled={!!result.error}
                      aria-label={`${expanded[result.ticker] ? 'Collapse' : 'Expand'} ${result.ticker}`}
                    >
                      {expanded[result.ticker] ? '-' : '+'}
                    </button>
                    <span className="tax-drag-symbol">{result.ticker}</span>
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
              ))}
            </tbody>
          </table>

          {results.filter(result => !result.error && expanded[result.ticker]).map(result => (
            <section key={result.ticker} className="tax-drag-annual-section">
              <div className="backtest-chart-heading">
                <h2 className="backtest-chart-title">{result.ticker} annual returns</h2>
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
