import { useCallback, useMemo, useState, type Dispatch, type ReactNode, type SetStateAction } from 'react'
import { ReferenceDot } from 'recharts'
import { ActionDiagnosticsTable, ResultsStatsTable } from '@/components/rebalance/RebalanceResultTables'
import {
  ACTIVE_DOT,
  LegendLine,
  RebalanceLineChart,
  VmTimingLineChart,
  type ActionPointChartKey,
  type CommonLineProps,
} from '@/components/rebalance/RebalanceCharts'
import { makeRechartsTooltip } from '@/lib/chartTooltip'
import { useChartTheme } from '@/lib/chartTheme'
import {
  ACTION_MARKERS,
  DEFAULT_ACTION_POINT_CHART_VISIBILITY,
  DEFAULT_FORCE_ACTION_POINT_CHART_DOTS,
  buildStatsRows,
  useRebalanceChartData,
  useVmTimingChartData,
  visibleActionPointGroups,
} from '@/lib/rebalanceStrategyResults'
import { BacktestResults } from '@/types/backtest'

function ActionPointTypeFilter({
  visibleTypes,
  onToggle,
}: {
  visibleTypes: Set<string>
  onToggle: (type: string, checked: boolean) => void
}) {
  return (
    <div className="chart-action-filter" aria-label="Action point type filters">
      <span>Points</span>
      {Object.entries(ACTION_MARKERS).map(([type, marker]) => (
        <label key={type} title={marker.label}>
          <input
            type="checkbox"
            checked={visibleTypes.has(type)}
            onChange={e => onToggle(type, e.target.checked)}
          />
          <span style={{ color: marker.color }}>{marker.short}</span>
        </label>
      ))}
    </div>
  )
}

