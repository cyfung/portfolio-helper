// ── DateFieldWithQuickSelect.tsx — Port of dateFieldWithQuickSelect() ─────────

const YEARS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30]

interface Props {
  label: string
  inputId: string
}

export default function DateFieldWithQuickSelect({ label, inputId }: Props) {
  const quickSelectId = `${inputId}-quick`
  return (
    <div className="backtest-date-field">
      <label htmlFor={inputId}>{label}</label>
      <div className="date-input-row">
        <div className="date-field-box">
          <input type="date" id={inputId} />
          <button
            type="button"
            className="date-clear-btn"
            data-target={inputId}
            title="Clear"
            style={{ visibility: 'hidden' }}
          >
            ×
          </button>
        </div>
        <select id={quickSelectId} aria-label="Years back">
          <option value="">Yrs</option>
          {YEARS.map(y => <option key={y} value={y}>{y}Y</option>)}
        </select>
      </div>
    </div>
  )
}
