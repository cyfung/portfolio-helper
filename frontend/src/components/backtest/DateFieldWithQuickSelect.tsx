// ── DateFieldWithQuickSelect.tsx ──────────────────────────────────────────────

const QUICK_SELECT_PERIODS = [
  { label: '1M', unit: 'month', amount: 1 },
  { label: '3M', unit: 'month', amount: 3 },
  { label: '6M', unit: 'month', amount: 6 },
  ...[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30].map(amount => ({
    label: `${amount}Y`,
    unit: 'year',
    amount,
  })),
] as const

function formatLocalDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

interface Props {
  label: string
  inputId: string
  value: string
  onChange: (date: string) => void
}

export default function DateFieldWithQuickSelect({ label, inputId, value, onChange }: Props) {
  const quickSelectId = `${inputId}-quick`

  function handleQuickSelect(e: React.ChangeEvent<HTMLSelectElement>) {
    const period = QUICK_SELECT_PERIODS.find(option => option.label === e.target.value)
    if (!period) return
    const date = new Date()
    if (period.unit === 'month') {
      date.setMonth(date.getMonth() - period.amount)
    } else {
      date.setFullYear(date.getFullYear() - period.amount)
    }
    onChange(formatLocalDate(date))
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
        <select id={quickSelectId} aria-label="Date period back" onChange={handleQuickSelect}>
          <option value="">Period</option>
          {QUICK_SELECT_PERIODS.map(period => (
            <option key={period.label} value={period.label}>{period.label}</option>
          ))}
        </select>
      </div>
    </div>
  )
}
