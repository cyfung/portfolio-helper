import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import CashflowControls from './CashflowControls'

describe('cashflow controls', () => {
  it('shows guardrail-specific annual fields and distribution frequency', () => {
    const guardrailCashflow = {
      mode: 'GUARDRAIL_WITHDRAWAL',
      initialAnnualWithdrawal: '12000',
      lowerWithdrawalRate: '3',
      upperWithdrawalRate: '6',
      minimumAnnualWithdrawal: '9000',
    } as const

    const markup = renderToStaticMarkup(
      <CashflowControls
        idPrefix="test"
        startingBalance="100000"
        cashflowAmount="0"
        cashflowFrequency="MONTHLY"
        betaReferenceTicker="SPY"
        guardrailCashflow={guardrailCashflow}
        onStartingBalanceChange={() => undefined}
        onCashflowAmountChange={() => undefined}
        onCashflowFrequencyChange={() => undefined}
        onBetaReferenceTickerChange={() => undefined}
        onGuardrailCashflowChange={() => undefined}
      />,
    )

    expect(markup).toContain('Guardrail Withdrawal')
    expect(markup).toContain('Initial Annual Withdrawal')
    expect(markup).toContain('Lower Withdrawal-Rate Limit (%)')
    expect(markup).toContain('Upper Withdrawal-Rate Limit (%)')
    expect(markup).toContain('Minimum Annual Withdrawal (optional)')
    expect(markup).toContain('Cashflow Frequency')
  })
})
