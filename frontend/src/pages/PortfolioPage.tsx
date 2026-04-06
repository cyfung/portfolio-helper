import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import PortfolioViewer from '@/components/portfolio/PortfolioViewer'
import type { PortfolioData } from '@/types/portfolio'

export default function PortfolioPage() {
  const { slug } = useParams<{ slug?: string }>()
  const navigate = useNavigate()
  const loadPortfolioData = usePortfolioStore(s => s.loadPortfolioData)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
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
        // Normalize URL: /portfolio/ → /portfolio/main
        if (!slug) navigate(`/portfolio/${data.portfolioId}`, { replace: true })
        setLoading(false)
      })
      .catch((e: Error) => {
        if (e.message !== 'Unauthorized') setError(e.message)
        setLoading(false)
      })
  }, [slug, navigate, loadPortfolioData])

  if (loading) return (
    <div className="container">
      <div style={{ padding: '2rem', color: 'var(--color-text-tertiary)' }}>Loading portfolio…</div>
    </div>
  )
  if (error) return (
    <div className="container">
      <div style={{ padding: '2rem', color: 'var(--color-negative)' }}>Error: {error}</div>
    </div>
  )

  return <PortfolioViewer />
}
