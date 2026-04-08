// ── DateFieldWithQuickSelect.tsx ──────────────────────────────────────────────

const YEARS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30]

interface Props {
  label: string
  inputId: string
  value: string
  onChange: (date: string) => void
}

export default function DateFieldWithQuickSelect({ label, inputId, value, onChange }: Props) {
  const quickSelectId = `${inputId}-quick`

  function handleQuickSelect(e: React.ChangeEvent<HTMLSelectElement>) {
    const years = parseInt(e.target.value, 10)
    if (!years) return
    const date = new Date()
    date.setFullYear(date.getFullYear() - years)
    onChange(date.toISOString().split('T')[0])
    e.target.value = ''
  }

  return (
    <div className="backtest-date-field">
      <label htmlFor={inputId}>{label}</label>
      <div className="date-input-row">
        <div className="date-field-box">
          <input
            type="date"
            id={inputId}
            value={value}
            onChange={e => onChange(e.target.value)}
          />
          <button
            type="button"
            className="date-clear-btn"
            title="Clear"
            style={{ visibility: value ? 'visible' : 'hidden' }}
            onClick={() => onChange('')}
          >
            ×
          </button>
        </div>
        <select id={quickSelectId} aria-label="Years back" onChange={handleQuickSelect}>
          <option value="">Yrs</option>
          {YEARS.map(y => <option key={y} value={y}>{y}Y</option>)}
        </select>
      </div>
    </div>
  )
}
