import { RebalStrategyState } from '@/types/rebalanceStrategy'
import { DEFAULT_POINTS, OptionalStrategySectionKey, keepSectionOpen } from './RebalanceStrategyControlUtils'
import { MarginPercentInput } from './RebalanceStrategyControls'
import { RemoveSectionButton, SelectOption } from './StrategySectionShared'

type StrategyPatch = Partial<RebalStrategyState>

type MarginActionSectionProps = {
  strategy: RebalStrategyState
  allocOptions: SelectOption[]
  triggerMargin: string
  restoreMargin: string
  marginPoints: string[]
  midMarginPoint: string
  sliderMax: number
  onSet: (patch: StrategyPatch) => void
  onRemove: (key: OptionalStrategySectionKey) => void
  onCommit: () => void
}

export function BuyLowSection({
  strategy,
  allocOptions,
  triggerMargin,
  restoreMargin,
  marginPoints,
  midMarginPoint,
  sliderMax,
  onSet,
  onRemove,
  onCommit,
}: MarginActionSectionProps) {
  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>
        BL
        <RemoveSectionButton sectionKey="buyLow" label="BL" onRemove={onRemove} />
      </summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Trigger At</label>
          <MarginPercentInput
            value={triggerMargin}
            placeholder={marginPoints[0] ?? DEFAULT_POINTS[0]}
            max={sliderMax}
            ariaLabel="BL trigger margin"
            onChange={value => onSet({ buyLowTriggerMargin: value, buyLowTriggerPointIndex: '' })}
            onCommit={onCommit}
          />
        </div>
        <div className="strategy-row">
          <label>Alloc Strategy</label>
          <select value={strategy.buyLowAllocStrategy} onChange={e => onSet({ buyLowAllocStrategy: e.target.value })}>
            {allocOptions.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Restore To</label>
          <MarginPercentInput
            value={restoreMargin}
            placeholder={midMarginPoint}
            max={sliderMax}
            ariaLabel="Buy low restore margin"
            onChange={value => onSet({ buyLowRestoreMargin: value, buyLowRestorePointIndex: '' })}
            onCommit={onCommit}
          />
        </div>
      </div>
    </details>
  )
}

export function SellHighSection({
  strategy,
  allocOptions,
  triggerMargin,
  restoreMargin,
  marginPoints,
  midMarginPoint,
  sliderMax,
  onSet,
  onRemove,
  onCommit,
}: MarginActionSectionProps) {
  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>
        SH
        <RemoveSectionButton sectionKey="sellHigh" label="SH" onRemove={onRemove} />
      </summary>
      <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Trigger At</label>
          <MarginPercentInput
            value={triggerMargin}
            placeholder={marginPoints[4] ?? DEFAULT_POINTS[4]}
            max={sliderMax}
            ariaLabel="SH trigger margin"
            onChange={value => onSet({ sellHighTriggerMargin: value, sellHighTriggerPointIndex: '' })}
            onCommit={onCommit}
          />
        </div>
        <div className="strategy-row">
          <label>Alloc Strategy</label>
          <select value={strategy.sellHighAllocStrategy} onChange={e => onSet({ sellHighAllocStrategy: e.target.value })}>
            {allocOptions.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Restore To</label>
          <MarginPercentInput
            value={restoreMargin}
            placeholder={midMarginPoint}
            max={sliderMax}
            ariaLabel="Sell high restore margin"
            onChange={value => onSet({ sellHighRestoreMargin: value, sellHighRestorePointIndex: '' })}
            onCommit={onCommit}
          />
        </div>
      </div>
    </details>
  )
}
