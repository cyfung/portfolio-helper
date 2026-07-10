import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { PageNavTabs, ConfigButton, HeaderRight, PrivacyToggleButton, ThemeToggle } from '@/components/Layout'
import PortfolioTabs from '@/components/portfolio/PortfolioTabs'
import IbkrConfigDialog from '@/components/portfolio/IbkrConfigDialog'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { useTransientToast } from '@/hooks/useTransientToast'
import type { PortfolioData } from '@/types/portfolio'

interface TradeRow {
  id: number
  tradeKey: string
  tradeDate: string
  tradeTime: string
  symbol: string
  side: string
  quantity: number
  price: number
  currency: string
  exchange: string
  assetCategory?: string
  commission: number | null
  commissionCurrency: string | null
  realizedPnl: number | null
}

type Period = '7D' | '30D' | '90D' | 'YTD' | '1Y' | 'All'
type TradesView = 'summary' | 'trades'

const PERIODS: Period[] = ['7D', '30D', '90D', 'YTD', '1Y', 'All']
const ISO_CURRENCIES = new Set([
  'AUD', 'CAD', 'CHF', 'CNH', 'CNY', 'EUR', 'GBP', 'HKD', 'JPY', 'MXN', 'NOK', 'NZD', 'SEK', 'SGD', 'USD',
])

function todayStr() {
  return new Date().toISOString().slice(0, 10)
}

function timeStr() {
  return new Date().toTimeString().slice(0, 8).replace(/:/g, '')
}

function defaultTradesPortfolioName(portfolioId: string | null | undefined) {
  return `Trades ${portfolioId ?? 'Portfolio'} ${todayStr()} ${timeStr()}`
}

function periodFrom(period: Period): string {
  const now = new Date()
  switch (period) {
    case '7D':  now.setDate(now.getDate() - 7); return now.toISOString().slice(0, 10)
    case '30D': now.setDate(now.getDate() - 30); return now.toISOString().slice(0, 10)
    case '90D': now.setDate(now.getDate() - 90); return now.toISOString().slice(0, 10)
    case 'YTD': return `${now.getFullYear()}-01-01`
    case '1Y':  now.setFullYear(now.getFullYear() - 1); return now.toISOString().slice(0, 10)
    case 'All': return ''
  }
}

function money(value: number | null | undefined, currency?: string | null) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-'
  const text = value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 })
  return currency ? `${text} ${currency}` : text
}

function money2(value: number | null | undefined, currency?: string | null) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-'
  const text = value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return currency ? `${text} ${currency}` : text
}

function numberFmt(value: number) {
  return value.toLocaleString(undefined, { maximumFractionDigits: 6 })
}

function isCurrencyTrade(trade: TradeRow) {
  const assetCategory = (trade.assetCategory ?? '').trim().toUpperCase()
  if (['CASH', 'FX', 'FOREX', 'CURRENCY'].includes(assetCategory)) return true
  const symbol = trade.symbol.trim().toUpperCase().replace(/[./]/g, '')
  return symbol.length === 6 && ISO_CURRENCIES.has(symbol.slice(0, 3)) && ISO_CURRENCIES.has(symbol.slice(3))
}

function signedQuantity(trade: TradeRow) {
  const qty = Math.abs(trade.quantity)
  return trade.side === 'SELL' ? -qty : qty
}

function grossTradeValue(trade: TradeRow) {
  return Math.abs(trade.quantity) * trade.price
}

function signedGrossTradeValue(trade: TradeRow) {
  return trade.side === 'SELL' ? -grossTradeValue(trade) : grossTradeValue(trade)
}

function commissionCashAdjustment(trade: TradeRow) {
  const commission = trade.commission ?? 0
  return commission <= 0 ? commission : -commission
}

function netCashAmount(trade: TradeRow) {
  return -signedQuantity(trade) * trade.price + commissionCashAdjustment(trade)
}

function tradeDateTime(trade: TradeRow) {
  return trade.tradeTime ? `${trade.tradeDate} ${trade.tradeTime}` : trade.tradeDate
}

