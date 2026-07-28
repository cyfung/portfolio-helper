import type { AnalysisWarning, AnalysisWarningInput } from '@/types/backtest'

export function tickerMappingWarnings(messages: string[]): AnalysisWarning[] {
  return messages.map(message => ({
    category: 'TICKER_MAPPING',
    message,
    occurrences: 1,
  }))
}

export function mergeAnalysisWarnings(
  existing: AnalysisWarningInput[] | undefined,
  additional: AnalysisWarning[],
): AnalysisWarningInput[] {
  const warnings = [...(existing ?? []), ...additional]
  const seen = new Set<string>()
  return warnings.filter(warning => {
    const key = typeof warning === 'string'
      ? `legacy:${warning}`
      : `${warning.category}:${warning.occurrences}:${warning.message}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
