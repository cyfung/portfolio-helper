import { describe, expect, it } from 'vitest'
import { formatSwapExpression, parseSwapExpression, resolveSwapTickerRows } from './tickerExpressions'

describe('swap expression compatibility boundary', () => {
  it('accepts legacy input but generates canonical prefix notation', () => {
    expect(parseSwapExpression('SWAP(SPY, TLT, -2)')).toMatchObject({
      from: 'SPY',
      to: 'TLT',
      factor: -2,
    })
    expect(formatSwapExpression('spy', 'tlt', -2)).toBe('SPY > -2 TLT')
  })

  it('retains structured source legs at the compatibility boundary', () => {
    expect(parseSwapExpression('2 SPY + TLT > QQQ')).toMatchObject({
      sources: [
        { ticker: 'SPY', weight: 2 },
        { ticker: 'TLT', weight: 1 },
      ],
    })

    expect(() => resolveSwapTickerRows([
      { ticker: 'SPY', weight: 30 },
      { ticker: 'TLT', weight: 30 },
      { ticker: '2 SPY + TLT > QQQ', weight: '*' },
    ])).toThrow('Multi-source swaps must be resolved through canonical portfolio composition.')
  })
})
