import { useCallback, useEffect, useRef, useState } from 'react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { BlockState } from '@/types/backtest'
import type { SavedPortfolio } from '@/types/backtest'
import { resolveBlockState, type ResolvedStockWeight } from '@/lib/portfolioRefs'
import { parseGroupsAttr } from '@/lib/portfolio-utils'

interface TickerConfig {
  letf: string
  groups: string
}

interface GroupRow {
  name: string
  weight: number
  children: ResolvedStockWeight[]
}

function blankBlock(): BlockState {
  return { label: '', tickers: [], rebalance: 'YEARLY', margins: [], includeNoMargin: true }
}

function marginRatio(block: BlockState, marginIndex: number) {
  return parseFloat(block.margins[marginIndex]?.ratio ?? '') || 0
}

function addWeight(map: Map<string, number>, ticker: string, weight: number) {
  const key = ticker.trim().toUpperCase()
  if (!key || weight <= 0) return
  map.set(key, (map.get(key) ?? 0) + weight)
}

function parseLetfDefinition(raw: string) {
  const tokens = raw.trim().replace(/,/g, ' ').split(/\s+/).filter(Boolean)
  const components: ResolvedStockWeight[] = []

  for (let i = 0; i < tokens.length;) {
    const token = tokens[i]
    if (/^[SRE]=/i.test(token)) {
      i += 1
      continue
    }

    const multiplier = parseFloat(token)
    if (Number.isFinite(multiplier) && i + 1 < tokens.length && !/^[SRE]=/i.test(tokens[i + 1])) {
      components.push({ ticker: tokens[i + 1].toUpperCase(), weight: multiplier })
      i += 2
    } else if (!Number.isFinite(multiplier)) {
      components.push({ ticker: token.toUpperCase(), weight: 1 })
      i += 1
    } else {
      i += 1
    }
  }

  return components
}

function expandLetfRows(rows: ResolvedStockWeight[], tickerConfigs: Record<string, TickerConfig>) {
  const weights = new Map<string, number>()
  let expanded = false

  for (const row of rows) {
    const letf = tickerConfigs[row.ticker.toUpperCase()]?.letf?.trim() || (row.ticker.includes(' ') ? row.ticker : '')
    const components = letf ? parseLetfDefinition(letf) : []
    if (components.length === 0) {
      addWeight(weights, row.ticker, row.weight)
      continue
    }

    expanded = true
    for (const component of components) {
      addWeight(weights, component.ticker, row.weight * component.weight)
    }
  }

  return {
    expanded,
    rows: [...weights.entries()]
      .map(([ticker, weight]) => ({ ticker, weight }))
      .sort((a, b) => a.ticker.localeCompare(b.ticker)),
  }
}

function normalizeRows(rows: ResolvedStockWeight[]) {
  const total = rows.reduce((sum, row) => sum + row.weight, 0)
  if (total <= 0) return rows
  return rows.map(row => ({ ...row, weight: row.weight * 100 / total }))
}

function buildGroupRows(rows: ResolvedStockWeight[], tickerConfigs: Record<string, TickerConfig>) {
  const groups = new Map<string, GroupRow>()

  for (const row of rows) {
    const groupConfig = tickerConfigs[row.ticker.toUpperCase()]?.groups?.trim() ?? ''
    if (!groupConfig) continue

    for (const group of parseGroupsAttr(groupConfig, row.ticker)) {
      const weight = row.weight * group.multiplier
      if (weight <= 0) continue

      const existing = groups.get(group.name) ?? { name: group.name, weight: 0, children: [] }
      existing.weight += weight
      const child = existing.children.find(child => child.ticker === row.ticker)
      if (child) child.weight += weight
      else existing.children.push({ ticker: row.ticker, weight })
      groups.set(group.name, existing)
    }
  }

  return [...groups.values()]
    .map(group => ({
      ...group,
      children: group.children.sort((a, b) => b.weight - a.weight || a.ticker.localeCompare(b.ticker)),
    }))
    .sort((a, b) => b.weight - a.weight || a.name.localeCompare(b.name))
}

