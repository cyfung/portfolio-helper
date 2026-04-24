import { lazy, Suspense, useEffect } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { useSSE } from '@/hooks/useSSE'
import { useUpdateChecker } from '@/hooks/useUpdateChecker'
import { usePortfolioStore } from '@/stores/portfolioStore'
import type { PortfolioData } from '@/types/portfolio'

const PortfolioPage        = lazy(() => import('@/pages/PortfolioPage'))
const PortfolioAnalystPage = lazy(() => import('@/pages/PortfolioAnalystPage'))
const LoanPage             = lazy(() => import('@/pages/LoanPage'))
const BacktestPage         = lazy(() => import('@/pages/BacktestPage'))
const MonteCarloPage       = lazy(() => import('@/pages/MonteCarloPage'))
const ConfigPage           = lazy(() => import('@/pages/ConfigPage'))

export default function App() {
  // Single global SSE connection for the whole app lifetime
  useSSE()
  // Poll server update state to keep version badge fresh on all pages
  useUpdateChecker()

  // Bootstrap: load appConfig + portfolioId so version badge and SSE work on all pages
  const loadPortfolioData = usePortfolioStore(s => s.loadPortfolioData)
  useEffect(() => {
    const match = window.location.pathname.match(/\/(portfolio|analyst)\/([^/]+)/)
    const slug = match?.[2]
    const url = slug ? `/api/portfolio/data?portfolio=${slug}` : '/api/portfolio/data'
    fetch(url)
      .then(r => {
        if (r.status === 401) { window.location.href = '/admin'; return null }
        if (!r.ok) return null
        return r.json() as Promise<PortfolioData>
      })
      .then(data => { if (data) loadPortfolioData(data) })
      .catch(() => {})
  }, [loadPortfolioData])

  return (
    <Suspense fallback={null}>
    <Routes>
      <Route path="/" element={<Navigate to="/portfolio/" replace />} />
      <Route path="/portfolio/" element={<PortfolioPage />} />
      <Route path="/portfolio/:slug" element={<PortfolioPage />} />
      <Route path="/analyst/" element={<PortfolioAnalystPage />} />
      <Route path="/analyst/:slug" element={<PortfolioAnalystPage />} />
      <Route path="/loan" element={<LoanPage />} />
      <Route path="/backtest" element={<BacktestPage />} />
      <Route path="/montecarlo" element={<MonteCarloPage />} />
      <Route path="/config" element={<ConfigPage />} />
    </Routes>
    </Suspense>
  )
}
