export interface HybridAllocStrategyConfig {
  id: string
  label: string
  first: string
  second: string
  firstRatio: number
  secondRatio: number
}

export interface AllocStrategyOption {
  value: string
  label: string
}

export const BASE_ALLOC_OPTIONS: AllocStrategyOption[] = [
  { value: 'PROPORTIONAL', label: 'Target Weight' },
  { value: 'CURRENT_WEIGHT', label: 'Current Weight' },
  { value: 'FULL_REBALANCE', label: 'Full Rebal' },
  { value: 'UNDERVALUED_PRIORITY', label: 'Underval First' },
  { value: 'WATERFALL', label: 'Waterfall' },
  { value: 'DAILY', label: 'Daily' },
]

export const DEFAULT_HYBRID_ALLOC_STRATEGIES: HybridAllocStrategyConfig[] = [
  {
    id: 'HYBRID_WATERFALL_FULL_REBALANCE',
    label: 'Hybrid Waterfall/Rebal',
    first: 'WATERFALL',
    second: 'FULL_REBALANCE',
    firstRatio: 1,
    secondRatio: 1,
  },
]

export function normalizeHybridStrategies(raw: unknown): HybridAllocStrategyConfig[] {
  const source = typeof raw === 'string'
    ? (raw.trim() ? JSON.parse(raw) : DEFAULT_HYBRID_ALLOC_STRATEGIES)
    : raw
  const arr = Array.isArray(source) ? source : DEFAULT_HYBRID_ALLOC_STRATEGIES
  const baseIds = new Set(BASE_ALLOC_OPTIONS.map(o => o.value))
  const rows = arr.flatMap((item: any) => {
    const id = String(item?.id ?? '').trim().toUpperCase().replace(/[^A-Z0-9_]+/g, '_')
    const label = String(item?.label ?? '').trim() || id
    const first = String(item?.first ?? '').trim().toUpperCase()
    const second = String(item?.second ?? '').trim().toUpperCase()
    if (!id || !baseIds.has(first) || !baseIds.has(second)) return []
    const firstRatio = Number(item?.firstRatio)
    const secondRatio = Number(item?.secondRatio)
    return [{
      id,
      label,
      first,
      second,
      firstRatio: Number.isFinite(firstRatio) && firstRatio >= 0 ? firstRatio : 1,
      secondRatio: Number.isFinite(secondRatio) && secondRatio >= 0 ? secondRatio : 1,
    }]
  })
  return rows.length ? rows : DEFAULT_HYBRID_ALLOC_STRATEGIES
}

export function parseHybridStrategies(raw: unknown): HybridAllocStrategyConfig[] {
  try {
    return normalizeHybridStrategies(raw)
  } catch {
    return DEFAULT_HYBRID_ALLOC_STRATEGIES
  }
}

export function allocOptionsFromHybridStrategies(
  strategies: HybridAllocStrategyConfig[],
  includeDaily = true,
): AllocStrategyOption[] {
  const base = includeDaily ? BASE_ALLOC_OPTIONS : BASE_ALLOC_OPTIONS.filter(o => o.value !== 'DAILY')
  return [
    ...base,
    ...strategies.map(s => ({ value: s.id, label: s.label })),
  ]
}
