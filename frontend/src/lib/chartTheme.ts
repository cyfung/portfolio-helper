// ── chartTheme.ts — Chart.js color helpers based on current theme ─────────────

export function getChartTheme() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'
  return {
    gridColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)',
    textColor: isDark ? '#c0c0c0' : '#495057',
  }
}
