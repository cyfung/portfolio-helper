import BacktestStatsTable from '@/components/backtest/BacktestStatsTable'
import { fmt2, money, pct } from '@/lib/statsFormatters'
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
    <BacktestStatsTable
      allChecked={allChecked}
      anyChecked={anyChecked}
      rows={rows}
      selected={selected}
      actionColumns={[
        { key: 'BUY_LOW', label: 'BL', title: '# buy-low action points' },
        { key: 'SELL_HIGH', label: 'SH', title: '# sell-high action points' },
        { key: 'BUY_DIP', label: 'BD', title: '# buy-dip action points' },
        { key: 'SELL_SURGE', label: 'SS', title: '# sell-surge action points' },
        { key: 'VM_TIMING_MR', label: 'VM', title: '# VM timing margin rebalance action points' },
      ]}
      onToggleAll={onToggleAll}
      onToggleCurve={onToggleCurve}
    />
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
