// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockTable from './StockTable'

const defaultStore = {
  stocks: [
    { label: 'ZZZ', amount: 1, targetWeight: 40, letf: '', groups: '' },
    { label: 'AAA', amount: 2, targetWeight: 60, letf: '', groups: '' },
  ],
  fxRates: { USD: 1 },
  currentDisplayCurrency: 'USD',
  config: { flexibleWeightMappings: '' },
  lastStockDisplay: null,
  lastGroupAllocData: null,
  lastPortfolioTotals: null,
  rebalTargetUsd: 0,
  marginTargetPct: 0,
  marginTargetUsd: 0,
  allocAddMode: 'PROPORTIONAL',
  allocReduceMode: 'PROPORTIONAL',
  showStockDisplayCurrency: false,
  groupViewActive: false,
  appConfig: null,
  stockGroupBy: 'none',
  portfolioColumnModeId: 'mode-1',
}

let store: Record<string, unknown> = { ...defaultStore }

vi.mock('@/stores/portfolioStore', () => ({
  usePortfolioStore: () => store,
}))

beforeEach(() => {
  store = { ...defaultStore }
})

afterEach(cleanup)

function renderedSymbols(container: HTMLElement): string[] {
  return [...container.querySelectorAll<HTMLTableRowElement>('tbody tr[data-symbol]')]
    .map(row => row.dataset.symbol ?? '')
}

describe('stock table sorting', () => {
  it('sorts a text column ascending on its first click and toggles direction', () => {
    const { container } = render(<StockTable />)

    expect(renderedSymbols(container)).toEqual(['ZZZ', 'AAA'])

    fireEvent.click(screen.getByRole('button', { name: 'Sort by Symbol ascending' }))
    expect(renderedSymbols(container)).toEqual(['AAA', 'ZZZ'])
    expect(screen.getByRole('button', { name: 'Sort by Symbol descending' }).textContent).toBe('Symbol ↑')

    fireEvent.click(screen.getByRole('button', { name: 'Sort by Symbol descending' }))
    expect(renderedSymbols(container)).toEqual(['ZZZ', 'AAA'])
  })

  it('offers separate full-size CUR, TGT, and DEV controls with descending first', () => {
    store = {
      ...defaultStore,
      lastPortfolioTotals: { stockGrossUsd: 100, stockGrossKnown: true, marginUsd: 0 },
      lastStockDisplay: {
        stocks: [
          { symbol: 'ZZZ', positionValueUsd: 70, currency: 'USD' },
          { symbol: 'AAA', positionValueUsd: 30, currency: 'USD' },
        ],
      },
    }
    const { container } = render(<StockTable />)

    expect(screen.getByLabelText('Weight').textContent).toBe('⚖️')
    expect(screen.getByRole('button', { name: 'Sort by current weight descending' }).textContent).toBe('CUR')
    expect(screen.getByRole('button', { name: 'Sort by target weight descending' }).textContent).toBe('TGT')
    expect(screen.getByRole('button', { name: 'Sort by weight deviation descending' }).textContent).toBe('DEV')

    fireEvent.click(screen.getByRole('button', { name: 'Sort by weight deviation descending' }))
    expect(renderedSymbols(container)).toEqual(['ZZZ', 'AAA'])
    expect(screen.getByRole('button', { name: 'Sort by weight deviation ascending' }).textContent).toBe('DEV ↓')

    fireEvent.click(screen.getByRole('button', { name: 'Sort by target weight descending' }))
    expect(renderedSymbols(container)).toEqual(['AAA', 'ZZZ'])
  })

  it('keeps missing numeric values last in both directions', () => {
    store = {
      ...defaultStore,
      stocks: [
        { label: 'MISSING', amount: 1, targetWeight: 0, letf: '', groups: '' },
        { label: 'LOW', amount: 1, targetWeight: 0, letf: '', groups: '' },
        { label: 'HIGH', amount: 1, targetWeight: 0, letf: '', groups: '' },
      ],
      lastStockDisplay: {
        stocks: [
          { symbol: 'MISSING', markPrice: null, currency: 'USD' },
          { symbol: 'LOW', markPrice: 10, currency: 'USD' },
          { symbol: 'HIGH', markPrice: 20, currency: 'USD' },
        ],
      },
    }
    const { container } = render(<StockTable />)

    fireEvent.click(screen.getByRole('button', { name: 'Sort by Mark descending' }))
    expect(renderedSymbols(container)).toEqual(['HIGH', 'LOW', 'MISSING'])

    fireEvent.click(screen.getByRole('button', { name: 'Sort by Mark ascending' }))
    expect(renderedSymbols(container)).toEqual(['LOW', 'HIGH', 'MISSING'])
  })

  it('sorts the percentage change displayed inside the Mark column', () => {
    store = {
      ...defaultStore,
      lastStockDisplay: {
        stocks: [
          { symbol: 'ZZZ', markPrice: 20, dayChangePct: -2, currency: 'USD' },
          { symbol: 'AAA', markPrice: 10, dayChangePct: 3, currency: 'USD' },
        ],
      },
    }
    const { container } = render(<StockTable />)

    fireEvent.click(screen.getByRole('button', { name: 'Sort by daily percentage change descending' }))

    expect(renderedSymbols(container)).toEqual(['AAA', 'ZZZ'])
    expect(screen.getByRole('button', { name: 'Sort by daily percentage change ascending' }).textContent).toBe('Δ% ↓')
  })

  it('sorts holdings within groups without changing group order', () => {
    store = {
      ...defaultStore,
      stocks: [
        { label: 'B2', amount: 1, targetWeight: 0, letf: '', groups: '1 Beta' },
        { label: 'A2', amount: 1, targetWeight: 0, letf: '', groups: '1 Alpha' },
        { label: 'B1', amount: 1, targetWeight: 0, letf: '', groups: '1 Beta' },
        { label: 'A1', amount: 1, targetWeight: 0, letf: '', groups: '1 Alpha' },
      ],
      stockGroupBy: 'mainGroup',
    }
    const { container } = render(<StockTable />)

    fireEvent.click(screen.getByRole('button', { name: 'Sort by Symbol ascending' }))

    const rows = [...container.querySelectorAll<HTMLTableRowElement>('tbody tr')]
    expect(rows.map(row => row.dataset.symbol ?? row.textContent?.trim())).toEqual([
      'Alpha', 'A1', 'A2', 'Beta', 'B1', 'B2',
    ])
  })

  it('provides independent CUR, FLEX, and DEV controls for flexible weight', () => {
    store = {
      ...defaultStore,
      portfolioColumnModeId: 'mode-4',
    }

    render(<StockTable />)

    expect(screen.getByLabelText('Flexible weight').textContent).toBe('F ⚖️')
    expect(screen.getByRole('button', { name: 'Sort by current weight descending' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sort by flexible current weight descending' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sort by flexible target weight descending' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sort by flexible weight deviation descending' })).toBeTruthy()
  })

  it('uses the accent F marker in every flexible column header', () => {
    store = {
      ...defaultStore,
      appConfig: {
        portfolioColumnModes: [{
          id: 'flex-all',
          name: 'Flexible',
          columns: ['flexWeight', 'flexRebalQty', 'flexRebalDollars'],
        }],
      },
      portfolioColumnModeId: 'flex-all',
    }

    const { container } = render(<StockTable />)

    expect(container.querySelectorAll('thead .flex-column-marker')).toHaveLength(3)
  })
})
