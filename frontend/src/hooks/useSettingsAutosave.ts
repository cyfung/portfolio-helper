import { useEffect, useRef } from 'react'

export function useSettingsAutosave(endpoint: string, payload: unknown, enabled: boolean, delayMs = 400) {
  const initializedRef = useRef(false)
  const lastSavedRef = useRef('')

  useEffect(() => {
    if (!enabled) return

    const serialized = JSON.stringify(payload)
    if (!initializedRef.current) {
      initializedRef.current = true
      lastSavedRef.current = serialized
      return
    }
    if (serialized === lastSavedRef.current) return

    const timeoutId = window.setTimeout(() => {
      lastSavedRef.current = serialized
      fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: serialized,
      }).catch(() => {})
    }, delayMs)

    return () => window.clearTimeout(timeoutId)
  }, [delayMs, enabled, endpoint, payload])
}
