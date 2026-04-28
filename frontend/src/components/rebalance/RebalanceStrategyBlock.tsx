// ── RebalanceStrategyBlock.tsx — One strategy configuration block ─────────────

import {
  RebalStrategyState,
  DipSurgeState,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS,
  CASHFLOW_SCALING_OPTIONS,
} from '@/types/rebalanceStrategy'
import { MARGIN_MODE_OPTIONS } from '@/types/backtest'
import DipSurgeSection from './DipSurgeSection'

interface Props {
  idx: number
  value: RebalStrategyState
  onChange: (s: RebalStrategyState) => void
}

export default function RebalanceStrategyBlock({ idx, value, onChange }: Props) {
  const s = value
  const set = (patch: Partial<RebalStrategyState>) => onChange({ ...s, ...patch })

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

      {/* ── Section 1: Basic Settings ───────────────────────────────────────── */}
      <details open>
        <summary className="strategy-section-title">Basic Settings</summary>
        <div className="strategy-section-body">
          <div className="strategy-row">
            <label>Margin Ratio %</label>
            <input type="number" min="0" step="1" value={s.marginRatio}
              onChange={e => set({ marginRatio: e.target.value })} style={{ width: '5rem' }} />
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
            <select value={s.cashflowScaling} onChange={e => set({ cashflowScaling: e.target.value })}>
              {CASHFLOW_SCALING_OPTIONS.map(o => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>
        </div>
      </details>

      {/* ── Section 2: Margin Deviation Mode ───────────────────────────────── */}
      <details open>
        <summary className="strategy-section-title">Margin Deviation Mode</summary>
        <div className="strategy-section-body">
          <div className="strategy-row">
            <label>Mode</label>
            <label style={{ display: 'inline-flex', gap: '1rem' }}>
              <label>
                <input type="radio" name={`dev-mode-${idx}`} value="ABSOLUTE"
                  checked={s.deviationMode === 'ABSOLUTE'}
                  onChange={() => set({ deviationMode: 'ABSOLUTE' })} />
                {' '}Absolute
              </label>
              <label>
                <input type="radio" name={`dev-mode-${idx}`} value="RELATIVE"
                  checked={s.deviationMode === 'RELATIVE'}
                  onChange={() => set({ deviationMode: 'RELATIVE' })} />
                {' '}Relative
              </label>
            </label>
          </div>
        </div>
      </details>

      {/* ── Section 3: Buy on Low Margin ───────────────────────────────────── */}
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
              <label>Threshold ({devLabel})</label>
              <input type="number" min="0" step="1" placeholder="required" value={s.buyLowDeviationPct}
                onChange={e => set({ buyLowDeviationPct: e.target.value })}
                style={{ width: '6rem', borderColor: !s.buyLowDeviationPct.trim() ? 'var(--color-danger, red)' : undefined }} />
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

      {/* ── Section 4: Sell on High Margin ─────────────────────────────────── */}
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
              <label>Threshold ({devLabel})</label>
              <input type="number" min="0" step="1" placeholder="required" value={s.sellHighDeviationPct}
                onChange={e => set({ sellHighDeviationPct: e.target.value })}
                style={{ width: '6rem', borderColor: !s.sellHighDeviationPct.trim() ? 'var(--color-danger, red)' : undefined }} />
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

      {/* ── Sections 5 & 6: Dip / Surge ────────────────────────────────────── */}
      <DipSurgeSection
        direction="buy"
        value={s.buyTheDip}
        onChange={(v: DipSurgeState | null) => set({ buyTheDip: v })}
      />
      <DipSurgeSection
        direction="sell"
        value={s.sellOnSurge}
        onChange={(v: DipSurgeState | null) => set({ sellOnSurge: v })}
      />
    </div>
  )
}
