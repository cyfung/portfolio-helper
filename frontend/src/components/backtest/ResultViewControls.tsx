import type { ReactNode } from 'react'

export default function ResultViewControls({
  inflationAdjusted,
  onInflationAdjustedChange,
  unavailableReason,
  children,
}: {
  inflationAdjusted: boolean
  onInflationAdjustedChange: (value: boolean) => void
  unavailableReason?: string | null
  children?: ReactNode
}) {
  return (
    <div className="result-view-controls" aria-label="Result view controls">
      <span className="result-view-controls-title">Adjust results</span>
      <label title={unavailableReason ?? 'Display values in start-date purchasing power'}>
        <input
          type="checkbox"
          checked={inflationAdjusted && !unavailableReason}
          disabled={!!unavailableReason}
          onChange={event => onInflationAdjustedChange(event.target.checked)}
        />
        Inflation adjusted
      </label>
      {children}
      {unavailableReason && <span className="result-view-controls-notice">{unavailableReason}</span>}
    </div>
  )
}