export default function PortfolioBuilderPage() {
  const [blocks, setBlocks] = useState<BlockState[]>([blankBlock(), blankBlock(), blankBlock()])
  const [savedPortfolios, setSavedPortfolios] = useState<SavedPortfolio[]>([])
  const [results, setResults] = useState<ResolvedStockWeight[][] | null>(null)
  const [tickerConfigs, setTickerConfigs] = useState<Record<string, TickerConfig>>({})
  const [showLetfExpandedByBlock, setShowLetfExpandedByBlock] = useState<Record<number, boolean>>({})
  const [selectedMarginByBlock, setSelectedMarginByBlock] = useState<Record<number, number>>({})
  const [hoveredGroupByBlock, setHoveredGroupByBlock] = useState<Record<number, string>>({})
  const [pinnedGroupByBlock, setPinnedGroupByBlock] = useState<Record<number, string>>({})
  const [groupOverlayPos, setGroupOverlayPos] = useState({ x: 0, y: 0 })
  const [error, setError] = useState('')
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)

  function updateGroupOverlayPos(e: React.MouseEvent) {
    const overlay = document.querySelector('.portfolio-builder-group-composition')
    const rect = overlay?.getBoundingClientRect()
    const estimatedWidth = rect?.width ?? 260
    const estimatedHeight = rect?.height ?? 120
    const gap = 14
    const margin = 8
    const x = Math.min(
      Math.max(margin, e.clientX + gap),
      Math.max(margin, window.innerWidth - estimatedWidth - margin),
    )
    const y = Math.min(
      Math.max(margin, e.clientY + gap),
      Math.max(margin, window.innerHeight - estimatedHeight - margin),
    )
    setGroupOverlayPos({ x, y })
  }

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

  async function loadTickerConfigs(rowsByBlock: ResolvedStockWeight[][]) {
    const configs: Record<string, TickerConfig> = {}
    const queue = new Set(rowsByBlock.flatMap(rows => rows.map(row => row.ticker.toUpperCase())))

    while (queue.size > 0) {
      const batch = [...queue].filter(ticker => !configs[ticker])
      queue.clear()
      if (batch.length === 0) break

      const entries = await Promise.all(batch.map(async ticker => {
        const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(ticker)}`)
        if (!res.ok) throw new Error(`Failed to load ticker config for ${ticker}`)
        const data = await res.json()
        return [ticker, { letf: data.letf ?? '', groups: data.groups ?? '' }] as const
      }))

      for (const [ticker, config] of entries) {
        configs[ticker] = config
        const letf = config.letf.trim() || (ticker.includes(' ') ? ticker : '')
        for (const component of parseLetfDefinition(letf)) {
          if (!configs[component.ticker]) queue.add(component.ticker)
        }
      }
    }

    return configs
  }

  async function handleAnalyse() {
    try {
      setError('')
      const nextResults = blocks.map(block => resolveBlockState(block, savedPortfolios))
      setTickerConfigs(await loadTickerConfigs(nextResults))
      setShowLetfExpandedByBlock({})
      setHoveredGroupByBlock({})
      setPinnedGroupByBlock({})
      setResults(nextResults)
    } catch (e: any) {
      setResults(null)
      setError(e.message || 'Unable to resolve portfolio references.')
    }
  }

  return (
    <div className="container" onClick={() => setPinnedGroupByBlock({})}>
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
          {results.map((rows, i) => {
            const block = blocks[i]
            const letfResult = expandLetfRows(rows, tickerConfigs)
            const showLetfExpanded = letfResult.expanded && (showLetfExpandedByBlock[i] ?? true)
            const rawDisplayRows = showLetfExpanded ? letfResult.rows : rows
            const normalizedRows = normalizeRows(rawDisplayRows)
            const groupRows = buildGroupRows(normalizedRows, tickerConfigs)
            const rawGroupRows = buildGroupRows(rawDisplayRows, tickerConfigs)
            const activeGroupName = pinnedGroupByBlock[i] ?? hoveredGroupByBlock[i]
            const activeGroup = groupRows.find(group => group.name === activeGroupName)
            const activeRawGroup = rawGroupRows.find(group => group.name === activeGroupName)
            const hasMargin = block.margins.length > 0
            const selectedMarginIndex = hasMargin
              ? Math.min(selectedMarginByBlock[i] ?? 0, block.margins.length - 1)
              : 0
            const multiplier = 1 + marginRatio(block, selectedMarginIndex) / 100
            const totalWeight = normalizedRows.reduce((sum, row) => sum + row.weight, 0)
            const totalMarginScaled = rawDisplayRows.reduce((sum, row) => sum + row.weight * multiplier, 0)

            return (
              <div key={i} className="portfolio-builder-result-block">
                <div className="portfolio-builder-result-title">
                  <span>{block.label.trim() || `Portfolio ${i + 1}`}</span>
                  <div className="portfolio-builder-result-controls">
                    {letfResult.expanded && (
                      <button
                        className="portfolio-builder-toggle-btn"
                        type="button"
                        onClick={() => setShowLetfExpandedByBlock(prev => ({ ...prev, [i]: !showLetfExpanded }))}
                      >
                        {showLetfExpanded ? 'LETF: On' : 'LETF: Off'}
                      </button>
                    )}
                    {block.margins.length > 1 && (
                      <select
                        className="portfolio-builder-margin-select"
                        value={selectedMarginIndex}
                        onChange={e => setSelectedMarginByBlock(prev => ({ ...prev, [i]: Number(e.target.value) }))}
                        aria-label={`Margin for ${block.label.trim() || `Portfolio ${i + 1}`}`}
                      >
                        {block.margins.map((margin, marginIndex) => (
                          <option key={margin.id} value={marginIndex}>
                            Margin {marginIndex + 1}: {(parseFloat(margin.ratio) || 0).toFixed(2)}%
                          </option>
                        ))}
                      </select>
                    )}
                  </div>
                </div>
                {normalizedRows.length === 0 ? (
                  <div className="portfolio-builder-empty">No stocks</div>
                ) : (
                  <table className="backtest-stats-table portfolio-builder-table">
                    <thead>
                      <tr>
                        <th>Stock</th>
                        <th>Weight</th>
                        {hasMargin && <th>Margin Scaled</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {normalizedRows.map((row, rowIndex) => (
                        <tr key={row.ticker}>
                          <td>{row.ticker}</td>
                          <td>{row.weight.toFixed(2)}%</td>
                          {hasMargin && <td>{(rawDisplayRows[rowIndex].weight * multiplier).toFixed(2)}%</td>}
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr>
                        <th>Total</th>
                        <th>{totalWeight.toFixed(2)}%</th>
                        {hasMargin && <th>{totalMarginScaled.toFixed(2)}%</th>}
                      </tr>
                    </tfoot>
                  </table>
                )}
                {groupRows.length > 0 && (
                  <div
                    className="portfolio-builder-group-section"
                    onMouseLeave={() => setHoveredGroupByBlock(prev => {
                      const next = { ...prev }
                      delete next[i]
                      return next
                    })}
                  >
                    <table className="backtest-stats-table portfolio-builder-table portfolio-builder-group-table">
                      <thead>
                        <tr><th>Group</th><th>Weight</th></tr>
                      </thead>
                      <tbody>
                        {groupRows.map(group => (
                          <tr
                            key={group.name}
                            className={activeGroup?.name === group.name ? 'selected' : undefined}
                            onMouseEnter={e => {
                              setHoveredGroupByBlock(prev => ({ ...prev, [i]: group.name }))
                              updateGroupOverlayPos(e)
                            }}
                            onMouseMove={e => {
                              if (!pinnedGroupByBlock[i]) updateGroupOverlayPos(e)
                            }}
                            onClick={e => {
                              e.stopPropagation()
                              updateGroupOverlayPos(e)
                              setPinnedGroupByBlock(prev => ({ ...prev, [i]: group.name }))
                            }}
                          >
                            <td>{group.name}</td>
                            <td>{group.weight.toFixed(2)}%</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>

                    {activeGroup && (
                      <table
                        className="backtest-stats-table portfolio-builder-table portfolio-builder-group-composition"
                        style={{ left: groupOverlayPos.x, top: groupOverlayPos.y }}
                        onClick={e => e.stopPropagation()}
                      >
                        <thead>
                          <tr>
                            <th>{activeGroup.name}</th>
                            <th>Weight</th>
                            {hasMargin && <th>Margin Scaled</th>}
                          </tr>
                        </thead>
                        <tbody>
                          {activeGroup.children.map(child => {
                            const rawChild = activeRawGroup?.children.find(raw => raw.ticker === child.ticker)
                            const groupWeight = activeGroup.weight > 0 ? child.weight * 100 / activeGroup.weight : 0
                            const marginScaled = (rawChild?.weight ?? 0) * multiplier

                            return (
                              <tr key={child.ticker}>
                                <td>{child.ticker}</td>
                                <td>{groupWeight.toFixed(2)}%</td>
                                {hasMargin && <td>{marginScaled.toFixed(2)}%</td>}
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
