import type { RebalStrategyState } from '@/types/rebalanceStrategy'

function labelKey(label: string) {
  return label.trim().toLocaleLowerCase()
}

export function uniqueSavedJsonConfigName(name: string, takenNames: Set<string>) {
  if (!takenNames.has(labelKey(name))) return name

  const match = /^(.*?) \((\d+)\)$/.exec(name)
  const baseName = match?.[1] ?? name
  let counter = match?.[2] ? parseInt(match[2], 10) + 1 : 2
  while (true) {
    const candidate = `${baseName} (${counter})`
    counter += 1
    if (!takenNames.has(labelKey(candidate))) return candidate
  }
}

export function makeUniqueStrategyLabels(strategies: RebalStrategyState[], portfolioLabel: string) {
  const taken = new Set<string>()
  if (portfolioLabel.trim()) taken.add(labelKey(portfolioLabel))

  return strategies.map((strategy, i) => {
    const base = strategy.label.trim() || `Strategy ${i + 1}`
    const label = uniqueSavedJsonConfigName(base, taken)
    taken.add(labelKey(label))
    return label === strategy.label ? strategy : { ...strategy, label }
  })
}
