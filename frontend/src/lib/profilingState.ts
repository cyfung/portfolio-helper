// ── profilingState.ts — Shared state backing profiling.tsx's ProfileBoundary ──
// Split from profiling.tsx (a component-only file) so Fast Refresh can treat
// that file as a pure component module.

import { useEffect, useState } from 'react'

let enabled = false
let checked = false
const listeners = new Set<() => void>()

async function check() {
  if (checked) return
  checked = true
  try {
    const r = await fetch('/api/admin/profiling-mode')
    if (!r.ok) return
    const data = await r.json()
    enabled = !!data.enabled
    listeners.forEach(l => l())
  } catch { /* not in profiling mode, or offline — stay disabled */ }
}

void check()

export function isProfilingEnabled() {
  return enabled
}

export function useProfilingEnabled() {
  const [value, setValue] = useState(enabled)
  useEffect(() => {
    const listener = () => setValue(enabled)
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  }, [])
  return value
}
