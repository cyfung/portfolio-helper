import { formatDays } from '@/lib/marketTiming/chartData'
import { pct } from '@/lib/statsFormatters'
import type { MarketTimingResult } from '@/types/marketTiming'

interface MarketTimingStatsTableProps {
  results: MarketTimingResult[]
}

export default function MarketTimingStatsTable({ results }: MarketTimingStatsTableProps) {
  return (
    <div className="stats-container">
      <table className="backtest-stats-table">
        <thead>
          <tr>
            <th>DD - Window</th>
            <th>Triggered</th>
            <th>Avg P/L %</th>
            <th>Median P/L %</th>
            <th>Avg Non-Zero P/L %</th>
            <th>Median Non-Zero P/L %</th>
            <th>Best P/L %</th>
            <th>Worst P/L %</th>
            <th title="Wins divided by wins plus losses. Neutral zero P/L cases are excluded.">Win/Loss Rate</th>
            <th>Avg Wait</th>
          </tr>
        </thead>
        <tbody>
          {results.map(result => (
            <tr key={`${result.drawdownPct}-${result.zeroWindowMonths ?? 0}`}>
              <td>{pct(result.drawdownPct)} - {result.zeroWindowMonths ?? 0}m</td>
              <td>{result.summary.triggeredPoints}/{result.summary.totalPoints}</td>
              <td>{result.summary.averageValue == null ? '-' : pct(result.summary.averageValue)}</td>
              <td>{result.summary.medianValue == null ? '-' : pct(result.summary.medianValue)}</td>
              <td>{result.summary.nonZeroAverageValue == null ? '-' : pct(result.summary.nonZeroAverageValue)}</td>
              <td>{result.summary.nonZeroMedianValue == null ? '-' : pct(result.summary.nonZeroMedianValue)}</td>
              <td>{result.summary.bestValue == null ? '-' : pct(result.summary.bestValue)}</td>
              <td>{result.summary.worstValue == null ? '-' : pct(result.summary.worstValue)}</td>
              <td>{result.summary.winRate == null ? '-' : pct(result.summary.winRate)}</td>
              <td>{formatDays(result.summary.averageDaysToTrigger)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
