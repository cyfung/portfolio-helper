import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  RebalStrategyState,
  DipSurgeState,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS,
} from '@/types/rebalanceStrategy'
import { MARGIN_MODE_OPTIONS } from '@/types/backtest'
import DipSurgeSection from './DipSurgeSection'

interface Props {
  idx: number
  value: RebalStrategyState
  onChange: (s: RebalStrategyState) => void
  sliderMax?: number
}

const DEFAULT_POINTS = ['40', '45', '50', '55', '60']

function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}

function parsePoint(v: string | undefined, fallback: number) {
  const n = parseInt(v ?? '', 10)
  return Number.isFinite(n) ? n : fallback
}

function normalizeMarginPoints(points: string[] | undefined, max: number) {
  const sliderMax = Math.max(4, Math.floor(max))
  const values = DEFAULT_POINTS.map((def, i) => clamp(parsePoint(points?.[i], parseInt(def, 10)), 0, sliderMax))

  values[0] = clamp(values[0], 0, sliderMax - 4)
  values[1] = clamp(values[1], values[0] + 1, sliderMax - 3)
  values[2] = clamp(values[2], values[1] + 1, sliderMax - 2)
  values[3] = clamp(values[3], values[2] + 1, sliderMax - 1)
  values[4] = clamp(values[4], values[3] + 1, sliderMax)

  for (let i = 3; i >= 0; i -= 1) {
    values[i] = Math.min(values[i], values[i + 1] - 1)
  }
  for (let i = 1; i < values.length; i += 1) {
    values[i] = Math.max(values[i], values[i - 1] + 1)
  }

  return values
}

function pointLabel(points: string[], i: number) {
  return `${points[i] ?? DEFAULT_POINTS[i]}%`
}

function MarginPointSlider({
  points, max, onChange,
}: { points: string[]; max: number; onChange: (points: string[]) => void }) {
  const trackRef = useRef<HTMLDivElement>(null)
  const draggingRef = useRef<number | null>(null)
  const [activeThumb, setActiveThumb] = useState<number | null>(null)
  const safePoints = normalizeMarginPoints(points, max)
  const [minPoint, maxPoint] = [safePoints[0], safePoints[4]]
  const span = Math.max(maxPoint - minPoint, 1)

  function emit(values: number[]) {
    onChange(values.map(String))
  }

  function updatePoint(i: number, value: number) {
    const next = [...safePoints]

    if (i === 0) {
      next[0] = clamp(value, 0, next[1] - 1)
    } else if (i === 4) {
      next[4] = clamp(value, next[3] + 1, Math.max(4, Math.floor(max)))
    } else {
      next[i] = clamp(value, next[i - 1] + 1, next[i + 1] - 1)
    }

    emit(next)
  }

  const valueFromClientX = useCallback((clientX: number) => {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect) return minPoint
    const pct = clamp((clientX - rect.left) / rect.width, 0, 1)
    return Math.round(minPoint + pct * span)
  }, [minPoint, span])

  const handlePointerMove = useCallback((event: PointerEvent) => {
    const i = draggingRef.current
    if (i == null) return
    updatePoint(i, valueFromClientX(event.clientX))
  }, [safePoints, valueFromClientX])

  useEffect(() => {
    const handlePointerUp = () => {
      draggingRef.current = null
      setActiveThumb(null)
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp)
    window.addEventListener('pointercancel', handlePointerUp)
    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
      window.removeEventListener('pointercancel', handlePointerUp)
    }
  }, [handlePointerMove])

  function startDrag(i: number, event: ReactPointerEvent<HTMLButtonElement>) {
    event.preventDefault()
    draggingRef.current = i
    setActiveThumb(i)
    updatePoint(i, valueFromClientX(event.clientX))
  }

  function pctFromValue(value: number) {
    return `${((value - minPoint) / span) * 100}%`
  }

  return (
    <div className="margin-point-slider">
      <div className="margin-point-range-stack" ref={trackRef}>
        <div className="margin-point-track" />
        <div
          className="margin-point-track-active"
          style={{ left: pctFromValue(safePoints[1]), right: `${100 - parseFloat(pctFromValue(safePoints[3]))}%` }}
        />
        {[1, 2, 3].map(i => (
          <button
            key={i}
            type="button"
            className={`margin-point-thumb${activeThumb === i ? ' active' : ''}`}
            style={{ left: pctFromValue(safePoints[i]) }}
            aria-label={`Margin point ${i + 1}`}
            onPointerDown={e => startDrag(i, e)}
          />
        ))}
      </div>
      <div className="margin-point-values">
        {safePoints.map((p, i) => (
          <input
            className="margin-point-number-input"
            key={i}
            type="number"
            min="0"
            max={max}
            step="1"
            value={p}
            aria-label={`Margin point ${i + 1} value`}
            onChange={e => updatePoint(i, parsePoint(e.target.value, p))}
          />
        ))}
      </div>
    </div>
  )
}

