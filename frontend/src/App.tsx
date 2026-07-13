import { lazy, Suspense, useEffect } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { useSSE } from '@/hooks/useSSE'
import { useUpdateChecker } from '@/hooks/useUpdateChecker'
import { usePortfolioStore } from '@/stores/portfolioStore'
import type { PortfolioData } from '@/types/portfolio'

const PortfolioPage        = lazy(() => import('@/pages/PortfolioPage'))
const PortfolioAnalystPage = lazy(() => import('@/pages/PortfolioAnalystPage'))
const TradesPage           = lazy(() => import('@/pages/TradesPage'))
const LoanPage             = lazy(() => import('@/pages/LoanPage'))
const TaxDragPage          = lazy(() => import('@/pages/TaxDragPage'))
const BacktestPage         = lazy(() => import('@/pages/BacktestPage'))
const PortfolioBuilderPage = lazy(() => import('@/pages/PortfolioBuilderPage'))
const MonteCarloPage            = lazy(() => import('@/pages/MonteCarloPage'))
const RebalanceStrategyPage     = lazy(() => import('@/pages/RebalanceStrategyPage'))
const MarketTimingPage          = lazy(() => import('@/pages/MarketTimingPage'))
const ConfigPage                = lazy(() => import('@/pages/ConfigPage'))
const TickerEditPage            = lazy(() => import('@/pages/TickerEditPage'))

export default function App() {
  // Single global SSE connection for the whole app lifetime
  useSSE()
  // Poll server update state to keep version badge fresh on all pages
  useUpdateChecker()

  // Bootstrap: load appConfig + portfolioId so version badge and SSE work on all pages
  const loadPortfolioData = usePortfolioStore(s => s.loadPortfolioData)
  const setSseStatus = usePortfolioStore(s => s.setSseStatus)
  useEffect(() => {
    const match = window.location.pathname.match(/\/(portfolio|analyst|trades)\/([^/]+)/)
    const slug = match?.[2]
    const url = slug ? `/api/portfolio/data?portfolio=${slug}` : '/api/portfolio/data'
    fetch(url)
      .then(r => {
        if (r.status === 401) { window.location.href = '/admin'; return null }
        if (!r.ok) return null
        return r.json() as Promise<PortfolioData>
      })
      .then(data => { if (data) loadPortfolioData(data) })
      .catch(() => setSseStatus('error'))
  }, [loadPortfolioData, setSseStatus])

  return (
    <Suspense fallback={null}>
    <Routes>
      <Route path="/" element={<Navigate to="/portfolio/" replace />} />
      <Route path="/portfolio/" element={<PortfolioPage />} />
      <Route path="/portfolio/:slug" element={<PortfolioPage />} />
      <Route path="/analyst/" element={<PortfolioAnalystPage />} />
      <Route path="/analyst/:slug" element={<PortfolioAnalystPage />} />
      <Route path="/trades/" element={<TradesPage />} />
      <Route path="/trades/:slug" element={<TradesPage />} />
      <Route path="/loan" element={<LoanPage />} />
      <Route path="/tax-drag" element={<TaxDragPage />} />
      <Route path="/backtest" element={<BacktestPage />} />
      <Route path="/portfolio-builder" element={<PortfolioBuilderPage />} />
      <Route path="/montecarlo" element={<MonteCarloPage />} />
      <Route path="/rebalance-strategy" element={<RebalanceStrategyPage />} />
      <Route path="/market-timing" element={<MarketTimingPage />} />
      <Route path="/ticker-edit" element={<TickerEditPage />} />
      <Route path="/config" element={<ConfigPage />} />
    </Routes>
    </Suspense>
  )
}
