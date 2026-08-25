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
      fixedPeriods: [],
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

  it('shows the fixed-period list and guardrail fields together for the staged mode', () => {
    const guardrailCashflow = {
      mode: 'STAGED',
      initialAnnualWithdrawal: '12000',
      lowerWithdrawalRate: '3',
      upperWithdrawalRate: '6',
      minimumAnnualWithdrawal: '9000',
      fixedPeriods: [
        { id: 'a', amount: '500', years: '2', inflationAdjusted: false },
        { id: 'b', amount: '800', years: '3', inflationAdjusted: true },
      ],
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

    expect(markup).toContain('Staged Fixed Periods, then Guardrail')
    expect(markup).toContain('Fixed Periods')
    expect(markup).toContain('Fixed Period 1 Amount')
    expect(markup).toContain('Fixed Period 2 Years')
    expect(markup).toContain('Fixed Period 2 Inflation-adjusted')
    expect(markup).toContain('Add Fixed Period')
    expect(markup).toContain('Initial Annual Withdrawal (after fixed periods)')
    expect(markup).toContain('Lower Withdrawal-Rate Limit (%)')
    expect(markup).toContain('Upper Withdrawal-Rate Limit (%)')
    expect(markup).toContain('Minimum Annual Withdrawal (optional)')
  })

  it('shows an empty fixed-period list with an add button when starting a new staged config', () => {
    const guardrailCashflow = {
      mode: 'STAGED',
      initialAnnualWithdrawal: '',
      lowerWithdrawalRate: '3',
      upperWithdrawalRate: '6',
      minimumAnnualWithdrawal: '',
      fixedPeriods: [],
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

    expect(markup).toContain('Add Fixed Period')
    expect(markup).not.toContain('Fixed Period 1 Amount')
  })
})
