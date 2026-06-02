import type { AllocStrategyOption } from '@/lib/allocStrategies'
import {
  DrawdownMarginTriggerState,
  DrawdownMarginTriggerTierState,
  PORTFOLIO_TRIGGER_SOURCE_OPTIONS,
  drawdownMarginTriggerIssues,
  emptyDrawdownMarginTriggerTier,
} from '@/types/rebalanceStrategy'
import { keepSectionOpen } from './RebalanceStrategyControlUtils'
import { MarginPercentInput } from './RebalanceStrategyControls'

interface Props {
  direction: 'buy' | 'sell'
  title: string
  value: DrawdownMarginTriggerState
  triggerPlaceholder: string
  midMarginPoint: string
  sliderMax: number
  allocOptions: AllocStrategyOption[]
  onChange: (value: DrawdownMarginTriggerState) => void
  onRemove: () => void
  onCommit: () => void
}

export default function DrawdownMarginTriggerSection({
  direction,
  title,
  value,
  triggerPlaceholder,
  midMarginPoint,
  sliderMax,
  allocOptions,
  onChange,
  onRemove,
  onCommit,
}: Props) {
  const tiers = value.tiers?.length ? value.tiers : [emptyDrawdownMarginTriggerTier(direction)]
  const issues = drawdownMarginTriggerIssues(value, direction, title)

  const update = (patch: Partial<DrawdownMarginTriggerState>) => {
    const next = { ...value, ...patch }
    if (next.portfolioSource !== 'REFERENCE_PORTFOLIO') next.referenceTicker = ''
    onChange(next)
  }

  const updateTier = (tierId: string, patch: Partial<DrawdownMarginTriggerTierState>) => {
    const nextTiers = tiers.map(tier => tier.id === tierId ? { ...tier, ...patch } : tier)
    onChange(syncFirstTier(value, nextTiers, direction))
  }

  const addTier = () => {
    const prev = tiers[tiers.length - 1]
    const prevEnter = parseFloat(prev?.enterDrawdownPct ?? '')
    const prevExit = parseFloat(prev?.exitDrawdownPct ?? '')
    const nextEnter = Number.isFinite(prevEnter) ? prevEnter + 5 : 10
    const nextExit = Number.isFinite(prevExit) ? prevExit : Math.max(0, nextEnter - 5)
    const tier = {
      ...(prev ?? emptyDrawdownMarginTriggerTier(direction)),
      id: emptyDrawdownMarginTriggerTier(direction).id,
      enterDrawdownPct: String(nextEnter),
      exitDrawdownPct: String(nextExit),
    }
    onChange(syncFirstTier(value, [...tiers, tier], direction))
  }

  const removeTier = (tierId: string) => {
    if (tiers.length <= 1) return
    onChange(syncFirstTier(value, tiers.filter(tier => tier.id !== tierId), direction))
  }

  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>
        {title}
        <button
          type="button"
          className="remove-margin-btn strategy-section-remove"
          title={`Remove ${title}`}
          aria-label={`Remove ${title}`}
          onClick={e => {
            e.stopPropagation()
            onRemove()
          }}
        >
          ✕
        </button>
      </summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Reference</label>
          <select
            value={value.portfolioSource ?? 'REFERENCE_PORTFOLIO'}
            onChange={e => update({
              portfolioSource: e.target.value,
              referenceTicker: e.target.value === 'REFERENCE_PORTFOLIO' ? (value.referenceTicker ?? '') : '',
            })}
          >
            {PORTFOLIO_TRIGGER_SOURCE_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        {(value.portfolioSource ?? 'REFERENCE_PORTFOLIO') === 'REFERENCE_PORTFOLIO' && (
          <div className="strategy-row">
            <label>Reference Ticker</label>
            <input
              type="text"
              value={value.referenceTicker ?? ''}
              placeholder="Portfolio"
              aria-label={`${title} reference ticker`}
              onChange={e => update({ referenceTicker: e.target.value.toUpperCase() })}
              onBlur={onCommit}
            />
          </div>
        )}
        {direction === 'buy' && (
          <>
            <div className="strategy-row">
              <label>Momentum Months</label>
              <input
                type="number"
                min="1"
                step="1"
                value={value.momentumLookbackMonths ?? ''}
                placeholder="Optional"
                aria-label={`${title} momentum lookback months`}
                onChange={e => update({ momentumLookbackMonths: e.target.value })}
                onBlur={onCommit}
              />
            </div>
            <div className="strategy-row">
              <label>Extend Exit Months</label>
              <input
                type="number"
                min="0"
                step="1"
                value={value.exitExtensionMonths ?? ''}
                placeholder="0"
                aria-label={`${title} exit extension months`}
                onChange={e => update({ exitExtensionMonths: e.target.value })}
                onBlur={onCommit}
              />
            </div>
            <div className="strategy-row">
              <label>Exit Target Margin</label>
              <MarginPercentInput
                value={value.exitTargetMargin ?? ''}
                placeholder="Optional"
                max={sliderMax}
                compact
                ariaLabel={`${title} exit target margin`}
                onChange={margin => update({ exitTargetMargin: margin })}
                onCommit={onCommit}
              />
            </div>
          </>
        )}
        <div className="drawdown-tier-table">
          <div className="drawdown-tier-header">
            <span title="Enter drawdown percent">DD In</span>
            <span title="Exit drawdown percent">Out</span>
            <span>Trigger</span>
            <span>Restore</span>
            <span>Alloc</span>
            <span />
          </div>
          {tiers.map(tier => (
            <div key={tier.id} className="drawdown-tier-row">
              <input
                type="number"
                min="0"
                step="1"
                value={tier.enterDrawdownPct}
                aria-label={`${title} tier enter drawdown`}
                onChange={e => updateTier(tier.id, { enterDrawdownPct: e.target.value })}
                onBlur={onCommit}
              />
              <input
                type="number"
                step="1"
                value={tier.exitDrawdownPct}
                aria-label={`${title} tier exit drawdown`}
                onChange={e => updateTier(tier.id, { exitDrawdownPct: e.target.value })}
                onBlur={onCommit}
              />
              <MarginPercentInput
                value={tier.triggerMargin}
                placeholder={triggerPlaceholder}
                max={sliderMax}
                compact
                ariaLabel={`${title} tier trigger margin`}
                onChange={margin => updateTier(tier.id, { triggerMargin: margin, triggerPointIndex: '' })}
                onCommit={onCommit}
              />
              <MarginPercentInput
                value={tier.restoreMargin}
                placeholder={midMarginPoint}
                max={sliderMax}
                compact
                ariaLabel={`${title} tier restore margin`}
                onChange={margin => updateTier(tier.id, { restoreMargin: margin, restorePointIndex: '' })}
                onCommit={onCommit}
              />
              <select
                value={tier.allocStrategy ?? 'PROPORTIONAL'}
                aria-label={`${title} tier allocation strategy`}
                onChange={e => updateTier(tier.id, { allocStrategy: e.target.value })}
              >
                {allocOptions.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
              <button
                type="button"
                className="remove-margin-btn drawdown-tier-remove-btn"
                title="Remove tier"
                aria-label={`Remove ${title} tier`}
                disabled={tiers.length <= 1}
                onClick={() => removeTier(tier.id)}
              >
                ✕
              </button>
            </div>
          ))}
        </div>
        {issues.length > 0 && <div className="strategy-hint input-error-text">{issues[0]}</div>}
        <div className="strategy-row">
          <label />
          <button type="button" className="add-ticker-btn" onClick={addTier}>
            + Add Tier
          </button>
        </div>
      </div>
    </details>
  )
}

function syncFirstTier(
  state: DrawdownMarginTriggerState,
  tiers: DrawdownMarginTriggerTierState[],
  direction: 'buy' | 'sell',
): DrawdownMarginTriggerState {
  const first = tiers[0] ?? emptyDrawdownMarginTriggerTier(direction)
  return {
    ...state,
    enterDrawdownPct: first.enterDrawdownPct,
    exitDrawdownPct: first.exitDrawdownPct,
    triggerPointIndex: first.triggerPointIndex,
    triggerMargin: first.triggerMargin,
    allocStrategy: first.allocStrategy,
    restorePointIndex: first.restorePointIndex,
    restoreMargin: first.restoreMargin,
    tiers,
  }
}
