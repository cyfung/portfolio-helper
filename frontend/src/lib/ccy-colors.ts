export const MAX_CCY_COLORS = 7

// Returns CSS class suffix '1'–'7' or 'plain'
export function getCcyClass(ccy: string, sortedCcys: string[]): string {
  const idx = sortedCcys.indexOf(ccy)
  if (idx < 0 || idx >= MAX_CCY_COLORS) return 'plain'
  return String(idx + 1)
}

// Build sorted, deduplicated global currency list across all portfolios.
// displayCurrencies comes from appConfig (server-aggregated across all portfolios' cash).
// stockCurrencies are best-effort from the current SSE snapshot.
export function buildSortedCcys(
  displayCurrencies: string[],
  stockCurrencies: string[],
): string[] {
  const set = new Set([...displayCurrencies, ...stockCurrencies])
  set.delete('P')
  return [...set].sort()
}
