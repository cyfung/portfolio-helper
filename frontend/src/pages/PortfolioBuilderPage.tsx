import { useCallback, useEffect, useRef, useState } from 'react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { BlockState } from '@/types/backtest'
import type { SavedPortfolio } from '@/types/backtest'
import { resolveBlockState, type ResolvedStockWeight } from '@/lib/portfolioRefs'

function blankBlock(): BlockState {
  return { label: '', tickers: [], rebalance: 'YEARLY', margins: [], includeNoMargin: true }
}

export default function PortfolioBuilderPage() {
  const [blocks, setBlocks] = useState<BlockState[]>([blankBlock(), blankBlock(), blankBlock()])
  const [savedPortfolios, setSavedPortfolios] = useState<SavedPortfolio[]>([])
  const [results, setResults] = useState<ResolvedStockWeight[][] | null>(null)
  const [error, setError] = useState('')
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)

  async function loadSaved() {
    try {
      const res = await fetch('/api/backtest/savedPortfolios')
      if (!res.ok) return
      setSavedPortfolios(await res.json())
    } catch (_) {}
  }

  useEffect(() => { loadSaved() }, [])

  const updateBlock = useCallback((i: number) =>
    (s: BlockState) => setBlocks(prev => { const n = [...prev]; n[i] = s; return n }),
    [],
  )

  const refreshSaved = useCallback(() => {
    savedBarRef.current?.refresh()
    loadSaved()
  }, [])

  function handleAnalyse() {
    try {
      setError('')
      setResults(blocks.map(block => resolveBlockState(block, savedPortfolios)))
    } catch (e: any) {
      setResults(null)
      setError(e.message || 'Unable to resolve portfolio references.')
    }
  }

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/portfolio-builder" /></div>
        <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
      </div>

      <div className="backtest-form-card">
        <SavedPortfoliosBar ref={savedBarRef} />

        <div className="portfolio-blocks">
          {blocks.map((b, i) => (
            <PortfolioBlock
              key={i}
              idx={i}
              value={b}
              onChange={updateBlock(i)}
              onSavedRefresh={refreshSaved}
            />
          ))}
        </div>

        <button className="run-backtest-btn" type="button" onClick={handleAnalyse}>
          Analyse
        </button>
      </div>

      {error && <div className="backtest-error">{error}</div>}

      {results && (
        <div className="portfolio-builder-results">
          {results.map((rows, i) => (
            <div key={i} className="portfolio-builder-result-block">
              <div className="portfolio-builder-result-title">{blocks[i].label.trim() || `Portfolio ${i + 1}`}</div>
              {rows.length === 0 ? (
                <div className="portfolio-builder-empty">No stocks</div>
              ) : (
                <table className="backtest-stats-table portfolio-builder-table">
                  <thead>
                    <tr><th>Stock</th><th>Weight</th></tr>
                  </thead>
                  <tbody>
                    {rows.map(row => (
                      <tr key={row.ticker}>
                        <td>{row.ticker}</td>
                        <td>{row.weight.toFixed(2)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
