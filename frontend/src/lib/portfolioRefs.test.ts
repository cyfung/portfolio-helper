import { describe, expect, it } from 'vitest'
import { blockStateToSettingsPortfolio } from './portfolioRefs'
import type { BlockState } from '@/types/backtest'

describe('autosaved portfolio persistence', () => {
  it('writes only current tagged rows', () => {
    const block: BlockState = {
      label: 'Autosaved',
      tickers: [{ id: 'holding', ticker: 'SPY', weight: '100' }],
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
