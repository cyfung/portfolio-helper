import { useEffect, useState } from 'react'
import {
  allocOptionsFromHybridStrategies,
  DEFAULT_HYBRID_ALLOC_STRATEGIES,
  parseHybridStrategies,
  type AllocStrategyOption,
} from '@/lib/allocStrategies'

export function useAllocStrategyOptions(includeDaily = true): AllocStrategyOption[] {
  const [options, setOptions] = useState(() =>
    allocOptionsFromHybridStrategies(DEFAULT_HYBRID_ALLOC_STRATEGIES, includeDaily)
  )

  useEffect(() => {
    let cancelled = false
    fetch('/api/admin/config-values')
      .then(r => r.json())
      .then(data => {
        if (cancelled) return
        setOptions(allocOptionsFromHybridStrategies(parseHybridStrategies(data.hybridAllocStrategies), includeDaily))
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [includeDaily])

  return options
}
