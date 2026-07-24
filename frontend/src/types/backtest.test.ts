import { describe, expect, it } from 'vitest'
import { resolvePortfolioComposition } from '@/lib/portfolioComposition'
import {
  blockStateToSavedConfig,
  configToBlockState,
  convertHoldingEditorRowToSwap,
  invalidPortfolioEditorRowIds,
  portfolioEditorRowMergeKey,
  sortAndMergePortfolioEditorRows,
  type BlockState,
} from './backtest'

describe('saved portfolio persistence', () => {
  it('round-trips explicit editor rows without legacy ticker conversion', () => {
    const state: BlockState = {
      label: 'Example',
      tickers: [
        { id: 'holding', type: 'HOLDING', instrument: ' spy R=Q ', allocation: '60' },
        {
          id: 'reference',
          type: 'PORTFOLIO_REFERENCE',
          portfolioName: 'Child',
          allocation: '40',
          normalizationMode: 'PRESERVE',
        },
        {
          id: 'swap',
          type: 'SWAP',
          source: 'SPY',
          transferMode: 'ALL_REMAINING',
          transferAmount: '',
          legs: [
            { id: 'leg-1', instrument: 'TLT', multiplier: '1.5' },
            { id: 'leg-2', instrument: 'KMLM', multiplier: '-0.25' },
          ],
        },
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
        normalizationMode: 'PRESERVE',
      },
      {
        id: 'swap',
        type: 'SWAP',
        source: 'SPY',
        transfer: { mode: 'ALL_REMAINING' },
        legs: [
          { instrument: 'TLT', multiplier: 1.5 },
          { instrument: 'KMLM', multiplier: -0.25 },
        ],
      },
    ])

    expect(configToBlockState(saved, 'Example').tickers).toEqual([
      { ...state.tickers[0], instrument: 'SPY R=Q' },
      state.tickers[1],
      {
        ...state.tickers[2],
        legs: [
          { id: 'swap-leg-0', instrument: 'TLT', multiplier: '1.5' },
          { id: 'swap-leg-1', instrument: 'KMLM', multiplier: '-0.25' },
        ],
      },
    ])
  })

  it('keeps legacy DUMMY holdings visible for repair while marking them invalid', () => {
    const state = configToBlockState({
      rows: [{ id: 'dummy', type: 'HOLDING', instrument: 'DUMMY', allocation: 25 }],
    }, 'Legacy')

    expect(state.tickers).toEqual([
      { id: 'dummy', type: 'HOLDING', instrument: 'DUMMY', allocation: '25' },
    ])
  })

  it('does not fall back to legacy rows when tagged persistence is invalid', () => {
    expect(() => configToBlockState({
      rows: [{ id: 'bad', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: true }, legs: [] }],
      tickers: [{ ticker: 'SWAP(SPY, TLT)', weight: 10 }],
    }, 'Invalid')).toThrow('invalid tagged rows')
  })

  it('rejects legacy persisted ticker rows after the database migration', () => {
    expect(() => configToBlockState({
      tickers: [{ ticker: 'SPY', weight: 100 }],
    }, 'Legacy')).toThrow('missing tagged rows')
  })

  it('converts holding swap syntax only through the explicit conversion action', () => {
    const holding = {
      id: 'candidate',
      type: 'HOLDING' as const,
      instrument: 'SPY > 1.5 TLT + -0.25 KMLM',
      allocation: '*',
    }

    expect(holding.type).toBe('HOLDING')
    expect(convertHoldingEditorRowToSwap(holding)).toEqual({
      id: 'candidate',
      type: 'SWAP',
      source: 'SPY',
      transferMode: 'ALL_REMAINING',
      transferAmount: '',
      legs: [
        { id: 'candidate-leg-0', instrument: 'TLT', multiplier: '1.5' },
        { id: 'candidate-leg-1', instrument: 'KMLM', multiplier: '-0.25' },
      ],
    })
  })

  it('blocks persistence for invalid and legacy DUMMY editor rows', () => {
    const invalidIds = invalidPortfolioEditorRowIds({
      tickers: [
        { id: 'valid', type: 'HOLDING', instrument: 'SPY', allocation: '100' },
        { id: 'dummy', type: 'HOLDING', instrument: 'DUMMY', allocation: '10' },
        { id: 'bad-swap', type: 'SWAP', source: '', transferMode: 'AMOUNT', transferAmount: '0', legs: [] },
      ],
    })

    expect([...invalidIds]).toEqual(['dummy', 'bad-swap'])
  })

  it('keeps reference normalization modes distinct when compacting rows', () => {
    const normalized = {
      id: 'normalized',
      type: 'PORTFOLIO_REFERENCE' as const,
      portfolioName: 'Child',
      allocation: '25',
      normalizationMode: 'NET_100' as const,
    }
    const preserving = {
      ...normalized,
      id: 'preserving',
      normalizationMode: 'PRESERVE' as const,
    }

    expect(portfolioEditorRowMergeKey(normalized, 0))
      .not.toBe(portfolioEditorRowMergeKey(preserving, 1))
  })
})

describe('ordered portfolio editing operations', () => {
  it('merges and sorts only within segments separated by swaps', () => {
    const rows: BlockState['tickers'] = [
      { id: 'z-before', type: 'HOLDING', instrument: 'ZZZ', allocation: '10' },
      { id: 'a-before', type: 'HOLDING', instrument: 'AAA', allocation: '20' },
      {
        id: 'swap',
        type: 'SWAP',
        source: 'AAA',
        transferMode: 'AMOUNT',
        transferAmount: '5',
        legs: [{ id: 'leg', instrument: 'BBB', multiplier: '1' }],
      },
      { id: 'a-after-1', type: 'HOLDING', instrument: 'AAA', allocation: '30' },
      { id: 'a-after-2', type: 'HOLDING', instrument: 'AAA', allocation: '40' },
    ]

    expect(sortAndMergePortfolioEditorRows(rows)).toEqual([
      { id: 'a-before', type: 'HOLDING', instrument: 'AAA', allocation: '20' },
      { id: 'z-before', type: 'HOLDING', instrument: 'ZZZ', allocation: '10' },
      rows[2],
      { id: 'a-after-1', type: 'HOLDING', instrument: 'AAA', allocation: '70' },
    ])

    const resolve = (tickers: BlockState['tickers']) => {
      const result = resolvePortfolioComposition(blockStateToSavedConfig({
        label: '',
        tickers,
        rebalance: 'YEARLY',
        margins: [],
        rebalanceStrategies: [],
        includeNoMargin: true,
      }).rows)
      return {
        net: result.net,
        issues: result.issues,
        exposures: Object.fromEntries(result.composition
          .map(position => [position.instrument, position.exposure] as const)
          .sort(([a], [b]) => a.localeCompare(b))),
      }
    }
    expect(resolve(sortAndMergePortfolioEditorRows(rows))).toEqual(resolve(rows))
  })
})
