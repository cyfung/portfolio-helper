import { isValidNumberInput } from '@/lib/numberInputs'
import { REBALANCE_PERIOD_OVERRIDE_OPTIONS, RebalStrategyState } from '@/types/rebalanceStrategy'
import {
  AvailableStrategySection,
} from './StrategySectionShared'
import {
  OptionalStrategySectionKey,
  keepSectionOpen,
} from './RebalanceStrategyControlUtils'
import { MarginPercentInput, MarginPointSlider } from './RebalanceStrategyControls'

type StrategyPatch = Partial<RebalStrategyState>

export function StrategyHeader({
  idx,
  label,
  enabled,
  saveMsg,
  onLabelChange,
  onEnabledChange,
  onCommit,
  onSave,
}: {
  idx: number
  label: string
  enabled: boolean
  saveMsg: string
  onLabelChange: (value: string) => void
  onEnabledChange: (value: boolean) => void
  onCommit: () => void
  onSave: (overwrite: boolean) => void
}) {
  return (
    <div className="block-header">
      <label className="strategy-enabled-toggle">
        <span>Enabled</span>
        <input
          type="checkbox"
          checked={enabled}
          onChange={e => onEnabledChange(e.target.checked)}
        />
      </label>
      <input
        className="block-label-input"
        type="text"
        placeholder={`Strategy ${idx + 1}`}
        value={label}
        onChange={e => onLabelChange(e.target.value)}
        onBlur={onCommit}
      />
      <button
        type="button"
        className="overwrite-portfolio-btn save-portfolio-btn"
        disabled={!label.trim()}
        onClick={() => onSave(true)}
      >
        {saveMsg || 'Save'}
      </button>
      <button
        type="button"
        className="save-portfolio-btn"
        disabled={!label.trim()}
        onClick={() => onSave(false)}
      >
        Save New
      </button>
    </div>
  )
}

export function MarginSection({
  strategy,
  marginPoints,
  sliderMax,
  spreadTouched,
  onSet,
  onSpreadTouched,
  onCommit,
  onMarginChange,
  onMarginCommit,
}: {
  strategy: RebalStrategyState
  marginPoints: string[]
  sliderMax: number
  spreadTouched: boolean
  onSet: (patch: StrategyPatch) => void
  onSpreadTouched: () => void
  onCommit: () => void
  onMarginChange: (points: string[]) => void
  onMarginCommit: (points: string[]) => void
}) {
  const spreadInvalid = spreadTouched && !isValidNumberInput(strategy.marginSpread, { min: 0 })

  return (
    <details open className="strategy-subsection strategy-margin-section">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>Margin</summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Margin Points %</label>
          <MarginPointSlider
            points={marginPoints}
            max={sliderMax}
            showComfortPoints
            onChange={onMarginChange}
            onCommit={onMarginCommit}
          />
        </div>
        <div className="strategy-row">
          <label>Spread %</label>
          <input
            type="number"
            min="0"
            step="0.1"
            value={strategy.marginSpread}
            className={spreadInvalid ? 'input-error' : undefined}
            onChange={e => onSet({ marginSpread: e.target.value })}
            onBlur={() => {
              onSpreadTouched()
              onCommit()
            }}
            aria-invalid={spreadInvalid}
            title={spreadInvalid ? 'Enter a valid non-negative spread percent' : undefined}
            style={{ width: '5rem' }}
          />
        </div>
        <div className="strategy-row">
          <label>Rebalance Period</label>
          <select
            value={strategy.portfolioRebalancePeriod ?? 'INHERIT'}
            onChange={e => onSet({ portfolioRebalancePeriod: e.target.value })}
          >
            {REBALANCE_PERIOD_OVERRIDE_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Use Comfort Zone</label>
          <input
            type="checkbox"
            checked={strategy.portfolioRebalanceUseComfortZone ?? true}
            onChange={e => onSet({ portfolioRebalanceUseComfortZone: e.target.checked })}
          />
        </div>
        <div className="strategy-row">
          <label>Buy Cooldown After SH</label>
          <input
            type="number"
            min="0"
            step="1"
            value={strategy.buyCooldownAfterSellHighDays ?? '10'}
            onChange={e => onSet({ buyCooldownAfterSellHighDays: e.target.value })}
            onBlur={onCommit}
            style={{ width: '5rem' }}
          />
        </div>
        <div className="strategy-row">
          <label>Sell Cooldown After BL</label>
          <input
            type="number"
            min="0"
            step="1"
            value={strategy.sellCooldownAfterBuyLowDays ?? '10'}
            onChange={e => onSet({ sellCooldownAfterBuyLowDays: e.target.value })}
            onBlur={onCommit}
            style={{ width: '5rem' }}
          />
        </div>
      </div>
    </details>
  )
}

export function OptionalStrategySectionPicker({
  sections,
  onAdd,
}: {
  sections: AvailableStrategySection[]
  onAdd: (key: OptionalStrategySectionKey) => void
}) {
  if (sections.length === 0) return null

  return (
    <div className="strategy-subsection">
      <div className="strategy-row strategy-section-picker">
        <label>Add Section</label>
        <select
          value=""
          aria-label="Add strategy section"
          onChange={e => {
            const key = e.target.value as OptionalStrategySectionKey
            if (key) onAdd(key)
          }}
        >
          <option value="" disabled>Choose section...</option>
          {sections.map(section => (
            <option key={section.key} value={section.key}>{section.label}</option>
          ))}
        </select>
      </div>
    </div>
  )
}

export function CashflowSection({
  strategy,
  cashflowScalingMargin,
  midMarginPoint,
  sliderMax,
  onSet,
  onCommit,
}: {
  strategy: RebalStrategyState
  cashflowScalingMargin: string
  midMarginPoint: string
  sliderMax: number
  onSet: (patch: StrategyPatch) => void
  onCommit: () => void
}) {
  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>Cashflow</summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Cashflow Immediate Invest %</label>
          <input
            type="number"
            min="0"
            max="100"
            step="5"
            value={strategy.cashflowImmediateInvestPct}
            onChange={e => onSet({ cashflowImmediateInvestPct: e.target.value })}
            onBlur={onCommit}
            style={{ width: '5rem' }}
          />
        </div>
        <div className="strategy-row">
          <label>Cashflow Scaling</label>
          <MarginPercentInput
            value={cashflowScalingMargin}
            placeholder={midMarginPoint}
            max={sliderMax}
            ariaLabel="Cashflow scaling margin"
            onChange={value => onSet({ cashflowScalingMargin: value, cashflowScalingPointIndex: '' })}
            onCommit={onCommit}
          />
        </div>
      </div>
    </details>
  )
}
