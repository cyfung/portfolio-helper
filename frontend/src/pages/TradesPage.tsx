import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { PageNavTabs, ConfigButton, HeaderRight, PrivacyToggleButton, ThemeToggle } from '@/components/Layout'
import TransientToast from '@/components/TransientToast'
import PortfolioTabs from '@/components/portfolio/PortfolioTabs'
import IbkrConfigDialog from '@/components/portfolio/IbkrConfigDialog'
import DateFieldWithQuickSelect, { type DateQuickSelectPeriod } from '@/components/backtest/DateFieldWithQuickSelect'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { useTransientToast } from '@/hooks/useTransientToast'
import { validateDateRange } from '@/lib/dateRange'
import type { CashData, PortfolioData, PriceQuoteEvent, StockData } from '@/types/portfolio'

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

type PortfolioStockCreateRow = {
  symbol: string
  amount: number
  targetWeight: number
  letf: string
  groups: string
}

type PortfolioCashCreateRow = CashData

type TradesView = 'summary' | 'trades'
type IngestSource = 'flex' | 'tws' | 'xml'
type ApiErrorBody = { error?: string; message?: string }

const TRADE_QUICK_SELECT_PERIODS: readonly DateQuickSelectPeriod[] = [
  { label: '1D', unit: 'day', amount: 1 },
  { label: '2D', unit: 'day', amount: 2 },
  { label: '3D', unit: 'day', amount: 3 },
  { label: '4D', unit: 'day', amount: 4 },
  { label: '5D', unit: 'day', amount: 5 },
  { label: '1W', unit: 'week', amount: 1 },
  { label: '2W', unit: 'week', amount: 2 },
  { label: '1M', unit: 'month', amount: 1 },
]
const ISO_CURRENCIES = new Set([
  'AUD', 'CAD', 'CHF', 'CNH', 'CNY', 'EUR', 'GBP', 'HKD', 'JPY', 'MXN', 'NOK', 'NZD', 'SEK', 'SGD', 'USD',
])

function formatLocalDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function todayStr() {
  return formatLocalDate(new Date())
}

function dateDaysAgo(days: number) {
  const date = new Date()
  date.setDate(date.getDate() - days)
  return formatLocalDate(date)
}

function timeStr() {
  return new Date().toTimeString().slice(0, 8).replace(/:/g, '')
}

