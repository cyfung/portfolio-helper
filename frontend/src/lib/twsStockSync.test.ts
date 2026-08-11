import { describe, expect, it } from 'vitest'
import { stageTwsStockSync } from './twsStockSync'

describe('stageTwsStockSync', () => {
  it('updates only synchronized holdings and reports TWS-reported manually managed holdings', () => {
    const result = stageTwsStockSync(
      [
        { label: 'SPY', amount: 10, originalAmount: 10, targetWeight: 40, letf: '', groups: '', manualQty: true },
        { label: 'TLT', amount: 20, originalAmount: 20, targetWeight: 30, letf: '', groups: '', manualQty: false },
        { label: 'GLD', amount: 30, originalAmount: 30, targetWeight: 30, letf: '', groups: '' },
      ],
      [
        { symbol: ' spy ', qty: 99 },
        { symbol: 'TLT', qty: 25 },
        { symbol: 'KMLM', qty: 5 },
        { symbol: ' kmlm ', qty: 6 },
      ],
    )

    expect(result.stocks).toEqual([
      { label: 'SPY', amount: 10, originalAmount: 10, targetWeight: 40, letf: '', groups: '', manualQty: true },
      { label: 'TLT', amount: 20, originalAmount: 25, targetWeight: 30, letf: '', groups: '', manualQty: false },
      { label: 'GLD', amount: 30, originalAmount: 0, targetWeight: 30, letf: '', groups: '' },
      { label: 'KMLM', amount: 6, originalAmount: 6, targetWeight: 0, letf: '', groups: '', manualQty: false },
    ])
    expect(result.reportedManuallyManagedHoldings).toEqual(['SPY'])
  })
})
