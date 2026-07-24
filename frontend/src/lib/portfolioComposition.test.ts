import { describe, expect, it } from 'vitest'
import {
  canonicalInstrumentExpression,
  canonicalPortfolioConfiguration,
  convertLegacyTickerRow,
  convertPortfolioRowToLegacyTickerRow,
  formatSwapRow,
  parseSwapInput,
  resolvePortfolioComposition,
  type PortfolioRow,
} from './portfolioComposition'

describe('canonical portfolio composition', () => {
  it('resolves holding allocations and swaps in visible row order', () => {
    expect(resolvePortfolioComposition([
      { id: 'before', type: 'SWAP', source: 'SPY', transfer: { mode: 'ALL_REMAINING' }, legs: [
        { instrument: 'TLT', multiplier: 1 },
      ] },
      { id: 'spy-1', type: 'HOLDING', instrument: 'spy S=1 R=Q', allocation: 60 },
      { id: 'spy-2', type: 'HOLDING', instrument: 'SPY R=Q S=1', allocation: 40 },
      { id: 'swap', type: 'SWAP', source: 'SPY S=1 R=Q', transfer: { mode: 'AMOUNT', amount: 25 }, legs: [
        { instrument: 'TLT', multiplier: 1.5 },
        { instrument: 'SPY R=Q S=1', multiplier: -0.5 },
      ] },
    ])).toEqual({
      composition: [
        { instrument: 'SPY R=Q S=1', exposure: 62.5 },
        { instrument: 'TLT', exposure: 37.5 },
      ],
      net: 100,
      issues: [
        { code: 'SOURCE_UNAVAILABLE', rowId: 'before', message: 'No positive SPY exposure is available to swap.' },
      ],
    })
  })

  it('keeps synthetic expressions atomic and applies signed, all-remaining, and self-destination legs', () => {
    expect(resolvePortfolioComposition([
      { id: 'synthetic', type: 'HOLDING', instrument: '(1 spy 1 tlt)', allocation: 20 },
      { id: 'cash', type: 'HOLDING', instrument: 'CASH', allocation: 30 },
      { id: 'swap', type: 'SWAP', source: 'cash', transfer: { mode: 'ALL_REMAINING' }, legs: [
        { instrument: 'CASH', multiplier: 0.5 },
        { instrument: '(1 SPY 1 TLT)', multiplier: -2 },
      ] },
    ])).toEqual({
      composition: [
        { instrument: '(1 SPY 1 TLT)', exposure: -40 },
        { instrument: 'CASH', exposure: 15 },
      ],
      net: -25,
      issues: [],
    })
  })

  it('reports invalid row fields and insufficient source exposure at their rows', () => {
    expect(resolvePortfolioComposition([
      { id: 'bad-instrument', type: 'HOLDING', instrument: '()', allocation: 10 },
      { id: 'bad-source', type: 'SWAP', source: '()', transfer: { mode: 'AMOUNT', amount: 1 }, legs: [
        { instrument: 'TLT', multiplier: 1 },
      ] },
      { id: 'bad-leg', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: 1 }, legs: [
        { instrument: '()', multiplier: 1 },
      ] },
      { id: 'bad-amount', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: -1 }, legs: [
        { instrument: 'TLT', multiplier: 1 },
      ] },
      { id: 'empty-legs', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: 1 }, legs: [] },
      { id: 'zero-leg', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: 1 }, legs: [
        { instrument: 'TLT', multiplier: 0 },
      ] },
      { id: 'spy', type: 'HOLDING', instrument: 'SPY', allocation: 5 },
      { id: 'too-much', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: 6 }, legs: [
        { instrument: 'TLT', multiplier: 1 },
      ] },
    ])).toEqual({
      composition: [{ instrument: 'SPY', exposure: 5 }],
      net: 5,
      issues: [
        { code: 'INVALID_INSTRUMENT', rowId: 'bad-instrument', message: 'The holding instrument expression is invalid.' },
        { code: 'INVALID_INSTRUMENT', rowId: 'bad-source', message: 'The swap source instrument expression is invalid.' },
        { code: 'INVALID_LEGS', rowId: 'bad-leg', message: 'The swap must have at least one valid non-zero leg.' },
        { code: 'INVALID_TRANSFER', rowId: 'bad-amount', message: 'The swap transfer amount must be positive and finite.' },
        { code: 'INVALID_LEGS', rowId: 'empty-legs', message: 'The swap must have at least one valid non-zero leg.' },
        { code: 'INVALID_LEGS', rowId: 'zero-leg', message: 'The swap must have at least one valid non-zero leg.' },
        {
          code: 'INSUFFICIENT_SOURCE',
          rowId: 'too-much',
          message: 'Only 5 of positive SPY exposure is available to swap 6.',
        },
      ],
    })
  })

  it('canonicalizes instrument identity and supported swap notation', () => {
    expect(canonicalInstrumentExpression('  spy   R=Q   S=1.5 ')).toBe('SPY R=Q S=1.5')
    expect(canonicalInstrumentExpression('SPY S=1.5 R=Q')).toBe('SPY R=Q S=1.5')
    expect(parseSwapInput('SPY > 1.5 TLT + GLD #-2')).toEqual({
      source: 'SPY',
      legs: [
        { instrument: 'TLT', multiplier: 1.5 },
        { instrument: 'GLD', multiplier: -2 },
      ],
      formatted: 'SPY > 1.5 TLT + -2 GLD',
    })
    expect(parseSwapInput('SPY > 0 TLT')).toBeNull()
    expect(parseSwapInput('SPY > 0.00000000001 TLT')).toEqual({
      source: 'SPY',
      legs: [{ instrument: 'TLT', multiplier: 0.00000000001 }],
      formatted: 'SPY > 1e-11 TLT',
    })
    expect(parseSwapInput('SPY > 2 ()')).toBeNull()
    expect(parseSwapInput('2 SPY > TLT')).toBeNull()
    expect(parseSwapInput('SPY > 2 QQQ 1 TLT')).toBeNull()
    expect(parseSwapInput('SPY > 2 (1 QQQ 1 TLT)')).toEqual({
      source: 'SPY',
      legs: [{ instrument: '(1 QQQ 1 TLT)', multiplier: 2 }],
      formatted: 'SPY > 2 (1 QQQ 1 TLT)',
    })
  })

  it('converts legacy overloaded rows only at an explicit boundary', () => {
    const rows: PortfolioRow[] = [
      convertLegacyTickerRow({ ticker: 'spy', weight: 60 }, 'holding')!,
      convertLegacyTickerRow({ ticker: 'Child', weight: -25, isPortfolioRef: true }, 'reference')!,
      convertLegacyTickerRow({ ticker: 'SPY > TLT #1.5', weight: '*' }, 'swap')!,
      convertLegacyTickerRow({ ticker: 'SWAP(GLD, TLT, -2)', weight: 10 }, 'legacy-swap')!,
    ]

    expect(rows).toEqual([
      { id: 'holding', type: 'HOLDING', instrument: 'SPY', allocation: 60 },
      {
        id: 'reference',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Child',
        allocation: -25,
        normalizationMode: 'NET_100',
      },
      {
        id: 'swap',
        type: 'SWAP',
        source: 'SPY',
        transfer: { mode: 'ALL_REMAINING' },
        legs: [{ instrument: 'TLT', multiplier: 1.5 }],
      },
      {
        id: 'legacy-swap',
        type: 'SWAP',
        source: 'GLD',
        transfer: { mode: 'AMOUNT', amount: 10 },
        legs: [{ instrument: 'TLT', multiplier: -2 }],
      },
    ])
    expect(formatSwapRow(rows[2] as Extract<PortfolioRow, { type: 'SWAP' }>)).toBe('SPY > 1.5 TLT')
    expect(convertPortfolioRowToLegacyTickerRow(rows[2])).toEqual({
      ticker: 'SPY > 1.5 TLT',
      weight: '*',
    })
    expect(convertLegacyTickerRow({ ticker: 'SPY > 0 TLT', weight: 10 }, 'bad-swap')).toBeNull()
    expect(convertLegacyTickerRow({ ticker: 'SWAP(SPY,)', weight: 10 }, 'bad-legacy-swap')).toBeNull()

    expect(canonicalPortfolioConfiguration({
      rows: [
        { id: 'h', type: 'HOLDING', instrument: ' spy  S=1 R=Q ', allocation: 60 },
        {
          id: 'r',
          type: 'PORTFOLIO_REFERENCE',
          portfolioName: ' Child ',
          allocation: 40,
          normalizationMode: 'PRESERVE',
        },
      ],
    })).toEqual({
      rows: [
        { id: 'h', type: 'HOLDING', instrument: 'SPY R=Q S=1', allocation: 60 },
        {
          id: 'r',
          type: 'PORTFOLIO_REFERENCE',
          portfolioName: 'Child',
          allocation: 40,
          normalizationMode: 'PRESERVE',
        },
      ],
    })
    expect(canonicalPortfolioConfiguration({
      rows: [{ id: 'legacy', type: 'HOLDING', instrument: 'SWAP(SPY,TLT)', allocation: 100 }],
    })).toBeNull()
    expect(canonicalPortfolioConfiguration({
      rows: [{
        id: 'bad-mode',
        type: 'SWAP',
        source: 'SPY',
        transfer: { mode: 'UNKNOWN', amount: 10 },
        legs: [{ instrument: 'TLT', multiplier: 1 }],
      }],
    })).toBeNull()
    expect(canonicalPortfolioConfiguration({
      rows: [{
        id: 'coerced-amount',
        type: 'SWAP',
        source: 'SPY',
        transfer: { mode: 'AMOUNT', amount: true },
        legs: [{ instrument: 'TLT', multiplier: 1 }],
      }],
    })).toBeNull()
    expect(canonicalPortfolioConfiguration({
      rows: [{
        id: 'bad-reference',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Child',
        allocation: 100,
        normalizationMode: 'UNKNOWN',
      }],
    })).toBeNull()
  })
})
