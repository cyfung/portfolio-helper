import { describe, expect, it } from 'vitest'
import {
  blockStateResolution,
  blockStateToSettingsPortfolio,
  resolvedBlockStateToAPIPortfolio,
} from './portfolioRefs'
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
          { id: 'child-holding', type: 'HOLDING', instrument: '0.6 tlt 0.4 gld', allocation: 100 },
        ],
      },
    }] as SavedPortfolio[]

    const payload = resolvedBlockStateToAPIPortfolio(block, 0, savedPortfolios)

    expect(payload.tickers).toEqual([
      { ticker: '0.6 TLT 0.4 GLD', weight: 50 },
      { ticker: 'SPY', weight: 50 },
    ])
    expect(payload.tickers.reduce((sum, row) => sum + row.weight, 0)).toBe(100)
    expect(payload.tickers.every(row => !('isPortfolioRef' in row) && !('portfolioRef' in row))).toBe(true)
  })

  it('reports nested issues with an actionable reference path and rejects the run', () => {
    const block: BlockState = {
      label: 'Root',
      tickers: [{
        id: 'child-ref',
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
    const savedPortfolios = [{
      name: 'Child',
      config: {
        rows: [{
          id: 'missing-ref',
          type: 'PORTFOLIO_REFERENCE',
          portfolioName: 'Missing',
          allocation: 100,
          normalizationMode: 'NET_100',
        }],
      },
    }] as SavedPortfolio[]

    expect(blockStateResolution(block, savedPortfolios).issues[0]).toMatchObject({
      code: 'MISSING_REFERENCE',
      referencePath: ['Root', 'Child', 'Missing'],
    })
    expect(() => resolvedBlockStateToAPIPortfolio(block, 0, savedPortfolios))
      .toThrow('Root → Child → Missing')
  })
})
