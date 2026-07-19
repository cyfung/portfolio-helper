import { dur, fmt2, money, pct } from '@/lib/statsFormatters'
import type { CommonStatsRow, SeriesStats } from '@/lib/backtestStats'

export interface StatsActionColumn {
  key: string
  label: string
  title: string
}

interface Props {
  allChecked: boolean
  anyChecked: boolean
  rows: CommonStatsRow[]
  selected: Set<string>
  actionColumns?: StatsActionColumn[]
  onToggleAll: (checked: boolean) => void
  onToggleCurve: (key: string, checked: boolean) => void
}

const COMMON_STATS_COLUMNS: {
  key: keyof SeriesStats
  label: string
  title?: string
  format: (value: number) => string
}[] = [
  { key: 'endingValue', label: 'End Value', format: money },
  { key: 'cagr', label: 'CAGR', format: pct },
  { key: 'maxDrawdown', label: 'Max DD', format: pct },
  { key: 'averageDrawdown', label: 'Avg DD', title: 'Average drawdown from running peak', format: pct },
  { key: 'longestDrawdownDays', label: 'Longest DD', title: 'Peak-to-recovery duration of the worst drawdown', format: dur },
  { key: 'annualVolatility', label: 'Volatility', title: 'Annualised volatility of daily returns', format: pct },
  { key: 'sharpe', label: 'Sharpe', format: fmt2 },
  { key: 'sortino', label: 'Sortino', format: fmt2 },
  { key: 'calmar', label: 'Calmar', title: 'CAGR divided by max drawdown', format: fmt2 },
  { key: 'beta', label: 'Beta', title: 'Daily-return beta versus the configured reference ticker', format: fmt2 },
  { key: 'ulcerIndex', label: 'Ulcer', title: 'Ulcer Index: RMS of drawdowns from peak', format: pct },
  { key: 'upi', label: 'UPI', title: 'Ulcer Performance Index (Martin Ratio)', format: fmt2 },
]

function formatStat(row: CommonStatsRow, column: (typeof COMMON_STATS_COLUMNS)[number]) {
  const raw = row.stats[column.key]
  if (typeof raw !== 'number' || !Number.isFinite(raw)) return '-'
  const value = column.key === 'endingValue'
    ? raw * (row.endingValueFactor ?? 1)
    : raw
  return column.format(value)
}

export default function BacktestStatsTable({
  allChecked,
  anyChecked,
  rows,
  selected,
  actionColumns = [],
  onToggleAll,
  onToggleCurve,
}: Props) {
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
            <th>Curve</th>
            {COMMON_STATS_COLUMNS.map(column => (
              <th key={column.key} title={column.title}>{column.label}</th>
            ))}
            <th title="Average margin utilization">Avg Margin</th>
            {actionColumns.map(column => (
              <th key={column.key} title={column.title}>{column.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.key}>
              <td>
                <input
                  type="checkbox"
                  checked={selected.has(row.key)}
                  onChange={e => onToggleCurve(row.key, e.target.checked)}
                />
              </td>
              <td style={{ color: row.color }}>{row.label}</td>
              {COMMON_STATS_COLUMNS.map(column => (
                <td key={column.key}>{formatStat(row, column)}</td>
              ))}
              <td>{row.avgMargin == null ? '-' : pct(row.avgMargin)}</td>
              {actionColumns.map(column => (
                <td key={column.key}>{row.actionCounts?.[column.key] ?? '-'}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