function tradeDateRange(rows: TradeRow[]) {
  const dates = rows.map(trade => trade.tradeDate).filter(Boolean).sort()
  const first = dates[0] ?? ''
  const last = dates[dates.length - 1] ?? ''
  if (!first) return '-'
  return first === last ? first : `${first} - ${last}`
}

function tradeSelectionKey(trade: TradeRow) {
  return `${trade.id}:${trade.tradeKey}`
}

function parseExchangeSuffixes(raw: string | undefined) {
  const entries = (raw ?? '').split(',')
    .map(part => part.trim())
    .filter(Boolean)
    .map(part => {
      const eq = part.indexOf('=')
      if (eq < 0) return null
      return [part.slice(0, eq).trim().toUpperCase(), part.slice(eq + 1).trim()] as const
    })
    .filter((entry): entry is readonly [string, string] => !!entry && !!entry[0])
  return new Map(entries)
}

function portfolioSymbolForTrade(trade: TradeRow, exchangeSuffixes: Map<string, string>) {
  const rawSymbol = trade.symbol.trim().toUpperCase()
  if (!rawSymbol) return ''

  const exchange = trade.exchange.trim().toUpperCase()
  const suffix = exchangeSuffixes.get(exchange) ?? ''
  return suffix && !rawSymbol.endsWith(suffix.toUpperCase()) ? `${rawSymbol}${suffix}` : rawSymbol
}

function SelectionCheckbox({
  checked,
  indeterminate = false,
  label,
  onChange,
}: {
  checked: boolean
  indeterminate?: boolean
  label: string
  onChange: (checked: boolean) => void
}) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate
  }, [indeterminate])

  return (
    <input
      ref={ref}
      type="checkbox"
      aria-label={label}
      checked={checked}
      onChange={e => onChange(e.target.checked)}
      onClick={e => e.stopPropagation()}
    />
  )
}

