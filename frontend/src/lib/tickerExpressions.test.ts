import { describe, expect, it } from 'vitest'
import { formatSwapExpression, parseSwapExpression } from './tickerExpressions'

describe('swap expression compatibility boundary', () => {
  it('accepts legacy input but generates canonical prefix notation', () => {
    expect(parseSwapExpression('SWAP(SPY, TLT, -2)')).toMatchObject({
      from: 'SPY',
      to: 'TLT',
      factor: -2,
    })
    expect(formatSwapExpression('spy', 'tlt', -2)).toBe('SPY > -2 TLT')
  })
})
