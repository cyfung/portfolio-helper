// ── SummaryTable.tsx — Port of buildSummaryRows from PortfolioRenderer.kt ────
import { useRef } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { formatDisplayCurrency, formatSignedCurrency } from '@/lib/portfolio-utils'

export default function SummaryTable() {
  const store = usePortfolioStore()
  const {
    cash, fxRates, currentDisplayCurrency,
    lastPortfolioTotals, lastCashDisplay,
    rebalTargetUsd, marginTargetPct,
    setRebalTargetUsd, setMarginTargetPct,
    portfolioId,
  } = store

  const hasMargin = cash.some(c => c.marginFlag)
  const hasCash = cash.length > 0

  const rebalInputRef = useRef<HTMLInputElement>(null)
  const marginInputRef = useRef<HTMLInputElement>(null)

  // ── Formatted display values ──────────────────────────────────────────────
  const fmt = (usd: number) => formatDisplayCurrency(usd, fxRates, currentDisplayCurrency)

  const grandTotal = lastPortfolioTotals?.grandTotalKnown
    ? fmt(lastPortfolioTotals.grandTotalUsd) : '—'

  const dayChangeUsd = lastPortfolioTotals?.dayChangeUsd ?? null
  const dayChangeStr = dayChangeUsd !== null
    ? formatSignedCurrency(dayChangeUsd) : ''

  const cashTotal = lastCashDisplay?.totalKnown
    ? fmt(lastCashDisplay.totalUsd) : '—'

  const marginUsdVal = lastPortfolioTotals?.marginUsd ?? 0
  const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
  const stockGross = lastPortfolioTotals?.stockGrossKnown
    ? fmt(stockGrossUsd) : '—'

  const stockDayChangeUsd = lastPortfolioTotals?.dayChangeUsd ?? null
  const stockDayChangeStr = stockDayChangeUsd !== null
    ? formatSignedCurrency(stockDayChangeUsd) : ''

  const marginPct = marginUsdVal > 0 && stockGrossUsd > 0
    ? ((marginUsdVal / stockGrossUsd) * 100).toFixed(2) + '%' : ''

  // ── Save rebal/margin targets to API ──────────────────────────────────────
  async function saveRebalTarget(val: string) {
    const num = parseFloat(val.replace(/,/g, ''))
    if (isNaN(num)) return
    setRebalTargetUsd(num > 0 ? num : null)
    await fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=rebalTarget&value=${num}`, { method: 'POST' })
  }

  async function saveMarginTarget(val: string) {
    const num = parseFloat(val.replace(/%/g, ''))
    if (isNaN(num)) return
    setMarginTargetPct(num > 0 ? num : null)
    await fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=marginTarget&value=${num}`, { method: 'POST' })
  }

  return (
    <table className="portfolio-cash-table">
      <tbody>
        {/* ── Grand total ──────────────────────────────────────────────── */}
        <tr className="grand-total-row">
          <td>Portfolio Value</td>
          <td />
          <td />
          <td>
            <span id="portfolio-total">{grandTotal}</span>
            {dayChangeStr && (
              <div className="summary-subvalue" id="total-day-change">{dayChangeStr}</div>
            )}
          </td>
        </tr>

        {/* ── Hidden anchor row (kept for JS compatibility) ────────────── */}
        <tr id="cash-rows-anchor" style={{ display: 'none' }} />

        {/* ── Cash rows from SSE ────────────────────────────────────────── */}
        {lastCashDisplay?.entries.map(entry => (
          <tr key={entry.entryId} className={entry.isMarginEntry ? 'cash-margin-entry' : ''}>
            <td>{entry.label}</td>
            <td>{entry.currency !== 'P' ? entry.currency : ''}</td>
            <td className="muted">
              {entry.currency !== 'P' && entry.rawAmount !== 0
                ? entry.rawAmount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
                : ''}
            </td>
            <td>
              {entry.valueUsd !== null ? fmt(entry.valueUsd) : '—'}
            </td>
          </tr>
        ))}

        {/* ── Cash summary rows ─────────────────────────────────────────── */}
        {hasCash && (
          <>
            <tr className="summary-divider"><td colSpan={4} /></tr>
            <tr className="total-cash-row">
              <td>Total Cash</td>
              <td /><td />
              <td><span id="cash-total-usd">{cashTotal}</span></td>
            </tr>

            {hasMargin && (
              <tr
                className="margin-row"
                data-margin-row="true"
                style={{ display: marginUsdVal ? undefined : 'none' }}
              >
                <td>Margin</td>
                <td /><td />
                <td>
                  <span id="margin-total-usd">
                    {marginUsdVal ? fmt(marginUsdVal) : '—'}
                  </span>
                  {marginPct && (
                    <span className="margin-percent" id="margin-percent">{marginPct}</span>
                  )}
                </td>
              </tr>
            )}

            <tr className="summary-section-break"><td colSpan={4} /></tr>

            <tr>
              <td>Stock Gross Value</td>
              <td /><td />
              <td>
                <div id="stock-gross-total">{stockGross}</div>
                {stockDayChangeStr && (
                  <div className="summary-subvalue" id="portfolio-day-change">{stockDayChangeStr}</div>
                )}
              </td>
            </tr>
          </>
        )}

        {/* ── Rebalance target input ────────────────────────────────────── */}
        <tr className="rebal-target-row">
          <td>Rebalance Target</td>
          <td /><td />
          <td>
            <input
              ref={rebalInputRef}
              type="text"
              id="rebal-target-input"
              className="rebal-target-input"
              autoComplete="off"
              defaultValue={rebalTargetUsd ?? ''}
              onBlur={e => saveRebalTarget(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') saveRebalTarget((e.target as HTMLInputElement).value) }}
            />
          </td>
        </tr>

        {/* ── Margin target input ───────────────────────────────────────── */}
        {hasMargin && (
          <tr className="margin-row" id="margin-target-row">
            <td>Margin Target</td>
            <td />
            <td><span id="margin-target-usd" /></td>
            <td>
              <input
                ref={marginInputRef}
                type="text"
                id="margin-target-input"
                className="margin-target-input"
                autoComplete="off"
                defaultValue={marginTargetPct ?? ''}
                onBlur={e => saveMarginTarget(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') saveMarginTarget((e.target as HTMLInputElement).value) }}
              />
              {' %'}
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}
