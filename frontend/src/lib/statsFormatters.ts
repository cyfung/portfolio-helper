// ── statsFormatters.ts — Shared stat formatters for backtest & monte carlo ────

export function pct(v: number): string { return (v * 100).toFixed(2) + '%' }
export function fmt2(v: number): string { return v.toFixed(2) }
export function money(v: number): string { return '$' + v.toFixed(0) }

/** Format trading-day count as human-readable duration (e.g. "2.5y", "8m", "0d") */
export function dur(tradingDays: number): string {
  if (tradingDays <= 0) return '0d'
  if (tradingDays >= 252) return (tradingDays / 252).toFixed(1) + 'y'
  return Math.round(tradingDays / 21) + 'm'
}
