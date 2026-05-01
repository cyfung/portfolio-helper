import { CASHFLOW_FREQUENCY_OPTIONS, type CashflowFormState } from '@/types/backtest'

interface Props extends CashflowFormState {
  idPrefix: string
  onStartingBalanceChange: (value: string) => void
  onCashflowAmountChange: (value: string) => void
  onCashflowFrequencyChange: (value: string) => void
}

export default function CashflowControls({
  idPrefix,
  startingBalance,
  cashflowAmount,
  cashflowFrequency,
  onStartingBalanceChange,
  onCashflowAmountChange,
  onCashflowFrequencyChange,
}: Props) {
  const startingBalanceId = `${idPrefix}-starting-balance`
  const cashflowAmountId = `${idPrefix}-cashflow-amount`
  const cashflowFrequencyId = `${idPrefix}-cashflow-frequency`

  return (
    <div className="backtest-section backtest-cashflow-row">
      <div>
        <label htmlFor={startingBalanceId}>Starting Balance</label>
        <input
          type="number"
          id={startingBalanceId}
          min="0"
          step="100"
          value={startingBalance}
          onChange={e => onStartingBalanceChange(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor={cashflowAmountId}>Cashflow Amount</label>
        <input
          type="number"
          id={cashflowAmountId}
          placeholder="e.g. 1000"
          min="0"
          step="100"
          value={cashflowAmount}
          onChange={e => onCashflowAmountChange(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor={cashflowFrequencyId}>Cashflow Frequency</label>
        <select
          id={cashflowFrequencyId}
          value={cashflowFrequency}
          onChange={e => onCashflowFrequencyChange(e.target.value)}
        >
          {CASHFLOW_FREQUENCY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </div>
    </div>
  )
}
