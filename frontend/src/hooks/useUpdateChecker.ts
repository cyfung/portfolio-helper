// ── useUpdateChecker.ts — Poll server update state and keep badge fresh ────────
// The server (UpdateService.initialize) already handles:
//   • Checking GitHub on startup (after 5s) and periodically
//   • Auto-downloading when autoUpdate + jpackage + hasUpdate
// This hook just polls /api/admin/update-info to surface that state in the UI.

import { useEffect, useCallback, useRef } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'

interface UpdateInfoResponse {
  hasUpdate: boolean
  latestVersion: string | null
  autoUpdate: boolean
  download: { phase: string }
}

const POLL_NORMAL_MS  = 60_000  // 1 min — server checks GitHub periodically
const POLL_FAST_MS    =  3_000  // 3 s  — while downloading, track progress
const STARTUP_DELAY_MS = 8_000  // 8 s  — catch server's initial 5s GitHub check

export function useUpdateChecker() {
  const appConfig       = usePortfolioStore(s => s.appConfig)
  const updateAppConfig = usePortfolioStore(s => s.updateAppConfig)

  const poll = useCallback(async () => {
    try {
      const r = await fetch('/api/admin/update-info')
      if (!r.ok) return
      const info: UpdateInfoResponse = await r.json()
      updateAppConfig({
        hasUpdate:     info.hasUpdate,
        latestVersion: info.latestVersion ?? null,
        downloadPhase: info.download?.phase ?? 'IDLE',
        autoUpdate:    info.autoUpdate,
      })
    } catch { /* network error — ignore, will retry */ }
  }, [updateAppConfig])

  // One-shot poll shortly after startup to pick up server's first GitHub check
  const startupFired = useRef(false)
  useEffect(() => {
    if (!appConfig || startupFired.current) return
    startupFired.current = true
    const t = setTimeout(poll, STARTUP_DELAY_MS)
    return () => clearTimeout(t)
  }, [!!appConfig, poll])

  // Periodic poll — faster while a download is in progress
  const isDownloading = appConfig?.downloadPhase === 'DOWNLOADING'
  useEffect(() => {
    if (!appConfig) return
    const ms = isDownloading ? POLL_FAST_MS : POLL_NORMAL_MS
    const t = setInterval(poll, ms)
    return () => clearInterval(t)
  }, [!!appConfig, isDownloading, poll])
}
