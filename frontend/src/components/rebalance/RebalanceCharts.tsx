import { memo, type ReactNode, useEffect, useRef } from 'react'
import {
  Brush,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  type TooltipContentProps,
  XAxis,
  YAxis,
} from 'recharts'
import type { RechartsChartData } from '@/lib/chartData'

export type ActionPointChartKey =
  | 'main'
  | 'drawdown'
  | 'recover'
  | 'margin'
  | 'marginCushion'
  | 'marginReciprocal'

export const ACTIVE_DOT = { r: 4 }

export type CommonLineProps = {
  type: 'monotone'
  dot: false
  activeDot: { r: number }
  connectNulls: false
  isAnimationActive: false
}

export function LegendLine({
  color,
  strokeWidth,
  strokeDasharray,
}: {
  color: string
  strokeWidth: number
  strokeDasharray?: string
}) {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    ctx.clearRect(0, 0, 28, 10)
    ctx.strokeStyle = color
    ctx.lineWidth = strokeWidth
    ctx.setLineDash(strokeDasharray ? strokeDasharray.split(' ').map(Number) : [])
    ctx.beginPath()
    ctx.moveTo(2, 5)
    ctx.lineTo(26, 5)
    ctx.stroke()
  }, [color, strokeWidth, strokeDasharray])

  return <canvas ref={ref} width={28} height={10} style={{ display: 'inline-block', verticalAlign: 'middle' }} />
}

const CHART_MARGIN = { top: 8, right: 16, bottom: 8, left: 8 }

function formatMoneyAxis(v: any) { return '$' + Number(v).toFixed(0) }
function formatMoneyTooltip(v: number) { return '$' + v.toFixed(2) }
function formatDrawdownAxis(v: any) { return (Number(v) * 100).toFixed(1) + '%' }
function formatPercentAxis(v: any) { return (Number(v) * 100).toFixed(0) + '%' }
function formatPercentTooltip(v: number) { return (v * 100).toFixed(2) + '%' }
function formatRecoverAxis(v: any) { return Number(v).toFixed(2) + 'x' }
function formatRecoverTooltip(v: number) { return v.toFixed(2) + 'x' }
function formatMultipleAxis(v: any) { return Number(v).toFixed(2) + 'x' }
function formatMultipleTooltip(v: number) { return v.toFixed(2) + 'x' }
function formatVmCapeAxis(v: any) { return Number(v).toFixed(0) }

type RebalanceChartKind = 'money' | 'drawdown' | 'recover' | 'margin' | 'multiple'

const CHART_FORMATTERS: Record<RebalanceChartKind, {
  axis: (v: any) => string
  tooltip: (v: number) => string
  width: number
}> = {
  money: { axis: formatMoneyAxis, tooltip: formatMoneyTooltip, width: 72 },
  drawdown: { axis: formatDrawdownAxis, tooltip: formatPercentTooltip, width: 60 },
  recover: { axis: formatRecoverAxis, tooltip: formatRecoverTooltip, width: 60 },
  margin: { axis: formatPercentAxis, tooltip: formatPercentTooltip, width: 60 },
  multiple: { axis: formatMultipleAxis, tooltip: formatMultipleTooltip, width: 60 },
}

type RebalanceLineChartProps = {
  chartData: RechartsChartData
  labelsLength: number
  gridColor: string
  textColor: string
  commonLineProps: CommonLineProps
  makeTooltip: (
    valueFmt: (v: number) => string,
    labelFmt?: (l: any) => string,
  ) => (props: TooltipContentProps) => ReactNode
  renderLegend: (props: any) => ReactNode
  renderActionMarkers: (rows: Record<string, any>[], chart: ActionPointChartKey) => ReactNode
  actionChart: ActionPointChartKey
  kind: RebalanceChartKind
  logScale?: boolean
  brushFill?: string
}

export const RebalanceLineChart = memo(function RebalanceLineChart({
  chartData,
  labelsLength,
  gridColor,
  textColor,
  commonLineProps,
  makeTooltip,
  renderLegend,
  renderActionMarkers,
  actionChart,
  kind,
  logScale = false,
  brushFill,
}: RebalanceLineChartProps) {
  const format = CHART_FORMATTERS[kind]
  const isMoney = kind === 'money'

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={chartData.rows} syncId="rs-backtest" margin={CHART_MARGIN}>
        <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
        <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
          interval={Math.max(1, Math.floor(labelsLength / 8))} />
        <YAxis scale={isMoney && logScale ? 'log' : 'linear'} domain={['auto', 'auto']}
          allowDataOverflow={isMoney && logScale} tick={{ fill: textColor, fontSize: 11 }}
          tickFormatter={format.axis} width={format.width} />
        <Tooltip content={makeTooltip(format.tooltip)} />
        <Legend content={renderLegend} />
        {chartData.datasets.map(ds => (
          <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
            stroke={ds.color} strokeWidth={ds.strokeWidth ?? 2} strokeDasharray={ds.strokeDasharray} />
        ))}
        {renderActionMarkers(chartData.rows, actionChart)}
        {brushFill && (
          <Brush dataKey="x" height={26} stroke={gridColor}
            fill={brushFill} travellerWidth={6} />
        )}
      </LineChart>
    </ResponsiveContainer>
  )
})

export type VmTimingChartData = {
  rows: Record<string, any>[]
  datasets: {
    dataKey: string
    label: string
    color: string
    yAxisId: 'cape' | 'factor'
    strokeDasharray?: string
  }[]
}

export const VmTimingLineChart = memo(function VmTimingLineChart({
  chartData,
  labelsLength,
  gridColor,
  textColor,
  commonLineProps,
  renderLegend,
}: {
  chartData: VmTimingChartData
  labelsLength: number
  gridColor: string
  textColor: string
  commonLineProps: CommonLineProps
  renderLegend: (props: any) => ReactNode
}) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={chartData.rows} syncId="rs-backtest" margin={CHART_MARGIN}>
        <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
        <XAxis dataKey="x" tick={{ fill: textColor, fontSize: 11 }}
          interval={Math.max(1, Math.floor(labelsLength / 8))} />
        <YAxis yAxisId="cape" domain={['auto', 'auto']} tick={{ fill: textColor, fontSize: 11 }}
          tickFormatter={formatVmCapeAxis} width={54} />
        <YAxis yAxisId="factor" orientation="right" domain={[0, 1]} tick={{ fill: textColor, fontSize: 11 }}
          tickFormatter={formatPercentAxis} width={54} />
        <Tooltip
          formatter={(value: any, name: any, item: any) => {
            const n = Number(value)
            const dataKey = String(item?.dataKey ?? '')
            return [dataKey.includes('Factor') ? formatPercentTooltip(n) : n.toFixed(2), name]
          }}
        />
        <Legend content={renderLegend} />
        {chartData.datasets.map(ds => (
          <Line key={ds.dataKey} {...commonLineProps} dataKey={ds.dataKey} name={ds.label}
            yAxisId={ds.yAxisId} stroke={ds.color} strokeWidth={2} strokeDasharray={ds.strokeDasharray} />
        ))}
      </LineChart>
    </ResponsiveContainer>
  )
})
