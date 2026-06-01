import { useMemo } from 'react'
import { Download } from 'lucide-react'
import {
  Brush, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { useChartTheme } from '@/lib/chartTheme'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import { makeUsCapeChartData, makeWorldCapeChartData } from '@/lib/marketTiming/chartData'
import { fmt2 } from '@/lib/statsFormatters'
import { PALETTE } from '@/types/backtest'
import type { UsCapePoint, WorldCapePoint } from '@/types/marketTiming'

type WorldCapeChartData = ReturnType<typeof makeWorldCapeChartData>
type UsCapeChartData = ReturnType<typeof makeUsCapeChartData>

interface CapeSummary<T> {
  latest: T
  startDate: string
  endDate: string
  min: number
  max: number
  count: number
}

interface WorldCapeHistoryChartProps {
  csvUrl: string
  chartData: WorldCapeChartData
  summary: CapeSummary<WorldCapePoint>
}

interface UsCapeHistoryChartProps {
  csvUrl: string
  chartData: UsCapeChartData
  summary: CapeSummary<UsCapePoint>
}

function CapeMeta({ label, value, summary }: { label: string; value: number; summary: CapeSummary<{ date: string }> }) {
  return (
    <div className="world-cape-meta" aria-label={`${label} dataset summary`}>
      <span>{summary.startDate} to {summary.endDate}</span>
      <span>{summary.count} observations</span>
      <span>Latest {fmt2(value)} on {summary.latest.date}</span>
      <span>Range {fmt2(summary.min)}-{fmt2(summary.max)}</span>
    </div>
  )
}

function CapeHeading({ title, csvUrl }: { title: string; csvUrl: string }) {
  return (
    <div className="backtest-chart-heading world-cape-heading">
      <div className="backtest-chart-title">{title}</div>
      <a className="h-btn subtle world-cape-download" href={csvUrl} download>
        <Download size={14} aria-hidden="true" />
        <span>CSV</span>
      </a>
    </div>
  )
}

export function WorldCapeHistoryChart({ csvUrl, chartData, summary }: WorldCapeHistoryChartProps) {
  const theme = useChartTheme()
  const tooltip = useMemo(() => makeRechartsTooltip(theme, (v: number) => fmt2(v)), [theme])

  return (
    <>
      <CapeHeading title="World CAPE History" csvUrl={csvUrl} />
      <CapeMeta label="World CAPE" value={summary.latest.worldCape} summary={summary} />
      <div className="backtest-chart-container world-cape-chart-container">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={chartData} syncId="world-cape" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(chartData.length / 10))} />
            <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => Number(v).toFixed(0)} width={48} />
            <Tooltip content={tooltip} />
            <Legend />
            <Line
              dataKey="usProxyCape"
              name="US Shiller proxy"
              stroke={theme.textColor}
              strokeWidth={1.8}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
              type="monotone"
            />
            <Line
              dataKey="syntheticCape"
              name="Synthetic world CAPE"
              stroke={PALETTE[0][0]}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
              type="monotone"
            />
            <Line
              dataKey="siblisCape"
              name="Siblis world CAPE"
              stroke={PALETTE[2][0]}
              strokeWidth={2.4}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
              type="monotone"
            />
            <Line
              dataKey="currentReferenceCape"
              name="RA current reference"
              stroke={PALETTE[4 % PALETTE.length][0]}
              strokeWidth={0}
              dot={{ r: 4, strokeWidth: 2 }}
              activeDot={{ r: 5 }}
              connectNulls={false}
              isAnimationActive={false}
              type="monotone"
            />
            <Brush dataKey="x" height={26} stroke={theme.gridColor} fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </>
  )
}

export function UsCapeHistoryChart({ csvUrl, chartData, summary }: UsCapeHistoryChartProps) {
  const theme = useChartTheme()
  const tooltip = useMemo(() => makeRechartsTooltip(theme, (v: number) => fmt2(v)), [theme])

  return (
    <>
      <CapeHeading title="US CAPE History" csvUrl={csvUrl} />
      <CapeMeta label="US CAPE" value={summary.latest.usCape} summary={summary} />
      <div className="backtest-chart-container world-cape-chart-container">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={chartData} syncId="us-cape" margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="x" tick={{ fill: theme.textColor, fontSize: 11 }} interval={Math.max(1, Math.floor(chartData.length / 10))} />
            <YAxis tick={{ fill: theme.textColor, fontSize: 11 }} tickFormatter={v => Number(v).toFixed(0)} width={48} />
            <Tooltip content={tooltip} />
            <Legend />
            <Line
              dataKey="usCape"
              name="US Shiller CAPE"
              stroke={PALETTE[1 % PALETTE.length][0]}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
              type="monotone"
            />
            <Brush dataKey="x" height={26} stroke={theme.gridColor} fill={theme.isDark ? '#1a1a1a' : '#f8f8f8'} travellerWidth={6} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </>
  )
}
