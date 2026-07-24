import { describe, expect, it } from 'vitest'
import { configToBlockState, type BlockState, type SavedPortfolio } from '@/types/backtest'
import { blockStateToSettingsPortfolio, resolveBlockStateRows } from './portfolioRefs'
import { expandLetfRows } from './tickerExpressions'

describe('tagged portfolio sharing and Portfolio Builder integration', () => {
  it('retains behavior through save, share, reopen, resolve, and post-resolution LETF projection', () => {
    const child: SavedPortfolio = {
      name: 'Child',
      config: {
        rows: [
          { id: 'synthetic', type: 'HOLDING', instrument: '0.6 TLT 0.4 GLD', allocation: 80 },
          { id: 'stock', type: 'HOLDING', instrument: 'SPY', allocation: 20 },
        ],
      },
    }
    const editor: BlockState = {
      label: 'Shared',
      tickers: [{
        id: 'reference',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Child',
        allocation: '100',
        normalizationMode: 'NET_100',
      }],
      rebalance: 'YEARLY',
      margins: [],
      rebalanceStrategies: [],
      includeNoMargin: true,
    }

    const sharedJson = JSON.stringify({
      portfolio: blockStateToSettingsPortfolio(editor, 0, { strict: true }),
      savedPortfolios: [child],
    })
    const imported = JSON.parse(sharedJson)
    const reopened = configToBlockState(imported.portfolio, 'Shared')
    const resolved = resolveBlockStateRows(reopened, imported.savedPortfolios, { normalize: false })

    expect(imported.portfolio).toHaveProperty('rows')
    expect(imported.portfolio).not.toHaveProperty('tickers')
    expect(resolved).toEqual([
      { ticker: '0.6 TLT 0.4 GLD', weight: 80 },
      { ticker: 'SPY', weight: 20 },
    ])

    expect(expandLetfRows(resolved, {
      '0.6 TLT 0.4 GLD': { letf: '0.6 TLT 0.4 GLD' },
    })).toEqual({
      expanded: true,
      rows: [
        { ticker: 'GLD', weight: 32 },
        { ticker: 'SPY', weight: 20 },
        { ticker: 'TLT', weight: 48 },
      ],
    })
  })
})
