import {
  CASHFLOW_FREQUENCY_OPTIONS,
  emptyFixedCashflowPeriod,
  type CashflowFormState,
  type FixedCashflowPeriodInput,
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
  const updatePeriod = (id: string, patch: Partial<FixedCashflowPeriodInput>) => {
    updateGuardrail({
      fixedPeriods: guardrailCashflow.fixedPeriods.map(period =>
        period.id === id ? { ...period, ...patch } : period),
    })
  }
  const addPeriod = () => {
    updateGuardrail({ fixedPeriods: [...guardrailCashflow.fixedPeriods, emptyFixedCashflowPeriod()] })
  }
  const removePeriod = (id: string) => {
    updateGuardrail({ fixedPeriods: guardrailCashflow.fixedPeriods.filter(period => period.id !== id) })
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
          <option value="STAGED">Staged Fixed Periods, then Guardrail</option>
        </select>
      </div>
      {guardrailCashflow.mode === 'FIXED' && (
        <div>
          <label htmlFor={cashflowAmountId}>Cashflow Amount</label>
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
      {guardrailCashflow.mode === 'STAGED' && (
        <div className="backtest-cashflow-periods">
          <div className="backtest-cashflow-periods-header">
            <span>Fixed Periods</span>
            <button className="add-cashflow-btn" type="button" onClick={addPeriod}>+ Add Period</button>
          </div>
          {guardrailCashflow.fixedPeriods.length === 0 && (
            <p className="cashflow-hint">No fixed periods yet. Add one to start staging fixed cashflows.</p>
          )}
          <div className="backtest-cashflow-period-rows">
            {guardrailCashflow.fixedPeriods.map((period, i) => (
              <div key={period.id} className="backtest-cashflow-period-row">
                <input
                  className="cf-amount"
                  type="number"
                  aria-label={`Fixed Period ${i + 1} Amount`}
                  placeholder="Amount"
                  step="100"
                  value={period.amount}
                  onChange={e => updatePeriod(period.id, { amount: e.target.value })}
                />
                <span className="cf-label">for</span>
                <input
                  className="cf-years"
                  type="number"
                  aria-label={`Fixed Period ${i + 1} Years`}
                  placeholder="Years"
                  min="1"
                  step="1"
                  value={period.years}
                  onChange={e => updatePeriod(period.id, { years: e.target.value })}
                />
                <span className="cf-label">years</span>
                <label className="backtest-cashflow-period-inflation">
                  <input
                    type="checkbox"
                    aria-label={`Fixed Period ${i + 1} Inflation-adjusted`}
                    checked={period.inflationAdjusted}
                    onChange={e => updatePeriod(period.id, { inflationAdjusted: e.target.checked })}
                  />
                  Inflation-adjusted
                </label>
                <button
                  type="button" className="cf-remove" aria-label={`Remove Fixed Period ${i + 1}`}
                  onClick={() => removePeriod(period.id)}
                >✕</button>
              </div>
            ))}
          </div>
        </div>
      )}
      {(guardrailCashflow.mode === 'GUARDRAIL_WITHDRAWAL' || guardrailCashflow.mode === 'STAGED') && (
        <div className="backtest-cashflow-guardrail-fields">
          <div>
            <label htmlFor={`${idPrefix}-initial-annual-withdrawal`}>
              {guardrailCashflow.mode === 'STAGED'
                ? 'Initial Annual Withdrawal (after fixed periods)'
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
        </div>
      )}
    </div>
  )
}
