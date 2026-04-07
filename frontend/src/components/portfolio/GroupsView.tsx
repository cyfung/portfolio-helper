// ── GroupsView.tsx — Port of groups.js group aggregation ─────────────────────
import { usePortfolioStore } from '@/stores/portfolioStore'
import {
  parseGroupsAttr, formatCurrency, formatSignedCurrency, toDisplayCurrency,
  weightDiffCls, actionCls, hasFxRate,
} from '@/lib/portfolio-utils'
import { getRebalTotal } from '@/lib/rebalance'

interface GroupEntry {
  mktVal: number
  prevMktVal: number
  targetWeight: number
  members: string[]
}

export default function GroupsView() {
  const {
    stocks, fxRates, currentDisplayCurrency,
    lastStockDisplay, lastAllocData, lastPortfolioTotals,
    rebalTargetUsd, marginTargetPct, marginTargetUsd,
  } = usePortfolioStore()

  const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
  const stockGrossKnown = lastPortfolioTotals?.stockGrossKnown ?? false
  const marginUsd = lastPortfolioTotals?.marginUsd ?? 0
  const rebalTotal = getRebalTotal(rebalTargetUsd, marginTargetPct, stockGrossUsd, marginUsd, marginTargetUsd)

  const liveBySymbol = new Map(
    (lastStockDisplay?.stocks ?? []).map(s => [s.symbol, s])
  )
  const allocBySymbol = new Map(
    (lastAllocData?.stocks ?? []).map(s => [s.symbol, s])
  )

  const fmt = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatCurrency(toDisplayCurrency(usd, fxRates, currentDisplayCurrency))
      : '—'
  const fmtSigned = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatSignedCurrency(toDisplayCurrency(usd, fxRates, currentDisplayCurrency))
      : '—'

  // ── Build group map ──────────────────────────────────────────────────────
  const groupMap = new Map<string, GroupEntry>()

  for (const stock of stocks) {
    if (!stock.groups) continue
    const sym = stock.label
    const live = liveBySymbol.get(sym) ?? null
    const groupEntries = parseGroupsAttr(stock.groups, sym)
    const targetWeight = stock.targetWeight ?? 0
    const qty = stock.amount
    const stockCcy = live?.currency ?? 'USD'
    const fxRate = fxRates[stockCcy] ?? null
    const mktValUsd = live?.positionValueUsd ?? null
    const prevMktValUsd = live?.closePrice != null && fxRate !== null
      ? live.closePrice * qty * fxRate : null

    for (const { multiplier, name } of groupEntries) {
      if (!groupMap.has(name)) {
        groupMap.set(name, { mktVal: 0, prevMktVal: 0, targetWeight: 0, members: [] })
      }
      const g = groupMap.get(name)!
      if (mktValUsd !== null) g.mktVal += mktValUsd * multiplier
      if (prevMktValUsd !== null) g.prevMktVal += prevMktValUsd * multiplier
      g.targetWeight += targetWeight * multiplier
      if (!g.members.includes(sym)) g.members.push(sym)
    }
  }

  if (groupMap.size === 0) return null

  return (
    <>
      <p style={{ fontSize: 'var(--font-size-md)', opacity: 0.7, margin: '0.5rem 0 0.75rem' }}>
        ⚠︎ Group values should be interpreted cautiously — their meaning depends heavily on how groups are defined.
      </p>
      <table className="portfolio-table" id="group-view-table">
        <thead>
          <tr>
            <th>Group</th>
            <th className="col-num col-market-data">CHG %</th>
            <th className="col-num col-market-data">P&amp;L</th>
            <th className="col-num col-market-data col-moreinfo">Mkt Val</th>
            <th className="col-num">
              Weight <span className="th-sub">Cur / Tgt / Dev</span>
            </th>
            <th className="rebal-column">Rebal</th>
            <th className="alloc-column">Alloc</th>
          </tr>
        </thead>
        <tbody>
          {Array.from(groupMap.entries()).map(([name, g]) => {
            const mktValChg = g.mktVal - g.prevMktVal
            const isZeroChg = Math.abs(mktValChg) < 0.01
            const chgCls = isZeroChg ? 'neutral' : mktValChg > 0 ? 'positive' : 'negative'

            const dayPct = g.prevMktVal > 0 ? (mktValChg / g.prevMktVal) * 100 : 0
            const isNeutralDayPct = Math.abs(dayPct) < 0.1
            const dayPctCls = isNeutralDayPct ? 'neutral' : dayPct > 0 ? 'positive' : 'negative'

            // Group alloc = sum of per-symbol allocs
            const groupAllocUsd = g.members.reduce((sum, sym) => {
              return sum + (allocBySymbol.get(sym)?.allocDollars ?? 0)
            }, 0)

            if (!stockGrossKnown || stockGrossUsd <= 0) {
              return (
                <tr key={name} data-group-name={name} data-group-members={g.members.join(',')}>
                  <td>{name}</td>
                  <td className={`col-num col-market-data price-change ${dayPctCls}`}>
                    <span className={`mark-day-pct ${dayPctCls}`}>
                      {dayPct >= 0 ? '+' : '−'}{Math.abs(dayPct).toFixed(2)}%
                    </span>
                  </td>
                  <td className={`price-change ${chgCls}`}>
                    {isZeroChg ? '—' : fmtSigned(mktValChg)}
                  </td>
                  <td className="col-num col-market-data value col-moreinfo">{fmt(g.mktVal)}</td>
                  <td className="col-num value">N/A</td>
                  <td className="action-neutral rebal-column">N/A</td>
                  <td className="action-neutral alloc-column">N/A</td>
                </tr>
              )
            }

            const weightPct = (g.mktVal / stockGrossUsd) * 100
            const targetWeightPct = g.targetWeight
            const weightDiff = weightPct - targetWeightPct
            const rebalDollars = (targetWeightPct / 100) * rebalTotal - g.mktVal
            const diffCls = weightDiffCls(weightDiff)
            const pillSign = weightDiff >= 0 ? '+' : ''

            return (
              <tr key={name} data-group-name={name} data-group-members={g.members.join(',')}>
                <td>{name}</td>
                <td className={`col-num col-market-data price-change ${dayPctCls}`}>
                  <span className={`mark-day-pct ${dayPctCls}`}>
                    {dayPct >= 0 ? '+' : '−'}{Math.abs(dayPct).toFixed(2)}%
                  </span>
                </td>
                <td className={`price-change ${chgCls}`}>
                  {isZeroChg ? '—' : fmtSigned(mktValChg)}
                </td>
                <td className="col-num col-market-data value col-moreinfo">{fmt(g.mktVal)}</td>
                <td className="col-num value">
                  <span className="weight-cur">{weightPct.toFixed(1)}%</span>
                  <span className="weight-sep">/</span>
                  <span className="weight-tgt">{targetWeightPct.toFixed(1)}%</span>
                  <span className={`weight-diff ${diffCls}`}>{pillSign}{weightDiff.toFixed(1)}%</span>
                </td>
                <td className={`action-neutral ${actionCls(rebalDollars)} rebal-column`}>
                  {fmtSigned(rebalDollars)}
                </td>
                <td className={`action-neutral ${actionCls(groupAllocUsd)} alloc-column`}>
                  {fmtSigned(groupAllocUsd)}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </>
  )
}
