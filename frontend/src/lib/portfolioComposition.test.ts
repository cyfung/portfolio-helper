import { describe, expect, it } from 'vitest'
import {
  canonicalInstrumentExpression,
  canonicalPortfolioConfiguration,
  convertLegacyTickerRow,
  convertPortfolioRowToLegacyTickerRow,
  formatSwapRow,
  parseSwapInput,
  resolvePortfolioComposition,
  resolvePortfolioReferenceComposition,
  resolveRootPortfolioComposition,
  type PortfolioRow,
} from './portfolioComposition'

describe('canonical portfolio composition', () => {
  it('materializes a reference exactly after nested swaps and signed parent scaling', () => {
    const saved = new Map([
      ['Grandchild', { rows: [
        { id: 'grandchild-holding', type: 'HOLDING', instrument: 'SPY', allocation: 100 },
        {
          id: 'grandchild-swap',
          type: 'SWAP',
          source: 'SPY',
          transfer: { mode: 'AMOUNT', amount: 25 },
          legs: [{ instrument: 'TLT', multiplier: 1 }],
        },
      ] }],
      ['Child', { rows: [{
        id: 'nested',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Grandchild',
        allocation: 80,
        normalizationMode: 'PRESERVE',
      }] }],
    ])

    expect(resolvePortfolioReferenceComposition({
      id: 'reference',
      type: 'PORTFOLIO_REFERENCE',
      portfolioName: 'Child',
      allocation: -50,
      normalizationMode: 'NET_100',
    }, saved)).toEqual({
      composition: [
        { instrument: 'SPY', exposure: -37.5 },
        { instrument: 'TLT', exposure: -12.5 },
      ],
      net: -50,
      issues: [],
    })
  })

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

  it('resolves nested references within local boundaries and normalizes the root', () => {
    const savedPortfolios = new Map([
      ['Normalized', { rows: [
        { id: 'child-holding', type: 'HOLDING', instrument: 'SPY', allocation: 60 },
        { id: 'child-swap', type: 'SWAP', source: 'SPY', transfer: { mode: 'AMOUNT', amount: 20 }, legs: [
          { instrument: 'TLT', multiplier: 1 },
        ] },
      ] }],
      ['Preserved', { rows: [
        { id: 'preserved-holding', type: 'HOLDING', instrument: 'GLD', allocation: 200 },
      ] }],
    ])

    expect(resolveRootPortfolioComposition([
      { id: 'parent-before', type: 'SWAP', source: 'SPY', transfer: { mode: 'ALL_REMAINING' }, legs: [
        { instrument: 'CASH', multiplier: 1 },
      ] },
      {
        id: 'normalized-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Normalized',
        allocation: -50,
        normalizationMode: 'NET_100',
      },
      {
        id: 'preserved-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Preserved',
        allocation: 100,
        normalizationMode: 'PRESERVE',
      },
      { id: 'parent-after', type: 'SWAP', source: 'GLD', transfer: { mode: 'AMOUNT', amount: 50 }, legs: [
        { instrument: 'CASH', multiplier: 1 },
      ] },
    ], savedPortfolios, { rootName: 'Root' })).toEqual({
      composition: [
        { instrument: 'SPY', exposure: -200 / 9 },
        { instrument: 'TLT', exposure: -100 / 9 },
        { instrument: 'GLD', exposure: 100 },
        { instrument: 'CASH', exposure: 50 * (100 / 150) },
      ],
      net: 150,
      issues: [
        {
          code: 'SOURCE_UNAVAILABLE',
          rowId: 'parent-before',
          referencePath: ['Root'],
          message: 'No positive SPY exposure is available to swap.',
        },
      ],
    })
  })

  it('reports nested reference failures with local rows and full paths', () => {
    const savedPortfolios = new Map([
      ['Broken', { rows: [
        { id: 'dummy', type: 'HOLDING', instrument: 'DUMMY', allocation: 25 },
        {
          id: 'missing',
          type: 'PORTFOLIO_REFERENCE',
          portfolioName: 'Gone',
          allocation: 75,
          normalizationMode: 'NET_100',
        },
      ] }],
      ['CycleA', { rows: [{
        id: 'to-b',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'CycleB',
        allocation: 100,
        normalizationMode: 'NET_100',
      }] }],
      ['CycleB', { rows: [{
        id: 'to-a',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'CycleA',
        allocation: 100,
        normalizationMode: 'NET_100',
      }] }],
      ['Negative', { rows: [
        { id: 'short', type: 'HOLDING', instrument: 'SPY', allocation: -100 },
      ] }],
    ])

    expect(resolveRootPortfolioComposition([
      {
        id: 'broken-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Broken',
        allocation: 50,
        normalizationMode: 'NET_100',
      },
      {
        id: 'cycle-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'CycleA',
        allocation: 25,
        normalizationMode: 'PRESERVE',
      },
      {
        id: 'negative-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Negative',
        allocation: 25,
        normalizationMode: 'NET_100',
      },
    ], savedPortfolios, { rootName: 'Root' }).issues).toEqual([
      {
        code: 'LEGACY_DUMMY',
        rowId: 'dummy',
        referencePath: ['Root', 'Broken'],
        message: 'Legacy DUMMY holdings must be rewritten before resolution.',
      },
      {
        code: 'MISSING_REFERENCE',
        rowId: 'missing',
        referencePath: ['Root', 'Broken', 'Gone'],
        message: 'Saved portfolio Gone was not found.',
      },
      {
        code: 'INVALID_NORMALIZED_CHILD',
        rowId: 'broken-ref',
        referencePath: ['Root', 'Broken'],
        message: 'Normalized portfolio reference Broken requires positive signed net exposure.',
      },
      {
        code: 'CIRCULAR_REFERENCE',
        rowId: 'to-a',
        referencePath: ['Root', 'CycleA', 'CycleB', 'CycleA'],
        message: 'Circular portfolio reference: Root -> CycleA -> CycleB -> CycleA.',
      },
      {
        code: 'INVALID_NORMALIZED_CHILD',
        rowId: 'to-b',
        referencePath: ['Root', 'CycleA', 'CycleB'],
        message: 'Normalized portfolio reference CycleB requires positive signed net exposure.',
      },
      {
        code: 'INVALID_NORMALIZED_CHILD',
        rowId: 'negative-ref',
        referencePath: ['Root', 'Negative'],
        message: 'Normalized portfolio reference Negative requires positive signed net exposure.',
      },
      {
        code: 'INVALID_ROOT_NET',
        rowId: 'Root',
        referencePath: ['Root'],
        message: 'Root portfolio requires positive signed net exposure.',
      },
    ])
  })

  it('normalizes references independently of legacy DUMMY exposure', () => {
    const savedPortfolios = new Map([
      ['Child', { rows: [
        { id: 'real', type: 'HOLDING', instrument: 'SPY', allocation: 50 },
        { id: 'dummy', type: 'HOLDING', instrument: 'DUMMY', allocation: 50 },
      ] }],
    ])

    const result = resolveRootPortfolioComposition([{
      id: 'child',
      type: 'PORTFOLIO_REFERENCE',
      portfolioName: 'Child',
      allocation: 100,
      normalizationMode: 'NET_100',
    }], savedPortfolios)

    expect(result.composition).toEqual([{ instrument: 'SPY', exposure: 100 }])
    expect(result.net).toBe(100)
    expect(result.issues[0]?.code).toBe('LEGACY_DUMMY')
  })

  it('preserves signed child exposure against its fixed local capital basis', () => {
    const savedPortfolios = new Map([
      ['Leveraged', { rows: [
        { id: 'leveraged', type: 'HOLDING', instrument: 'SPY', allocation: 200 },
      ] }],
    ])

    expect(resolveRootPortfolioComposition([
      { id: 'cash', type: 'HOLDING', instrument: 'CASH', allocation: 300 },
      {
        id: 'short-child',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Leveraged',
        allocation: -100,
        normalizationMode: 'PRESERVE',
      },
    ], savedPortfolios)).toEqual({
      composition: [
        { instrument: 'CASH', exposure: 300 },
        { instrument: 'SPY', exposure: -200 },
      ],
      net: 100,
      issues: [],
    })
  })

  it('keeps sibling exposure outside a child swap boundary', () => {
    const savedPortfolios = new Map([
      ['Swapper', { rows: [{
        id: 'child-swap',
        type: 'SWAP',
        source: 'SPY',
        transfer: { mode: 'ALL_REMAINING' },
        legs: [{ instrument: 'TLT', multiplier: 1 }],
      }] }],
      ['Source', { rows: [
        { id: 'sibling-spy', type: 'HOLDING', instrument: 'SPY', allocation: 100 },
      ] }],
    ])

    expect(resolveRootPortfolioComposition([
      {
        id: 'source-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Source',
        allocation: 100,
        normalizationMode: 'PRESERVE',
      },
      {
        id: 'swapper-ref',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Swapper',
        allocation: 100,
        normalizationMode: 'PRESERVE',
      },
    ], savedPortfolios, { rootName: 'Root' })).toEqual({
      composition: [{ instrument: 'SPY', exposure: 100 }],
      net: 100,
      issues: [{
        code: 'SOURCE_UNAVAILABLE',
        rowId: 'child-swap',
        referencePath: ['Root', 'Swapper'],
        message: 'No positive SPY exposure is available to swap.',
      }],
    })
  })
})
