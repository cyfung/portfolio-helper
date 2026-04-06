// ── PortfolioTabs.tsx — Tab bar with drag-and-drop reorder ────────────────────
import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'

interface Props {
  onSaveToBacktest: () => void
}

export default function PortfolioTabs({ onSaveToBacktest }: Props) {
  const { allPortfolios, portfolioId } = usePortfolioStore()
  const dragSrcRef = useRef<string | null>(null)
  const dragOverRef = useRef<string | null>(null)

  function onDragStart(e: React.DragEvent, slug: string) {
    dragSrcRef.current = slug
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', slug)
  }

  function onDragOver(e: React.DragEvent, slug: string) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    dragOverRef.current = slug
  }

  async function onDrop(e: React.DragEvent, targetSlug: string) {
    e.preventDefault()
    const srcSlug = dragSrcRef.current
    dragSrcRef.current = null
    dragOverRef.current = null
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
    <div className="portfolio-tabs">
      {allPortfolios.map(p => {
        const href = p.slug === 'main' ? '/portfolio/' : `/portfolio/${p.slug}`
        return (
          <Link
            key={p.slug}
            to={href}
            className={`tab-link${p.slug === portfolioId ? ' active' : ''}`}
            data-slug={p.slug}
            data-seq-order={p.seqOrder}
            draggable
            onDragStart={e => onDragStart(e, p.slug)}
            onDragOver={e => onDragOver(e, p.slug)}
            onDrop={e => onDrop(e, p.slug)}
          >
            <span className="tab-drag-handle">⠿</span>
            {p.name}
          </Link>
        )
      })}
      <button
        className="save-portfolio-btn"
        id="save-to-backtest-btn"
        type="button"
        title="Save current portfolio as a backtest preset"
        onClick={onSaveToBacktest}
      >
        Save to Backtest
      </button>
    </div>
  )
}
