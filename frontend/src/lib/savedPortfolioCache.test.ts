import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  getSavedPortfolios,
  invalidateSavedPortfolioCache,
  refreshSavedPortfolios,
} from './savedPortfolioCache'

afterEach(() => {
  invalidateSavedPortfolioCache()
  vi.unstubAllGlobals()
})

describe('saved portfolio cache', () => {
  it('shares one request and retains full saved configurations', async () => {
    const saved = [{ name: 'Child', config: { rows: [{ id: 'holding', type: 'HOLDING' }] } }]
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => saved,
    })
    vi.stubGlobal('fetch', fetchMock)

    const [first, second] = await Promise.all([getSavedPortfolios(), getSavedPortfolios()])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(first).toEqual(saved)
    expect(second).toBe(first)
  })

  it('refreshes the shared value explicitly after saved portfolios change', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [{ name: 'One', config: { rows: [] } }] })
      .mockResolvedValueOnce({ ok: true, json: async () => [{ name: 'Two', config: { rows: [] } }] })
    vi.stubGlobal('fetch', fetchMock)

    await getSavedPortfolios()
    const refreshed = await refreshSavedPortfolios()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(refreshed[0].name).toBe('Two')
  })
})
