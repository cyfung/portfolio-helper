import { describe, expect, it } from 'vitest'
import {
  portfolioListDropTarget,
  portfolioRowDropPosition,
  reorderPortfolioRows,
} from './portfolioRowDrag'

describe('portfolio row dragging', () => {
  it('targets before or after the hovered row from the mouse position against its center', () => {
    expect(portfolioRowDropPosition(119, { top: 100, height: 40 })).toBe('before')
    expect(portfolioRowDropPosition(120, { top: 100, height: 40 })).toBe('after')
    expect(portfolioRowDropPosition(139, { top: 100, height: 40 })).toBe('after')
  })

  it('keeps list edges and gaps available as portfolio row drop targets', () => {
    const rows = [
      { rowId: 'first', top: 100, height: 30 },
      { rowId: 'second', top: 140, height: 30 },
    ]

    expect(portfolioListDropTarget(90, rows)).toEqual({ rowId: 'first', position: 'before' })
    expect(portfolioListDropTarget(135, rows)).toEqual({ rowId: 'second', position: 'before' })
    expect(portfolioListDropTarget(180, rows)).toEqual({ rowId: 'second', position: 'after' })
  })

  it('moves a row before or after the hovered row', () => {
    const rows = [
      { id: 'holding', type: 'HOLDING' as const, instrument: 'SPY', allocation: '50' },
      { id: 'reference', type: 'PORTFOLIO_REFERENCE' as const, portfolioName: 'Child', allocation: '40', normalizationMode: 'NET_100' as const },
      { id: 'swap', type: 'SWAP' as const, source: 'SPY', transferMode: 'AMOUNT' as const, transferAmount: '10', legs: [] },
    ]

    expect(reorderPortfolioRows(rows, 'swap', 'holding', 'before').map(row => row.id))
      .toEqual(['swap', 'holding', 'reference'])
    expect(reorderPortfolioRows(rows, 'holding', 'reference', 'after').map(row => row.id))
      .toEqual(['reference', 'holding', 'swap'])
  })

  it('returns the original rows when the drop does not change their order', () => {
    const rows = [
      { id: 'one', type: 'HOLDING' as const, instrument: 'SPY', allocation: '50' },
      { id: 'two', type: 'HOLDING' as const, instrument: 'TLT', allocation: '50' },
    ]

    expect(reorderPortfolioRows(rows, 'one', 'one', 'after')).toBe(rows)
    expect(reorderPortfolioRows(rows, 'one', 'two', 'before')).toBe(rows)
  })
})
