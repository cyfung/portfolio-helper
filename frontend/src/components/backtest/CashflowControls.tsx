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
          <option value="FIXED_THEN_GUARDRAIL">Fixed, then Guardrail</option>
        </select>
      </div>
      {(guardrailCashflow.mode === 'FIXED' || guardrailCashflow.mode === 'FIXED_THEN_GUARDRAIL') && (
        <div>
          <label htmlFor={cashflowAmountId}>
            {guardrailCashflow.mode === 'FIXED_THEN_GUARDRAIL' ? 'Fixed Cashflow Amount' : 'Cashflow Amount'}
          </label>
          <input
            type="number"
            id={cashflowAmountId}
            placeholder="e.g. 1000"
            step="100"
            value={cashflowAmount}
            onChange={e => onCashflowAmountChange(e.target.value)}
          />
        </div>
      )}
      {guardrailCashflow.mode === 'FIXED_THEN_GUARDRAIL' && (
        <div>
          <label htmlFor={`${idPrefix}-fixed-years`}>Fixed Years</label>
          <input
            type="number"
            id={`${idPrefix}-fixed-years`}
            min="0"
            step="1"
            value={guardrailCashflow.fixedYears}
            onChange={e => updateGuardrail({ fixedYears: e.target.value })}
          />
        </div>
      )}
      {(guardrailCashflow.mode === 'GUARDRAIL_WITHDRAWAL' || guardrailCashflow.mode === 'FIXED_THEN_GUARDRAIL') && (
        <>
          <div>
            <label htmlFor={`${idPrefix}-initial-annual-withdrawal`}>
              {guardrailCashflow.mode === 'FIXED_THEN_GUARDRAIL'
                ? 'Initial Annual Withdrawal (after fixed years)'
                : 'Initial Annual Withdrawal'}
            </label>
            <input
              type="number"
              id={`${idPrefix}-initial-annual-withdrawal`}
              placeholder="e.g. 12000"
              min="0"
              step="100"
              value={guardrailCashflow.initialAnnualWithdrawal}
              onChange={e => updateGuardrail({ initialAnnualWithdrawal: e.target.value })}
            />
          </div>
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
