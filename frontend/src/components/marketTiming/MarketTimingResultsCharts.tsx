import { useMemo } from 'react'
import {
  Brush, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { useChartTheme } from '@/lib/chartTheme'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import {
  MARGIN_COMPARISON_LEVELS,
  makeMarginComparisonChartData,
  makeMarketTimingChartData,
  makeReferenceDrawdownChartData,
  makeWindowAverageChartData,
  marketTimingResultLabel,
} from '@/lib/marketTiming/chartData'
import { makeMarketTimingTooltip } from '@/lib/marketTiming/tooltips'
import { pct } from '@/lib/statsFormatters'
import { PALETTE } from '@/types/backtest'
import type { MarketTimingResponse, MarketTimingResult } from '@/types/marketTiming'
import MarketTimingStatsTable from './MarketTimingStatsTable'

type MarketTimingChartData = NonNullable<ReturnType<typeof makeMarketTimingChartData>>
type WindowAverageChartData = NonNullable<ReturnType<typeof makeWindowAverageChartData>>
type MarginComparisonChartData = NonNullable<ReturnType<typeof makeMarginComparisonChartData>>
type ReferenceDrawdownChartData = ReturnType<typeof makeReferenceDrawdownChartData>

interface LineStyle {
  stroke: string
  strokeWidth: number
}

interface MarketTimingResultsChartsProps {
  results: MarketTimingResponse
  chartData: MarketTimingChartData
  windowAverageChartData: WindowAverageChartData | null
  marginComparisonChartData: MarginComparisonChartData | null
  referenceDrawdownChartData: ReferenceDrawdownChartData
  marginComparisonResult: MarketTimingResult | undefined
  effectiveMarginComparisonIndex: number
  marginComparisonBaseMargin: number
  normalizeWindowDayZero: boolean
  lineStyles: LineStyle[]
  onNormalizeWindowDayZeroChange: (value: boolean) => void
  onMarginComparisonIndexChange: (value: number) => void
  onMarginComparisonBaseMarginChange: (value: number) => void
}

export default function MarketTimingResultsCharts({
  results,
  chartData,
  windowAverageChartData,
  marginComparisonChartData,
  referenceDrawdownChartData,
  marginComparisonResult,
  effectiveMarginComparisonIndex,
  marginComparisonBaseMargin,
  normalizeWindowDayZero,
  lineStyles,
  onNormalizeWindowDayZeroChange,
  onMarginComparisonIndexChange,
  onMarginComparisonBaseMarginChange,
}: MarketTimingResultsChartsProps) {
  const theme = useChartTheme()
  const tooltip = useMemo(() => makeMarketTimingTooltip(theme), [theme])
  const windowAverageTooltip = useMemo(() => makeRechartsTooltip(
    theme,
    (v: number) => pct(v),
    (label: any) => {
      const months = Number(label)
      if (!Number.isFinite(months)) return String(label)
      if (months === 0) return 'Day 0'
      return months < 0 ? `${Math.abs(months)} months before` : `${months} months after`
    },
  ), [theme])

  return (
    <>
      <MarketTimingStatsTable results={results.results} />

      <div className="market-timing-pl-note" role="note">
        P/L is the percentage advantage at the dip trigger for <span className="market-timing-pl-emphasis">buy and hold from the start date</span> against
        <span className="market-timing-pl-emphasis"> waiting for the drawdown trigger, then buying and holding</span>.
        After the trigger, both strategies hold the same portfolio, so later returns are a common multiplier.
        <span className="market-timing-pl-positive"> Positive P/L</span> means buying immediately is ahead.
        <span className="market-timing-pl-negative"> Negative P/L</span> means waiting for the dip is ahead.
      </div>

      <div className="backtest-chart-heading">
        <div className="backtest-chart-title">Buy Now vs Wait for Dip P/L %</div>
      </div>
      <div className="backtest-chart-container">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={chartData.rows} syncId="market-timing" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(chartData.rows.length / 8))} />
            <YAxis yAxisId="pnl" tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => pct(Number(v))} width={72} />
            <YAxis
              yAxisId="reference"
              orientation="right"
              tick={{ fill: theme.textColor, fontSize: 11 }}
              tickFormatter={v => '$' + Number(v).toFixed(0)}
              width={72}
            />
            <Tooltip content={tooltip} />
            <Legend />
            <Line
              yAxisId="reference"
              dataKey="reference"
              name={`Reference - ${results.referenceLabel || 'Portfolio'}`}
              stroke={theme.textColor}
              strokeWidth={1.8}
              strokeDasharray="4 3"
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
              type="monotone"
            />
            {results.results.map((result, i) => (
              <Line
                key={`${result.drawdownPct}-${result.zeroWindowMonths ?? 0}`}
                yAxisId="pnl"
                dataKey={`dd${i}`}
                name={marketTimingResultLabel(result)}
                stroke={lineStyles[i]?.stroke ?? PALETTE[0][0]}
                strokeWidth={lineStyles[i]?.strokeWidth ?? 2}
                dot={false}
                activeDot={{ r: 4 }}
                connectNulls={false}
                isAnimationActive={false}
                type="monotone"
              />
            ))}
            <Brush dataKey="x" height={26} stroke={theme.gridColor} fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {windowAverageChartData && (
        <>
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">
              {normalizeWindowDayZero ? 'Average Normalized P/L Around Best Wait Day' : 'Average P/L Around Best Wait Day'}
            </div>
            <button
              type="button"
              className={`btn-outline-accent market-timing-normalize-toggle${normalizeWindowDayZero ? ' active' : ''}`}
              aria-pressed={normalizeWindowDayZero}
              onClick={() => onNormalizeWindowDayZeroChange(!normalizeWindowDayZero)}
            >
              Day 0 Zeroed
            </button>
          </div>
          <div className="backtest-chart-container market-timing-window-average-chart">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={windowAverageChartData.rows} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis
                  dataKey="x"
                  tick={{ fill: theme.textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(windowAverageChartData.rows.length / 10))}
                />
                <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => pct(Number(v))} width={72} />
                <Tooltip content={windowAverageTooltip} />
                <Legend />
                <ReferenceLine x={0} stroke={theme.gridColor} strokeDasharray="4 3" />
                {results.results.map((result, i) => (
                  windowAverageChartData.windowCounts[i] > 0 && (
                    <Line
                      key={`${result.drawdownPct}-${result.zeroWindowMonths ?? 0}`}
                      dataKey={`dd${i}`}
                      name={marketTimingResultLabel(result)}
                      stroke={lineStyles[i]?.stroke ?? PALETTE[0][0]}
                      strokeWidth={lineStyles[i]?.strokeWidth ?? 2}
                      dot={false}
                      activeDot={{ r: 4 }}
                      connectNulls={false}
                      isAnimationActive={false}
                      type="monotone"
                    />
                  )
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}

      {marginComparisonChartData && marginComparisonResult && (
        <>
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">
              {normalizeWindowDayZero ? 'Normalized Margin Difference Around Best Wait Day' : 'Margin Difference Around Best Wait Day'}
            </div>
            <div className="market-timing-chart-controls">
              <label>
                <span>Window</span>
                <select
                  value={effectiveMarginComparisonIndex}
                  onChange={e => onMarginComparisonIndexChange(Number(e.target.value))}
                >
                  {results.results.map((result, i) => (
                    <option key={`${result.drawdownPct}-${result.zeroWindowMonths ?? 0}`} value={i}>
                      {marketTimingResultLabel(result)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Base Margin</span>
                <select
                  value={marginComparisonBaseMargin}
                  onChange={e => onMarginComparisonBaseMarginChange(Number(e.target.value))}
                >
                  {MARGIN_COMPARISON_LEVELS.map(margin => (
                    <option key={margin} value={margin}>{margin}%</option>
                  ))}
                </select>
              </label>
            </div>
          </div>
          <div className="backtest-chart-container market-timing-window-average-chart">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={marginComparisonChartData.rows} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis
                  dataKey="x"
                  tick={{ fill: theme.textColor, fontSize: 11 }}
                  interval={Math.max(1, Math.floor(marginComparisonChartData.rows.length / 10))}
                />
                <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => pct(Number(v))} width={72} />
                <Tooltip content={windowAverageTooltip} />
                <Legend />
                <ReferenceLine x={0} stroke={theme.gridColor} strokeDasharray="4 3" />
                <ReferenceLine y={0} stroke={theme.gridColor} strokeDasharray="4 3" />
                {MARGIN_COMPARISON_LEVELS.map((margin, i) => {
                  const palette = PALETTE[i % PALETTE.length]
                  const variant = Math.floor(i / PALETTE.length)
                  return (
                    <Line
                      key={margin}
                      dataKey={`m${margin}`}
                      name={`${margin}% margin`}
                      stroke={palette[variant % palette.length]}
                      strokeWidth={margin === marginComparisonBaseMargin ? 1.5 : 2}
                      strokeDasharray={margin === marginComparisonBaseMargin ? '4 3' : undefined}
                      dot={false}
                      activeDot={{ r: 4 }}
                      connectNulls={false}
                      isAnimationActive={false}
                      type="monotone"
                    />
                  )
                })}
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}

      {referenceDrawdownChartData.length > 0 && (
        <>
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Reference Drawdown %</div>
          </div>
          <div className="backtest-chart-container market-timing-reference-drawdown-chart">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={referenceDrawdownChartData} syncId="market-timing" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(referenceDrawdownChartData.length / 8))} />
                <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => pct(Number(v))} width={72} />
                <Tooltip content={tooltip} />
                <Legend />
                <Line
                  dataKey="referenceDrawdown"
                  name={`Reference Drawdown - ${results.referenceLabel || 'Portfolio'}`}
                  stroke={PALETTE[1 % PALETTE.length][0]}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="monotone"
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </>
  )
}
