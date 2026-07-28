import { AlertTriangle, ChevronDown } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { AnalysisWarning, AnalysisWarningInput, WarningCategory } from '@/types/backtest'

interface WarningSummaryProps {
  warnings?: AnalysisWarningInput[]
  resultKey: unknown
}

interface WarningCategoryPresentation {
  heading: string
  singular: string
  plural: string
}

const CATEGORY_ORDER: WarningCategory[] = [
  'NULL_DATA',
  'FILLED_DATA',
  'SPLIT_REPAIR',
  'TICKER_MAPPING',
  'OTHER',
]

const CATEGORY_PRESENTATION: Record<WarningCategory, WarningCategoryPresentation> = {
  NULL_DATA: {
    heading: 'Null data issues',
    singular: 'null data issue',
    plural: 'null data issues',
  },
  FILLED_DATA: {
    heading: 'Filled data points',
    singular: 'filled data point',
    plural: 'filled data points',
  },
  SPLIT_REPAIR: {
    heading: 'Split repairs',
    singular: 'split repair',
    plural: 'split repairs',
  },
  TICKER_MAPPING: {
    heading: 'Ticker mapping warnings',
    singular: 'ticker mapping warning',
    plural: 'ticker mapping warnings',
  },
  OTHER: {
    heading: 'Other warnings',
    singular: 'other warning',
    plural: 'other warnings',
  },
}

function normalizeWarning(warning: AnalysisWarningInput): AnalysisWarning {
  if (typeof warning === 'string') {
    return { category: 'OTHER', message: warning, occurrences: 1 }
  }
  return {
    category: CATEGORY_PRESENTATION[warning.category] ? warning.category : 'OTHER',
    message: warning.message,
    occurrences: Number.isInteger(warning.occurrences) && warning.occurrences > 0
      ? warning.occurrences
      : 1,
  }
}

export default function WarningSummary({ warnings = [], resultKey }: WarningSummaryProps) {
  const [expandedResultKey, setExpandedResultKey] = useState<unknown>(null)
  const expanded = expandedResultKey === resultKey
  const groupedWarnings = useMemo(() => {
    const groups = new Map<WarningCategory, AnalysisWarning[]>()
    warnings.map(normalizeWarning).forEach(warning => {
      groups.set(warning.category, [...(groups.get(warning.category) ?? []), warning])
    })
    return CATEGORY_ORDER
      .map(category => ({ category, warnings: groups.get(category) ?? [] }))
      .filter(group => group.warnings.length > 0)
  }, [warnings])

  if (groupedWarnings.length === 0) return null

  return (
    <section className="warning-summary" aria-label="Analysis warnings">
      <button
        type="button"
        className="warning-summary-toggle"
        aria-label={expanded ? 'Hide warning details' : 'Show warning details'}
        aria-expanded={expanded}
        onClick={() => setExpandedResultKey(expanded ? null : resultKey)}
      >
        <AlertTriangle aria-hidden="true" size={18} />
        <span className="warning-summary-counts">
          {groupedWarnings.map(({ category, warnings: categoryWarnings }) => {
            const count = categoryWarnings.reduce((sum, warning) => sum + warning.occurrences, 0)
            const presentation = CATEGORY_PRESENTATION[category]
            return (
              <span className="warning-summary-count" key={category}>
                {count} {count === 1 ? presentation.singular : presentation.plural}
              </span>
            )
          })}
        </span>
        <ChevronDown className="warning-summary-chevron" aria-hidden="true" size={18} />
      </button>

      {expanded && (
        <div className="warning-summary-details">
          {groupedWarnings.map(({ category, warnings: categoryWarnings }) => (
            <section className="warning-summary-group" key={category}>
              <h3>{CATEGORY_PRESENTATION[category].heading}</h3>
              <ul>
                {categoryWarnings.map((warning, index) => (
                  <li key={`${warning.message}-${index}`}>{warning.message}</li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </section>
  )
}
