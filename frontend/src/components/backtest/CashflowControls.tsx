import {
  CASHFLOW_FREQUENCY_OPTIONS,
  type CashflowFormState,
  type GuardrailCashflowState,
} from '@/types/backtest'

interface Props extends CashflowFormState {
  idPrefix: string
  onStartingBalanceChange: (value: string) => void
  onCashflowAmountChange: (value: string) => void
  onCashflowFrequencyChange: (value: string) => void
  onBetaReferenceTickerChange: (value: string) => void
  onGuardrailCashflowChange: (value: GuardrailCashflowState) => void
}

export default function CashflowControls({
  idPrefix,
  startingBalance,
  cashflowAmount,
  cashflowFrequency,
  betaReferenceTicker,
  guardrailCashflow,
  onStartingBalanceChange,
  onCashflowAmountChange,
  onCashflowFrequencyChange,
  onBetaReferenceTickerChange,
  onGuardrailCashflowChange,
}: Props) {
  const updateGuardrail = (patch: Partial<GuardrailCashflowState>) => {
    onGuardrailCashflowChange({ ...guardrailCashflow, ...patch })
  }
  const startingBalanceId = `${idPrefix}-starting-balance`
  const cashflowAmountId = `${idPrefix}-cashflow-amount`
  const cashflowFrequencyId = `${idPrefix}-cashflow-frequency`
  const betaReferenceTickerId = `${idPrefix}-beta-reference-ticker`

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
        <label htmlFor={`${idPrefix}-cashflow-mode`}>Cashflow Mode</label>
        <select
          id={`${idPrefix}-cashflow-mode`}
          value={guardrailCashflow.mode}
          onChange={e => updateGuardrail({ mode: e.target.value as GuardrailCashflowState['mode'] })}
        >
          <option value="FIXED">Fixed Cashflow</option>
          <option value="GUARDRAIL_WITHDRAWAL">Guardrail Withdrawal</option>
        </select>
      </div>
      <div>
        <label htmlFor={cashflowAmountId}>
          {guardrailCashflow.mode === 'FIXED' ? 'Cashflow Amount' : 'Initial Annual Withdrawal'}
        </label>
        <input
          type="number"
          id={cashflowAmountId}
          placeholder="e.g. 1000"
          min={guardrailCashflow.mode === 'GUARDRAIL_WITHDRAWAL' ? '0' : undefined}
          step="100"
          value={guardrailCashflow.mode === 'FIXED' ? cashflowAmount : guardrailCashflow.initialAnnualWithdrawal}
          onChange={e => guardrailCashflow.mode === 'FIXED'
            ? onCashflowAmountChange(e.target.value)
            : updateGuardrail({ initialAnnualWithdrawal: e.target.value })}
        />
      </div>
      {guardrailCashflow.mode === 'GUARDRAIL_WITHDRAWAL' && (
        <>
          <div>
            <label>Lower Withdrawal-Rate Limit (%)</label>
            <input type="number" min="0" step="0.1" value={guardrailCashflow.lowerWithdrawalRate}
              onChange={e => updateGuardrail({ lowerWithdrawalRate: e.target.value })} />
          </div>
          <div>
            <label>Upper Withdrawal-Rate Limit (%)</label>
            <input type="number" min="0" step="0.1" value={guardrailCashflow.upperWithdrawalRate}
              onChange={e => updateGuardrail({ upperWithdrawalRate: e.target.value })} />
          </div>
          <div>
            <label>Minimum Annual Withdrawal (optional)</label>
            <input type="number" min="0" value={guardrailCashflow.minimumAnnualWithdrawal}
              onChange={e => updateGuardrail({ minimumAnnualWithdrawal: e.target.value })} />
          </div>
        </>
      )}
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
      <div>
        <label htmlFor={betaReferenceTickerId}>Beta Reference</label>
        <input
          type="text"
          id={betaReferenceTickerId}
          placeholder="SPY"
          spellCheck={false}
          value={betaReferenceTicker}
          onChange={e => onBetaReferenceTickerChange(e.target.value)}
          onBlur={e => onBetaReferenceTickerChange(e.target.value.trim().toUpperCase() || 'SPY')}
        />
      </div>
    </div>
  )
}
