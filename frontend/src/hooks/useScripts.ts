// ── useScripts.ts — Dynamically load one or more scripts in order ─────────────
import { useEffect } from 'react'

/** Load an array of scripts sequentially (each waits for previous to load). */
export function useScripts(srcs: string[]) {
  useEffect(() => {
    if (!srcs.length) return
    const scripts: HTMLScriptElement[] = []
    let cancelled = false

    function loadNext(idx: number) {
      if (cancelled || idx >= srcs.length) return
      const s = document.createElement('script')
      s.src = srcs[idx]
      s.async = false
      s.onload = () => loadNext(idx + 1)
      document.body.appendChild(s)
      scripts.push(s)
    }

    loadNext(0)

    return () => {
      cancelled = true
      scripts.forEach(s => { try { document.body.removeChild(s) } catch (_) {} })
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
