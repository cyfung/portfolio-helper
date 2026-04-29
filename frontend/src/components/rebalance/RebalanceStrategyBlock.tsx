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

function normalizePointIndex(v: string | undefined) {
  const n = parseInt(v ?? '', 10)
  return Number.isFinite(n) && n >= 0 && n < 5 ? String(n) : '2'
}

function pointLabel(points: string[], i: number) {
  return `${points[i] ?? DEFAULT_POINTS[i]}%`
}

function MarginPointSlider({
  points, max, onChange,
}: { points: string[]; max: number; onChange: (points: string[]) => void }) {
  const safePoints = DEFAULT_POINTS.map((def, i) => points?.[i] ?? def)

  function updatePoint(i: number, value: string) {
    const next = [...safePoints]
    next[i] = value
    onChange(next)
  }

  return (
    <div className="margin-point-slider">
      <div className="margin-point-range-stack">
        {safePoints.map((p, i) => (
          <input
            key={i}
            type="range"
            min="0"
            max={max}
            step="1"
            value={p}
            aria-label={`Margin point ${i + 1}`}
            onChange={e => updatePoint(i, e.target.value)}
          />
        ))}
      </div>
      <div className="margin-point-values">
        {safePoints.map((p, i) => (
          <input
            key={i}
            type="number"
            min="0"
            max={max}
            step="1"
            value={p}
            aria-label={`Margin point ${i + 1} value`}
            onChange={e => updatePoint(i, e.target.value)}
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
        <summary className="strategy-section-title">Basic Settings</summary>
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
            <input type="number" min="0" step="0.1" value={s.marginSpread}
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
          <div className="strategy-row">
            <label>Cashflow Immediate Invest %</label>
            <input type="number" min="0" max="100" step="5" value={s.cashflowImmediateInvestPct}
              onChange={e => set({ cashflowImmediateInvestPct: e.target.value })} style={{ width: '5rem' }} />
          </div>
          <div className="strategy-row">
            <label>Scaling</label>
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
              <label>Reference Point</label>
              <select value={normalizePointIndex(s.buyLowPointIndex)} onChange={e => set({ buyLowPointIndex: e.target.value })}>
                {marginPoints.map((_, i) => <option key={i} value={i}>{pointLabel(marginPoints, i)}</option>)}
              </select>
            </div>
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
              <label>Reference Point</label>
              <select value={normalizePointIndex(s.sellHighPointIndex)} onChange={e => set({ sellHighPointIndex: e.target.value })}>
                {marginPoints.map((_, i) => <option key={i} value={i}>{pointLabel(marginPoints, i)}</option>)}
              </select>
            </div>
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
