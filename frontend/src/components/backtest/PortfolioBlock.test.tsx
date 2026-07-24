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
  it('renders a dedicated drag handle for every portfolio row type', () => {
    const markup = renderPortfolioBlock([
      {
        id: 'holding',
        type: 'HOLDING',
        instrument: 'SPY',
        allocation: '50',
      },
      {
        id: 'reference',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Child',
        allocation: '40',
        normalizationMode: 'NET_100',
      },
      {
        id: 'swap',
        type: 'SWAP',
        source: 'SPY',
        transferMode: 'AMOUNT',
        transferAmount: '10',
        legs: [{ id: 'leg', instrument: 'TLT', multiplier: '1' }],
      },
    ])

    expect(markup.match(/class="portfolio-row-drag-handle"/g)).toHaveLength(3)
    expect(markup).toContain('aria-label="Drag SPY row"')
    expect(markup).toContain('aria-label="Drag Child portfolio reference row"')
    expect(markup).toContain('aria-label="Drag SPY swap row"')
  })

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
    expect(markup).toContain('Ref 100')
    expect(markup).toContain('Reference mode for Child')
    expect(markup).toContain('Decompose Child to resolved holdings')
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

  it('renders multi-leg swaps as canonical collapsed text', () => {
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

    expect(markup).toContain('aria-label="Swap structure"')
    expect(markup).toContain('value="SPY &gt; TLT + 0.5 KMLM"')
    expect(markup).toContain('class="ticker-input swap-expression-field"')
    expect(markup).toContain('class="swap-row-badge">SWAP</span>')
    expect(markup).toContain('aria-label="Swap transfer amount"')
    expect(markup).toContain('aria-label="Edit swap"')
  })

  it('places percentage units inside allocation controls for every numeric row type', () => {
    const markup = renderPortfolioBlock([
      {
        id: 'holding',
        type: 'HOLDING',
        instrument: 'SPY',
        allocation: '25',
      },
      {
        id: 'reference',
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: 'Child',
        allocation: '40',
        normalizationMode: 'NET_100',
      },
      {
        id: 'swap',
        type: 'SWAP',
        source: 'SPY',
        transferMode: 'AMOUNT',
        transferAmount: '10',
        legs: [{ id: 'one', instrument: 'TLT', multiplier: '1' }],
      },
      {
        id: 'all-remaining-swap',
        type: 'SWAP',
        source: 'TLT',
        transferMode: 'ALL_REMAINING',
        transferAmount: '',
        legs: [{ id: 'two', instrument: 'SPY', multiplier: '1' }],
      },
    ])

    expect(markup.match(/<label class="allocation-field"><input[^>]+value="(?:25|40)"[^>]*\/><span class="allocation-unit"[^>]*>%<\/span><\/label>/g)).toHaveLength(2)
    expect(markup).toMatch(/<label class="swap-amount-field allocation-field"><input[^>]+value="10"[^>]*\/><span class="allocation-unit"[^>]*>%<\/span><\/label>/)
    expect(markup).toMatch(/<label class="swap-amount-field allocation-field"><input[^>]+value="\*"[^>]*\/><\/label>/)
    expect(markup).not.toContain('class="weight-unit"')
  })

  it('keeps a one-leg swap in the same compact editor', () => {
    const markup = renderPortfolioBlock([{
      id: 'swap',
      type: 'SWAP',
      source: 'SPY',
      transferMode: 'AMOUNT',
      transferAmount: '10',
      legs: [{ id: 'one', instrument: 'TLT', multiplier: '1' }],
    }])

    expect(markup).toContain('value="SPY &gt; TLT"')
    expect(markup).not.toContain('+ Destination')
  })

  it('shows the live pre-root resolved net instead of the input allocation total', () => {
    const markup = renderPortfolioBlock([{
      id: 'holding',
      type: 'HOLDING',
      instrument: 'SPY',
      allocation: '80',
    }])

    expect(markup).toContain('Resolved net: 80.00%')
    expect(markup).not.toContain('Total: 80.00%')
  })
})
