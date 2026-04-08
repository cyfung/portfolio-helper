// ── SavedPortfoliosBar.tsx — Draggable chips for saved portfolios ─────────────

import { useEffect, useState, useImperativeHandle, forwardRef } from 'react'
import type { SavedPortfolio } from '@/types/backtest'

export interface SavedPortfoliosBarRef {
  refresh: () => void
}

interface Props {
  apiPath?: string
}

const SavedPortfoliosBar = forwardRef<SavedPortfoliosBarRef, Props>(
  function SavedPortfoliosBar({ apiPath = '/api/backtest/savedPortfolios' }, ref) {
    const [list, setList] = useState<SavedPortfolio[]>([])

    async function refresh() {
      try {
        const res = await fetch(apiPath)
        if (!res.ok) return
        setList(await res.json())
      } catch (_) {}
    }

    useEffect(() => { refresh() }, [])

    useImperativeHandle(ref, () => ({ refresh }))

    async function handleDelete(name: string) {
      await fetch(`${apiPath}?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
      refresh()
    }

    if (list.length === 0) return null

    return (
      <div className="saved-portfolios-bar">
        {list.map(p => (
          <div
            key={p.name}
            className="saved-portfolio-chip"
            draggable
            onDragStart={e => {
              e.dataTransfer.setData('application/x-portfolio-chip', JSON.stringify({ name: p.name, config: p.config }))
              e.dataTransfer.effectAllowed = 'copy'
            }}
          >
            <span>{p.name}</span>
            <button
              className="saved-portfolio-chip-del"
              type="button"
              title="Delete"
              onClick={e => { e.stopPropagation(); handleDelete(p.name) }}
            >
              ✕
            </button>
          </div>
        ))}
      </div>
    )
  }
)

export default SavedPortfoliosBar
