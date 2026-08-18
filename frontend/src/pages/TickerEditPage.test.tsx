// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TickerEditPage from './TickerEditPage'

vi.mock('@/components/Layout', () => ({
  ConfigButton: () => null,
  HeaderRight: ({ children }: { children: unknown }) => children,
  PageNavTabs: () => null,
  PrivacyToggleButton: () => null,
  ThemeToggle: () => null,
}))

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('ticker editor', () => {
  it('saves all ticker metadata changes in one bulk request', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify([
        { symbol: 'SSO', letf: '2 SPY', groups: '1 Equity' },
        { symbol: 'UPRO', letf: '3 SPY', groups: '1 Equity' },
      ]), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('', { status: 200 }))

    render(<TickerEditPage />)
    await screen.findByText('Loaded 2 tickers.')

    const letfInputs = screen.getAllByPlaceholderText('3,SPY')
    fireEvent.change(letfInputs[0], { target: { value: '2.5 SPY' } })
    fireEvent.change(letfInputs[1], { target: { value: '3.5 SPY' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save All (2)' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(fetchMock.mock.calls[0][0]).toBe('/api/ticker-config')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/ticker-config/bulk')
    const body = JSON.parse(String(fetchMock.mock.calls[1][1]?.body))
    expect(body).toEqual({
      upserts: [
        { symbol: 'SSO', letf: '2.5 SPY', groups: '1 Equity' },
        { symbol: 'UPRO', letf: '3.5 SPY', groups: '1 Equity' },
      ],
      deletes: [],
    })
  })
})
