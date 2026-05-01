// ── SavedStrategiesBar.tsx — Draggable chips for saved rebalance strategies ───

import { useEffect, useState, useImperativeHandle, forwardRef } from 'react'

export interface SavedStrategiesBarRef {
  refresh: () => void
}

interface SavedStrategy {
  name: string
  config: any
}

const SavedStrategiesBar = forwardRef<SavedStrategiesBarRef, object>(
  function SavedStrategiesBar(_, ref) {
    const apiPath = '/api/rebalance-strategy/savedStrategies'
    const [list, setList] = useState<SavedStrategy[]>([])

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
        {list.map(s => (
          <div
            key={s.name}
            className="saved-portfolio-chip saved-strategy-chip"
            draggable
            onDragStart={e => {
              e.dataTransfer.setData('application/x-strategy-chip', JSON.stringify({ name: s.name, config: s.config }))
              e.dataTransfer.effectAllowed = 'copy'
            }}
          >
            <span>{s.name}</span>
            <button
              className="saved-portfolio-chip-del"
              type="button"
              title="Delete"
              onClick={e => { e.stopPropagation(); handleDelete(s.name) }}
            >
              ✕
            </button>
          </div>
        ))}
      </div>
    )
  }
)

export default SavedStrategiesBar
