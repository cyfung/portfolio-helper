import { useCallback, useEffect, useRef, useState } from 'react'
import { Pin, Settings } from 'lucide-react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { BlockState } from '@/types/backtest'
import type { SavedPortfolio } from '@/types/backtest'
import { configToBlockInputLabel, configToBlockState } from '@/types/backtest'
import { blockStateToSettingsPortfolio, isPlaceholderTicker, resolveBlockStateRows, type ResolvedStockWeight } from '@/lib/portfolioRefs'
import { parseGroupsAttr } from '@/lib/portfolio-utils'
import { expandLetfRows, normalizeTickerExpression, parseLetfComponents } from '@/lib/tickerExpressions'

interface TickerConfig {
  letf: string
  groups: string
}

interface GroupRow {
  name: string
  weight: number
  children: ResolvedStockWeight[]
}

interface TickerConfigEditor {
  blockIndex: number
  label: string
  rows: {
    symbol: string
    letf: string
    groups: string
  }[]
  saving: boolean
  error: string
}

function blankBlock(): BlockState {
  return { label: '', tickers: [], rebalance: 'YEARLY', margins: [], rebalanceStrategies: [], includeNoMargin: true }
}

function marginRatio(block: BlockState, marginIndex: number) {
  return parseFloat(block.margins[marginIndex]?.ratio ?? '') || 0
}

function addWeight(map: Map<string, number>, ticker: string, weight: number) {
  const key = ticker.trim().toUpperCase()
  if (!key || weight === 0) return
  map.set(key, (map.get(key) ?? 0) + weight)
}

function isEditableTicker(ticker: string) {
  const symbol = ticker.trim().toUpperCase()
  return symbol.length > 0 && !/\s/.test(symbol) && /^[A-Z0-9^][A-Z0-9._-]*$/.test(symbol)
}

function editableTickerSymbols(...rowGroups: ResolvedStockWeight[][]) {
  const symbols = new Set<string>()
  rowGroups.flat().forEach(row => {
    const symbol = row.ticker.trim().toUpperCase()
    if (!isPlaceholderTicker(symbol) && isEditableTicker(symbol)) symbols.add(symbol)
  })
  return [...symbols].sort((a, b) => a.localeCompare(b))
}

function normalizeRows(rows: ResolvedStockWeight[]) {
  const total = rows.reduce((sum, row) => sum + row.weight, 0)
  if (total <= 0) return rows
  let allocated = 0
  return rows.map((row, index) => {
    const weight = index === rows.length - 1 ? 100 - allocated : row.weight * 100 / total
    allocated += weight
    return { ...row, weight }
  })
}

