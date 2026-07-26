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
    fireEvent.click(screen.getByRole('button', { name: 'Hide cash entries' }))

    expect(screen.queryByText('Broker Cash')).toBeNull()
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
    expect(screen.queryByText('Broker Cash')).toBeNull()
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
