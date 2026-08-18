// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IbkrPerformanceFetchControl from './IbkrPerformanceFetchControl'

describe('IBKR performance fetch control', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('shows running progress and keeps the success result visible', async () => {
    let resolveFetch!: (response: Response) => void
    const fetchPromise = new Promise<Response>(resolve => { resolveFetch = resolve })
    vi.stubGlobal('fetch', vi.fn(() => fetchPromise))

    render(<IbkrPerformanceFetchControl portfolioSlug="main" onFetched={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch from IBKR' }))

    expect(screen.getByRole('button', { name: /Fetching/ })).toHaveProperty('disabled', true)
    expect(screen.getByRole('status').textContent).toBe('Fetching performance data from IBKR…')

    resolveFetch(new Response(JSON.stringify({ written: 3 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe('Fetched from IBKR — 3 new snapshots.')
    })
  })

  it('reports a successful fetch with no new snapshots as already up to date', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ written: 0 }), { status: 200 })))

    render(<IbkrPerformanceFetchControl portfolioSlug="main" onFetched={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch from IBKR' }))

    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe('Fetch completed — data is already up to date.')
    })
  })

  it('shows the actionable server failure as an alert', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'IBKR timed out' }), { status: 500 })))

    render(<IbkrPerformanceFetchControl portfolioSlug="main" onFetched={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch from IBKR' }))

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toBe('Fetch failed — IBKR timed out.')
    })
  })

  it('falls back to the HTTP status when an error response is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>Bad gateway</html>', { status: 502 })))

    render(<IbkrPerformanceFetchControl portfolioSlug="main" onFetched={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch from IBKR' }))

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toBe('Fetch failed — HTTP 502.')
    })
  })

  it('warns when ingestion succeeds but refreshing displayed data fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ written: 2 }), { status: 200 })))

    render(<IbkrPerformanceFetchControl portfolioSlug="main" onFetched={vi.fn(async () => { throw new Error('refresh failed') })} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch from IBKR' }))

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toBe('Fetch succeeded, but the chart could not be refreshed.')
    })
  })

  it('clears status and ignores completion when the selected portfolio changes', async () => {
    let resolveFetch!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { resolveFetch = resolve })))
    const onFetched = vi.fn()
    const view = render(<IbkrPerformanceFetchControl portfolioSlug="first" onFetched={onFetched} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch from IBKR' }))
    expect(screen.getByRole('status')).toBeTruthy()

    view.rerender(<IbkrPerformanceFetchControl portfolioSlug="second" onFetched={onFetched} />)
    expect(screen.queryByRole('status')).toBeNull()

    resolveFetch(new Response(JSON.stringify({ written: 4 }), { status: 200 }))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(screen.queryByRole('status')).toBeNull()
    expect(onFetched).not.toHaveBeenCalled()
  })
})
