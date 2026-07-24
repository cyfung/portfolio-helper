import { describe, expect, it } from 'vitest'
import { blockStateToSavedConfig, configToBlockState, type BlockState } from './backtest'

describe('saved portfolio persistence', () => {
  it('writes tagged rows and never legacy ticker rows', () => {
    const state: BlockState = {
      label: 'Example',
      tickers: [
        { id: 'holding', ticker: ' spy R=Q ', weight: '60' },
        { id: 'reference', ticker: 'Child', weight: '40', isPortfolioRef: true },
        { id: 'swap', ticker: 'SPY > TLT #1.5', weight: '*' },
      ],
      rebalance: 'YEARLY',
      margins: [],
      rebalanceStrategies: [],
      includeNoMargin: true,
    }

    const saved = blockStateToSavedConfig(state)
    expect(saved).not.toHaveProperty('tickers')
    expect(saved.rows).toEqual([
      { id: 'holding', type: 'HOLDING', instrument: 'SPY R=Q', allocation: 60 },
      {
        id: 'reference',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Child',
        allocation: 40,
        normalizationMode: 'NET_100',
      },
      {
        id: 'swap',
        type: 'SWAP',
        source: 'SPY',
        transfer: { mode: 'ALL_REMAINING' },
        legs: [{ instrument: 'TLT', multiplier: 1.5 }],
      },
    ])
  })

  it('does not fall back to legacy rows when tagged persistence is invalid', () => {
    expect(() => configToBlockState({
      rows: [{ id: 'bad', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: true }, legs: [] }],
      tickers: [{ ticker: 'SWAP(SPY, TLT)', weight: 10 }],
    }, 'Invalid')).toThrow('invalid tagged rows')
  })
})
