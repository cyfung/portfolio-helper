// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { usePortfolioStore } from '@/stores/portfolioStore'
import SummaryTable from './SummaryTable'

function seedCashSummary() {
  usePortfolioStore.setState({
    portfolioId: 'main',
    cash: [{ label: 'Broker Cash', currency: 'USD', amount: 1250, marginFlag: true }],
    fxRates: { USD: 1 },
    currentDisplayCurrency: 'USD',
    lastCashDisplay: {
      type: 'cash-display',
      portfolioId: 'main',
      entries: [{
        label: 'Broker Cash',
        currency: 'USD',
        rawCcyAmount: 1250,
        baseUsd: 1250,
        isMarginEntry: true,
        entryId: 'broker-cash',
      }],
      totalBaseUsd: 1250,
      totalKnown: true,
      marginBaseUsd: 0,
    },
    lastPortfolioTotals: {
      type: 'portfolio-totals',
      portfolioId: 'main',
      stockGrossUsd: 10_000,
      stockGrossKnown: true,
      cashTotalUsd: 1250,
      cashKnown: true,
      grandTotalUsd: 11_250,
      grandTotalKnown: true,
      marginUsd: 0,
      dayChangeUsd: 25,
      prevDayUsd: 11_225,
    },
  })
}

describe('cash summary disclosure', () => {
  beforeEach(() => {
    localStorage.clear()
    seedCashSummary()
  })

  afterEach(cleanup)

  it('hides cash entries while retaining every summary row', () => {
    render(<SummaryTable />)

    const totalCash = screen.getByText('Total Cash')
    const cashEntry = screen.getByText('Broker Cash')
    expect(totalCash.compareDocumentPosition(cashEntry) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('1 entry')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Hide cash entries' }))

    expect(screen.queryByRole('row', { name: /Broker Cash/ })).toBeNull()
    expect(screen.getByText('Broker Cash').closest('tbody')?.getAttribute('aria-hidden')).toBe('true')
    expect(screen.getByText('Total Cash')).toBeTruthy()
    expect(screen.getByText('1 entry')).toBeTruthy()
    expect(screen.getByText('Portfolio Value')).toBeTruthy()
    expect(screen.getByText('Stock Gross Value')).toBeTruthy()
    expect(screen.getByText('Rebalance Target')).toBeTruthy()
    expect(screen.getByText('Margin Target')).toBeTruthy()
  })

  it('restores the collapsed choice after remounting', () => {
    const view = render(<SummaryTable />)
    fireEvent.click(screen.getByRole('button', { name: 'Hide cash entries' }))
    view.unmount()

    render(<SummaryTable />)

    expect(screen.getByRole('button', { name: 'Show cash entries' })).toBeTruthy()
    expect(screen.queryByRole('row', { name: /Broker Cash/ })).toBeNull()
    expect(screen.getByText('Broker Cash').closest('tbody')?.getAttribute('aria-hidden')).toBe('true')
    expect(screen.getByText('1 entry')).toBeTruthy()
  })

  it('omits the disclosure when there are no displayable cash entries', () => {
    usePortfolioStore.setState({
      lastCashDisplay: {
        type: 'cash-display',
        portfolioId: 'main',
        entries: [],
        totalBaseUsd: 1250,
        totalKnown: true,
        marginBaseUsd: 0,
      },
    })

    render(<SummaryTable />)

    expect(screen.queryByRole('button', { name: 'Hide cash entries' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Show cash entries' })).toBeNull()
    expect(screen.queryByText('0 entries')).toBeNull()
    expect(screen.getByText('Total Cash')).toBeTruthy()
  })
})

describe('portfolio value cards', () => {
  beforeEach(() => {
    localStorage.clear()
    seedCashSummary()
  })

  afterEach(cleanup)

  it('shows portfolio, equity-base, and stock-gross values with their own daily percentages', () => {
    usePortfolioStore.setState({
      lastPortfolioTotals: {
        type: 'portfolio-totals',
        portfolioId: 'main',
        stockGrossUsd: 120_000,
        stockGrossKnown: true,
        cashTotalUsd: -25_000,
        cashKnown: true,
        grandTotalUsd: 95_000,
        grandTotalKnown: true,
        marginUsd: -20_000,
        dayChangeUsd: 1_000,
        prevDayUsd: 94_000,
      },
    })

    render(<SummaryTable />)

    expect(screen.getByRole('group', { name: 'Portfolio Value' }).textContent).toContain('95,000.00+1.06%')
    expect(screen.getByRole('group', { name: 'Equity Base' }).textContent).toContain('100,000.00+1.01%')
    expect(screen.getByRole('group', { name: 'Stock Gross Value' }).textContent).toContain('120,000.00+0.84%')

    const dayChangeRow = screen.getByRole('row', { name: /Day Change/ })
    expect(dayChangeRow.textContent).toContain('1,000.00')
    expect(dayChangeRow.textContent).not.toContain('%')
    expect(dayChangeRow.compareDocumentPosition(screen.getByText('Total Cash')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getAllByText('Portfolio Value')).toHaveLength(1)
    expect(screen.getAllByText('Stock Gross Value')).toHaveLength(1)
  })

  it('hides the equity-base card when it is less than one percent from portfolio value', () => {
    usePortfolioStore.setState({
      lastPortfolioTotals: {
        type: 'portfolio-totals',
        portfolioId: 'main',
        stockGrossUsd: 100_000,
        stockGrossKnown: true,
        cashTotalUsd: -500,
        cashKnown: true,
        grandTotalUsd: 99_500,
        grandTotalKnown: true,
        marginUsd: -500,
        dayChangeUsd: -100,
        prevDayUsd: 99_600,
      },
    })

    render(<SummaryTable />)

    expect(screen.queryByRole('group', { name: 'Equity Base' })).toBeNull()
    expect(screen.getByRole('group', { name: 'Portfolio Value' })).toBeTruthy()
    expect(screen.getByRole('group', { name: 'Stock Gross Value' })).toBeTruthy()
  })

  it('hides the equity-base card when cash totals are unknown', () => {
    usePortfolioStore.setState({
      lastPortfolioTotals: {
        type: 'portfolio-totals',
        portfolioId: 'main',
        stockGrossUsd: 120_000,
        stockGrossKnown: true,
        cashTotalUsd: -25_000,
        cashKnown: false,
        grandTotalUsd: 95_000,
        grandTotalKnown: true,
        marginUsd: -20_000,
        dayChangeUsd: 1_000,
        prevDayUsd: 94_000,
      },
    })

    render(<SummaryTable />)

    expect(screen.queryByRole('group', { name: 'Equity Base' })).toBeNull()
  })
})
