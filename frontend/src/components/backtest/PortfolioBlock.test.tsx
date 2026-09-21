// @vitest-environment jsdom

import { renderToStaticMarkup } from 'react-dom/server'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PortfolioBlock from './PortfolioBlock'
import type { BlockState } from '@/types/backtest'

afterEach(cleanup)

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
  it('adds an empty swap row without opening the swap dialog', () => {
    const onChange = vi.fn()
    render(
      <PortfolioBlock
        idx={0}
        value={{
          label: 'Example',
          tickers: [],
          rebalance: 'YEARLY',
          margins: [],
          rebalanceStrategies: [],
          includeNoMargin: true,
        }}
        onChange={onChange}
        onSavedRefresh={() => undefined}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '+Swap' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect((screen.getByLabelText('Swap structure') as HTMLInputElement).value).toBe(' > ')
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      tickers: [expect.objectContaining({
        type: 'SWAP',
        sources: [expect.objectContaining({ instrument: '', multiplier: '1' })],
        transferMode: 'AMOUNT',
        transferAmount: '',
        legs: [expect.objectContaining({ instrument: '', multiplier: '1' })],
      })],
    }))
  })

  it('explains how to select bundled simulated history', () => {
    const markup = renderPortfolioBlock([])

    expect(markup).toContain('Add $ to a supported ticker to use bundled simulated history')
    expect(markup).toContain('ordinary tickers use market-provider history only')
  })

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
        sources: [{ id: 'source', instrument: 'SPY', multiplier: '1' }],
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
    expect(markup).toContain('class="portfolio-ref-mode-select"')
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
      sources: [{ id: 'source', instrument: 'SPY', multiplier: '1' }],
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

  it('adds and saves another source leg through the swap dialog', () => {
    const onChange = vi.fn()
    render(
      <PortfolioBlock
        idx={0}
        value={{
          label: 'Example',
          tickers: [{
            id: 'swap',
            type: 'SWAP',
            sources: [{ id: 'source-1', instrument: 'SPY', multiplier: '1' }],
            transferMode: 'AMOUNT',
            transferAmount: '10',
            legs: [{ id: 'destination-1', instrument: 'QQQ', multiplier: '1' }],
          }],
          rebalance: 'YEARLY',
          margins: [],
          rebalanceStrategies: [],
          includeNoMargin: true,
        }}
        onChange={onChange}
        onSavedRefresh={() => undefined}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Edit swap' }))
    fireEvent.click(screen.getByRole('button', { name: '+ Source' }))

    const sourceInputs = screen.getAllByLabelText('Swap source') as HTMLInputElement[]
    const multiplierInputs = screen.getAllByLabelText('Swap source multiplier') as HTMLInputElement[]
    fireEvent.change(sourceInputs[1], { target: { value: '(1 TLT 1 GLD)' } })
    fireEvent.change(multiplierInputs[1], { target: { value: '2' } })
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Save' }))

    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      tickers: [expect.objectContaining({
        sources: [
          expect.objectContaining({ instrument: 'SPY', multiplier: '1' }),
          expect.objectContaining({ instrument: '(1 TLT 1 GLD)', multiplier: '2' }),
        ],
      })],
    }))
    expect((screen.getByLabelText('Swap structure') as HTMLInputElement).value)
      .toBe('SPY + 2 (1 TLT 1 GLD) > QQQ')
  })

  it('removes a source leg through the swap dialog', () => {
    const onChange = vi.fn()
    render(
      <PortfolioBlock
        idx={0}
        value={{
          label: 'Example',
          tickers: [{
            id: 'swap',
            type: 'SWAP',
            sources: [
              { id: 'source-1', instrument: 'SPY', multiplier: '1' },
              { id: 'source-2', instrument: 'TLT', multiplier: '1' },
            ],
            transferMode: 'AMOUNT',
            transferAmount: '10',
            legs: [{ id: 'destination-1', instrument: 'QQQ', multiplier: '1' }],
          }],
          rebalance: 'YEARLY',
          margins: [],
          rebalanceStrategies: [],
          includeNoMargin: true,
        }}
        onChange={onChange}
        onSavedRefresh={() => undefined}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Edit swap' }))
    const dialog = within(screen.getByRole('dialog'))
    fireEvent.click(dialog.getByRole('button', { name: 'Remove source TLT' }))
    fireEvent.click(dialog.getByRole('button', { name: 'Save' }))

    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      tickers: [expect.objectContaining({
        sources: [expect.objectContaining({ instrument: 'SPY', multiplier: '1' })],
      })],
    }))
  })

  it('preserves multi-source shorthand when switching to expanded editing', () => {
    const onChange = vi.fn()
    render(
      <PortfolioBlock
        idx={0}
        value={{
          label: 'Example',
          tickers: [{
            id: 'swap',
            type: 'SWAP',
            sources: [{ id: 'source-1', instrument: 'SPY', multiplier: '1' }],
            transferMode: 'AMOUNT',
            transferAmount: '10',
            legs: [{ id: 'destination-1', instrument: 'QQQ', multiplier: '1' }],
          }],
          rebalance: 'YEARLY',
          margins: [],
          rebalanceStrategies: [],
          includeNoMargin: true,
        }}
        onChange={onChange}
        onSavedRefresh={() => undefined}
      />,
    )

    const structure = screen.getByLabelText('Swap structure') as HTMLInputElement
    fireEvent.change(structure, { target: { value: '2 spy + (1 tlt 1 gld) > -0.5 qqq' } })
    fireEvent.blur(structure)
    fireEvent.click(screen.getByRole('button', { name: 'Edit swap' }))

    expect((screen.getAllByLabelText('Swap source') as HTMLInputElement[]).map(input => input.value))
      .toEqual(['SPY', '(1 TLT 1 GLD)'])
    expect((screen.getAllByLabelText('Swap source multiplier') as HTMLInputElement[]).map(input => input.value))
      .toEqual(['2', '1'])
    expect((screen.getByLabelText('Swap destination multiplier') as HTMLInputElement).value).toBe('-0.5')
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
        sources: [{ id: 'source-1', instrument: 'SPY', multiplier: '1' }],
        transferMode: 'AMOUNT',
        transferAmount: '10',
        legs: [{ id: 'one', instrument: 'TLT', multiplier: '1' }],
      },
      {
        id: 'all-remaining-swap',
        type: 'SWAP',
        sources: [{ id: 'source-2', instrument: 'TLT', multiplier: '1' }],
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
      sources: [{ id: 'source', instrument: 'SPY', multiplier: '1' }],
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
