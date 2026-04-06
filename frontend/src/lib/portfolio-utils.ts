// ── portfolio-utils.ts — Port of utils.js + formatting from ui-helpers.js ────

// ── Currency formatting ───────────────────────────────────────────────────────

export function formatCurrency(val: number): string {
  const sign = val < 0 ? '-' : ''
  return sign + Math.abs(val).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function formatSignedCurrency(val: number): string {
  return (val >= 0 ? '+' : '') + formatCurrency(val)
}

export function formatPct(val: number, decimals = 2): string {
  return val.toFixed(decimals) + '%'
}

export function formatSignedPct(val: number, decimals = 2): string {
  return (val >= 0 ? '+' : '') + formatPct(val, decimals)
}

/** Parse a price string like "$1,234.56" or "1234.56" to a number, or null if invalid */
export function parsePrice(text: string | null | undefined): number | null {
  if (!text) return null
  const cleaned = text.replace(/[$,]/g, '').trim()
  if (cleaned === '' || cleaned === '—') return null
  const val = parseFloat(cleaned)
  return isNaN(val) ? null : val
}

// ── FX / display currency ─────────────────────────────────────────────────────

export function toDisplayCurrency(
  usd: number,
  fxRates: Record<string, number>,
  currency: string
): number {
  const rate = fxRates[currency]
  if (!rate || rate === 0 || currency === 'USD') return usd
  return usd / rate
}

export function formatDisplayCurrency(
  usd: number,
  fxRates: Record<string, number>,
  currency: string
): string {
  return formatCurrency(toDisplayCurrency(usd, fxRates, currency))
}

// ── Quantity formatting ───────────────────────────────────────────────────────

/** Display integer quantities without decimals */
export function formatQty(amount: number): string {
  if (amount === Math.trunc(amount)) return amount.toString()
  return amount.toString()
}

// ── LETF / groups parsing ─────────────────────────────────────────────────────

/** Parse "mult,sym,mult,sym" → [{mult, sym}] (matches data-letf attribute format) */
export function parseLetfAttr(attr: string): { mult: number; sym: string }[] {
  if (!attr) return []
  const tokens = attr.split(',')
  const result: { mult: number; sym: string }[] = []
  for (let i = 0; i + 1 < tokens.length; i += 2) {
    const mult = parseFloat(tokens[i])
    const sym = tokens[i + 1]
    if (!isNaN(mult) && sym) result.push({ mult, sym })
  }
  return result
}

/** Parse "mult name;mult name" → [{multiplier, name}] (matches data-groups format) */
export function parseGroupsAttr(
  attr: string,
  symbol: string
): { multiplier: number; name: string }[] {
  const raw = (attr || '').trim()
  if (!raw) return [{ multiplier: 1, name: symbol }]
  return raw.split(';').map(part => {
    const trimmed = part.trim()
    const spaceIdx = trimmed.indexOf(' ')
    if (spaceIdx < 0) return null
    const mult = parseFloat(trimmed.substring(0, spaceIdx))
    const name = trimmed.substring(spaceIdx + 1).trim()
    if (isNaN(mult) || !name) return null
    return { multiplier: mult, name }
  }).filter((x): x is { multiplier: number; name: string } => x !== null)
}

// ── Time formatting ───────────────────────────────────────────────────────────

export function formatTime(ms: number): string {
  const d = new Date(ms)
  return d.toLocaleTimeString()
}

export function formatSavedAt(millis: number): string {
  const d = new Date(millis)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// ── IBKR display helpers ──────────────────────────────────────────────────────

export function formatIbkrRate(rate: number): string {
  return (rate * 100).toFixed(3) + '%'
}

// ── Weight deviation CSS class ────────────────────────────────────────────────

export function weightDiffClass(devPct: number): string {
  const abs = Math.abs(devPct)
  if (abs < 1) return ''
  if (devPct > 0) return abs >= 5 ? 'weight-over-high' : 'weight-over'
  return abs >= 5 ? 'weight-under-high' : 'weight-under'
}

// ── Action class (buy/sell/neutral) ──────────────────────────────────────────

export function actionClass(dollars: number | null): string {
  if (dollars === null || dollars === 0) return 'action-neutral'
  return dollars > 0 ? 'action-buy' : 'action-sell'
}
