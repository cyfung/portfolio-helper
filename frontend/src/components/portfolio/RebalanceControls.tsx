// ── RebalanceControls.tsx — Alloc strategy selects ───────────────────────────
import { usePortfolioStore } from '@/stores/portfolioStore'
import type { AllocMode } from '@/types/portfolio'

const ALLOC_OPTIONS: { value: AllocMode; label: string }[] = [
  { value: 'PROPORTIONAL',       label: 'Target Wt' },
  { value: 'CURRENT_WEIGHT',     label: 'Current Wt' },
  { value: 'UNDERVALUED_PRIORITY', label: 'Underval First' },
  { value: 'WATERFALL',          label: 'Waterfall' },
]

export default function RebalanceControls({ showGroupBy }: { showGroupBy?: boolean }) {
  const {
    allocAddMode, allocReduceMode, portfolioId,
    setAllocAddMode, setAllocReduceMode,
    stockGroupBy, setStockGroupBy,
  } = usePortfolioStore()

  async function saveMode(key: string, value: string) {
    await fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=${key}`, { method: 'POST', body: value })
  }

  return (
    <div className="alloc-controls">
      {showGroupBy && (
        <div className="group-by-control">
          <span className="alloc-controls-label">Group By</span>
          <select
            id="stock-group-by"
            value={stockGroupBy}
            onChange={e => setStockGroupBy(e.target.value as 'none' | 'ccy' | 'mainGroup')}
          >
            <option value="none">None</option>
            <option value="ccy">CCY</option>
            <option value="mainGroup">Main Group</option>
          </select>
          {stockGroupBy !== 'none' && (
            <button
              className="group-by-clear"
              onClick={() => setStockGroupBy('none')}
              title="Clear group by"
            >✕</button>
          )}
        </div>
      )}
      <span className="alloc-controls-label">Alloc Strategy</span>

      <div className="alloc-mode-group">
        <label className="alloc-mode-label alloc-mode-label-deposit" htmlFor="alloc-add-mode">+</label>
        <select
          id="alloc-add-mode"
          value={allocAddMode}
          onChange={e => {
            const mode = e.target.value as AllocMode
            setAllocAddMode(mode)
            saveMode('allocAddMode', mode)
          }}
        >
          {ALLOC_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      <div className="alloc-mode-group">
        <label className="alloc-mode-label alloc-mode-label-withdraw" htmlFor="alloc-reduce-mode">−</label>
        <select
          id="alloc-reduce-mode"
          value={allocReduceMode}
          onChange={e => {
            const mode = e.target.value as AllocMode
            setAllocReduceMode(mode)
            saveMode('allocReduceMode', mode)
          }}
        >
          {ALLOC_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>
    </div>
  )
}