export default function RebalanceStrategyResults({
  results,
  selected,
  setSelected,
}: {
  results: BacktestResults
  selected: Set<string>
  setSelected: Dispatch<SetStateAction<Set<string>>>
}) {
  const theme = useChartTheme()
  const { gridColor, textColor } = theme
  const chartData = useRebalanceChartData(results, selected)
  const [logScale, setLogScale] = useState(false)
  const [visibleActionPointTypes, setVisibleActionPointTypes] = useState<Set<string>>(
    () => new Set(Object.entries(ACTION_MARKERS).filter(([, marker]) => marker.defaultVisible !== false).map(([type]) => type)),
  )
  const [actionPointChartVisibility, setActionPointChartVisibility] = useState<Record<ActionPointChartKey, boolean>>(
    () => ({ ...DEFAULT_ACTION_POINT_CHART_VISIBILITY }),
  )
  const [forceActionPointChartDots, setForceActionPointChartDots] = useState<Record<ActionPointChartKey, boolean>>(
    () => ({ ...DEFAULT_FORCE_ACTION_POINT_CHART_DOTS }),
  )

  const allKeys = useMemo(
    () => results.portfolios.flatMap((p, pi) => p.curves.map((_, ci) => `${pi}-${ci}`)),
    [results],
  )
  const allChecked = allKeys.length > 0 && allKeys.every(k => selected.has(k))
  const anyChecked = selected.size > 0

  const selectedStrategyCurve = useMemo(() => {
    if (selected.size !== 1) return null
    const key = [...selected][0]
    const [piText, ciText] = key.split('-')
    const pi = parseInt(piText, 10)
    const ci = parseInt(ciText, 10)
    if (!Number.isFinite(pi) || !Number.isFinite(ci)) return null
    const curve = results.portfolios[pi]?.curves[ci]
    if (!curve?.actionPoints?.length) return null
    return { dataKey: `p${pi}-c${ci}`, curve }
  }, [results, selected])

  const selectedActionPointGroups = useMemo(() => (
    visibleActionPointGroups(selectedStrategyCurve?.curve.actionPoints, visibleActionPointTypes, chartData.labels)
  ), [chartData.labels, selectedStrategyCurve, visibleActionPointTypes])

  const selectedActionDiagnostics = useMemo(() => (
    selectedStrategyCurve?.curve.actionPoints
      ?.filter(point => point.detail)
      .slice(0, 250) ?? []
  ), [selectedStrategyCurve])

  const vmTimingChartData = useVmTimingChartData(results, chartData.labels, selected)

  const statsRows = useMemo(() => buildStatsRows(results), [results])

  const toggleCurve = useCallback((key: string, checked: boolean) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }, [setSelected])

  const toggleAll = useCallback((checked: boolean) => {
    setSelected(checked ? new Set(allKeys) : new Set())
  }, [allKeys, setSelected])

  const toggleActionPointType = useCallback((type: string, checked: boolean) => {
    setVisibleActionPointTypes(prev => {
      const next = new Set(prev)
      if (checked) next.add(type)
      else next.delete(type)
      return next
    })
  }, [])

  const toggleActionPointChart = useCallback((chart: ActionPointChartKey, checked: boolean) => {
    setActionPointChartVisibility(prev => ({ ...prev, [chart]: checked }))
  }, [])

  const toggleForceActionPointChartDots = useCallback((chart: ActionPointChartKey, checked: boolean) => {
    setForceActionPointChartDots(prev => ({ ...prev, [chart]: checked }))
  }, [])

  const makeTooltip = useCallback(
    (valueFmt: (v: number) => string, labelFmt?: (l: unknown) => string) =>
      makeRechartsTooltip(theme, valueFmt, labelFmt),
    [theme],
  )

  const commonLineProps = useMemo<CommonLineProps>(() => ({
    type: 'monotone' as const,
    dot: false as const,
    activeDot: ACTIVE_DOT,
    connectNulls: false,
    isAnimationActive: false,
  }), [])

  const renderActionDotControls = useCallback((chart: ActionPointChartKey) => {
    if (!selectedStrategyCurve) return null
    const dotsEnabled = actionPointChartVisibility[chart]
    const hasDenseGroups = selectedActionPointGroups.denseGroups.length > 0
    return (
      <div className="chart-dot-controls" aria-label="Action point display">
        <label>
          <input
            type="checkbox"
            checked={dotsEnabled}
            onChange={e => toggleActionPointChart(chart, e.target.checked)}
          />
          <span>Dots</span>
        </label>
        {dotsEnabled && hasDenseGroups && (
          <label title="Render dense action points as chart dots">
            <input
              type="checkbox"
              checked={forceActionPointChartDots[chart]}
              onChange={e => toggleForceActionPointChartDots(chart, e.target.checked)}
            />
            <span>Force dots</span>
          </label>
        )}
      </div>
    )
  }, [
    actionPointChartVisibility,
    forceActionPointChartDots,
    selectedActionPointGroups.denseGroups.length,
    selectedStrategyCurve,
    toggleActionPointChart,
    toggleForceActionPointChartDots,
  ])

  const renderActionMarkers = useCallback((rows: Record<string, unknown>[], chart: ActionPointChartKey) => {
    if (!selectedStrategyCurve || !actionPointChartVisibility[chart]) return null
    const points = forceActionPointChartDots[chart]
      ? selectedActionPointGroups.markers.concat(selectedActionPointGroups.denseGroups.flatMap(group => group.points))
      : selectedActionPointGroups.markers

    return points.map((point, i) => {
      const marker = ACTION_MARKERS[point.type]
      if (!marker) return null
      const row = rows[point.rowIndex]
      const y = row?.[selectedStrategyCurve.dataKey]
      if (typeof y !== 'number' || !Number.isFinite(y)) return null

      const duplicateKey = `${point.date}-${point.type}`
      return (
        <ReferenceDot
          key={`${duplicateKey}-${i}`}
          x={point.date}
          y={y}
          r={5}
          fill={marker.color}
          stroke={theme.isDark ? '#111' : '#fff'}
          strokeWidth={2}
          ifOverflow="extendDomain"
          label={{ value: marker.short, position: 'top', fill: marker.color, fontSize: 10 }}
        />
      )
    })
  }, [
    actionPointChartVisibility,
    forceActionPointChartDots,
    selectedActionPointGroups,
    selectedStrategyCurve,
    theme.isDark,
  ])

  const renderDenseActionStrips = useCallback((chart: ActionPointChartKey) => {
    if (
      !chartData.labels.length ||
      !actionPointChartVisibility[chart] ||
      forceActionPointChartDots[chart] ||
      selectedActionPointGroups.denseGroups.length === 0
    ) return null
    const maxX = Math.max(chartData.labels.length - 1, 1)
    return (
      <div className="chart-action-density" aria-label="Dense action point timeline">
        {selectedActionPointGroups.denseGroups.map(group => {
          const marker = ACTION_MARKERS[group.type]
          if (!marker) return null
          const path = group.points.map(point => {
            const x = (point.rowIndex / maxX) * 1000
            return `M ${x.toFixed(2)} 0 V 10`
          }).join(' ')
          return (
            <div className="chart-action-density-row" key={group.type}>
              <span style={{ color: marker.color }}>{marker.short}</span>
              <svg viewBox="0 0 1000 10" preserveAspectRatio="none" aria-hidden="true">
                <path d={path} stroke={marker.color} strokeWidth="1.2" vectorEffect="non-scaling-stroke" />
              </svg>
              <span>{group.points.length}</span>
            </div>
          )
        })}
      </div>
    )
  }, [
    actionPointChartVisibility,
    chartData.labels,
    forceActionPointChartDots,
    selectedActionPointGroups.denseGroups,
  ])

  const renderLegend = useCallback((props: { payload?: { color?: string; value?: ReactNode }[] }): ReactNode => {
    const { payload } = props
    if (!payload?.length) return null
    return (
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem 0.8rem', fontSize: '0.78em', color: textColor, padding: '4px 8px 0' }}>
        {payload.map((entry, i) => (
          <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
            <LegendLine color={entry.color ?? textColor} strokeWidth={2} />
            <span>{entry.value}</span>
          </span>
        ))}
      </div>
    )
  }, [textColor])

  return (
    <>
      <ResultsStatsTable
        allChecked={allChecked}
        anyChecked={anyChecked}
        rows={statsRows}
        selected={selected}
        onToggleAll={toggleAll}
        onToggleCurve={toggleCurve}
      />

      <ActionDiagnosticsTable points={selectedActionDiagnostics} />

      <div className="backtest-chart-heading">
        <div className="backtest-chart-title">Portfolio Value</div>
        {renderActionDotControls('main')}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.4rem', marginBottom: '0.25rem', flexWrap: 'wrap' }}>
        {selectedStrategyCurve && (
          <ActionPointTypeFilter visibleTypes={visibleActionPointTypes} onToggle={toggleActionPointType} />
        )}
        <button
          className={`chart-scale-toggle${logScale ? ' active' : ''}`}
          type="button"
          style={{ position: 'static' }}
          onClick={() => setLogScale(value => !value)}
        >
          Log
        </button>
      </div>
      {renderDenseActionStrips('main')}
      <div className="backtest-chart-container">
        <RebalanceLineChart
          chartData={chartData.mainData}
          labelsLength={chartData.labels.length}
          gridColor={gridColor}
          textColor={textColor}
          commonLineProps={commonLineProps}
          makeTooltip={makeTooltip}
          renderLegend={renderLegend}
          renderActionMarkers={renderActionMarkers}
          actionChart="main"
          kind="money"
          logScale={logScale}
          brushFill={theme.isDark ? '#1a1a1a' : '#f8f8f8'}
        />
      </div>

      <div className="backtest-chart-heading">
        <div className="backtest-chart-title">Drawdown</div>
        {renderActionDotControls('drawdown')}
      </div>
      {renderDenseActionStrips('drawdown')}
      <div className="backtest-chart-container">
        <RebalanceLineChart
          chartData={chartData.ddData}
          labelsLength={chartData.labels.length}
          gridColor={gridColor}
          textColor={textColor}
          commonLineProps={commonLineProps}
          makeTooltip={makeTooltip}
          renderLegend={renderLegend}
          renderActionMarkers={renderActionMarkers}
          actionChart="drawdown"
          kind="drawdown"
        />
      </div>

      <div className="backtest-chart-heading">
        <div className="backtest-chart-title">Return Required to Recover</div>
        {renderActionDotControls('recover')}
      </div>
      {renderDenseActionStrips('recover')}
      <div className="backtest-chart-container">
        <RebalanceLineChart
          chartData={chartData.rtrData}
          labelsLength={chartData.labels.length}
          gridColor={gridColor}
          textColor={textColor}
          commonLineProps={commonLineProps}
          makeTooltip={makeTooltip}
          renderLegend={renderLegend}
          renderActionMarkers={renderActionMarkers}
          actionChart="recover"
          kind="recover"
        />
      </div>

      {chartData.marginData.datasets.length > 0 && (
        <>
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">Margin Utilization</div>
            {renderActionDotControls('margin')}
          </div>
          {renderDenseActionStrips('margin')}
          <div className="backtest-chart-container">
            <RebalanceLineChart
              chartData={chartData.marginData}
              labelsLength={chartData.labels.length}
              gridColor={gridColor}
              textColor={textColor}
              commonLineProps={commonLineProps}
              makeTooltip={makeTooltip}
              renderLegend={renderLegend}
              renderActionMarkers={renderActionMarkers}
              actionChart="margin"
              kind="margin"
            />
          </div>
        </>
      )}

      {vmTimingChartData && (
        <>
          <div className="backtest-chart-heading">
            <div className="backtest-chart-title">VM Timing Debug</div>
          </div>
          <div className="backtest-chart-container">
            <VmTimingLineChart
              chartData={vmTimingChartData}
              labelsLength={chartData.labels.length}
              gridColor={gridColor}
              textColor={textColor}
              commonLineProps={commonLineProps}
              renderLegend={renderLegend}
            />
          </div>
        </>
      )}
    </>
  )
}
