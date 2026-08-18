// @vitest-environment jsdom

import { cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import EditMode from './EditMode'

vi.mock('@/stores/portfolioStore', () => ({
  usePortfolioStore: () => ({
    stocks: [],
    portfolioId: 'test',
    config: { flexibleWeightMappings: '' },
    appConfig: null,
  }),
}))

vi.mock('@/lib/savedPortfolioCache', () => ({
  useSavedPortfolios: () => ({ savedPortfolios: [] }),
}))

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('portfolio edit mode', () => {
  it('renders weight and manual quantity controls under their matching labels', () => {
    const { container } = render(
      <EditMode
        saveKey={0}
        onSaved={() => undefined}
        initialStocks={[{
          label: 'SPY',
          amount: 5,
          originalAmount: 5,
          targetWeight: 60,
          manualQty: true,
        } as never]}
      />,
    )

    const cells = container.querySelectorAll('#stock-edit-table tbody tr:first-child td')

    expect(cells[3].querySelector('.edit-weight')).toBeTruthy()
    expect(cells[4].querySelector('input[type="checkbox"]')).toBeTruthy()
  })

  it('saves positions once without reading or writing ticker metadata', async () => {
    let finishSave!: (response: Response) => void
    const saveResponse = new Promise<Response>(resolve => { finishSave = resolve })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => saveResponse)
    const onSaved = vi.fn(async () => undefined)
    const onSavingChange = vi.fn()
    const { container, rerender } = render(
      <EditMode
        saveKey={0}
        onSaved={onSaved}
        onSavingChange={onSavingChange}
        initialStocks={[{
          label: 'SSO',
          amount: 5,
          originalAmount: 5,
          targetWeight: 60,
          letf: '2 SPY',
          groups: '1 Equity',
        } as never]}
      />,
    )

    rerender(<EditMode saveKey={1} onSaved={onSaved} onSavingChange={onSavingChange} initialStocks={[]} />)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/portfolio/save-all?portfolio=test')
    const body = JSON.parse(String(options?.body))
    expect(body.stocks).toEqual([{ symbol: 'SSO', amount: 5, targetWeight: 60, manualQty: false }])
    expect(container.querySelector('fieldset')?.disabled).toBe(true)

    rerender(<EditMode saveKey={2} onSaved={onSaved} onSavingChange={onSavingChange} initialStocks={[]} />)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    finishSave(new Response('', { status: 200 }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(onSavingChange).toHaveBeenNthCalledWith(1, true)
    expect(onSavingChange).toHaveBeenLastCalledWith(false)
  })

  it('keeps edits available for retry when saving fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('failed', { status: 500 }))
    const onSaved = vi.fn()
    const { container, rerender } = render(
      <EditMode
        saveKey={0}
        onSaved={onSaved}
        initialStocks={[{ label: 'SSO', amount: 7, originalAmount: 7, targetWeight: 100 } as never]}
      />,
    )

    rerender(
      <EditMode
        saveKey={1}
        onSaved={onSaved}
        initialStocks={[{ label: 'SSO', amount: 7, originalAmount: 7, targetWeight: 100 } as never]}
      />,
    )

    await waitFor(() => expect(container.textContent).toContain('Failed to save: Save failed'))
    expect(onSaved).not.toHaveBeenCalled()
    expect(container.querySelector('fieldset')?.disabled).toBe(false)
    expect((container.querySelector('.edit-qty') as HTMLInputElement).value).toBe('7')
  })
})
