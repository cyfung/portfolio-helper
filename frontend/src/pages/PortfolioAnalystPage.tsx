import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioTabs from '@/components/portfolio/PortfolioTabs'
import PerformanceChart from '@/components/portfolio/PerformanceChart'
import type { PortfolioData } from '@/types/portfolio'

export default function PortfolioAnalystPage() {
  const { slug } = useParams<{ slug?: string }>()
  const navigate = useNavigate()
  const loadPortfolioData = usePortfolioStore(s => s.loadPortfolioData)
  const portfolioId = usePortfolioStore(s => s.portfolioId)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!slug) {
      const stored = usePortfolioStore.getState().portfolioId
      if (stored) { navigate(`/analyst/${stored}`, { replace: true }); return }
    }
    setLoading(true)
    setError(null)
    const url = slug ? `/api/portfolio/data?portfolio=${slug}` : '/api/portfolio/data'
    fetch(url)
      .then(r => {
        if (r.status === 401) { window.location.href = '/admin'; throw new Error('Unauthorized') }
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json() as Promise<PortfolioData>
      })
      .then(data => {
        loadPortfolioData(data)
        if (!slug) navigate(`/analyst/${data.portfolioId}`, { replace: true })
        setLoading(false)
      })
      .catch((e: Error) => {
        if (e.message !== 'Unauthorized') setError(e.message)
        setLoading(false)
      })
  }, [slug, navigate, loadPortfolioData])

  if (loading) return (
    <div className="container">
      <div style={{ padding: '2rem', color: 'var(--color-text-tertiary)' }}>Loading…</div>
    </div>
  )
  if (error) return (
    <div className="container">
      <div style={{ padding: '2rem', color: 'var(--color-negative)' }}>Error: {error}</div>
    </div>
  )

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/analyst/" />
        </div>
        <HeaderRight>
          <PrivacyToggleButton />
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>
      <PortfolioTabs
        basePath="/analyst/"
        showControls={false}
        onSaveToBacktest={() => {}}
        onTwsSync={() => {}}
        twsSyncing={false}
        lastUpdateTime=""
        sseDotClass=""
        sseDotTitle=""
      />
      <PerformanceChart portfolioSlug={portfolioId} />
    </div>
  )
}
