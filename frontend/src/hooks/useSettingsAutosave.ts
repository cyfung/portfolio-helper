import { useCallback, useEffect, useMemo, useRef } from 'react'

function safeSerialize(value: unknown) {
  const seen = new WeakSet<object>()
  return JSON.stringify(value, (_, item) => {
    if (typeof item === 'function') return undefined
    if (item == null || typeof item !== 'object') return item

    if (
      typeof Event !== 'undefined' && item instanceof Event ||
      typeof Node !== 'undefined' && item instanceof Node ||
      (
        'nativeEvent' in item &&
        'currentTarget' in item &&
        'preventDefault' in item &&
        'stopPropagation' in item
      )
    ) {
      return undefined
    }

    if (seen.has(item)) return undefined
    seen.add(item)
    return item
  })
}

export function useSettingsAutosave(endpoint: string, payload: unknown, enabled: boolean, delayMs = 400) {
  const initializedRef = useRef(false)
  const lastSavedRef = useRef('')
  const pendingSerializedRef = useRef<string | null>(null)
  const endpointRef = useRef(endpoint)
  const timeoutRef = useRef<number | null>(null)

  useEffect(() => {
    endpointRef.current = endpoint
  }, [endpoint])

  const clearPendingTimeout = useCallback(() => {
    if (timeoutRef.current == null) return
    window.clearTimeout(timeoutRef.current)
    timeoutRef.current = null
  }, [])

  const saveSerialized = useCallback((serialized: string | null, keepalive = false) => {
    if (!serialized || serialized === lastSavedRef.current) return

    clearPendingTimeout()
    lastSavedRef.current = serialized
    pendingSerializedRef.current = null

    if (keepalive) {
      const body = new Blob([serialized], { type: 'application/json' })
      if (navigator.sendBeacon?.(endpointRef.current, body)) return
    }

    fetch(endpointRef.current, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: serialized,
      keepalive,
    }).catch(() => {})
  }, [clearPendingTimeout])

  const flush = useCallback((nextPayload?: unknown) => {
    if (!enabled) return
    saveSerialized(nextPayload === undefined ? pendingSerializedRef.current : safeSerialize(nextPayload), true)
  }, [enabled, saveSerialized])

  useEffect(() => {
    if (!enabled) return

    const flushPending = () => {
      saveSerialized(pendingSerializedRef.current, true)
    }

    window.addEventListener('pagehide', flushPending)
    return () => {
      window.removeEventListener('pagehide', flushPending)
      flushPending()
    }
  }, [enabled, saveSerialized])

  useEffect(() => {
    if (!enabled) return

    const serialized = safeSerialize(payload)
    if (!initializedRef.current) {
      initializedRef.current = true
      lastSavedRef.current = serialized
      return
    }
    if (serialized === lastSavedRef.current) return

    pendingSerializedRef.current = serialized
    clearPendingTimeout()
    timeoutRef.current = window.setTimeout(() => {
      saveSerialized(serialized)
    }, delayMs)

    return clearPendingTimeout
  }, [clearPendingTimeout, delayMs, enabled, payload, saveSerialized])

  return useMemo(() => ({ flush }), [flush])
}
