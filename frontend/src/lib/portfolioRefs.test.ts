import { describe, expect, it } from 'vitest'
import { blockStateToSettingsPortfolio, resolvedBlockStateToAPIPortfolio } from './portfolioRefs'
import type { BlockState, SavedPortfolio } from '@/types/backtest'

describe('autosaved portfolio persistence', () => {
  it('writes only current tagged rows', () => {
    const block: BlockState = {
      label: 'Autosaved',
      tickers: [{ id: 'holding', type: 'HOLDING', instrument: 'SPY', allocation: '100' }],
      rebalance: 'YEARLY',
      margins: [],
      rebalanceStrategies: [],
      includeNoMargin: true,
    }

    const settings = blockStateToSettingsPortfolio(block, 0)

    expect(settings).not.toHaveProperty('tickers')
    expect(settings.rows).toEqual([
      { id: 'holding', type: 'HOLDING', instrument: 'SPY', allocation: 100 },
    ])
  })
})

describe('analysis run payloads', () => {
  it('contain only flattened holding allocations in the resolved portfolio composition', () => {
    const block: BlockState = {
      label: 'Root',
      tickers: [
        { id: 'holding', type: 'HOLDING', instrument: 'SPY', allocation: '50' },
        {
          id: 'reference',
          type: 'PORTFOLIO_REFERENCE',
          portfolioName: 'Child',
          allocation: '50',
          normalizationMode: 'NET_100',
        },
      ],
      rebalance: 'YEARLY',
      margins: [],
      rebalanceStrategies: [],
      includeNoMargin: true,
    }
    const savedPortfolios = [{
      name: 'Child',
      config: {
        rows: [
          { id: 'child-holding', type: 'HOLDING', instrument: '0.6 tlt 0.4 gld', allocation: 80 },
          { id: 'child-placeholder', type: 'HOLDING', instrument: 'DUMMY', allocation: 20 },
        ],
      },
    }] as SavedPortfolio[]

    const payload = resolvedBlockStateToAPIPortfolio(block, 0, savedPortfolios)

    expect(payload.tickers).toEqual([
      { ticker: '0.6 TLT 0.4 GLD', weight: 44.44444444444444 },
      { ticker: 'SPY', weight: 55.55555555555556 },
    ])
    expect(payload.tickers.reduce((sum, row) => sum + row.weight, 0)).toBe(100)
    expect(payload.tickers.every(row => !('isPortfolioRef' in row) && !('portfolioRef' in row))).toBe(true)
  })
})
