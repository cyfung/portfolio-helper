// ── StockTable.tsx — Port of buildStockTable from PortfolioRenderer.kt ────────
import { usePortfolioStore } from '@/stores/portfolioStore'
import {
  formatCurrency, formatQty, toDisplayCurrency,
  parseLetfAttr, formatSignedCurrency,
  weightDiffCls, actionCls,
} from '@/lib/portfolio-utils'
import { getRebalTotal } from '@/lib/rebalance'

// ── Component ─────────────────────────────────────────────────────────────────

export default function StockTable() {
  const {
    stocks, fxRates, currentDisplayCurrency,
    lastStockDisplay, lastAllocData, lastPortfolioTotals,
    rebalTargetUsd, marginTargetPct,
  } = usePortfolioStore()

  const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
  const stockGrossKnown = lastPortfolioTotals?.stockGrossKnown ?? false
  const marginUsd = lastPortfolioTotals?.marginUsd ?? 0
  const rebalTotal = getRebalTotal(rebalTargetUsd, marginTargetPct, stockGrossUsd, marginUsd)

  // Index SSE data by symbol
  const liveBySymbol = new Map(
    (lastStockDisplay?.stocks ?? []).map(s => [s.symbol, s])
  )
  const allocBySymbol = new Map(
    (lastAllocData?.stocks ?? []).map(s => [s.symbol, s])
  )

  const fmt = (usd: number) => {
    const val = toDisplayCurrency(usd, fxRates, currentDisplayCurrency)
    return formatCurrency(val)
  }

  const fmtSigned = (usd: number) => {
    const val = toDisplayCurrency(usd, fxRates, currentDisplayCurrency)
    return formatSignedCurrency(val)
  }

  // Check if any stock has target weight > 0 (for rebal warning)
  const totalTargetWeight = stocks.reduce((sum, s) => sum + (s.targetWeight ?? 0), 0)
  const showWeightWarning = totalTargetWeight > 0 && Math.abs(totalTargetWeight - 100) > 1

  return (
    <>
      <table className="portfolio-table" id="stock-view-table">
        <thead>
          <tr>
            <th>Symbol</th>
            <th className="col-num col-moreinfo">Qty</th>
            <th className="col-num col-market-data col-moreinfo">Last NAV</th>
            <th className="col-num col-market-data" id="th-est-val">
              EST{' '}
              <span className="col-info-hint" title="Hover a cell to see price targets">ⓘ</span>
            </th>
            <th className="col-num col-market-data col-moreinfo">Last</th>
            <th className="col-num col-market-data">Mark</th>
            <th className="col-num col-market-data">CHG</th>
            <th className="col-num col-market-data">P&amp;L</th>
            <th className="col-num col-market-data col-moreinfo">Mkt Val</th>
            <th className="col-num">
              Weight <span className="th-sub">Cur / Tgt / Dev</span>
            </th>
            <th className="rebal-column">Rebal</th>
            <th className="rebal-column col-moreinfo">Rebal Qty</th>
            <th className="alloc-column">Alloc</th>
            <th className="alloc-column col-moreinfo">Alloc Qty</th>
          </tr>
        </thead>
        <tbody>
          {stocks.map(stock => {
            const sym = stock.label
            const live = liveBySymbol.get(sym) ?? null
            const alloc = allocBySymbol.get(sym) ?? null
            const targetWeight = stock.targetWeight ?? 0

            // ── Computed values ──────────────────────────────────────────
            const qty = stock.amount
            const markPrice = live?.markPrice ?? null
            const closePrice = live?.closePrice ?? null
            const navPrice = live?.lastNav ?? null
            const estPrice = live?.estPriceNative ?? null
            const posVal = live?.positionValueUsd ?? null
            const dayCh = live?.dayChangeDollars ?? null
            const stockCcy = live?.currency ?? 'USD'
            const fxRate = fxRates[stockCcy] ?? 1.0

            // Mark value (native currency)
            const markStr = markPrice !== null ? formatCurrency(markPrice) : '—'
            const dayPct = live?.dayChangePct ?? null
            const dayPctCls = dayPct === null ? '' : dayPct > 0 ? 'positive' : dayPct < 0 ? 'negative' : 'neutral'
            const dayPctStr = dayPct !== null
              ? `${dayPct >= 0 ? '+' : ''}${dayPct.toFixed(2)}%` : ''

            // Mkt Val in display currency
            const mktValStr = posVal !== null ? fmt(posVal) : '—'

            // Day change (CHG) in display currency
            const dayChStr = dayCh !== null ? fmtSigned(dayCh) : ''
            const dayChCls = dayCh === null ? 'neutral' : dayCh > 0 ? 'positive' : dayCh < 0 ? 'negative' : 'neutral'

            // Weight columns (only when stock gross known)
            let weightCells = null
            if (stockGrossKnown && stockGrossUsd > 0) {
              const curWeight = posVal !== null ? (posVal / stockGrossUsd) * 100 : 0
              const weightDiff = curWeight - targetWeight
              const diffCls = weightDiffCls(weightDiff)
              const pillSign = weightDiff >= 0 ? '+' : ''

              // Rebal
              const rebalDollars = targetWeight > 0
                ? (targetWeight / 100) * rebalTotal - (posVal ?? 0)
                : null
              const rebalQty = (rebalDollars !== null && markPrice && markPrice > 0 && fxRate)
                ? rebalDollars / (markPrice * fxRate) : null

              // Alloc (server only sends allocDollars; compute qty client-side)
              const allocDollars = alloc?.allocDollars ?? null
              const allocQty = (allocDollars !== null && markPrice && markPrice > 0 && fxRate)
                ? allocDollars / (markPrice * fxRate) : null

              weightCells = (
                <>
                  <td className="weight-display col-num" id={`current-weight-${sym}`}>
                    <span className="weight-cur">{curWeight.toFixed(1)}%</span>
                    <span className="weight-sep">/</span>
                    <span className="weight-tgt">{targetWeight.toFixed(1)}%</span>
                    <span className={`weight-diff ${diffCls}`}>
                      {pillSign}{weightDiff.toFixed(1)}%
                    </span>
                  </td>
                  <td className={`action-neutral rebal-column ${actionCls(rebalDollars)}`} id={`rebal-dollars-${sym}`}>
                    {rebalDollars !== null ? fmtSigned(rebalDollars) : ''}
                  </td>
                  <td className={`action-neutral rebal-column col-moreinfo ${actionCls(rebalDollars)}`} id={`rebal-qty-${sym}`}>
                    {rebalQty !== null ? rebalQty.toFixed(2) : ''}
                  </td>
                  <td className={`action-neutral alloc-column ${actionCls(allocDollars)}`} id={`alloc-dollars-${sym}`}>
                    {allocDollars !== null ? fmtSigned(allocDollars) : '—'}
                  </td>
                  <td className={`action-neutral alloc-column col-moreinfo ${actionCls(allocDollars)}`} id={`alloc-qty-${sym}`}>
                    {allocQty !== null ? allocQty.toFixed(2) : ''}
                  </td>
                </>
              )
            } else {
              weightCells = (
                <>
                  <td className="weight-display col-num" id={`current-weight-${sym}`} />
                  <td className="action-neutral rebal-column" id={`rebal-dollars-${sym}`} />
                  <td className="action-neutral rebal-column col-moreinfo" id={`rebal-qty-${sym}`} />
                  <td className="action-neutral alloc-column" id={`alloc-dollars-${sym}`} />
                  <td className="action-neutral alloc-column col-moreinfo" id={`alloc-qty-${sym}`} />
                </>
              )
            }

            // Build letf string for data attr
            const letfTokens = parseLetfAttr(stock.letf)
            const letfAttr = letfTokens.map(t => `${t.mult},${t.sym}`).join(',')

            return (
              <tr
                key={sym}
                data-symbol={sym}
                data-qty={formatQty(qty)}
                data-raw-qty={qty.toString()}
                data-weight={targetWeight.toString()}
                data-letf={letfAttr || undefined}
                data-groups={stock.groups || undefined}
              >
                {/* Symbol */}
                <td>{sym}</td>

                {/* Qty */}
                <td className="amount col-moreinfo" id={`amount-${sym}`}>
                  {formatQty(qty)}
                </td>

                {/* Last NAV */}
                <td className="col-market-data price muted col-moreinfo" id={`nav-${sym}`}>
                  {navPrice !== null ? formatCurrency(navPrice) : '—'}
                </td>

                {/* EST */}
                <td className="col-market-data price" id={`est-val-${sym}`}>
                  {estPrice !== null ? formatCurrency(estPrice) : '—'}
                </td>

                {/* Last (close) */}
                <td className="col-market-data price col-moreinfo" id={`close-${sym}`}>
                  {closePrice !== null ? formatCurrency(closePrice) : '—'}
                </td>

                {/* Mark + day % */}
                <td className="col-market-data price" id={`mark-${sym}`}>
                  <span className="mark-price-value">{markStr}</span>
                  {dayPctStr && (
                    <span className={`mark-day-pct ${dayPctCls}`} id={`day-percent-${sym}`}>
                      {dayPctStr}
                    </span>
                  )}
                </td>

                {/* CHG */}
                <td className={`col-market-data price-change ${dayChCls}`} id={`day-change-${sym}`}>
                  {dayChStr}
                </td>

                {/* P&L (position day change) */}
                <td className={`col-market-data price-change ${dayChCls}`} id={`position-change-${sym}`}>
                  {dayChStr}
                </td>

                {/* Mkt Val */}
                <td className="col-market-data value col-moreinfo" id={`value-${sym}`}>
                  {mktValStr}
                </td>

                {/* Weight / Rebal / Alloc */}
                {weightCells}
              </tr>
            )
          })}
        </tbody>
      </table>

      {showWeightWarning && (
        <div className="rebal-weight-warning" id="rebal-weight-warning">
          Target weights sum to {totalTargetWeight.toFixed(1)}% (should be 100%)
        </div>
      )}
    </>
  )
}
