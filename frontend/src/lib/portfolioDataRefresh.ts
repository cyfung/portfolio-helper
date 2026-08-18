import { usePortfolioStore } from '@/stores/portfolioStore'
import type { PortfolioData } from '@/types/portfolio'

const refreshes = new Map<string, Promise<PortfolioData>>()

export function refreshPortfolioData(portfolioId: string): Promise<PortfolioData> {
  const existing = refreshes.get(portfolioId)
  if (existing) return existing

  const refresh = fetch(`/api/portfolio/data?portfolio=${encodeURIComponent(portfolioId)}`)
    .then(async response => {
      if (!response.ok) throw new Error(`Portfolio refresh failed: HTTP ${response.status}`)
      const data = await response.json() as PortfolioData
      usePortfolioStore.getState().loadPortfolioData(data)
      return data
    })
    .finally(() => {
      if (refreshes.get(portfolioId) === refresh) refreshes.delete(portfolioId)
    })

  refreshes.set(portfolioId, refresh)
  return refresh
}
