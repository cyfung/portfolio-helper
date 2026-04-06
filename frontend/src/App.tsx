import { Routes, Route, Navigate } from 'react-router-dom'
import { useSSE } from '@/hooks/useSSE'
import PortfolioPage from '@/pages/PortfolioPage'
import LoanPage from '@/pages/LoanPage'
import BacktestPage from '@/pages/BacktestPage'
import MonteCarloPage from '@/pages/MonteCarloPage'
import ConfigPage from '@/pages/ConfigPage'

export default function App() {
  // Single global SSE connection for the whole app lifetime
  useSSE()

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
