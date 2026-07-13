// ── EditMode.tsx — Port of edit-mode.js (stock edit table only) ───────────────
// Cash editing is handled by the always-visible CashEditTable in summary-and-rates.
// On save, cash is read from the DOM (matching the original JS approach).
import { useCallback, useEffect, useRef, useState } from 'react'
import { Copy } from 'lucide-react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import {
  isPlaceholderTicker,
  resolveSavedPortfolioConfig,
  savedPortfolioConfig,
  savedPortfolioConfigMap,
  stripPlaceholderAndNormalizeResolvedRows,
} from '@/lib/portfolioRefs'
import type { SavedPortfolio } from '@/types/backtest'
import type { StockData } from '@/types/portfolio'

interface Props {
  saveKey: number   // incrementing triggers save
  onSaved: () => void
  pendingDividendDate?: string
  initialStocks?: StockData[]
}

type NumericInputValue = number | string

interface StockRow {
  symbol: string
  qty: NumericInputValue
  weight: NumericInputValue
  deleted?: boolean
}


const STOCK_COLUMNS: (keyof StockRow)[] = ['symbol', 'qty', 'weight']

function roundWeightsToHundred(rows: { ticker: string; weight: number }[]) {
  const validRows = rows.filter(row => row.ticker.trim() && row.weight !== 0 && !isPlaceholderTicker(row.ticker))
  const total = validRows.reduce((sum, row) => sum + row.weight, 0)
  if (total <= 0) return validRows

  const scaled = validRows.map(row => {
    const exactCents = row.weight * 10000 / total
    const cents = exactCents < 0 ? Math.ceil(exactCents) : Math.floor(exactCents)
    return {
      ticker: row.ticker,
      cents,
      remainder: Math.abs(exactCents - cents),
    }
  })

  let diff = 10000 - scaled.reduce((sum, row) => sum + row.cents, 0)
  const byRemainder = [...scaled].sort((a, b) => b.remainder - a.remainder || a.ticker.localeCompare(b.ticker))
  const step = diff < 0 ? -1 : 1
  for (let i = 0; diff !== 0 && byRemainder.length > 0; i += 1, diff -= step) {
    byRemainder[i % byRemainder.length].cents += step
  }

  return scaled.map(row => ({ ticker: row.ticker, weight: row.cents / 100 }))
}

function inputRawValue(input: HTMLInputElement | null): string {
  return input?.dataset.rawValue ?? input?.value ?? ''
}