export default function RebalanceStrategyBlock({ idx, value, onChange, sliderMax = 150 }: Props) {
  const s = value
  const set = (patch: Partial<RebalStrategyState>) => onChange({ ...s, ...patch })
  const marginPoints = DEFAULT_POINTS.map((def, i) => s.marginPoints?.[i] ?? def)
  const cashflowPointIndex = s.cashflowScalingPointIndex ?? '3'

  return (
    <div className="portfolio-block">
      <div className="block-header">
        <input
          className="block-label-input"
          type="text"
          placeholder={`Strategy ${idx + 1}`}
          value={s.label}
          onChange={e => set({ label: e.target.value })}
        />
      </div>

      <details open>
        <summary className="strategy-section-title">General</summary>
        <div className="strategy-section-body">
          <div className="strategy-row">
            <label>Margin Points %</label>
            <MarginPointSlider
              points={marginPoints}
              max={sliderMax}
              onChange={points => set({ marginPoints: points, marginRatio: points[2] })}
            />
          </div>
          <div className="strategy-row">
            <label>Spread %</label>
            <input className="no-spinner" type="number" min="0" step="0.1" value={s.marginSpread}
              onChange={e => set({ marginSpread: e.target.value })} style={{ width: '5rem' }} />
          </div>
          <div className="strategy-row">
            <label>Rebalance Period</label>
            <select value={s.rebalancePeriod} onChange={e => set({ rebalancePeriod: e.target.value })}>
              {REBALANCE_PERIOD_OVERRIDE_OPTIONS.map(o => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>
        </div>
      </details>

      <details open>
        <summary className="strategy-section-title">Cashflow</summary>
        <div className="strategy-section-body">
          <div className="strategy-row">
            <label>Cashflow Immediate Invest %</label>
            <input className="no-spinner" type="number" min="0" max="100" step="5" value={s.cashflowImmediateInvestPct}
              onChange={e => set({ cashflowImmediateInvestPct: e.target.value })} style={{ width: '5rem' }} />
          </div>
          <div className="strategy-row">
            <label>Cashflow Scaling</label>
            <select value={cashflowPointIndex} onChange={e => set({ cashflowScalingPointIndex: e.target.value })}>
              <option value="0">0%</option>
              {marginPoints.map((_, i) => (
                <option key={i} value={String(i + 1)}>{pointLabel(marginPoints, i)}</option>
              ))}
            </select>
          </div>
        </div>
      </details>

      <details open={s.buyLowEnabled}>
        <summary className="strategy-section-title">
          Buy on Low Margin
          <label className="dip-surge-toggle" onClick={e => e.stopPropagation()}>
            <input type="checkbox" checked={s.buyLowEnabled}
              onChange={e => set({ buyLowEnabled: e.target.checked })} />
            {' '}Enable
          </label>
        </summary>
        {s.buyLowEnabled && (
          <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.buyLowAllocStrategy}
                onChange={e => set({ buyLowAllocStrategy: e.target.value })}>
                {MARGIN_MODE_OPTIONS.filter(o => o.value !== 'DAILY').map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
          </div>
        )}
      </details>

      <details open={s.sellHighEnabled}>
        <summary className="strategy-section-title">
          Sell on High Margin
          <label className="dip-surge-toggle" onClick={e => e.stopPropagation()}>
            <input type="checkbox" checked={s.sellHighEnabled}
              onChange={e => set({ sellHighEnabled: e.target.checked })} />
            {' '}Enable
          </label>
        </summary>
        {s.sellHighEnabled && (
          <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.sellHighAllocStrategy}
                onChange={e => set({ sellHighAllocStrategy: e.target.value })}>
                {MARGIN_MODE_OPTIONS.filter(o => o.value !== 'DAILY').map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
          </div>
        )}
      </details>

      <DipSurgeSection
        direction="buy"
        value={s.buyTheDip}
        onChange={(v: DipSurgeState | null) => set({ buyTheDip: v })}
        marginPoints={marginPoints}
      />
      <DipSurgeSection
        direction="sell"
        value={s.sellOnSurge}
        onChange={(v: DipSurgeState | null) => set({ sellOnSurge: v })}
        marginPoints={marginPoints}
      />
    </div>
  )
}
