import { useSyncExternalStore } from 'react'
import type { SavedPortfolio } from '@/types/backtest'

export const SAVED_PORTFOLIOS_CHANGED_EVENT = 'saved-portfolios-changed'

let savedPortfolios: SavedPortfolio[] = []
let loaded = false
let pending: Promise<SavedPortfolio[]> | null = null
const listeners = new Set<() => void>()

function notify() {
  listeners.forEach(listener => listener())
}

async function loadSavedPortfolios(): Promise<SavedPortfolio[]> {
  const response = await fetch('/api/backtest/savedPortfolios')
  if (!response.ok) throw new Error('Unable to load saved portfolios.')
  const value = await response.json()
  savedPortfolios = Array.isArray(value) ? value : []
  loaded = true
  notify()
  return savedPortfolios
}

export function refreshSavedPortfolios(): Promise<SavedPortfolio[]> {
  if (pending != null) return pending
  pending = loadSavedPortfolios()
    .catch(() => {
      loaded = true
      savedPortfolios = []
      notify()
      return savedPortfolios
    })
    .finally(() => {
      pending = null
    })
  return pending
}

export function getSavedPortfolios(): Promise<SavedPortfolio[]> {
  return loaded ? Promise.resolve(savedPortfolios) : refreshSavedPortfolios()
}

export function savedPortfolioSnapshot() {
  return savedPortfolios
}

export function subscribeToSavedPortfolios(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useSavedPortfolios() {
  const snapshot = useSyncExternalStore(
    subscribeToSavedPortfolios,
    savedPortfolioSnapshot,
    savedPortfolioSnapshot,
  )
  if (!loaded && pending == null) void refreshSavedPortfolios()
  return { savedPortfolios: snapshot, loaded }
}

export function announceSavedPortfoliosChanged() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(SAVED_PORTFOLIOS_CHANGED_EVENT))
  } else {
    void refreshSavedPortfolios()
  }
}

export function invalidateSavedPortfolioCache() {
  savedPortfolios = []
  loaded = false
  pending = null
  notify()
}

if (typeof window !== 'undefined') {
  window.addEventListener(SAVED_PORTFOLIOS_CHANGED_EVENT, () => {
    void refreshSavedPortfolios()
  })
}
