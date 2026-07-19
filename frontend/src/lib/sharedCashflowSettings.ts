import {
  DEFAULT_BETA_REFERENCE_TICKER,
  DEFAULT_CASHFLOW_FREQUENCY,
  type CashflowFormState,
  cashflowStateFromSettings,
} from '@/types/backtest'

const SHARED_CASHFLOW_SETTINGS_KEY = 'ib-viewer-shared-cashflow-settings'
const SHARED_CASHFLOW_SETTINGS_CHANGED = 'ib-viewer-shared-cashflow-settings-changed'

function hasBrowserStorage() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

function normalizeCashflowState(state: Partial<CashflowFormState>): CashflowFormState {
  const startingBalance = String(state.startingBalance ?? '').trim() || '10000'
  const cashflowAmount = String(state.cashflowAmount ?? '').trim() || '0'
  const cashflowFrequency = String(state.cashflowFrequency ?? '').trim() || DEFAULT_CASHFLOW_FREQUENCY
  const betaReferenceTicker = String(state.betaReferenceTicker ?? '').trim().toUpperCase() || DEFAULT_BETA_REFERENCE_TICKER
  return { startingBalance, cashflowAmount, cashflowFrequency, betaReferenceTicker }
}

export function readSharedCashflowSettings(): CashflowFormState | null {
  if (!hasBrowserStorage()) return null
  try {
    const raw = window.localStorage.getItem(SHARED_CASHFLOW_SETTINGS_KEY)
    if (!raw) return null
    return normalizeCashflowState(JSON.parse(raw))
  } catch {
    return null
  }
}

export function cashflowStateFromSettingsWithSharedCache(settings: unknown): CashflowFormState {
  const pageSettings = settings && typeof settings === 'object' ? settings : {}
  return normalizeCashflowState({
    ...(readSharedCashflowSettings() ?? {}),
    ...cashflowStateFromSettings(pageSettings),
  })
}

export function writeSharedCashflowSettings(state: Partial<CashflowFormState>) {
  if (!hasBrowserStorage()) return
  const normalized = normalizeCashflowState(state)
  try {
    const serialized = JSON.stringify(normalized)
    if (window.localStorage.getItem(SHARED_CASHFLOW_SETTINGS_KEY) === serialized) return
    window.localStorage.setItem(SHARED_CASHFLOW_SETTINGS_KEY, serialized)
    window.dispatchEvent(new CustomEvent(SHARED_CASHFLOW_SETTINGS_CHANGED, { detail: normalized }))
  } catch {
    // Ignore unavailable storage; page-local state still works.
  }
}

export function subscribeSharedCashflowSettings(onChange: (state: CashflowFormState) => void) {
  if (typeof window === 'undefined') return () => {}

  const onCustomEvent = (event: Event) => {
    const detail = event instanceof CustomEvent ? event.detail : null
    onChange(normalizeCashflowState(detail ?? {}))
  }
  const onStorage = (event: StorageEvent) => {
    if (event.key !== SHARED_CASHFLOW_SETTINGS_KEY || !event.newValue) return
    try {
      onChange(normalizeCashflowState(JSON.parse(event.newValue)))
    } catch {
      // Ignore malformed values from external edits.
    }
  }

  window.addEventListener(SHARED_CASHFLOW_SETTINGS_CHANGED, onCustomEvent)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(SHARED_CASHFLOW_SETTINGS_CHANGED, onCustomEvent)
    window.removeEventListener('storage', onStorage)
  }
}
