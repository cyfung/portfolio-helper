export interface McRunProgressDetail {
  label: string
  value: string | number
}

export interface McRunProgress {
  phase: string
  phaseLabel: string
  action: string
  progressLabel: string
  completed: number
  total: number
  currentStep: number
  totalSteps: number
  details: McRunProgressDetail[]
  done?: boolean
}

export interface MonteCarloProgressEventDetail {
  progress?: McRunProgress | null
  startPolling?: boolean
  keepIdle?: boolean
  clearAfterMs?: number
}

export const MONTE_CARLO_PROGRESS_EVENT = 'monte-carlo-progress'

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

function finiteNumber(value: unknown, fallback = 0) {
  const num = Number(value ?? fallback)
  return Number.isFinite(num) ? num : fallback
}

function normalizeDetail(value: unknown): McRunProgressDetail | null {
  const raw = asRecord(value)
  const label = String(raw.label ?? '').trim()
  if (!label) return null

  const detailValue = raw.value
  if (detailValue === undefined || detailValue === null) return null
  const numericValue = typeof detailValue === 'number' && Number.isFinite(detailValue)
    ? detailValue
    : null

  return {
    label,
    value: numericValue ?? String(detailValue),
  }
}

export function normalizeMonteCarloRunProgress(raw: unknown): McRunProgress {
  const obj = asRecord(raw)
  const phase = String(obj.phase ?? 'simulate')
  const completed = finiteNumber(obj.completed)
  const total = finiteNumber(obj.total)
  const details = Array.isArray(obj.details)
    ? obj.details.map(normalizeDetail).filter((detail): detail is McRunProgressDetail => !!detail)
    : []

  return {
    phase,
    phaseLabel: String(obj.phaseLabel ?? 'Running simulations'),
    action: String(obj.action ?? 'Computing simulation iterations'),
    progressLabel: String(obj.progressLabel ?? 'Progress'),
    completed,
    total,
    currentStep: finiteNumber(obj.currentStep, total > 0 ? 4 : 0),
    totalSteps: finiteNumber(obj.totalSteps, 7),
    details,
    done: !!obj.done || phase.toLowerCase() === 'complete',
  }
}

export function progressValue(value: string | number) {
  return typeof value === 'number' ? value.toLocaleString() : value
}

export function isActiveMonteCarloRunProgress(progress: McRunProgress) {
  return progress.phase !== 'idle' && !progress.done
}

export function publishMonteCarloRunProgress(
  progress: McRunProgress,
  options: Omit<MonteCarloProgressEventDetail, 'progress'> = {},
) {
  window.dispatchEvent(new CustomEvent<MonteCarloProgressEventDetail>(MONTE_CARLO_PROGRESS_EVENT, {
    detail: { ...options, progress },
  }))
}

export function clearMonteCarloRunProgress(clearAfterMs?: number) {
  window.dispatchEvent(new CustomEvent<MonteCarloProgressEventDetail>(MONTE_CARLO_PROGRESS_EVENT, {
    detail: { progress: null, clearAfterMs },
  }))
}
