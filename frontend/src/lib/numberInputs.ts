export const DEFAULT_SPREAD_PERCENT = 1.5

export function parseStrictNumberInput(value: string | number | null | undefined): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  const text = String(value ?? '').trim()
  if (!text) return null
  const parsed = Number(text)
  return Number.isFinite(parsed) ? parsed : null
}

export function isValidNumberInput(
  value: string | number | null | undefined,
  { min, max }: { min?: number; max?: number } = {},
): boolean {
  const parsed = parseStrictNumberInput(value)
  if (parsed == null) return false
  if (min != null && parsed < min) return false
  if (max != null && parsed > max) return false
  return true
}

export function normalizeNumberInput(
  value: string | number | null | undefined,
  fallback: number,
  options: { min?: number; max?: number } = {},
): string {
  return isValidNumberInput(value, options) ? String(value).trim() : String(fallback)
}

export function percentInputToFraction(
  value: string | number | null | undefined,
  fallbackPercent: number,
  options: { min?: number; max?: number } = {},
): number {
  const parsed = parseStrictNumberInput(value)
  const valid = parsed != null &&
    (options.min == null || parsed >= options.min) &&
    (options.max == null || parsed <= options.max)
  return (valid ? parsed : fallbackPercent) / 100
}
