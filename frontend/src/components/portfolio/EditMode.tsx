// ── EditMode.tsx — Port of edit-mode.js (stock edit table only) ───────────────
// Cash editing is handled by the always-visible CashEditTable in summary-and-rates.
// On save, cash is read from the DOM (matching the original JS approach).
import { useEffect, useRef, useState } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'

interface Props {
  saveKey: number   // incrementing triggers save
  onSaved: () => void
}

interface StockRow {
  symbol: string
  qty: number
  weight: number
  letf: string
  groups: string
  deleted?: boolean
}

function letfAttrToStr(attr: string): string {
  if (!attr) return ''
  const tokens = attr.split(',')
  const parts: string[] = []
  for (let i = 0; i + 1 < tokens.length; i += 2) parts.push(`${tokens[i]} ${tokens[i + 1]}`)
  return parts.join(' ')
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
      const multiplier = parseFloat((row.querySelector('.cash-edit-multiplier') as HTMLInputElement)?.value) || 1.0
      cashUpdates.push({ label, currency: 'P', marginFlag, amount: multiplier, portfolioRef })
    } else {
      const currency = ((row.querySelector('.cash-edit-currency') as HTMLInputElement)?.value ?? '').trim().toUpperCase() || 'USD'
      const amount = parseFloat(((row.querySelector('.cash-edit-amount') as HTMLInputElement)?.value ?? '').replace(/,/g, '')) || 0
      cashUpdates.push({ label, currency, marginFlag, amount })
    }
  })
  return cashUpdates
}

export default function EditMode({ saveKey, onSaved }: Props) {
  const { stocks, portfolioId, config } = usePortfolioStore()

  const [stockRows, setStockRows] = useState<StockRow[]>(() =>
    stocks.map(s => ({
      symbol: s.label,
      qty: s.amount,
      weight: s.targetWeight ?? 0,
      letf: letfAttrToStr(s.letf),
      groups: s.groups,
    }))
  )
  const [saving, setSaving] = useState(false)
  const [dividendDate, setDividendDate] = useState(config.dividendStartDate ?? '')

  const totalWeight = stockRows
    .filter(r => !r.deleted)
    .reduce((sum, r) => sum + (r.weight || 0), 0)

  // ── Trigger save when saveKey increments ──────────────────────────────────
  const prevSaveKey = useRef(saveKey)
  useEffect(() => {
    if (saveKey !== prevSaveKey.current) {
      prevSaveKey.current = saveKey
      doSave()
    }
  })

  async function doSave() {
    const updates = stockRows
      .filter(r => !r.deleted && r.symbol.trim())
      .map(r => ({
        symbol: r.symbol.trim().toUpperCase(),
        amount: r.qty || 0,
        targetWeight: r.weight || 0,
        letf: r.letf || '',
        groups: r.groups || '',
      }))

    // Read cash from CashEditTable DOM (always-visible section)
    const cashUpdates = readCashFromDom()

    setSaving(true)
    try {
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
    setStockRows(rows => [...rows, { symbol: '', qty: 0, weight: 0, letf: '', groups: '' }])
  }

  function updateStock(idx: number, field: keyof StockRow, value: string | number | boolean) {
    setStockRows(rows => rows.map((r, i) => i === idx ? { ...r, [field]: value } : r))
  }

  return (
    <>
      {/* ── Stock edit table ─────────────────────────────────────────── */}
      <table className="portfolio-table" id="stock-edit-table">
        <thead>
          <tr>
            <th className="drag-handle-cell" />
            <th>Symbol</th>
            <th className="amount">Qty</th>
            <th>Weight %</th>
            <th>LETF</th>
            <th>Groups</th>
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
                />
              </td>
              <td className="amount">
                <input
                  type="number"
                  className="edit-input edit-qty"
                  data-column="qty"
                  data-symbol={row.symbol}
                  value={row.qty}
                  min={0}
                  step="any"
                  style={{ display: 'block' }}
                  onChange={e => updateStock(idx, 'qty', parseFloat(e.target.value) || 0)}
                />
              </td>
              <td>
                <input
                  type="number"
                  className="edit-input edit-weight"
                  data-column="weight"
                  data-symbol={row.symbol}
                  value={row.weight}
                  min={0} max={100} step={0.1}
                  onChange={e => updateStock(idx, 'weight', parseFloat(e.target.value) || 0)}
                />
              </td>
              <td>
                <input
                  type="text"
                  className="edit-input edit-letf"
                  data-column="letf"
                  data-symbol={row.symbol}
                  value={row.letf}
                  placeholder="e.g. 2 IVV"
                  style={{ textAlign: 'left', width: 180 }}
                  onChange={e => updateStock(idx, 'letf', e.target.value)}
                />
              </td>
              <td>
                <input
                  type="text"
                  className="edit-input edit-groups"
                  data-column="groups"
                  data-symbol={row.symbol}
                  value={row.groups}
                  placeholder="e.g. 1 Equity"
                  style={{ textAlign: 'left', width: 180 }}
                  onChange={e => updateStock(idx, 'groups', e.target.value)}
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
            <td id="target-weight-total">
              {totalWeight > 0 ? `${totalWeight.toFixed(1)}%` : ''}
            </td>
            <td /><td /><td />
          </tr>
        </tfoot>
      </table>

      <p className="edit-hint" id="stock-edit-hint">
        Paste from spreadsheet (Ctrl+V) fills from focused cell
      </p>

      <button type="button" id="add-stock-btn" className="add-stock-btn" onClick={addStockRow}>
        + Add Stock
      </button>

      {config.virtualBalanceEnabled && (
        <div className="dividend-from-section" style={{ marginTop: '0.5rem' }}>
          <label htmlFor="dividend-from-input-edit">Dividend From</label>
          <input
            type="date"
            id="dividend-from-input"
            className="dividend-from-input"
            value={dividendDate}
            autoComplete="off"
            onChange={e => setDividendDate(e.target.value)}
          />
        </div>
      )}

      {saving && (
        <p style={{ color: 'var(--color-text-secondary)', marginTop: '0.5rem' }}>Saving…</p>
      )}
    </>
  )
}
