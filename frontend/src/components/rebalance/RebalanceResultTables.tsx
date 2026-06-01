import { fmt2, dur, money, pct } from '@/lib/statsFormatters'
import type { RebalanceStatsRow } from '@/lib/rebalanceStrategyResults'
import type { BacktestCurve } from '@/types/backtest'

export function ResultsStatsTable({
  allChecked,
  anyChecked,
  rows,
  selected,
  onToggleAll,
  onToggleCurve,
}: {
  allChecked: boolean
  anyChecked: boolean
  rows: RebalanceStatsRow[]
  selected: Set<string>
  onToggleAll: (checked: boolean) => void
  onToggleCurve: (key: string, checked: boolean) => void
}) {
  return (
    <div className="stats-container">
      <table className="backtest-stats-table">
        <thead>
          <tr>
            <th>
              <input
                type="checkbox"
                checked={allChecked}
                ref={el => { if (el) el.indeterminate = anyChecked && !allChecked }}
                onChange={e => onToggleAll(e.target.checked)}
              />
            </th>
            <th>Curve</th><th>End Value</th><th>CAGR</th><th>Max DD</th>
            <th title="Peak-to-recovery duration of the worst drawdown">Longest DD</th>
            <th title="Annualised volatility of daily returns">Volatility</th>
            <th>Sharpe</th>
            <th title="Ulcer Index">Ulcer</th>
            <th title="Ulcer Performance Index">UPI</th>
            <th title="Average margin utilization">Avg Margin</th>
            <th title="# buy-low action points">BL</th>
            <th title="# sell-high action points">SH</th>
            <th title="# buy-dip action points">BD</th>
            <th title="# sell-surge action points">SS</th>
            <th title="# VM timing margin rebalance action points">VM</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => {
            const s = row.stats
            return (
              <tr key={row.key}>
                <td>
                  <input
                    type="checkbox"
                    checked={selected.has(row.key)}
                    onChange={e => onToggleCurve(row.key, e.target.checked)}
                  />
                </td>
                <td style={{ color: row.color }}>{row.label}</td>
                <td>{money(s.endingValue)}</td>
                <td>{pct(s.cagr)}</td>
                <td>{pct(s.maxDrawdown)}</td>
                <td>{dur(s.longestDrawdownDays)}</td>
                <td>{pct(s.annualVolatility)}</td>
                <td>{fmt2(s.sharpe)}</td>
                <td>{pct(s.ulcerIndex)}</td>
                <td>{fmt2(s.upi)}</td>
                <td>{row.avgMargin == null ? '-' : pct(row.avgMargin)}</td>
                <td>{row.actionCounts.BUY_LOW ?? 0}</td>
                <td>{row.actionCounts.SELL_HIGH ?? 0}</td>
                <td>{row.actionCounts.BUY_DIP ?? 0}</td>
                <td>{row.actionCounts.SELL_SURGE ?? 0}</td>
                <td>{row.actionCounts.VM_TIMING_MR ?? 0}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function ActionDiagnosticsTable({
  points,
}: {
  points: NonNullable<BacktestCurve['actionPoints']>
}) {
  if (points.length === 0) return null

  return (
    <div className="stats-container">
      <table className="backtest-stats-table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Type</th>
            <th title="Zero-based trading date index in the backtest date set">Idx</th>
            <th title="Trading dates since previous action for the same trigger key">Since Prev</th>
            <th>Key</th>
            <th>Dir</th>
            <th>Trigger Value</th>
            <th>Amount</th>
            <th>Eligible</th>
            <th>Margin Before</th>
            <th>Margin After</th>
            <th>Alloc</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point, i) => {
            const d = point.detail!
            return (
              <tr key={`${point.date}-${point.type}-${i}`}>
                <td>{point.date}</td>
                <td>{point.type}</td>
                <td>{d.tradingDayIndex ?? '-'}</td>
                <td>{d.daysSincePrevious ?? '-'}</td>
                <td>{d.key ?? '-'}</td>
                <td>{d.direction ?? '-'}</td>
                <td>{d.triggerValue == null ? '-' : fmt2(d.triggerValue)}</td>
                <td>{d.amount == null ? '-' : money(d.amount)}</td>
                <td>{d.eligibleAmount == null ? '-' : money(d.eligibleAmount)}</td>
                <td>{d.marginBefore == null ? '-' : pct(d.marginBefore)}</td>
                <td>{d.marginAfter == null ? '-' : pct(d.marginAfter)}</td>
                <td>{d.allocStrategy ?? '-'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
