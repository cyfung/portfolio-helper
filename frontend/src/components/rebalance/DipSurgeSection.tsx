// ── DipSurgeSection.tsx — Reusable Buy the Dip / Sell on Surge section ────────

import {
  DipSurgeState, PriceMoveTriggerState, ExecutionMethodState,
  PRICE_MOVE_TRIGGER_OPTIONS, EXECUTION_METHOD_OPTIONS, DIP_SURGE_SCOPE_OPTIONS,
  emptyTrigger,
} from '@/types/rebalanceStrategy'
import { MARGIN_MODE_OPTIONS, newId } from '@/types/backtest'

interface Props {
  direction: 'buy' | 'sell'
  value: DipSurgeState | null
  onChange: (v: DipSurgeState | null) => void
}

const triggerLabels: Record<string, (d: string) => string> = {
  VS_N_DAYS_AGO:  d => `${d === 'buy' ? 'Drop' : 'Rise'} vs N days ago by X%`,
  VS_RUNNING_AVG: d => `${d === 'buy' ? 'Drop' : 'Rise'} vs running avg (N days) by X%`,
  PEAK_DEVIATION: d => d === 'buy' ? 'Drawdown by X%' : 'Surge from trough by X%',
}

const methodLabels: Record<string, (d: string) => string> = {
  ONCE:        d => `${d === 'buy' ? 'Buy' : 'Sell'} Once`,
  CONSECUTIVE: d => `Consecutive ${d === 'buy' ? 'Buy' : 'Sell'}`,
  STEPPED:     d => `Averaging ${d === 'buy' ? 'Down' : 'Up'}`,
}

export default function DipSurgeSection({ direction, value, onChange }: Props) {
  const title = direction === 'buy' ? 'Buy the Dip' : 'Sell on Surge'
  const enabled = value !== null

  function enable() {
    onChange({ scope: 'INDIVIDUAL_STOCK', allocStrategy: 'PROPORTIONAL', triggers: [], execution: { method: 'ONCE' } })
  }

  function update(patch: Partial<DipSurgeState>) {
    if (!value) return
    onChange({ ...value, ...patch })
  }

  function addTrigger(type: PriceMoveTriggerState['type']) {
    if (!value) return
    onChange({ ...value, triggers: [...value.triggers, emptyTrigger(type)] })
  }

  function removeTrigger(id: string) {
    if (!value) return
    onChange({ ...value, triggers: value.triggers.filter(t => t.id !== id) })
  }

  function updateTrigger(id: string, patch: Partial<PriceMoveTriggerState>) {
    if (!value) return
    onChange({
      ...value,
      triggers: value.triggers.map(t => t.id === id ? { ...t, ...patch } as any : t),
    })
  }

  function updateExecution(exec: ExecutionMethodState) {
    if (!value) return
    onChange({ ...value, execution: exec })
  }

  return (
    <details open={enabled}>
      <summary className="strategy-section-title">
        {title}
        <label className="dip-surge-toggle" onClick={e => e.stopPropagation()}>
          <input type="checkbox" checked={enabled} onChange={e => e.target.checked ? enable() : onChange(null)} />
          {' '}Enable
        </label>
      </summary>

      {enabled && value && (
        <div className="strategy-section-body">
          {/* Scope */}
          <div className="strategy-row">
            <label>Scope</label>
            <select value={value.scope} onChange={e => update({ scope: e.target.value })}>
              {DIP_SURGE_SCOPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>

          {value.scope === 'WHOLE_PORTFOLIO' && (
            <div className="strategy-row">
              <label>Allocation Strategy</label>
              <select value={value.allocStrategy} onChange={e => update({ allocStrategy: e.target.value })}>
                {MARGIN_MODE_OPTIONS.filter(o => o.value !== 'DAILY').map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
          )}

          {/* Triggers */}
          <div className="strategy-row">
            <label>Triggers</label>
            <select
              defaultValue=""
              onChange={e => { if (e.target.value) { addTrigger(e.target.value as any); e.target.value = '' } }}
            >
              <option value="">+ Add trigger…</option>
              {PRICE_MOVE_TRIGGER_OPTIONS.map(o => (
                <option key={o.value} value={o.value}>{triggerLabels[o.value]?.(direction) ?? o.label}</option>
              ))}
            </select>
          </div>

          {value.triggers.map(t => (
            <div key={t.id} className="dip-surge-trigger-row">
              <span className="dip-surge-trigger-label">
                {triggerLabels[t.type]?.(direction) ?? t.type}
              </span>
              {t.type !== 'PEAK_DEVIATION' && (
                <label>
                  N:
                  <input
                    type="number" min="1" step="1"
                    value={(t as any).nDays}
                    onChange={e => updateTrigger(t.id, { nDays: e.target.value } as any)}
                    style={{ width: '4rem' }}
                  />
                </label>
              )}
              <label>
                {direction === 'buy' ? 'Drop' : 'Rise'} %:
                <input
                  type="number" min="0" step="1"
                  value={t.pct}
                  onChange={e => updateTrigger(t.id, { pct: e.target.value } as any)}
                  style={{ width: '4rem' }}
                />
              </label>
              <button type="button" className="dip-surge-remove" onClick={() => removeTrigger(t.id)}>×</button>
            </div>
          ))}

          {/* Execution Method */}
          <div className="strategy-row">
            <label>Method</label>
            <select
              value={value.execution.method}
              onChange={e => updateExecution({ method: e.target.value as any } as ExecutionMethodState)}
            >
              {EXECUTION_METHOD_OPTIONS.map(o => (
                <option key={o.value} value={o.value}>{methodLabels[o.value]?.(direction) ?? o.label}</option>
              ))}
            </select>
          </div>

          {value.execution.method === 'CONSECUTIVE' && (
            <div className="strategy-row">
              <label>Days (Y)</label>
              <input
                type="number" min="2" step="1"
                value={value.execution.days}
                onChange={e => updateExecution({ method: 'CONSECUTIVE', days: e.target.value })}
                style={{ width: '5rem' }}
              />
            </div>
          )}

          {value.execution.method === 'STEPPED' && (
            <>
              <div className="strategy-row">
                <label>Portions</label>
                <input
                  type="number" min="2" step="1"
                  value={value.execution.portions}
                  onChange={e => updateExecution({ method: 'STEPPED', portions: e.target.value, additionalPct: value.execution.method === 'STEPPED' ? value.execution.additionalPct : '5' })}
                  style={{ width: '5rem' }}
                />
              </div>
              <div className="strategy-row">
                <label>Each additional %</label>
                <input
                  type="number" min="0.1" step="0.5"
                  value={value.execution.method === 'STEPPED' ? value.execution.additionalPct : ''}
                  onChange={e => updateExecution({ method: 'STEPPED', portions: value.execution.method === 'STEPPED' ? value.execution.portions : '3', additionalPct: e.target.value })}
                  style={{ width: '5rem' }}
                />
              </div>
            </>
          )}
        </div>
      )}
    </details>
  )
}
