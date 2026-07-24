import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import PortfolioBlock from './PortfolioBlock'
import type { BlockState } from '@/types/backtest'

function renderPortfolioBlock(tickers: BlockState['tickers']) {
  return renderToStaticMarkup(
    <PortfolioBlock
      idx={0}
      value={{
        label: 'Example',
        tickers,
        rebalance: 'YEARLY',
        margins: [],
        rebalanceStrategies: [],
        includeNoMargin: true,
      }}
      onChange={() => undefined}
      onSavedRefresh={() => undefined}
    />,
  )
}

describe('portfolio row editor', () => {
  it('renders compact explicit row actions and reference controls', () => {
    const markup = renderPortfolioBlock([{
      id: 'reference',
      type: 'PORTFOLIO_REFERENCE',
      portfolioName: 'Child',
      allocation: '40',
      normalizationMode: 'NET_100',
    }])

    expect(markup).toContain('>+Ticker</button>')
    expect(markup).toContain('>+Swap</button>')
    expect(markup).toContain('Ref · 100')
    expect(markup).toContain('Reference mode for Child')
    expect(markup).toContain('Decompose Child one layer')
  })

  it('offers conversion without changing a holding row and disables save for invalid rows', () => {
    const markup = renderPortfolioBlock([{
      id: 'candidate',
      type: 'HOLDING',
      instrument: 'SPY > TLT',
      allocation: '10',
    }])

    expect(markup).toContain('Convert to swap')
    expect(markup).toContain('value="SPY &gt; TLT"')
    expect(markup).not.toContain('aria-label="Swap source"')
    expect(markup).toMatch(/overwrite-portfolio-btn save-portfolio-btn" disabled/)
  })

  it('expands the structured editor for multi-leg swaps', () => {
    const markup = renderPortfolioBlock([{
      id: 'swap',
      type: 'SWAP',
      source: 'SPY',
      transferMode: 'AMOUNT',
      transferAmount: '10',
      legs: [
        { id: 'one', instrument: 'TLT', multiplier: '1' },
        { id: 'two', instrument: 'KMLM', multiplier: '0.5' },
      ],
    }])

    expect(markup).toContain('swap-editor-row-complex')
    expect(markup.match(/aria-label="Swap destination"/g)).toHaveLength(2)
    expect(markup).toContain('+ Destination')
  })

  it('keeps a one-leg swap in the compact editor', () => {
    const markup = renderPortfolioBlock([{
      id: 'swap',
      type: 'SWAP',
      source: 'SPY',
      transferMode: 'AMOUNT',
      transferAmount: '10',
      legs: [{ id: 'one', instrument: 'TLT', multiplier: '1' }],
    }])

    expect(markup).toContain('swap-editor-row')
    expect(markup).not.toContain('swap-editor-row-complex')
    expect(markup.match(/aria-label="Swap destination"/g)).toHaveLength(1)
  })
})
