// ── PortfolioTabs.tsx — Tab links for the portfolio context dropdown ────────────
import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'

interface Props {
  basePath?: string
}

export default function PortfolioTabs({ basePath = '/portfolio/' }: Props) {
  const { allPortfolios, portfolioId } = usePortfolioStore()
  const dragSrcRef = useRef<string | null>(null)

  function onDragStart(e: React.DragEvent, slug: string) {
    dragSrcRef.current = slug
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', slug)
  }

  function onDragOver(e: React.DragEvent) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
  }

  async function onDrop(e: React.DragEvent, targetSlug: string) {
    e.preventDefault()
    const srcSlug = dragSrcRef.current
    dragSrcRef.current = null
    if (!srcSlug || srcSlug === targetSlug) return
    try {
      await fetch('/api/portfolios/move-tab', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slug: srcSlug, targetSlug }),
      })
      window.location.reload()
    } catch (_) {}
  }

  return (
    <>
      {allPortfolios.map(p => (
        <Link
          key={p.slug}
          to={`${basePath}${p.slug}`}
          className={`v4-pop-item${p.slug === portfolioId ? ' active' : ''}`}
          draggable
          onDragStart={e => onDragStart(e, p.slug)}
          onDragOver={onDragOver}
          onDrop={e => onDrop(e, p.slug)}
        >
          {p.name}
        </Link>
      ))}
    </>
  )
}