export default function TradesPage() {
  const { slug } = useParams<{ slug?: string }>()
  const navigate = useNavigate()
  const loadPortfolioData = usePortfolioStore(s => s.loadPortfolioData)
  const portfolioId = usePortfolioStore(s => s.portfolioId)
  const allPortfolios = usePortfolioStore(s => s.allPortfolios)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showConfig, setShowConfig] = useState(false)
  const [period, setPeriod] = useState<Period>('30D')
  const [view, setView] = useState<TradesView>('summary')
  const [showCurrencyTrades, setShowCurrencyTrades] = useState(false)
  const [expandedSymbols, setExpandedSymbols] = useState<Set<string>>(new Set())
  const [selectedTradeKeys, setSelectedTradeKeys] = useState<Set<string>>(new Set())
  const [allTrades, setAllTrades] = useState<TradeRow[]>([])
  const [ingesting, setIngesting] = useState(false)
  const [creatingPortfolio, setCreatingPortfolio] = useState(false)
  const [notice, setNotice] = useState('')
  const [exchangeSuffixes, setExchangeSuffixes] = useState<Map<string, string>>(new Map())
  const xmlInputRef = useRef<HTMLInputElement>(null)
  const { toast, showToast } = useTransientToast()

  const from = periodFrom(period)
  const to = todayStr()

  useEffect(() => {
    setAllTrades([])
    setSelectedTradeKeys(new Set())
    setExpandedSymbols(new Set())
    setView('summary')
    setNotice('')
    if (xmlInputRef.current) xmlInputRef.current.value = ''
  }, [slug])

  useEffect(() => {
    if (!slug) {
      const stored = usePortfolioStore.getState().portfolioId
      if (stored) { navigate(`/trades/${stored}`, { replace: true }); return }
    }
    setLoading(true)
    setError('')
    const url = slug ? `/api/portfolio/data?portfolio=${slug}` : '/api/portfolio/data'
    fetch(url)
      .then(r => {
        if (r.status === 401) { window.location.href = '/admin'; throw new Error('Unauthorized') }
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json() as Promise<PortfolioData>
      })
      .then(data => {
        loadPortfolioData(data)
        if (!slug) navigate(`/trades/${data.portfolioId}`, { replace: true })
      })
      .catch((e: Error) => {
        if (e.message !== 'Unauthorized') setError(e.message)
      })
      .finally(() => setLoading(false))
  }, [slug, navigate, loadPortfolioData])

  useEffect(() => {
    fetch('/api/admin/config-values')
      .then(r => r.ok ? r.json() : null)
      .then((cfg: { exchangeSuffixes?: string } | null) => setExchangeSuffixes(parseExchangeSuffixes(cfg?.exchangeSuffixes)))
      .catch(() => setExchangeSuffixes(new Map()))
  }, [])

  const trades = useMemo(() => {
    return allTrades.filter(trade =>
      (!from || trade.tradeDate >= from) &&
      trade.tradeDate <= to &&
      (showCurrencyTrades || !isCurrencyTrade(trade))
    )
  }, [allTrades, from, to, showCurrencyTrades])

  const tradeGroups = useMemo(() => {
    const grouped = new Map<string, TradeRow[]>()
    trades.forEach(trade => {
      const symbol = trade.symbol || '(Unknown)'
      grouped.set(symbol, [...(grouped.get(symbol) ?? []), trade])
    })
    return [...grouped.entries()]
      .map(([symbol, rows]) => {
        const sortedRows = [...rows].sort((a, b) =>
          b.tradeDate.localeCompare(a.tradeDate) ||
          b.tradeTime.localeCompare(a.tradeTime) ||
          a.tradeKey.localeCompare(b.tradeKey)
        )
        return {
          symbol,
          rows: sortedRows,
          count: sortedRows.length,
          netQuantity: sortedRows.reduce((sum, trade) => sum + signedQuantity(trade), 0),
          netGross: sortedRows.reduce((sum, trade) => sum + signedGrossTradeValue(trade), 0),
          netCash: sortedRows.reduce((sum, trade) => sum + netCashAmount(trade), 0),
          netCashCurrency: sortedRows[0]?.currency,
          commissions: sortedRows.reduce((sum, trade) => sum + (trade.commission ?? 0), 0),
          commissionCurrency: sortedRows.find(trade => trade.commissionCurrency)?.commissionCurrency ?? sortedRows[0]?.currency,
          dateRange: tradeDateRange(sortedRows),
          exchange: [...new Set(sortedRows.map(trade => trade.exchange).filter(Boolean))].join(', '),
        }
      })
      .sort((a, b) => a.symbol.localeCompare(b.symbol))
  }, [trades])

  const selectedVisibleTrades = useMemo(() => {
    return trades.filter(trade => selectedTradeKeys.has(tradeSelectionKey(trade)))
  }, [selectedTradeKeys, trades])

  const selectedVisibleCount = selectedVisibleTrades.length

  function toggleGroup(symbol: string) {
    setExpandedSymbols(current => {
      const next = new Set(current)
      if (next.has(symbol)) next.delete(symbol)
      else next.add(symbol)
      return next
    })
  }

  function toggleTradeSelection(tradeKey: string, checked: boolean) {
    setSelectedTradeKeys(current => {
      const next = new Set(current)
      if (checked) next.add(tradeKey)
      else next.delete(tradeKey)
      return next
    })
  }

  function toggleTradeGroupSelection(rows: TradeRow[], checked: boolean) {
    setSelectedTradeKeys(current => {
      const next = new Set(current)
      rows.forEach(trade => {
        const key = tradeSelectionKey(trade)
        if (checked) next.add(key)
        else next.delete(key)
      })
      return next
    })
  }

  function isGroupSelected(rows: TradeRow[]) {
    return rows.length > 0 && rows.every(trade => selectedTradeKeys.has(tradeSelectionKey(trade)))
  }

  function isGroupPartiallySelected(rows: TradeRow[]) {
    return rows.some(trade => selectedTradeKeys.has(tradeSelectionKey(trade))) && !isGroupSelected(rows)
  }

  function buildPortfolioRowsFromTrades(rows: TradeRow[]) {
    const stockBySymbol = new Map<string, number>()
    const cashByCurrency = new Map<string, number>()

    rows.forEach(trade => {
      const symbol = portfolioSymbolForTrade(trade, exchangeSuffixes)
      const quantity = signedQuantity(trade)
      const currency = trade.currency.trim().toUpperCase() || 'USD'
      if (symbol && Number.isFinite(quantity)) {
        stockBySymbol.set(symbol, (stockBySymbol.get(symbol) ?? 0) + quantity)
      }

      const cashAmount = netCashAmount(trade)
      if (Number.isFinite(cashAmount)) {
        cashByCurrency.set(currency, (cashByCurrency.get(currency) ?? 0) + cashAmount)
      }
    })

    const stocks = [...stockBySymbol.entries()]
      .filter(([, amount]) => Number.isFinite(amount) && Math.abs(amount) > 1e-9)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([symbol, amount]) => ({ symbol, amount, targetWeight: 0, letf: '', groups: '' }))

    const cash = [...cashByCurrency.entries()]
      .filter(([, amount]) => Number.isFinite(amount) && Math.abs(amount) > 0.005)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([currency, amount]) => ({ label: `Trades Net Cash ${currency}`, currency, marginFlag: false, amount }))

    return { stocks, cash }
  }

  async function createPortfolioFromSelectedTrades() {
    const selectedRows = selectedVisibleTrades
    if (selectedRows.length === 0) return
    const defaultName = defaultTradesPortfolioName(portfolioId)
    const name = window.prompt('New portfolio name', defaultName)?.trim()
    if (!name) return

    const { stocks, cash } = buildPortfolioRowsFromTrades(selectedRows)
    if (stocks.length === 0 && cash.length === 0) {
      showToast('Selected trades do not produce any non-zero stock or cash rows.', 'error', 10000)
      return
    }

    setCreatingPortfolio(true)
    setNotice('')
    try {
      const createResponse = await fetch('/api/portfolio/create-with-contents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, stocks, cash, dividendStartDate: null }),
      })
      const createData = await createResponse.json().catch(() => ({})) as { slug?: string; message?: string }
      if (!createResponse.ok || !createData.slug) throw new Error(createData.message ?? `Create failed: HTTP ${createResponse.status}`)

      setNotice(`Created portfolio ${name} from ${selectedRows.length} selected trade(s).`)
      navigate(`/portfolio/${createData.slug}`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to create portfolio', 'error', 10000)
    } finally {
      setCreatingPortfolio(false)
    }
  }

  async function ingestFromIbkr() {
    if (!portfolioId) return
    setIngesting(true)
    setNotice('')
    try {
      const r = await fetch(`/api/trades/fetch/${portfolioId}`, { method: 'POST' })
      const d = await r.json() as { trades?: TradeRow[]; error?: string }
      if (!r.ok) throw new Error(d.error ?? `HTTP ${r.status}`)
      const fetchedTrades = d.trades ?? []
      setAllTrades(fetchedTrades)
      setSelectedTradeKeys(new Set(fetchedTrades.map(tradeSelectionKey)))
      setNotice(`Fetched ${d.trades?.length ?? 0} trade(s).`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to fetch trades', 'error', 10000)
    } finally {
      setIngesting(false)
    }
  }

  async function importXml(files: FileList) {
    if (!portfolioId) return
    setIngesting(true)
    setNotice('')
    try {
      let total = 0
      const imported: TradeRow[] = []
      for (let i = 0; i < files.length; i++) {
        const xml = await files[i].text()
        const r = await fetch(`/api/trades/ingest-xml/${portfolioId}`, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: xml,
        })
        const d = await r.json() as { trades?: TradeRow[]; error?: string }
        if (!r.ok) throw new Error(`${files[i].name}: ${d.error ?? `HTTP ${r.status}`}`)
        imported.push(...(d.trades ?? []))
        total += d.trades?.length ?? 0
      }
      setAllTrades(imported)
      setSelectedTradeKeys(new Set(imported.map(tradeSelectionKey)))
      setNotice(`Imported ${total} trade(s) from file(s).`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to import trades', 'error', 10000)
    } finally {
      setIngesting(false)
      if (xmlInputRef.current) xmlInputRef.current.value = ''
    }
  }

  const totals = useMemo(() => {
    const commissions = trades.reduce((sum, t) => sum + (t.commission ?? 0), 0)
    const hiddenCurrencyTrades = allTrades.filter(trade =>
      (!from || trade.tradeDate >= from) &&
      trade.tradeDate <= to &&
      isCurrencyTrade(trade)
    ).length
    return { count: trades.length, commissions, hiddenCurrencyTrades }
  }, [allTrades, from, to, trades])

  if (loading) return <div className="container"><div className="trades-page-status">Loading...</div></div>

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs
            active="/trades/"
            contextLabel={allPortfolios.find(p => p.slug === portfolioId)?.name}
            contextChildren={<PortfolioTabs basePath="/trades/" />}
          />
        </div>
        <HeaderRight>
          <PrivacyToggleButton />
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      <div className={`config-status config-status-${toast.type}${toast.msg ? ' visible' : ''}`}>
        {toast.msg}
      </div>

      <div className="trades-page">
        <div className="trades-toolbar">
          <div className="trades-periods">
            {PERIODS.map(p => (
              <button key={p} type="button" className={`backtest-config-btn${period === p ? ' active' : ''}`} onClick={() => setPeriod(p)}>
                {p}
              </button>
            ))}
            <label className="trades-filter-toggle">
              <input
                type="checkbox"
                checked={showCurrencyTrades}
                onChange={e => setShowCurrencyTrades(e.target.checked)}
              />
              <span>Currency trades</span>
            </label>
          </div>
          <div className="trades-actions">
            <button type="button" className="backtest-config-btn" onClick={() => setShowConfig(true)} disabled={ingesting}>IB Config</button>
            <button type="button" className="backtest-config-btn" onClick={ingestFromIbkr} disabled={ingesting}>
              {ingesting ? <>Fetching<span className="btn-spinner" /></> : 'Fetch Trades'}
            </button>
            <input
              ref={xmlInputRef}
              type="file"
              accept=".xml"
              multiple
              style={{ display: 'none' }}
              onChange={e => { if (e.target.files?.length) importXml(e.target.files) }}
            />
            <button type="button" className="backtest-config-btn" onClick={() => xmlInputRef.current?.click()} disabled={ingesting}>Import XML</button>
          </div>
        </div>

        {notice && <div className={`trades-notice${notice.startsWith('Error:') ? ' error' : ''}`}>{notice}</div>}
        {error && <div className="trades-notice error">{error}</div>}

        {allTrades.length === 0 ? (
          <div className="flex-query-guide">
            <div className="flex-query-guide-header">
              <span className="flex-query-guide-badge trades">Trades query</span>
              <h2>No trades loaded</h2>
            </div>
            <div className="flex-query-steps">
              <div><strong>1.</strong> In IBKR, open <strong>Reports - Flex Queries</strong> and create a query.</div>
              <div><strong>2.</strong> Enable the <strong>Trades</strong> section and set <strong>Format</strong> to <strong>XML</strong>.</div>
              <div><strong>3.</strong> Use the trade history date range you want, then save the query.</div>
              <div><strong>4.</strong> Generate or reuse a <strong>Flex Web Service token</strong>.</div>
              <div><strong>5.</strong> In <strong>IB Config</strong>, fill <strong>Flex Web Service Token</strong> and <strong>Trades Query ID</strong>, then click <strong>Fetch Trades</strong>.</div>
            </div>
            <div className="flex-query-table-wrap">
              <table className="flex-query-field-table">
                <thead>
                <tr>
                  <th>Flex section</th>
                  <th>Required fields</th>
                  <th>Used for</th>
                </tr>
                </thead>
                <tbody>
                <tr>
                  <td><code>Trades</code></td>
                  <td><code>tradeDate</code> or <code>reportDate</code>, <code>tradeTime</code> or <code>dateTime</code></td>
                  <td>Date and time columns</td>
                </tr>
                <tr>
                  <td><code>Trades</code></td>
                  <td><code>symbol</code>, <code>buySell</code>, <code>quantity</code>, <code>tradePrice</code></td>
                  <td>Main trade details</td>
                </tr>
                <tr>
                  <td><code>Trades</code></td>
                  <td><code>currency</code>, <code>exchange</code></td>
                  <td>Currency and venue display</td>
                </tr>
                <tr>
                  <td><code>Trades</code></td>
                  <td><code>assetCategory</code> or <code>secType</code></td>
                  <td>Currency-trade filter</td>
                </tr>
                <tr>
                  <td><code>Trades</code></td>
                  <td><code>ibCommission</code>, <code>ibCommissionCurrency</code>, <code>fifoPnlRealized</code></td>
                  <td>Commission and realized P/L columns</td>
                </tr>
                <tr>
                  <td><code>Trades</code></td>
                  <td><code>tradeID</code>, <code>transactionID</code>, or <code>ibExecID</code></td>
                  <td>Stable row identity</td>
                </tr>
                </tbody>
              </table>
            </div>
            <div className="flex-query-note">Trades are not saved to the database. Fetch from IBKR or import XML again when you need to reload the table.</div>
          </div>
        ) : (
          <>
            <div className="trades-summary">
              <span>{totals.count} trade(s)</span>
              {selectedVisibleCount > 0 && <span>{selectedVisibleCount} selected</span>}
              {!showCurrencyTrades && totals.hiddenCurrencyTrades > 0 && <span>{totals.hiddenCurrencyTrades} currency hidden</span>}
              <span>Commission: {money(totals.commissions, trades.find(t => t.commissionCurrency)?.commissionCurrency)}</span>
            </div>
            <div className="trades-result-controls">
              <div className="trades-view-tabs">
                <button type="button" className={`trades-view-tab${view === 'summary' ? ' active' : ''}`} onClick={() => setView('summary')}>Summary</button>
                <button type="button" className={`trades-view-tab${view === 'trades' ? ' active' : ''}`} onClick={() => setView('trades')}>Trades</button>
              </div>
              <button
                type="button"
                className="backtest-config-btn"
                onClick={createPortfolioFromSelectedTrades}
                disabled={creatingPortfolio || selectedVisibleCount === 0}
              >
                {creatingPortfolio ? <>Creating<span className="btn-spinner" /></> : 'Create Portfolio'}
              </button>
            </div>
            <div className="trades-table-wrap">
              <table className="trades-table">
                <thead>
                <tr>
                  <th className="trade-select-col" />
                  <th>Date / Time</th>
                  <th>Side</th>
                  <th>Symbol</th>
                  <th>Qty</th>
                  <th>Price</th>
                  <th>Net Cash</th>
                  <th>Commission</th>
                  <th>Exchange</th>
                </tr>
                </thead>
                <tbody>
                {trades.length === 0 ? (
                  <tr><td colSpan={9} className="trades-page-status">No trades in this period.</td></tr>
                ) : view === 'summary' ? tradeGroups.map(group => (
                  <Fragment key={group.symbol}>
                    <tr
                      className={`trade-group-row${isGroupSelected(group.rows) ? ' selected' : ''}${isGroupPartiallySelected(group.rows) ? ' partial-selected' : ''}`}
                      onClick={() => toggleGroup(group.symbol)}
                      aria-expanded={expandedSymbols.has(group.symbol)}
                    >
                      <td className="trade-select-col">
                        <SelectionCheckbox
                          checked={isGroupSelected(group.rows)}
                          indeterminate={isGroupPartiallySelected(group.rows)}
                          label={`Select ${group.symbol} group`}
                          onChange={checked => toggleTradeGroupSelection(group.rows, checked)}
                        />
                      </td>
                      <td>{group.dateRange}</td>
                      <td>
                        <button
                          type="button"
                          className="trade-group-toggle"
                          onClick={e => { e.stopPropagation(); toggleGroup(group.symbol) }}
                          aria-label={`${expandedSymbols.has(group.symbol) ? 'Collapse' : 'Expand'} ${group.symbol} trades`}
                        >
                          {expandedSymbols.has(group.symbol) ? '−' : '+'}
                        </button>
                        <span className="trade-group-count">{group.count} trade(s)</span>
                      </td>
                      <td className="trade-symbol trade-group-symbol">{group.symbol}</td>
                      <td className="num">{numberFmt(group.netQuantity)}</td>
                      <td className="num">{Math.abs(group.netQuantity) > 1e-9 ? money2(Math.abs(group.netGross / group.netQuantity), group.netCashCurrency) : '-'}</td>
                      <td className={`num cash-flow ${group.netCash < 0 ? 'negative' : 'positive'}`}>{money2(group.netCash, group.netCashCurrency)}</td>
                      <td className="num">{money(group.commissions, group.commissionCurrency)}</td>
                      <td>{group.exchange || '-'}</td>
                    </tr>
                    {expandedSymbols.has(group.symbol) && group.rows.map(trade => (
                      <tr
                        key={tradeSelectionKey(trade)}
                        className={selectedTradeKeys.has(tradeSelectionKey(trade)) ? 'selected' : ''}
                        onClick={() => toggleTradeSelection(tradeSelectionKey(trade), !selectedTradeKeys.has(tradeSelectionKey(trade)))}
                      >
                        <td className="trade-select-col">
                          <SelectionCheckbox
                            checked={selectedTradeKeys.has(tradeSelectionKey(trade))}
                            label={`Select ${trade.symbol} trade ${tradeDateTime(trade)}`}
                            onChange={checked => toggleTradeSelection(tradeSelectionKey(trade), checked)}
                          />
                        </td>
                        <td>{tradeDateTime(trade)}</td>
                        <td><span className={`trade-side ${trade.side === 'SELL' ? 'sell' : 'buy'}`}>{trade.side || '-'}</span></td>
                        <td className="trade-symbol">{trade.symbol}</td>
                        <td className="num">{numberFmt(signedQuantity(trade))}</td>
                        <td className="num">{money2(trade.price, trade.currency)}</td>
                        <td className={`num cash-flow ${netCashAmount(trade) < 0 ? 'negative' : 'positive'}`}>{money2(netCashAmount(trade), trade.currency)}</td>
                        <td className="num">{money(trade.commission, trade.commissionCurrency ?? trade.currency)}</td>
                        <td>{trade.exchange || '-'}</td>
                      </tr>
                    ))}
                  </Fragment>
                )) : trades.map(trade => (
                  <tr
                    key={tradeSelectionKey(trade)}
                    className={selectedTradeKeys.has(tradeSelectionKey(trade)) ? 'selected' : ''}
                    onClick={() => toggleTradeSelection(tradeSelectionKey(trade), !selectedTradeKeys.has(tradeSelectionKey(trade)))}
                  >
                    <td className="trade-select-col">
                      <SelectionCheckbox
                        checked={selectedTradeKeys.has(tradeSelectionKey(trade))}
                        label={`Select ${trade.symbol} trade ${tradeDateTime(trade)}`}
                        onChange={checked => toggleTradeSelection(tradeSelectionKey(trade), checked)}
                      />
                    </td>
                    <td>{tradeDateTime(trade)}</td>
                    <td><span className={`trade-side ${trade.side === 'SELL' ? 'sell' : 'buy'}`}>{trade.side || '-'}</span></td>
                    <td className="trade-symbol">{trade.symbol}</td>
                    <td className="num">{numberFmt(signedQuantity(trade))}</td>
                    <td className="num">{money2(trade.price, trade.currency)}</td>
                    <td className={`num cash-flow ${netCashAmount(trade) < 0 ? 'negative' : 'positive'}`}>{money2(netCashAmount(trade), trade.currency)}</td>
                    <td className="num">{money(trade.commission, trade.commissionCurrency ?? trade.currency)}</td>
                    <td>{trade.exchange || '-'}</td>
                  </tr>
                ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>

      {showConfig && <IbkrConfigDialog portfolioSlug={portfolioId} onClose={() => setShowConfig(false)} />}
    </div>
  )
}
