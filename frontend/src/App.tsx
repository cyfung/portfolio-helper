import { useEffect } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { useSSE } from '@/hooks/useSSE'
import { useUpdateChecker } from '@/hooks/useUpdateChecker'
import { usePortfolioStore } from '@/stores/portfolioStore'
import PortfolioPage from '@/pages/PortfolioPage'
import LoanPage from '@/pages/LoanPage'
import BacktestPage from '@/pages/BacktestPage'
import MonteCarloPage from '@/pages/MonteCarloPage'
import ConfigPage from '@/pages/ConfigPage'
import type { PortfolioData } from '@/types/portfolio'

export default function App() {
  // Single global SSE connection for the whole app lifetime
  useSSE()
  // Poll server update state to keep version badge fresh on all pages
  useUpdateChecker()

  // Bootstrap: load appConfig + portfolioId so version badge and SSE work on all pages
  const loadPortfolioData = usePortfolioStore(s => s.loadPortfolioData)
  useEffect(() => {
    fetch('/api/portfolio/data')
      .then(r => {
        if (r.status === 401) { window.location.href = '/admin'; return null }
        if (!r.ok) return null
        return r.json() as Promise<PortfolioData>
      })
      .then(data => { if (data) loadPortfolioData(data) })
      .catch(() => {})
  }, [loadPortfolioData])

  return (
    <Routes>
      <Route path="/" element={<Navigate to="/portfolio/" replace />} />
      <Route path="/portfolio/" element={<PortfolioPage />} />
      <Route path="/portfolio/:slug" element={<PortfolioPage />} />
      <Route path="/loan" element={<LoanPage />} />
      <Route path="/backtest" element={<BacktestPage />} />
      <Route path="/montecarlo" element={<MonteCarloPage />} />
      <Route path="/config" element={<ConfigPage />} />
    </Routes>
  )
}
