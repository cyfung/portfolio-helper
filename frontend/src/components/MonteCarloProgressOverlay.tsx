import { useEffect, useRef, useState } from 'react'
import { Maximize2, Minimize2 } from 'lucide-react'
import {
  MONTE_CARLO_PROGRESS_EVENT,
  isActiveMonteCarloRunProgress,
  normalizeMonteCarloRunProgress,
  progressValue,
  type McRunProgress,
  type MonteCarloProgressEventDetail,
} from '@/lib/monteCarloProgress'

const MINIMIZED_STORAGE_KEY = 'monte-carlo-progress-minimized'

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

function hasMonteCarloResult(value: unknown) {
  return Array.isArray(asRecord(value).portfolios)
}

function progressPercent(progress: McRunProgress) {
  return progress.total > 0
    ? Math.min(100, Math.max(0, (progress.completed / progress.total) * 100))
    : null
}

function progressDetails(progress: McRunProgress) {
  return progress.details.filter(detail =>
    detail.label !== progress.progressLabel &&
    detail.value !== undefined &&
    detail.value !== null &&
    String(detail.value) !== '',
  )
}

export default function MonteCarloProgressOverlay() {
  const [progress, setProgress] = useState<McRunProgress | null>(null)
  const [minimized, setMinimized] = useState(() => localStorage.getItem(MINIMIZED_STORAGE_KEY) === '1')
  const pollRef = useRef<number | null>(null)
  const clearRef = useRef<number | null>(null)

  function stopPolling() {
    if (pollRef.current != null) window.clearInterval(pollRef.current)
    pollRef.current = null
  }

  function cancelClear() {
    if (clearRef.current != null) window.clearTimeout(clearRef.current)
    clearRef.current = null
  }

  function clearSoon(ms = 2500) {
    cancelClear()
    clearRef.current = window.setTimeout(() => {
      setProgress(null)
      clearRef.current = null
    }, ms)
  }

  async function pollRunState(keepIdle = false, showDone = true) {
    const response = await fetch('/api/montecarlo/run-state')
    const state: unknown = await response.json()
    const rawState = asRecord(state)
    const nextProgress = normalizeMonteCarloRunProgress(rawState.progress ?? state)
    const hasResult = hasMonteCarloResult(rawState.result)

    if (nextProgress.phase === 'idle' && !hasResult) {
      if (!keepIdle) setProgress(null)
      return keepIdle
    }

    if (nextProgress.done) {
      if (!showDone) {
        setProgress(null)
        return false
      }
      setProgress(nextProgress)
      clearSoon()
      return false
    }

    if (hasResult && !isActiveMonteCarloRunProgress(nextProgress)) {
      setProgress(null)
      return false
    }

    setProgress(nextProgress)
    return isActiveMonteCarloRunProgress(nextProgress)
  }

  function startPolling(keepIdle = false, showDone = true) {
    stopPolling()
    pollRunState(keepIdle, showDone)
      .then(keepPolling => {
        if (!keepPolling) return
        pollRef.current = window.setInterval(() => {
          pollRunState(keepIdle, true)
            .then(nextKeepPolling => {
              if (!nextKeepPolling) stopPolling()
            })
            .catch(() => stopPolling())
        }, 300)
      })
      .catch(() => {})
  }

  useEffect(() => {
    startPolling(false, false)

    const handleProgress = (event: Event) => {
      const detail = (event as CustomEvent<MonteCarloProgressEventDetail>).detail ?? {}
      cancelClear()

      if ('progress' in detail) {
        if (detail.progress) {
          setProgress(detail.progress)
          if (detail.clearAfterMs != null) clearSoon(detail.clearAfterMs)
        } else if (detail.clearAfterMs != null) {
          clearSoon(detail.clearAfterMs)
        } else {
          setProgress(null)
          stopPolling()
        }
      }

      if (detail.startPolling) startPolling(!!detail.keepIdle)
    }

    window.addEventListener(MONTE_CARLO_PROGRESS_EVENT, handleProgress)
    return () => {
      window.removeEventListener(MONTE_CARLO_PROGRESS_EVENT, handleProgress)
      stopPolling()
      cancelClear()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- overlay owns one global polling loop for the app lifetime.
  }, [])

  useEffect(() => {
    localStorage.setItem(MINIMIZED_STORAGE_KEY, minimized ? '1' : '0')
  }, [minimized])

  if (!progress) return null

  const pct = progressPercent(progress)
  const details = progressDetails(progress)
  const toggleLabel = minimized ? 'Expand Monte Carlo progress' : 'Minimize Monte Carlo progress'
  const ToggleIcon = minimized ? Maximize2 : Minimize2

  return (
    <div className="mc-run-progress-slot">
      <div
        className={`mc-run-progress is-floating${progress.done ? ' done' : ''}${minimized ? ' minimized' : ''}`}
        role="status"
        aria-live="polite"
      >
        <div className="mc-run-progress-header">
          <div className={minimized ? 'mc-run-progress-mini' : 'mc-run-progress-title'}>
            {minimized ? (
              <>
                <div className="mc-run-progress-mini-main">
                  <strong>{progress.phaseLabel}</strong>
                  {pct != null && <span>{Math.round(pct)}%</span>}
                </div>
                <span>{progress.total > 0 ? `${progress.completed.toLocaleString()} / ${progress.total.toLocaleString()}` : progress.action}</span>
              </>
            ) : (
              <>
                <strong>{progress.phaseLabel}</strong>
                <span>{progress.action}</span>
              </>
            )}
          </div>
          <button
            className="mc-run-progress-icon-btn"
            type="button"
            title={toggleLabel}
            aria-label={toggleLabel}
            onClick={() => setMinimized(value => !value)}
          >
            <ToggleIcon size={13} strokeWidth={2.2} aria-hidden="true" />
          </button>
          {!minimized && progress.currentStep > 0 && progress.totalSteps > 0 && (
            <span className="mc-run-progress-step">
              Step {progress.currentStep}/{progress.totalSteps}
            </span>
          )}
        </div>
        {!minimized && pct != null && (
          <div className="mc-run-progress-bar" aria-label={`${progress.phaseLabel} progress`}>
            <div style={{ width: `${pct}%` }} />
          </div>
        )}
        {!minimized && (
          <div className="mc-run-progress-details">
            {progress.total > 0 && (
              <span>
                <span>{progress.progressLabel}</span>
                <strong>{progress.completed.toLocaleString()} / {progress.total.toLocaleString()}</strong>
              </span>
            )}
            {details.map((detail, i) => (
              <span key={`${detail.label}-${i}`}>
                <span>{detail.label}</span>
                <strong>{progressValue(detail.value)}</strong>
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