function defaultTradesPortfolioName(portfolioId: string | null | undefined) {
  return `Trades ${portfolioId ?? 'Portfolio'} ${todayStr()} ${timeStr()}`
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

function tradeQuoteSymbol(trade: TradeRow, exchangeSuffixes: Map<string, string>) {
  return isCurrencyTrade(trade) ? '' : portfolioSymbolForTrade(trade, exchangeSuffixes).toUpperCase()
}

function tradePnl(trade: TradeRow, quotes: Record<string, PriceQuoteEvent>, exchangeSuffixes: Map<string, string>) {
  const symbol = tradeQuoteSymbol(trade, exchangeSuffixes)
  if (!symbol) return null
  const quote = quotes[symbol]
  const quotePrice = quote?.price ?? quote?.previousClose ?? null
  if (quotePrice === null || !Number.isFinite(quotePrice)) return null
  const tradeCurrency = trade.currency.trim().toUpperCase()
  const quoteCurrency = quote.currency?.trim().toUpperCase()
  if (quoteCurrency && tradeCurrency && quoteCurrency !== tradeCurrency) return null
  return signedQuantity(trade) * quotePrice + netCashAmount(trade)
}

function groupPnl(rows: TradeRow[], quotes: Record<string, PriceQuoteEvent>, exchangeSuffixes: Map<string, string>) {
  let total = 0
  for (const trade of rows) {
    const pnl = tradePnl(trade, quotes, exchangeSuffixes)
    if (pnl === null) return null
    total += pnl
  }
  return total
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

function tradesNetCashLabel(currency: string) {
  return `Trades Net Cash ${currency}`
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
  const priceQuotes = usePortfolioStore(s => s.priceQuotes)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showConfig, setShowConfig] = useState(false)
  const [fromDate, setFromDate] = useState(() => dateDaysAgo(7))
  const [toDate, setToDate] = useState('')
  const [view, setView] = useState<TradesView>('summary')
  const [showCurrencyTrades, setShowCurrencyTrades] = useState(false)
  const [expandedSymbols, setExpandedSymbols] = useState<Set<string>>(new Set())
  const [selectedTradeKeys, setSelectedTradeKeys] = useState<Set<string>>(new Set())
  const [allTrades, setAllTrades] = useState<TradeRow[]>([])
  const [ingestSource, setIngestSource] = useState<IngestSource | null>(null)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [createPortfolioName, setCreatePortfolioName] = useState('')
  const [createBasePortfolioSlug, setCreateBasePortfolioSlug] = useState('')
  const [creatingPortfolio, setCreatingPortfolio] = useState(false)
  const [notice, setNotice] = useState('')
  const [exchangeSuffixes, setExchangeSuffixes] = useState<Map<string, string>>(new Map())
  const xmlInputRef = useRef<HTMLInputElement>(null)
  const { toast, showToast, clearToast } = useTransientToast()

  const dateRangeError = validateDateRange(fromDate, toDate)
  const from = fromDate
  const to = toDate || todayStr()
  const ingesting = ingestSource !== null

  useEffect(() => {
    setAllTrades([])
    setSelectedTradeKeys(new Set())
    setExpandedSymbols(new Set())
    setView('summary')
    setNotice('')
    setCreateDialogOpen(false)
    setCreatePortfolioName('')
    setCreateBasePortfolioSlug('')
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

  const quoteSymbols = useMemo(() => {
    return [...new Set(trades.map(trade => tradeQuoteSymbol(trade, exchangeSuffixes)).filter(Boolean))].sort()
  }, [exchangeSuffixes, trades])
  const quoteSymbolsKey = quoteSymbols.join('|')

  useEffect(() => {
    if (!quoteSymbolsKey) {
      return
    }

    const symbols = quoteSymbolsKey.split('|')
    const controller = new AbortController()
    fetch('/api/prices/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbols }),
      signal: controller.signal,
    })
      .then(async r => {
        if (!r.ok) {
          const data = await r.json().catch(() => ({})) as { message?: string }
          throw new Error(data.message ?? `HTTP ${r.status}`)
        }
      })
      .catch((e: Error) => {
        if (e.name !== 'AbortError') showToast(e.message || 'Failed to request trade prices', 'error', 10000)
      })

    return () => controller.abort()
  }, [quoteSymbolsKey, showToast])

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
          pnl: groupPnl(sortedRows, priceQuotes, exchangeSuffixes),
          commissions: sortedRows.reduce((sum, trade) => sum + (trade.commission ?? 0), 0),
          commissionCurrency: sortedRows.find(trade => trade.commissionCurrency)?.commissionCurrency ?? sortedRows[0]?.currency,
          dateRange: tradeDateRange(sortedRows),
          exchange: [...new Set(sortedRows.map(trade => trade.exchange).filter(Boolean))].join(', '),
        }
      })
      .sort((a, b) => a.symbol.localeCompare(b.symbol))
  }, [exchangeSuffixes, priceQuotes, trades])

  const selectedVisibleTrades = useMemo(() => {
    return trades.filter(trade => selectedTradeKeys.has(tradeSelectionKey(trade)))
  }, [selectedTradeKeys, trades])

  const selectedVisibleCount = selectedVisibleTrades.length

  function pnlCellText(trade: TradeRow) {
    const pnl = tradePnl(trade, priceQuotes, exchangeSuffixes)
    if (pnl !== null) return money2(pnl, trade.currency)
    return '-'
  }

  function groupPnlCellText(rows: TradeRow[], pnl: number | null, currency?: string) {
    if (pnl !== null) return money2(pnl, currency)
    return '-'
  }

  function pnlClass(value: number | null) {
    if (value === null || Math.abs(value) < 1e-9) return 'neutral'
    return value < 0 ? 'negative' : 'positive'
  }

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

  function buildPortfolioRowsFromTrades(rows: TradeRow[]): { stocks: PortfolioStockCreateRow[]; cash: PortfolioCashCreateRow[] } {
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
      .map(([currency, amount]) => ({ label: tradesNetCashLabel(currency), currency, marginFlag: false, amount }))

    return { stocks, cash }
  }

  async function loadBasePortfolioData(baseSlug: string) {
    const r = await fetch(`/api/portfolio/data?portfolio=${encodeURIComponent(baseSlug)}`)
    const data = await r.json().catch(() => null) as PortfolioData | { message?: string } | null
    if (!r.ok || !data || !('stocks' in data)) {
      const message = data && 'message' in data ? data.message : null
      throw new Error(message ?? `Failed to load base portfolio: HTTP ${r.status}`)
    }
    return data
  }

  function mergeTradesWithBasePortfolio(base: PortfolioData, tradeRows: ReturnType<typeof buildPortfolioRowsFromTrades>) {
    const stockBySymbol = new Map<string, PortfolioStockCreateRow>()
    base.stocks.forEach((stock: StockData) => {
      const symbol = stock.label.trim().toUpperCase()
      if (!symbol) return
      stockBySymbol.set(symbol, {
        symbol,
        amount: stock.originalAmount ?? stock.amount ?? 0,
        targetWeight: stock.targetWeight ?? 0,
        letf: stock.letf ?? '',
        groups: stock.groups ?? '',
      })
    })
    tradeRows.stocks.forEach(delta => {
      const current = stockBySymbol.get(delta.symbol)
      if (current) {
        stockBySymbol.set(delta.symbol, { ...current, amount: current.amount + delta.amount })
      } else {
        stockBySymbol.set(delta.symbol, delta)
      }
    })

    const cash = base.cash.map(entry => ({ ...entry }))
    tradeRows.cash.forEach(delta => {
      const currency = delta.currency.trim().toUpperCase()
      const label = tradesNetCashLabel(currency)
      const existingIndex = cash.findIndex(entry =>
        entry.label.trim().toLowerCase() === label.toLowerCase() &&
        entry.currency.trim().toUpperCase() === currency
      )
      if (existingIndex >= 0) {
        cash[existingIndex] = {
          ...cash[existingIndex],
          amount: cash[existingIndex].amount + delta.amount,
        }
      } else {
        cash.push({ label, currency, marginFlag: false, amount: delta.amount })
      }
    })

    return {
      stocks: [...stockBySymbol.values()]
        .filter(stock => Number.isFinite(stock.amount) && Math.abs(stock.amount) > 1e-9)
        .sort((a, b) => a.symbol.localeCompare(b.symbol)),
      cash,
    }
  }

  function openCreatePortfolioDialog() {
    if (selectedVisibleTrades.length === 0) return
    setCreatePortfolioName(defaultTradesPortfolioName(portfolioId))
    setCreateBasePortfolioSlug('')
    setCreateDialogOpen(true)
  }

  async function createPortfolioFromSelectedTrades() {
    const selectedRows = selectedVisibleTrades
    if (selectedRows.length === 0) return
    const name = createPortfolioName.trim()
    if (!name) return

    const tradeRows = buildPortfolioRowsFromTrades(selectedRows)
    if (tradeRows.stocks.length === 0 && tradeRows.cash.length === 0) {
      showToast('Selected trades do not produce any non-zero stock or cash rows.', 'error', 10000)
      return
    }

    setCreatingPortfolio(true)
    setNotice('')
    try {
      const base = createBasePortfolioSlug ? await loadBasePortfolioData(createBasePortfolioSlug) : null
      const { stocks, cash } = base ? mergeTradesWithBasePortfolio(base, tradeRows) : tradeRows
      const createResponse = await fetch('/api/portfolio/create-with-contents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, stocks, cash, dividendStartDate: base?.config.dividendStartDate || null }),
      })
      const createData = await createResponse.json().catch(() => ({})) as { slug?: string; message?: string }
      if (!createResponse.ok || !createData.slug) throw new Error(createData.message ?? `Create failed: HTTP ${createResponse.status}`)

      setNotice(`Created portfolio ${name} from ${selectedRows.length} selected trade(s)${base ? ` merged with ${base.portfolioName}` : ''}.`)
      setCreateDialogOpen(false)
      navigate(`/portfolio/${createData.slug}`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to create portfolio', 'error', 10000)
    } finally {
      setCreatingPortfolio(false)
    }
  }

  async function ingestFromIbkr() {
    if (!portfolioId) return
    setIngestSource('flex')
    setNotice('')
    try {
      const r = await fetch(`/api/trades/fetch/${portfolioId}`, { method: 'POST' })
      const d = await r.json() as { trades?: TradeRow[] } & ApiErrorBody
      if (!r.ok) throw new Error(d.error ?? d.message ?? `HTTP ${r.status}`)
      const fetchedTrades = d.trades ?? []
      setAllTrades(fetchedTrades)
      setSelectedTradeKeys(new Set(fetchedTrades.map(tradeSelectionKey)))
      setNotice(`Fetched ${d.trades?.length ?? 0} trade(s).`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to fetch trades', 'error', 10000)
    } finally {
      setIngestSource(null)
    }
  }

  async function ingestFromTws() {
    if (!portfolioId) return
    setIngestSource('tws')
    setNotice('')
    try {
      const r = await fetch(`/api/trades/fetch-tws/${portfolioId}`, { method: 'POST' })
      const d = await r.json() as { trades?: TradeRow[] } & ApiErrorBody
      if (!r.ok) throw new Error(d.error ?? d.message ?? `HTTP ${r.status}`)
      const fetchedTrades = d.trades ?? []
      setAllTrades(fetchedTrades)
      setSelectedTradeKeys(new Set(fetchedTrades.map(tradeSelectionKey)))
      setNotice(`Fetched ${fetchedTrades.length} trade(s) from TWS.`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to fetch TWS trades', 'error', 10000)
    } finally {
      setIngestSource(null)
    }
  }

  async function importXml(files: FileList) {
    if (!portfolioId) return
    setIngestSource('xml')
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
        const d = await r.json() as { trades?: TradeRow[] } & ApiErrorBody
        if (!r.ok) throw new Error(`${files[i].name}: ${d.error ?? d.message ?? `HTTP ${r.status}`}`)
        imported.push(...(d.trades ?? []))
        total += d.trades?.length ?? 0
      }
      setAllTrades(imported)
      setSelectedTradeKeys(new Set(imported.map(tradeSelectionKey)))
      setNotice(`Imported ${total} trade(s) from file(s).`)
    } catch (e: any) {
      showToast(e.message ?? 'Failed to import trades', 'error', 10000)
    } finally {
      setIngestSource(null)
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

      <TransientToast msg={toast.msg} type={toast.type} onDismiss={clearToast} />

      <div className="trades-page">
        <div className="trades-toolbar">
          <div className="trades-filters">
            <div className="trades-date-filter backtest-section">
              <div className="backtest-date-range-controls">
                <DateFieldWithQuickSelect
                  label="From Date"
                  inputId="trades-from-date"
                  value={fromDate}
                  onChange={setFromDate}
                  quickSelectPeriods={TRADE_QUICK_SELECT_PERIODS}
                />
                <DateFieldWithQuickSelect
                  label="To Date"
                  inputId="trades-to-date"
                  value={toDate}
                  onChange={setToDate}
                  quickSelectPeriods={TRADE_QUICK_SELECT_PERIODS}
                />
                {dateRangeError && (
                  <div className="backtest-date-range-error" role="alert">
                    {dateRangeError}
                  </div>
                )}
              </div>
            </div>
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
              {ingestSource === 'flex' ? <>Fetching<span className="btn-spinner" /></> : 'Fetch Flex'}
            </button>
            <button type="button" className="backtest-config-btn" onClick={ingestFromTws} disabled={ingesting}>
              {ingestSource === 'tws' ? <>Fetching<span className="btn-spinner" /></> : 'Fetch TWS'}
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
              <div><strong>5.</strong> In <strong>IB Config</strong>, fill <strong>Flex Web Service Token</strong> and <strong>Trades Query ID</strong>, then click <strong>Fetch Flex</strong>.</div>
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
            <div className="flex-query-note">Trades are not saved to the database. Fetch with Flex, fetch recent executions from TWS, or import XML again when you need to reload the table. TWS may only return trades inside its Trade Log window.</div>
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
                onClick={openCreatePortfolioDialog}
                disabled={creatingPortfolio || selectedVisibleCount === 0}
              >
                Create Portfolio
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
                  <th>P&amp;L</th>
                  <th>Commission</th>
                  <th>Exchange</th>
                </tr>
                </thead>
                <tbody>
                {trades.length === 0 ? (
                  <tr><td colSpan={10} className="trades-page-status">No trades in this date range.</td></tr>
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
                      <td className={`num trade-pnl ${pnlClass(group.pnl)}`}>{groupPnlCellText(group.rows, group.pnl, group.netCashCurrency)}</td>
                      <td className="num">{money(group.commissions, group.commissionCurrency)}</td>
                      <td>{group.exchange || '-'}</td>
                    </tr>
                    {expandedSymbols.has(group.symbol) && group.rows.map(trade => (
                      (() => {
                        const pnl = tradePnl(trade, priceQuotes, exchangeSuffixes)
                        return (
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
                        <td className={`num trade-pnl ${pnlClass(pnl)}`}>{pnlCellText(trade)}</td>
                        <td className="num">{money(trade.commission, trade.commissionCurrency ?? trade.currency)}</td>
                        <td>{trade.exchange || '-'}</td>
                      </tr>
                        )
                      })()
                    ))}
                  </Fragment>
                )) : trades.map(trade => (
                  (() => {
                    const pnl = tradePnl(trade, priceQuotes, exchangeSuffixes)
                    return (
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
                    <td className={`num trade-pnl ${pnlClass(pnl)}`}>{pnlCellText(trade)}</td>
                    <td className="num">{money(trade.commission, trade.commissionCurrency ?? trade.currency)}</td>
                    <td>{trade.exchange || '-'}</td>
                  </tr>
                    )
                  })()
                ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>

      {showConfig && <IbkrConfigDialog portfolioSlug={portfolioId} onClose={() => setShowConfig(false)} />}
      {createDialogOpen && (
        <div className="trades-create-overlay" role="presentation" onMouseDown={e => {
          if (e.target === e.currentTarget && !creatingPortfolio) setCreateDialogOpen(false)
        }}>
          <form
            className="trades-create-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="trades-create-title"
            onSubmit={e => {
              e.preventDefault()
              if (!creatingPortfolio) createPortfolioFromSelectedTrades()
            }}
          >
            <div className="trades-create-dialog-header">
              <h2 id="trades-create-title">Create Portfolio</h2>
              <button
                type="button"
                className="trades-create-close"
                aria-label="Close"
                onClick={() => setCreateDialogOpen(false)}
                disabled={creatingPortfolio}
              >
                x
              </button>
            </div>
            <label className="trades-create-field" htmlFor="trades-create-name">
              <span>Name</span>
              <input
                id="trades-create-name"
                value={createPortfolioName}
                onChange={e => setCreatePortfolioName(e.target.value)}
                autoFocus
                disabled={creatingPortfolio}
              />
            </label>
            <label className="trades-create-field" htmlFor="trades-create-base">
              <span>Base</span>
              <select
                id="trades-create-base"
                value={createBasePortfolioSlug}
                onChange={e => setCreateBasePortfolioSlug(e.target.value)}
                disabled={creatingPortfolio}
              >
                <option value="">Selected trades only</option>
                {allPortfolios.map(portfolio => (
                  <option key={portfolio.slug} value={portfolio.slug}>{portfolio.name}</option>
                ))}
              </select>
            </label>
            <div className="trades-create-actions">
              <button type="button" className="backtest-config-btn" onClick={() => setCreateDialogOpen(false)} disabled={creatingPortfolio}>
                Cancel
              </button>
              <button type="submit" className="backtest-config-btn active" disabled={creatingPortfolio || !createPortfolioName.trim()}>
                {creatingPortfolio ? <>Creating<span className="btn-spinner" /></> : 'Create'}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
