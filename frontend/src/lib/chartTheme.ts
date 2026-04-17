// ── chartTheme.ts — Chart color helpers based on current theme ────────────────

import { useState, useEffect } from 'react'

export function getChartTheme() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'
  return {
    isDark,
    gridColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)',
    textColor: isDark ? '#c0c0c0' : '#495057',
  }
}

// Reactive hook: re-derives theme whenever data-theme attribute changes.
export function useChartTheme() {
  const [theme, setTheme] = useState(getChartTheme)
  useEffect(() => {
    const obs = new MutationObserver(() => setTheme(getChartTheme()))
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => obs.disconnect()
  }, [])
  return theme
}