function numberFromInputValue(value: NumericInputValue): number {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  const parsed = parseFloat(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function numberInputValue(value: NumericInputValue): string | number {
  return value
}

function zeroAsEmpty(value: number): NumericInputValue {
  return value === 0 ? '' : value
}

function normalizeNumberInputOnBlur(value: NumericInputValue): NumericInputValue {
  if (value === '') return ''
  const parsed = numberFromInputValue(value)
  return parsed === 0 ? '' : value
}

function sanitizeNumberEditValue(value: string): string {
  return value.replace(/[^0-9eE.+-]/g, '')
}

function numberClipboardValue(value: NumericInputValue): string {
  if (value === '' || numberFromInputValue(value) === 0) return ''
  return String(value)
}

/** Read all cash rows from the CashEditTable DOM (matching original JS save logic) */
function readCashFromDom(): object[] {
  const cashUpdates: object[] = []
  document.querySelectorAll('[data-cash-edit-row]').forEach(tr => {
    const row = tr as HTMLElement
    if (row.dataset.deleted) return
    const label = ((row.querySelector('.cash-edit-label') as HTMLInputElement)?.value ?? '').trim()
    if (!label) return
    const isRef = (row.querySelector('.cash-edit-is-ref') as HTMLInputElement)?.checked ?? false
    const marginFlag = (row.querySelector('.cash-edit-margin') as HTMLInputElement)?.checked ?? false
    if (isRef) {
      const portfolioRef = (row.querySelector('.cash-edit-portfolio-ref') as HTMLSelectElement)?.value ?? ''
      const multiplier = parseFloat(inputRawValue(row.querySelector('.cash-edit-multiplier') as HTMLInputElement)) || 1.0
      cashUpdates.push({ label, currency: 'P', marginFlag, amount: multiplier, portfolioRef })
    } else {
      const currency = ((row.querySelector('.cash-edit-currency') as HTMLInputElement)?.value ?? '').trim().toUpperCase() || 'USD'
      const amount = parseFloat(inputRawValue(row.querySelector('.cash-edit-amount') as HTMLInputElement).replace(/,/g, '')) || 0
      cashUpdates.push({ label, currency, marginFlag, amount })
    }
  })
  return cashUpdates
}

function flashCopyButton(btn: HTMLElement) {
  btn.classList.add('copy-btn-flash')
  setTimeout(() => btn.classList.remove('copy-btn-flash'), 900)
}

async function fetchTickerConfig(symbol: string): Promise<{ letf: string; groups: string }> {
  const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(symbol)}`)
  if (!res.ok) throw new Error(`Failed to load ticker config for ${symbol}`)
  const data = await res.json()
  return {
    letf: String(data.letf ?? '').trim(),
    groups: String(data.groups ?? '').trim(),
  }
}

async function latestTickerConfigBySymbol(symbols: string[]) {
  const uniqueSymbols = [...new Set(symbols.map(s => s.trim().toUpperCase()).filter(Boolean))]
  const entries = await Promise.all(
    uniqueSymbols.map(async symbol => [symbol, await fetchTickerConfig(symbol)] as const)
  )
  return new Map(entries)
}

export default function EditMode({ saveKey, onSaved, pendingDividendDate, initialStocks }: Props) {
  const { stocks, portfolioId, config, appConfig } = usePortfolioStore()
  const dividendDate = pendingDividendDate ?? config.dividendStartDate ?? ''
  const [savedPortfolios, setSavedPortfolios] = useState<SavedPortfolio[]>([])
  const [selectedImportName, setSelectedImportName] = useState('')
  const [importStatus, setImportStatus] = useState('')
  const [focusedQtyRows, setFocusedQtyRows] = useState<Set<number>>(() => new Set())
  const privacyScalingActive =
    !!appConfig?.privacyScaleEnabled && (parseFloat(appConfig.privacyScalePct ?? '') || 0) > 0

  const [stockRows, setStockRows] = useState<StockRow[]>(() =>
    (initialStocks ?? stocks)
      .filter(s => !isPlaceholderTicker(s.label))
      .map(s => ({
        symbol: s.label,
        qty: zeroAsEmpty(s.originalAmount ?? s.amount),
        weight: zeroAsEmpty(s.targetWeight ?? 0),
      }))
  )
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetch('/api/backtest/savedPortfolios')
      .then(res => res.ok ? res.json() : [])
      .then((data: SavedPortfolio[]) => {
        if (cancelled) return
        const list = data ?? []
        setSavedPortfolios(list)
        setSelectedImportName(current => current || list[list.length - 1]?.name || '')
      })
      .catch(() => {
        if (!cancelled) setSavedPortfolios([])
      })
    return () => { cancelled = true }
  }, [])

  const totalWeight = stockRows
    .filter(r => !r.deleted && !isPlaceholderTicker(r.symbol))
    .reduce((sum, r) => sum + numberFromInputValue(r.weight), 0)

  // ── Trigger save when saveKey increments ──────────────────────────────────
  const prevSaveKey = useRef(saveKey)
  useEffect(() => {
    if (saveKey !== prevSaveKey.current) {
      prevSaveKey.current = saveKey
      doSave()
    }
  }, [saveKey])

  async function doSave() {
    const stockUpdates = stockRows
      .filter(r => !r.deleted && r.symbol.trim() && !isPlaceholderTicker(r.symbol))
      .map(r => ({
        symbol: r.symbol.trim().toUpperCase(),
        amount: numberFromInputValue(r.qty),
        targetWeight: numberFromInputValue(r.weight),
      }))

    // Read cash from CashEditTable DOM (always-visible section)
    const cashUpdates = readCashFromDom()

    setSaving(true)
    try {
      const tickerConfigBySymbol = await latestTickerConfigBySymbol(stockUpdates.map(row => row.symbol))
      const updates = stockUpdates.map(row => {
        const tickerConfig = tickerConfigBySymbol.get(row.symbol)
        return {
          ...row,
          letf: tickerConfig?.letf ?? '',
          groups: tickerConfig?.groups ?? '',
        }
      })

      const r = await fetch(`/api/portfolio/save-all?portfolio=${portfolioId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          stocks: updates,
          cash: cashUpdates,
          dividendStartDate: dividendDate || null,
        }),
      })
      if (!r.ok) throw new Error('Save failed')
      onSaved()
    } catch (err) {
      alert(`Failed to save: ${err}`)
    } finally {
      setSaving(false)
    }
  }

  function addStockRow() {
    setStockRows(rows => [...rows, { symbol: '', qty: '', weight: '' }])
  }

  function updateStock(idx: number, field: keyof StockRow, value: string | number | boolean) {
    setStockRows(rows => rows.map((r, i) => i === idx ? { ...r, [field]: value } : r))
  }

  function setQtyFocused(idx: number, focused: boolean) {
    setFocusedQtyRows(prev => {
      const next = new Set(prev)
      if (focused) next.add(idx)
      else next.delete(idx)
      return next
    })
  }

  function handleNumberFocus(idx: number, field: 'qty' | 'weight') {
    setStockRows(rows => rows.map((r, i) => (
      i === idx && numberFromInputValue(r[field]) === 0 ? { ...r, [field]: '' } : r
    )))
  }

  function handleNumberBlur(idx: number, field: 'qty' | 'weight') {
    setStockRows(rows => rows.map((r, i) => (
      i === idx ? { ...r, [field]: normalizeNumberInputOnBlur(r[field]) } : r
    )))
  }

  function importSavedPortfolioWeights() {
    const selected = savedPortfolios.find(p => p.name === selectedImportName)
    if (!selected) return

    try {
      const savedByName = savedPortfolioConfigMap(savedPortfolios)
      const resolved = resolveSavedPortfolioConfig(
        savedPortfolioConfig(selected.config),
        savedByName,
        [selected.name],
      )
      const rounded = roundWeightsToHundred(stripPlaceholderAndNormalizeResolvedRows(resolved))

      setStockRows(rows => {
        const next = rows.map(r => ({ ...r }))
        const importedSymbols = new Set(
          rounded
            .map(row => row.ticker.trim().toUpperCase())
            .filter(Boolean)
        )
        const rowBySymbol = new Map<string, number>()
        next.forEach((row, idx) => {
          if (row.deleted) return
          const symbol = row.symbol.trim().toUpperCase()
          if (!symbol) return
          rowBySymbol.set(symbol, idx)
          if (!importedSymbols.has(symbol)) row.weight = 0
        })

        for (const row of rounded) {
          const symbol = row.ticker.trim().toUpperCase()
          if (!symbol) continue
          const existingIdx = rowBySymbol.get(symbol)
          if (existingIdx !== undefined) {
            next[existingIdx].weight = row.weight
          } else {
            rowBySymbol.set(symbol, next.length)
            next.push({ symbol, qty: '', weight: row.weight })
          }
        }
        return next
      })

      setImportStatus(`Imported ${rounded.length} weights from ${selected.name}. Review and save when ready.`)
    } catch (err: any) {
      setImportStatus(err?.message || 'Unable to import saved portfolio weights.')
    }
  }

  // ── Enter key: move to same column in next visible row ───────────────────
  function handleEnterKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return
    e.preventDefault()
    const col = (e.currentTarget as HTMLElement).getAttribute('data-column')
    if (!col) return
    const tbody = document.querySelector('#stock-edit-table tbody')
    if (!tbody) return
    const allInputs = Array.from(tbody.querySelectorAll<HTMLInputElement>(`input[data-column="${col}"]`))
    const currentIdx = allInputs.indexOf(e.currentTarget)
    if (currentIdx >= 0 && currentIdx + 1 < allInputs.length) {
      allInputs[currentIdx + 1].focus()
    }
  }

  // ── Paste-from-spreadsheet handler ───────────────────────────────────────
  const handlePaste = useCallback((e: ClipboardEvent) => {
    const activeEl = document.activeElement as HTMLElement | null
    if (!activeEl || !activeEl.classList.contains('edit-input')) return

    const clipText = (e.clipboardData || (window as any).clipboardData)?.getData('text') ?? ''
    const lines = clipText.split(/[\r\n]+/).filter((l: string) => l.trim() !== '')

    // Single-line paste: strip % from weight field
    if (lines.length <= 1) {
      const isWeight = activeEl.classList.contains('edit-weight') ||
        activeEl.getAttribute('data-column') === 'weight'
      if (isWeight) {
        const stripped = clipText.replace(/%/g, '').trim()
        if (stripped !== clipText.trim()) {
          e.preventDefault()
          const inp = activeEl as HTMLInputElement
          inp.value = stripped
          inp.dispatchEvent(new Event('input', { bubbles: true }))
        }
      }
      return
    }

    e.preventDefault()

    const rows = lines.map((l: string) => l.split('\t'))

    // Find which column index the focused input is in
    const focusedCol = activeEl.getAttribute('data-column') as keyof StockRow | null
    if (!focusedCol) return
    const startColIdx = STOCK_COLUMNS.indexOf(focusedCol)
    if (startColIdx < 0) return

    // Find which row the focused input is in (among visible rows)
    const tbody = document.querySelector('#stock-edit-table tbody')
    if (!tbody) return
    const allTrs = Array.from(tbody.querySelectorAll('tr'))
    const focusedTr = activeEl.closest('tr')
    let startRowIdx = focusedTr ? allTrs.indexOf(focusedTr as HTMLTableRowElement) : allTrs.length
    if (startRowIdx < 0) startRowIdx = allTrs.length

    setStockRows(prev => {
      const next = prev.map(r => ({ ...r }))
      // Get visible (non-deleted) row indices into `next`
      const visibleIndices = next
        .map((r, i) => (!r.deleted ? i : -1))
        .filter(i => i >= 0)

      rows.forEach((cols: string[], i: number) => {
        const visiblePos = startRowIdx + i
        let targetIdx: number
        if (visiblePos < visibleIndices.length) {
          targetIdx = visibleIndices[visiblePos]
        } else {
          // Need to append a new row
          next.push({ symbol: '', qty: '', weight: '' })
          targetIdx = next.length - 1
          visibleIndices.push(targetIdx)
        }
        cols.forEach((val: string, j: number) => {
          const colIdx = startColIdx + j
          if (colIdx >= STOCK_COLUMNS.length) return
          const col = STOCK_COLUMNS[colIdx]
          const isWeight = col === 'weight'
          const cleaned = isWeight ? val.replace(/%/g, '').trim() : val.trim()
          if (col === 'qty' || col === 'weight') {
            ;(next[targetIdx] as any)[col] = normalizeNumberInputOnBlur(cleaned)
          } else {
            ;(next[targetIdx] as any)[col] = cleaned
          }
        })
      })
      return next
    })
  }, [])

  useEffect(() => {
    document.addEventListener('paste', handlePaste)
    return () => document.removeEventListener('paste', handlePaste)
  }, [handlePaste])

  // ── Copy table / column handler ───────────────────────────────────────────
  function handleCopyClick(e: React.MouseEvent<HTMLTableElement>) {
    const target = e.target as HTMLElement

    const copyTableBtn = target.closest('.copy-table-btn') as HTMLElement | null
    if (copyTableBtn) {
      const lines = stockRows
        .filter(r => !r.deleted)
        .map(r => [r.symbol, numberClipboardValue(r.qty), numberClipboardValue(r.weight)].join('\t'))
      navigator.clipboard.writeText(lines.join('\n')).then(() => flashCopyButton(copyTableBtn))
      return
    }

    const copyColBtn = target.closest('.copy-col-btn[data-column]') as HTMLElement | null
    if (copyColBtn) {
      const col = copyColBtn.getAttribute('data-column') as keyof StockRow
      const values = stockRows
        .filter(r => !r.deleted)
        .map(r => col === 'qty' || col === 'weight' ? numberClipboardValue(r[col]) : String(r[col] ?? ''))
      navigator.clipboard.writeText(values.join('\n')).then(() => flashCopyButton(copyColBtn))
    }
  }

  return (
    <>
      {savedPortfolios.length > 0 && (
        <div className="edit-import-controls">
          <span className="edit-import-label">Import Weights</span>
          <select
            value={selectedImportName}
            onChange={e => {
              setSelectedImportName(e.target.value)
              setImportStatus('')
            }}
          >
            {[...savedPortfolios].reverse().map(p => (
              <option key={p.name} value={p.name}>{p.name}</option>
            ))}
          </select>
          <button
            type="button"
            className="add-stock-btn"
            disabled={!selectedImportName}
            onClick={importSavedPortfolioWeights}
          >
            Import
          </button>
          {importStatus && <span className="edit-import-status">{importStatus}</span>}
        </div>
      )}

      {/* ── Stock edit table ─────────────────────────────────────────── */}
      <table className="portfolio-table" id="stock-edit-table" onClick={handleCopyClick}>
        <thead>
          <tr>
            <th className="drag-handle-cell">
              <button type="button" className="copy-table-btn copy-col-btn" title="Copy table to clipboard (Google Sheets)">
                <Copy size={12} />
              </button>
            </th>
            <th>Symbol <button type="button" className="copy-col-btn" data-column="symbol" title="Copy Symbol column"><Copy size={12} /></button></th>
            <th className="amount">Qty <button type="button" className="copy-col-btn col-num" data-column="qty" title="Copy Qty column"><Copy size={12} /></button></th>
            <th>Weight % <button type="button" className="copy-col-btn" data-column="weight" title="Copy Weight % column"><Copy size={12} /></button></th>
            <th />
          </tr>
        </thead>
        <tbody>
          {stockRows.map((row, idx) => row.deleted ? null : (
            <tr key={idx}>
              <td className="drag-handle-cell">
                <span className="drag-handle">⠿</span>
              </td>
              <td>
                <input
                  type="text"
                  className="edit-input edit-symbol"
                  data-column="symbol"
                  data-symbol={row.symbol}
                  value={row.symbol}
                  placeholder="TICKER"
                  style={{ textAlign: 'left', width: 80, display: 'block' }}
                  onChange={e => updateStock(idx, 'symbol', e.target.value.toUpperCase())}
                  onKeyDown={handleEnterKey}
                />
              </td>
              <td className="amount">
                <input
                  type="text"
                  inputMode="decimal"
                  className="edit-input edit-qty"
                  data-column="qty"
                  data-symbol={row.symbol}
                  value={privacyScalingActive && !focusedQtyRows.has(idx) ? '' : numberInputValue(row.qty)}
                  placeholder={privacyScalingActive && !focusedQtyRows.has(idx) ? 'Set' : undefined}
                  style={{ display: 'block' }}
                  onFocus={() => {
                    handleNumberFocus(idx, 'qty')
                    setQtyFocused(idx, true)
                  }}
                  onBlur={() => {
                    handleNumberBlur(idx, 'qty')
                    setQtyFocused(idx, false)
                  }}
                  onChange={e => updateStock(idx, 'qty', sanitizeNumberEditValue(e.target.value))}
                  onKeyDown={handleEnterKey}
                />
              </td>
              <td>
                <input
                  type="text"
                  inputMode="decimal"
                  className="edit-input edit-weight"
                  data-column="weight"
                  data-symbol={row.symbol}
                  value={numberInputValue(row.weight)}
                  onFocus={() => handleNumberFocus(idx, 'weight')}
                  onBlur={() => handleNumberBlur(idx, 'weight')}
                  onChange={e => updateStock(idx, 'weight', sanitizeNumberEditValue(e.target.value))}
                  onKeyDown={handleEnterKey}
                />
              </td>
              <td>
                <button
                  type="button"
                  className="delete-row-btn"
                  onClick={() => updateStock(idx, 'deleted', true)}
                >
                  ×
                </button>
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td /><td>Total</td><td />
            <td id="target-weight-total" style={totalWeight > 0 && Math.abs(totalWeight - 100) > 0.001 ? { color: 'red' } : undefined}>
              {totalWeight > 0 ? `${totalWeight.toFixed(2)}%` : ''}
            </td>
            <td />
          </tr>
        </tfoot>
      </table>

      <p className="edit-hint" id="stock-edit-hint">
        Paste from spreadsheet (Ctrl+V) fills from focused cell
      </p>

      <div className="edit-add-buttons">
        <button type="button" id="add-stock-btn" className="add-stock-btn" onClick={addStockRow}>
          + Add Stock
        </button>
      </div>

      {saving && (
        <p style={{ color: 'var(--color-text-secondary)', marginTop: '0.5rem' }}>Saving…</p>
      )}
    </>
  )
}