function buildGroupRows(rows: ResolvedStockWeight[], tickerConfigs: Record<string, TickerConfig>) {
  const groups = new Map<string, GroupRow>()

  for (const row of rows) {
    const groupConfig = tickerConfigs[row.ticker.toUpperCase()]?.groups?.trim() ?? ''
    if (!groupConfig) continue

    for (const group of parseGroupsAttr(groupConfig, row.ticker)) {
      const weight = row.weight * group.multiplier
      if (weight === 0) continue

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
  const [tickerConfigEditor, setTickerConfigEditor] = useState<TickerConfigEditor | null>(null)
  const [error, setError] = useState('')
  const savedBarRef = useRef<SavedPortfoliosBarRef>(null)
  const settingsLoadedRef = useRef(false)
  const lastSavedPortfoliosRef = useRef('')

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

  useEffect(() => {
    loadSaved()
    fetch('/api/backtest/settings')
      .then(res => res.ok ? res.json() : null)
      .then((settings: any) => {
        if (!settings?.portfolios) return
        setBlocks(prev => {
          const next = [...prev]
          settings.portfolios.forEach((portfolio: any, i: number) => {
            if (i < next.length) next[i] = configToBlockState(portfolio, configToBlockInputLabel(portfolio, i))
          })
          lastSavedPortfoliosRef.current = JSON.stringify(next.map((block, i) => blockStateToSettingsPortfolio(block, i)))
          return next
        })
      })
      .catch(() => {})
      .finally(() => { settingsLoadedRef.current = true })
  }, [])

  useEffect(() => {
    if (!settingsLoadedRef.current) return
    const portfolios = blocks.map((block, i) => blockStateToSettingsPortfolio(block, i))
    const serialized = JSON.stringify(portfolios)
    if (serialized === lastSavedPortfoliosRef.current) return
    lastSavedPortfoliosRef.current = serialized

    fetch('/api/backtest/settings/portfolios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ portfolios }),
    }).catch(() => {})
  }, [blocks])

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
    const queue = new Set(rowsByBlock
      .flatMap(rows => rows.map(row => normalizeTickerExpression(row.ticker)))
      .filter(ticker => !isPlaceholderTicker(ticker)))

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
        for (const component of parseLetfComponents(letf)) {
          if (!configs[component.ticker]) queue.add(component.ticker)
        }
      }
    }

    return configs
  }

  async function handleAnalyse() {
    try {
      setError('')
      const nextResults = blocks.map(block => resolveBlockStateRows(block, savedPortfolios, { normalize: false }))
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

  function openTickerConfigEditor(blockIndex: number, label: string, symbols: string[]) {
    setTickerConfigEditor({
      blockIndex,
      label,
      rows: symbols.map(symbol => ({
        symbol,
        letf: tickerConfigs[symbol]?.letf ?? '',
        groups: tickerConfigs[symbol]?.groups ?? '',
      })),
      saving: false,
      error: '',
    })
  }

  function updateTickerConfigEditorRow(symbol: string, field: 'letf' | 'groups', value: string) {
    setTickerConfigEditor(editor => editor && {
      ...editor,
      rows: editor.rows.map(row => row.symbol === symbol ? { ...row, [field]: value } : row),
      error: '',
    })
  }

  async function saveTickerConfigEditor() {
    if (!tickerConfigEditor || tickerConfigEditor.saving) return
    const editor = tickerConfigEditor
    setTickerConfigEditor({ ...editor, saving: true, error: '' })

    try {
      await Promise.all(editor.rows.map(async row => {
        const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(row.symbol)}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ letf: row.letf, groups: row.groups }),
        })
        if (!res.ok) throw new Error(`Failed to save ${row.symbol}`)
      }))

      if (results) setTickerConfigs(await loadTickerConfigs(results))
      else {
        setTickerConfigs(prev => {
          const next = { ...prev }
          editor.rows.forEach(row => { next[row.symbol] = { letf: row.letf, groups: row.groups } })
          return next
        })
      }
      setTickerConfigEditor(null)
    } catch (err: unknown) {
      setTickerConfigEditor({
        ...editor,
        saving: false,
        error: err instanceof Error ? err.message : String(err),
      })
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
              showTickerConfig
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
            const baseRows = normalizeRows(rows)
            const letfResult = expandLetfRows(baseRows, tickerConfigs)
            const showLetfExpanded = letfResult.expanded && (showLetfExpandedByBlock[i] ?? true)
            const marginExposureRows = showLetfExpanded ? letfResult.rows : baseRows
            const normalizedRows = normalizeRows(marginExposureRows)
            const groupRows = buildGroupRows(normalizedRows, tickerConfigs)
            const marginGroupRows = buildGroupRows(marginExposureRows, tickerConfigs)
            const editableSymbols = editableTickerSymbols(rows, letfResult.rows)
            const activeGroupName = pinnedGroupByBlock[i] ?? hoveredGroupByBlock[i]
            const activeGroup = groupRows.find(group => group.name === activeGroupName)
            const activeMarginGroup = marginGroupRows.find(group => group.name === activeGroupName)
            const hasMargin = block.margins.length > 0
            const selectedMarginIndex = hasMargin
              ? Math.min(selectedMarginByBlock[i] ?? 0, block.margins.length - 1)
              : 0
            const multiplier = 1 + marginRatio(block, selectedMarginIndex) / 100
            const totalWeight = normalizedRows.reduce((sum, row) => sum + row.weight, 0)
            const totalMarginScaled = marginExposureRows.reduce((sum, row) => sum + row.weight * multiplier, 0)

            return (
              <div key={i} className="portfolio-builder-result-block">
                <div className="portfolio-builder-result-title">
                  <span>{block.label.trim() || `Portfolio ${i + 1}`}</span>
                  <div className="portfolio-builder-result-controls">
                    {editableSymbols.length > 0 && (
                      <button
                        className="portfolio-builder-edit-tickers-btn"
                        type="button"
                        title="Edit LETF and Groups"
                        onClick={() => openTickerConfigEditor(i, block.label.trim() || `Portfolio ${i + 1}`, editableSymbols)}
                      >
                        <Settings size={13} aria-hidden="true" />
                        Edit
                      </button>
                    )}
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
                      {normalizedRows.map(row => (
                        <tr key={row.ticker}>
                          <td>{row.ticker}</td>
                          <td>{row.weight.toFixed(2)}%</td>
                          {hasMargin && (
                            <td>{(((marginExposureRows.find(marginRow => marginRow.ticker === row.ticker)?.weight) ?? 0) * multiplier).toFixed(2)}%</td>
                          )}
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
                        <tr>
                          <th>Group</th>
                          <th>Weight</th>
                          {hasMargin && <th>Margin Scaled</th>}
                        </tr>
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
                              setPinnedGroupByBlock(prev => {
                                const next = { ...prev }
                                if (next[i] === group.name) delete next[i]
                                else next[i] = group.name
                                return next
                              })
                            }}
                          >
                            <td>
                              <span className="portfolio-builder-group-name">
                                {pinnedGroupByBlock[i] === group.name && <Pin size={12} aria-hidden="true" />}
                                {group.name}
                              </span>
                            </td>
                            <td>{group.weight.toFixed(2)}%</td>
                            {hasMargin && (
                              <td>{((marginGroupRows.find(marginGroup => marginGroup.name === group.name)?.weight ?? 0) * multiplier).toFixed(2)}%</td>
                            )}
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
                            const marginChild = activeMarginGroup?.children.find(marginRow => marginRow.ticker === child.ticker)
                            const groupWeight = activeGroup.weight !== 0 ? child.weight * 100 / activeGroup.weight : 0
                            const marginScaled = (marginChild?.weight ?? 0) * multiplier

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

      {tickerConfigEditor && (
        <div className="ticker-config-overlay" role="dialog" aria-modal="true" onMouseDown={e => {
          if (e.target === e.currentTarget && !tickerConfigEditor.saving) setTickerConfigEditor(null)
        }}>
          <div className="ticker-config-dialog portfolio-builder-ticker-editor" onClick={e => e.stopPropagation()}>
            <div className="ticker-config-header">
              <h2>{tickerConfigEditor.label}</h2>
              <button
                type="button"
                className="ticker-config-close"
                disabled={tickerConfigEditor.saving}
                onClick={() => setTickerConfigEditor(null)}
              >
                x
              </button>
            </div>
            <div className="portfolio-builder-ticker-editor-table-wrap">
              <table className="portfolio-table portfolio-builder-ticker-editor-table">
                <thead>
                  <tr>
                    <th>Symbol</th>
                    <th>LETF</th>
                    <th>Groups</th>
                  </tr>
                </thead>
                <tbody>
                  {tickerConfigEditor.rows.map(row => (
                    <tr key={row.symbol}>
                      <td>{row.symbol}</td>
                      <td>
                        <input
                          type="text"
                          value={row.letf}
                          placeholder="e.g. 2 IVV"
                          onChange={e => updateTickerConfigEditorRow(row.symbol, 'letf', e.target.value)}
                        />
                      </td>
                      <td>
                        <input
                          type="text"
                          value={row.groups}
                          placeholder="e.g. 1 Equity"
                          onChange={e => updateTickerConfigEditorRow(row.symbol, 'groups', e.target.value)}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {tickerConfigEditor.error && <div className="ticker-config-error">{tickerConfigEditor.error}</div>}
            <div className="ticker-config-actions">
              <button type="button" disabled={tickerConfigEditor.saving} onClick={() => setTickerConfigEditor(null)}>Cancel</button>
              <button
                type="button"
                className="ticker-config-save"
                disabled={tickerConfigEditor.saving}
                onClick={saveTickerConfigEditor}
              >
                {tickerConfigEditor.saving ? 'Saving...' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
