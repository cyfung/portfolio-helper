import {
  DrawdownMarginOverrideState,
  MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS,
  PORTFOLIO_TRIGGER_SOURCE_OPTIONS,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS,
  RebalStrategyState,
  VmTimingMrState,
} from '@/types/rebalanceStrategy'
import { OptionalStrategySectionKey, keepSectionOpen } from './RebalanceStrategyControlUtils'
import { MarginPercentInput } from './RebalanceStrategyControls'
import { RemoveSectionButton, SelectOption } from './StrategySectionShared'

type StrategyPatch = Partial<RebalStrategyState>

export function MarginRebalanceSection({
  strategy,
  allocOptions,
  marginRebalanceRestoreMargin,
  midMarginPoint,
  sliderMax,
  onSet,
  onRemove,
  onCommit,
}: {
  strategy: RebalStrategyState
  allocOptions: SelectOption[]
  marginRebalanceRestoreMargin: string
  midMarginPoint: string
  sliderMax: number
  onSet: (patch: StrategyPatch) => void
  onRemove: (key: OptionalStrategySectionKey) => void
  onCommit: () => void
}) {
  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>
        Margin Rebalance
        <RemoveSectionButton sectionKey="marginRebalance" label="Margin Rebalance" onRemove={onRemove} />
      </summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Margin Rebalance</label>
          <select value={strategy.rebalancePeriod} onChange={e => onSet({ rebalancePeriod: e.target.value })}>
            {REBALANCE_PERIOD_OVERRIDE_OPTIONS.filter(option => option.value !== 'INHERIT').map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Trade Direction</label>
          <select
            value={strategy.marginRebalanceTradeDirection ?? 'BOTH'}
            onChange={e => onSet({
              marginRebalanceTradeDirection: e.target.value as RebalStrategyState['marginRebalanceTradeDirection'],
            })}
          >
            {MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Alloc Strategy</label>
          <select
            value={strategy.rebalanceAllocStrategy ?? 'PROPORTIONAL'}
            onChange={e => onSet({ rebalanceAllocStrategy: e.target.value })}
          >
            {allocOptions.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        {(strategy.marginRebalanceTradeDirection ?? 'BOTH') === 'BOTH' ? (
          <div className="strategy-row">
            <label>Use Comfort Zone</label>
            <input
              type="checkbox"
              checked={strategy.useComfortZone ?? true}
              onChange={e => onSet({ useComfortZone: e.target.checked })}
            />
          </div>
        ) : (
          <div className="strategy-row">
            <label>Restore To</label>
            <MarginPercentInput
              value={marginRebalanceRestoreMargin}
              placeholder={midMarginPoint}
              max={sliderMax}
              ariaLabel="Margin rebalance restore margin"
              onChange={value => onSet({ marginRebalanceRestoreMargin: value })}
              onCommit={onCommit}
            />
          </div>
        )}
      </div>
    </details>
  )
}

export function DrawdownMarginOverrideSection({
  value,
  allocOptions,
  sliderMax,
  onChange,
  onRemove,
  onCommit,
}: {
  value: DrawdownMarginOverrideState
  allocOptions: SelectOption[]
  sliderMax: number
  onChange: (patch: Partial<DrawdownMarginOverrideState>) => void
  onRemove: (key: OptionalStrategySectionKey) => void
  onCommit: () => void
}) {
  const tradeDirection = value.tradeDirection ?? 'BOTH'
  const allocStrategyValue = tradeDirection === 'SELL_ONLY'
    ? (value.sellAllocStrategy ?? value.allocStrategy ?? 'PROPORTIONAL')
    : (value.buyAllocStrategy ?? value.allocStrategy ?? 'PROPORTIONAL')

  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>
        Drawdown MR Override
        <RemoveSectionButton sectionKey="drawdownMarginOverride" label="Drawdown MR Override" onRemove={onRemove} />
      </summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Trigger Source</label>
          <select
            value={value.portfolioSource ?? 'REFERENCE_PORTFOLIO'}
            onChange={e => onChange({
              portfolioSource: e.target.value,
              referenceTicker: e.target.value === 'REFERENCE_PORTFOLIO' ? (value.referenceTicker ?? '') : '',
            })}
          >
            {PORTFOLIO_TRIGGER_SOURCE_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
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
              aria-label="Drawdown MR override reference ticker"
              onChange={e => onChange({ referenceTicker: e.target.value.toUpperCase() })}
              onBlur={onCommit}
            />
          </div>
        )}
        <div className="strategy-row">
          <label>Enter DD %</label>
          <input type="number" min="0" step="1" value={value.enterDrawdownPct}
            onChange={e => onChange({ enterDrawdownPct: e.target.value })} onBlur={onCommit} style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Exit DD %</label>
          <input type="number" min="0" step="1" value={value.exitDrawdownPct}
            onChange={e => onChange({ exitDrawdownPct: e.target.value })} onBlur={onCommit} style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Override MR</label>
          <select value={value.rebalancePeriod} onChange={e => onChange({ rebalancePeriod: e.target.value })}>
            {REBALANCE_PERIOD_OVERRIDE_OPTIONS.filter(option => option.value !== 'INHERIT').map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Rebalance On Enter</label>
          <input type="checkbox" checked={value.rebalanceOnEnter ?? true} onChange={e => onChange({ rebalanceOnEnter: e.target.checked })} />
        </div>
        <div className="strategy-row">
          <label>Target Margin</label>
          <MarginPercentInput
            value={value.targetMargin || '95'}
            placeholder="95"
            max={sliderMax}
            ariaLabel="Drawdown MR override target margin"
            onChange={targetMargin => onChange({ targetMargin })}
            onCommit={onCommit}
          />
        </div>
        <div className="strategy-row">
          <label>Trade Direction</label>
          <select value={tradeDirection} onChange={e => onChange({
            tradeDirection: e.target.value as RebalStrategyState['marginRebalanceTradeDirection'],
          })}>
            {MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        {tradeDirection === 'BOTH' ? (
          <>
            <div className="strategy-row">
              <label>Buy Alloc Strategy</label>
              <select value={value.buyAllocStrategy ?? value.allocStrategy ?? 'PROPORTIONAL'} onChange={e => onChange({ buyAllocStrategy: e.target.value })}>
                {allocOptions.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Sell Alloc Strategy</label>
              <select value={value.sellAllocStrategy ?? value.allocStrategy ?? 'PROPORTIONAL'} onChange={e => onChange({ sellAllocStrategy: e.target.value })}>
                {allocOptions.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </div>
          </>
        ) : (
          <div className="strategy-row">
            <label>Alloc Strategy</label>
            <select
              value={allocStrategyValue}
              onChange={e => onChange(
                tradeDirection === 'SELL_ONLY'
                  ? { sellAllocStrategy: e.target.value, allocStrategy: e.target.value }
                  : { buyAllocStrategy: e.target.value, allocStrategy: e.target.value },
              )}
            >
              {allocOptions.map(option => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </div>
        )}
      </div>
    </details>
  )
}

export function VmTimingMrSection({
  value,
  allocOptions,
  sliderMax,
  onChange,
  onRemove,
  onCommit,
}: {
  value: VmTimingMrState
  allocOptions: SelectOption[]
  sliderMax: number
  onChange: (patch: Partial<VmTimingMrState>) => void
  onRemove: (key: OptionalStrategySectionKey) => void
  onCommit: () => void
}) {
  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>
        VM-timing-MR
        <RemoveSectionButton sectionKey="vmTimingMr" label="VM-timing-MR" onRemove={onRemove} />
      </summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>CAPE Source</label>
          <select value={value.capeSource} onChange={e => onChange({ capeSource: e.target.value as VmTimingMrState['capeSource'] })}>
            <option value="WORLD">World CAPE</option>
            <option value="US">US CAPE</option>
          </select>
        </div>
        <div className="strategy-row">
          <label>Lower Margin %</label>
          <input type="number" min="-100" max={sliderMax} step="5" value={value.lowerMargin}
            onChange={e => onChange({ lowerMargin: e.target.value })} onBlur={onCommit} style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Upper Margin %</label>
          <input type="number" min="-100" max={sliderMax} step="5" value={value.upperMargin}
            onChange={e => onChange({ upperMargin: e.target.value })} onBlur={onCommit} style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Momentum Months</label>
          <input type="number" min="1" max="120" step="1" value={value.momentumLookbackMonths}
            onChange={e => onChange({ momentumLookbackMonths: e.target.value })} onBlur={onCommit} style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Momentum Ref</label>
          <select
            value={value.momentumSource ?? 'REFERENCE_PORTFOLIO'}
            onChange={e => onChange({
              momentumSource: e.target.value,
              momentumReferenceTicker: e.target.value === 'REFERENCE_PORTFOLIO' ? (value.momentumReferenceTicker ?? '') : '',
            })}
          >
            {PORTFOLIO_TRIGGER_SOURCE_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        {(value.momentumSource ?? 'REFERENCE_PORTFOLIO') === 'REFERENCE_PORTFOLIO' && (
          <div className="strategy-row">
            <label>Reference Ticker</label>
            <input
              type="text"
              value={value.momentumReferenceTicker ?? ''}
              placeholder="Portfolio"
              aria-label="VM timing momentum reference ticker"
              onChange={e => onChange({ momentumReferenceTicker: e.target.value.toUpperCase() })}
              onBlur={onCommit}
            />
          </div>
        )}
        <div className="strategy-row">
          <label>Rebalance Period</label>
          <select value={value.rebalancePeriod} onChange={e => onChange({ rebalancePeriod: e.target.value })}>
            {REBALANCE_PERIOD_OVERRIDE_OPTIONS.filter(option => option.value !== 'INHERIT').map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Alloc Strategy</label>
          <select value={value.allocStrategy} onChange={e => onChange({ allocStrategy: e.target.value })}>
            {allocOptions.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
      </div>
    </details>
  )
}
