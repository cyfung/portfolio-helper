import { useEffect, useRef, useState, type CSSProperties } from 'react'

type FetchStatus =
  | { phase: 'idle'; message: '' }
  | { phase: 'running' | 'success' | 'warning' | 'failure'; message: string }

interface Props {
  portfolioSlug: string
  onFetched: () => void | Promise<void>
  disabled?: boolean
  onBusyChange?: (busy: boolean) => void
  style?: CSSProperties
}

interface IngestResponse {
  written?: number
  error?: string
  message?: string
}

async function readResponse(response: Response): Promise<IngestResponse> {
  const text = await response.text()
  if (!text) return {}
  try {
    return JSON.parse(text) as IngestResponse
  } catch {
    return {}
  }
}

export default function IbkrPerformanceFetchControl({
  portfolioSlug,
  onFetched,
  disabled = false,
  onBusyChange,
  style,
}: Props) {
  const [status, setStatus] = useState<FetchStatus>({ phase: 'idle', message: '' })
  const requestGeneration = useRef(0)
  const busy = status.phase === 'running'

  useEffect(() => {
    requestGeneration.current += 1
    setStatus({ phase: 'idle', message: '' })
    onBusyChange?.(false)
  }, [portfolioSlug, onBusyChange])

  useEffect(() => () => {
    requestGeneration.current += 1
    onBusyChange?.(false)
  }, [onBusyChange])

  async function handleFetch() {
    const generation = ++requestGeneration.current
    setStatus({ phase: 'running', message: 'Fetching performance data from IBKR…' })
    onBusyChange?.(true)
    try {
      const response = await fetch(`/api/performance/ingest/${portfolioSlug}`, { method: 'POST' })
      const body = await readResponse(response)
      if (generation !== requestGeneration.current) return
      if (!response.ok) throw new Error(body.error ?? body.message ?? `HTTP ${response.status}`)
      try {
        await onFetched()
      } catch {
        if (generation === requestGeneration.current) {
          setStatus({ phase: 'warning', message: 'Fetch succeeded, but the chart could not be refreshed.' })
        }
        return
      }
      if (generation !== requestGeneration.current) return
      const written = body.written ?? 0
      setStatus({
        phase: 'success',
        message: written === 0
          ? 'Fetch completed — data is already up to date.'
          : `Fetched from IBKR — ${written} new snapshot${written === 1 ? '' : 's'}.`,
      })
    } catch (error) {
      if (generation !== requestGeneration.current) return
      setStatus({
        phase: 'failure',
        message: `Fetch failed — ${error instanceof Error ? error.message : String(error)}.`,
      })
    } finally {
      if (generation === requestGeneration.current) onBusyChange?.(false)
    }
  }

  return (
    <>
      <button
        className="backtest-config-btn"
        type="button"
        style={style}
        onClick={handleFetch}
        disabled={disabled || busy || !portfolioSlug}
      >
        {busy ? <>Fetching…<span className="btn-spinner" /></> : 'Fetch from IBKR'}
      </button>
      {status.message && (
        <span
          className={`ibkr-fetch-status ${status.phase}`}
          role={status.phase === 'failure' || status.phase === 'warning' ? 'alert' : 'status'}
        >
          {status.message}
        </span>
      )}
    </>
  )
}
