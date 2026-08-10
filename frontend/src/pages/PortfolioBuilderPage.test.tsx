// @vitest-environment jsdom

import { forwardRef } from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PortfolioBuilderPage from './PortfolioBuilderPage'

vi.mock('@/components/Layout', () => ({
  PageNavTabs: () => null,
  ConfigButton: () => null,
  ThemeToggle: () => null,
  HeaderRight: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PrivacyToggleButton: () => null,
}))

vi.mock('@/components/backtest/PortfolioBlock', () => ({
  default: ({ value, onChange }: {
    value: { label: string; includeNoMargin: boolean }
    onChange: (value: unknown) => void
  }) => (
    <>
      <span>{value.label}</span>
      {value.label && value.includeNoMargin && (
        <button type="button" onClick={() => onChange({ ...value, includeNoMargin: false })}>
          Disable no margin for {value.label}
        </button>
      )}
    </>
  ),
}))
vi.mock('@/components/backtest/SavedPortfoliosBar', () => ({
  default: forwardRef(() => null),
}))
vi.mock('@/lib/savedPortfolioCache', () => ({
  useSavedPortfolios: () => ({ savedPortfolios: [] }),
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('portfolio builder margin analysis', () => {
  it('offers no margin and keeps LETF exposure in the margin-scaled column', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/backtest/settings') {
        return new Response(JSON.stringify({
          portfolios: [{
            inputLabel: 'Leveraged',
            rows: [{ id: 'holding', type: 'HOLDING', instrument: 'SSO', allocation: 100 }],
            rebalanceStrategy: 'YEARLY',
            marginStrategies: [{ marginRatio: 0.5 }, { marginRatio: 0.25 }],
            includeNoMargin: true,
          }],
        }))
      }
      if (url === '/api/ticker-config?symbol=SSO') {
        return new Response(JSON.stringify({ letf: '2 SPY', groups: '' }))
      }
      if (url === '/api/ticker-config?symbol=SPY') {
        return new Response(JSON.stringify({ letf: '', groups: '' }))
      }
      return new Response('{}')
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<PortfolioBuilderPage />)
    await waitFor(() => expect(screen.getByText('Leveraged')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Analyse' }))

    const marginSelect = await screen.findByRole('combobox', { name: 'Margin for Leveraged' })
    expect(within(marginSelect).getByRole('option', { name: 'No margin' })).toBeTruthy()
    expect(within(marginSelect).getByRole('option', { name: 'Margin 1: 50.00%' })).toBeTruthy()

    fireEvent.change(marginSelect, { target: { value: 'no-margin' } })

    const spyRow = screen.getByRole('row', { name: /SPY/ })
    expect(within(spyRow).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'SPY',
      '100.00%',
      '200.00%',
    ])

    fireEvent.click(screen.getByRole('button', { name: 'Disable no margin for Leveraged' }))

    expect(within(marginSelect).queryByRole('option', { name: 'No margin' })).toBeNull()
    expect((marginSelect as HTMLSelectElement).value).toBe('0')
    expect(within(spyRow).getAllByRole('cell').map(cell => cell.textContent)).toEqual([
      'SPY',
      '100.00%',
      '300.00%',
    ])
  })
})
