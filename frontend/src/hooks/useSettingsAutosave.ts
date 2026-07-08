import { useEffect, useRef } from 'react'

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

  useEffect(() => {
    if (!enabled) return

    const serialized = safeSerialize(payload)
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
