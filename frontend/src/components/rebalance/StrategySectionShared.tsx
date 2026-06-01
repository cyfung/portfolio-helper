import { OptionalStrategySectionKey } from './RebalanceStrategyControlUtils'

export type SelectOption = { value: string; label: string }

export type AvailableStrategySection = {
  key: OptionalStrategySectionKey
  label: string
}

export function RemoveSectionButton({
  sectionKey,
  label,
  onRemove,
}: {
  sectionKey: OptionalStrategySectionKey
  label: string
  onRemove: (key: OptionalStrategySectionKey) => void
}) {
  return (
    <button
      type="button"
      className="btn-remove strategy-section-remove"
      title={`Remove ${label}`}
      aria-label={`Remove ${label}`}
      onClick={e => {
        e.stopPropagation()
        onRemove(sectionKey)
      }}
    >
      x
    </button>
  )
}
