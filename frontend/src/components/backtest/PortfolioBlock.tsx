// ── PortfolioBlock.tsx — Controlled portfolio block for Backtest & MonteCarlo ─

import React, { useState, useEffect, useRef } from 'react'
import { ArrowDownAZ, ChevronDown, Pencil, Settings, Ungroup } from 'lucide-react'
import {
  BlockState, MarginRow, RebalanceStrategyRow, SwapEditorRow, newId,
  blockStateToSavedConfig, configToBlockState, normalizeBlockSpreadInputs,
  convertHoldingEditorRowToSwap, invalidPortfolioEditorRowIds,
  sortAndMergePortfolioEditorRows,
  REBALANCE_OPTIONS,
} from '@/types/backtest'
import { savedConfigToStrategyState, strategyStateToSavedConfig } from '@/types/rebalanceStrategy'
import { isValidNumberInput, parseStrictNumberInput } from '@/lib/numberInputs'
import { useAllocStrategyOptions } from '@/hooks/useAllocStrategyOptions'
import {
  blockStateResolution,
  resolvePortfolioReferenceExposures,
} from '@/lib/portfolioRefs'
import {
  announceSavedPortfoliosChanged,
  refreshSavedPortfolios,
  useSavedPortfolios,
} from '@/lib/savedPortfolioCache'
import { formatSwapRow, parseInstrumentExpression, parseSwapInput } from '@/lib/portfolioComposition'
import {
  portfolioListDropTarget,
  portfolioRowDropPosition,
  reorderPortfolioRows,
} from '@/lib/portfolioRowDrag'
import type { PortfolioRowDropPosition } from '@/lib/portfolioRowDrag'

interface Props {
  idx: number
  value: BlockState
  onChange: (s: BlockState) => void
  onSavedRefresh: () => void
  showTickerConfig?: boolean
}

const PORTFOLIO_ROW_DRAG_TYPE = 'application/x-portfolio-row'

function validateSwapDraft(row: SwapEditorRow) {
  const sourceInvalid = parseInstrumentExpression(row.source) == null
  const amountInvalid = row.transferMode === 'AMOUNT' &&
    (!Number.isFinite(Number(row.transferAmount)) || Number(row.transferAmount) <= 0)
  const legErrors = row.legs.map(leg => ({
    instrumentInvalid: parseInstrumentExpression(leg.instrument) == null,
    multiplierInvalid: !Number.isFinite(Number(leg.multiplier)) || Number(leg.multiplier) === 0,
  }))
  return {
    sourceInvalid,
    amountInvalid,
    legErrors,
    invalid: sourceInvalid || amountInvalid || row.legs.length === 0 ||
      legErrors.some(error => error.instrumentInvalid || error.multiplierInvalid),
  }
}

const PortfolioBlock = React.memo(function PortfolioBlock({ idx, value, onChange, onSavedRefresh }: Props) {
  const marginModeOptions = useAllocStrategyOptions(true)
  const [dragOver, setDragOver] = useState<'chip' | 'portfolio-ref' | 'margin' | null>(null)
  const [saveMsg, setSaveMsg] = useState('')
  const { savedPortfolios, loaded: savedPortfolioNamesLoaded } = useSavedPortfolios()
  const savedPortfolioNames = new Set(savedPortfolios.map(portfolio => portfolio.name))
  const [tickerConfig, setTickerConfig] = useState<{
    symbol: string
    letf: string
    groups: string
    loading: boolean
    saving: boolean
    error: string
  } | null>(null)

  // Local state — text inputs update this only; parent is notified on blur or structural change
  const [local, setLocal] = useState<BlockState>(value)
  const [spreadTouched, setSpreadTouched] = useState<Record<string, boolean>>({})
  const [swapTextDrafts, setSwapTextDrafts] = useState<Record<string, string>>({})
  const [invalidSwapTextIds, setInvalidSwapTextIds] = useState<Set<string>>(new Set())
  const [draggedPortfolioRowId, setDraggedPortfolioRowId] = useState<string | null>(null)
  const [portfolioRowDropTarget, setPortfolioRowDropTarget] = useState<{
    rowId: string
    position: PortfolioRowDropPosition
  } | null>(null)
  const [swapDialog, setSwapDialog] = useState<{ draft: SwapEditorRow; isNew: boolean; submitted: boolean } | null>(null)
  const localRef = useRef<BlockState>(local)
  const prevValueRef = useRef<BlockState>(value)
  const swapDialogRef = useRef<HTMLDivElement>(null)
  const draggedPortfolioRowIdRef = useRef<string | null>(null)

  // Sync from parent when value changes externally (import, drag-drop, clear, load)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      localRef.current = value
      setLocal(value)
      setSpreadTouched({})
      setSwapTextDrafts({})
      setInvalidSwapTextIds(new Set())
    }
  }, [value])

  useEffect(() => {
    if (!swapDialog) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSwapDialog(null)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [swapDialog])

  function updateLocal(next: BlockState) {
    localRef.current = next
    setLocal(next)
  }

  // Structural changes → update local AND notify parent immediately
  function commit(next: BlockState) {
    const nextRowIds = new Set(next.tickers.map(row => row.id))
    setSwapTextDrafts(current => Object.fromEntries(
      Object.entries(current).filter(([id]) => nextRowIds.has(id)),
    ))
    setInvalidSwapTextIds(current => new Set([...current].filter(id => nextRowIds.has(id))))
    prevValueRef.current = next  // prevent useEffect from re-syncing back
    localRef.current = next
    setLocal(next)
    onChange(next)
  }

  // Text input blur → push latest local value to parent
  function commitBlur() {
    prevValueRef.current = localRef.current
    onChange(localRef.current)
  }

  function commitSpreadBlur(id: string) {
    setSpreadTouched(prev => ({ ...prev, [id]: true }))
    commitBlur()
  }

  async function refreshSavedPortfolioNames() {
    return refreshSavedPortfolios()
  }

  // ── Allocation hint ───────────────────────────────────────────────────────

  const resolution = blockStateResolution(local, savedPortfolios)
  let weightHintText = ''
  let weightHintCls = 'backtest-weight-hint'
  if (local.tickers.length > 0) {
    if (resolution.issues.length > 0) {
      weightHintText = 'Resolution issues'
      weightHintCls = 'backtest-weight-hint hint-warn'
    } else if (Math.abs(resolution.net - 100) < 0.001) {
      weightHintText = 'Resolved net: 100.00% ✓'
      weightHintCls = 'backtest-weight-hint hint-ok'
    } else {
      weightHintText = `Resolved net: ${resolution.net.toFixed(2)}%`
      weightHintCls = 'backtest-weight-hint hint-warn'
    }
  }

  // ── Ticker helpers ────────────────────────────────────────────────────────

  function addHolding(instrument = '', allocation = '') {
    commit({
      ...localRef.current,
      tickers: [...localRef.current.tickers, {
        id: newId(),
        type: 'HOLDING',
        instrument: instrument.toUpperCase(),
        allocation,
      }],
    })
  }

  function addSwap() {
    const id = newId()
    setSwapDialog({
      isNew: true,
      submitted: false,
      draft: {
        id,
        type: 'SWAP',
        source: '',
        transferMode: 'AMOUNT',
        transferAmount: '',
        legs: [{ id: `${id}-leg-0`, instrument: '', multiplier: '1' }],
      },
    })
  }

  function openSwapDialog(row: SwapEditorRow) {
    const draft = structuredClone(row)
    if (draft.legs.length === 0) {
      draft.legs.push({ id: `${draft.id}-leg-0`, instrument: '', multiplier: '1' })
    }
    setSwapDialog({ draft, isNew: false, submitted: false })
  }

  function saveSwapDialog() {
    if (!swapDialog) return
    if (validateSwapDraft(swapDialog.draft).invalid) {
      setSwapDialog(current => current ? { ...current, submitted: true } : current)
      setTimeout(() => {
        swapDialogRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
      }, 0)
      return
    }
    const normalizedDraft: SwapEditorRow = {
      ...swapDialog.draft,
      source: parseInstrumentExpression(swapDialog.draft.source)!,
      transferAmount: swapDialog.draft.transferMode === 'AMOUNT'
        ? String(Number(swapDialog.draft.transferAmount))
        : '',
      legs: swapDialog.draft.legs.map(leg => ({
        ...leg,
        instrument: parseInstrumentExpression(leg.instrument)!,
        multiplier: String(Number(leg.multiplier)),
      })),
    }
    const nextRows = swapDialog.isNew
      ? [...localRef.current.tickers, normalizedDraft]
      : localRef.current.tickers.map(row => row.id === normalizedDraft.id ? normalizedDraft : row)
    commit({ ...localRef.current, tickers: nextRows })
    setSwapTextDrafts(current => ({ ...current, [normalizedDraft.id]: formatSwapRow({
      source: normalizedDraft.source,
      legs: normalizedDraft.legs.map(leg => ({ instrument: leg.instrument, multiplier: Number(leg.multiplier) })),
    }) }))
    setInvalidSwapTextIds(current => {
      const nextIds = new Set(current)
      nextIds.delete(normalizedDraft.id)
      return nextIds
    })
    setSwapDialog(null)
  }

  function commitSwapText(row: SwapEditorRow) {
    const text = swapTextDrafts[row.id] ?? formatSwapRow({
      source: row.source,
      legs: row.legs.map(leg => ({ instrument: leg.instrument, multiplier: Number(leg.multiplier) })),
    })
    const parsed = parseSwapInput(text)
    if (!parsed) {
      setInvalidSwapTextIds(current => new Set(current).add(row.id))
      return
    }
    const next: SwapEditorRow = {
      ...row,
      source: parsed.source,
      legs: parsed.legs.map((leg, index) => ({
        id: row.legs[index]?.id ?? `${row.id}-leg-${index}`,
        instrument: leg.instrument,
        multiplier: String(leg.multiplier),
      })),
    }
    setSwapTextDrafts(current => ({ ...current, [row.id]: parsed.formatted }))
    setInvalidSwapTextIds(current => {
      const nextIds = new Set(current)
      nextIds.delete(row.id)
      return nextIds
    })
    commit({ ...localRef.current, tickers: localRef.current.tickers.map(item => item.id === row.id ? next : item) })
  }

  function updateSwapAmount(row: SwapEditorRow, value: string) {
    const allRemaining = value.trim() === '*'
    updateLocal({
      ...localRef.current,
      tickers: localRef.current.tickers.map(item => item.id === row.id && item.type === 'SWAP'
        ? { ...item, transferMode: allRemaining ? 'ALL_REMAINING' : 'AMOUNT', transferAmount: allRemaining ? '' : value }
        : item),
    })
  }

  function sortAndMergeTickers() {
    commit({ ...localRef.current, tickers: sortAndMergePortfolioEditorRows(localRef.current.tickers) })
  }

  function updateHoldingInstrument(id: string, instrument: string) {
    updateLocal({
      ...localRef.current,
      tickers: localRef.current.tickers.map(x =>
        x.id === id && x.type === 'HOLDING' ? { ...x, instrument: instrument.toUpperCase() } : x),
    })
  }

  function updatePortfolioRef(id: string, name: string) {
    updateLocal({
      ...localRef.current,
      tickers: localRef.current.tickers.map(x =>
        x.id === id && x.type === 'PORTFOLIO_REFERENCE' ? { ...x, portfolioName: name } : x),
    })
  }

  function addPortfolioRef(name: string, allocation = '') {
    commit({
      ...localRef.current,
      tickers: [...localRef.current.tickers, {
        id: newId(),
        type: 'PORTFOLIO_REFERENCE',
        portfolioName: name,
        allocation,
        normalizationMode: 'NET_100',
      }],
    })
  }

  function removeTicker(id: string) {
    commit({ ...localRef.current, tickers: localRef.current.tickers.filter(t => t.id !== id) })
  }

  async function decomposePortfolioRef(rowId: string) {
    const row = localRef.current.tickers.find(t => t.id === rowId)
    if (row?.type !== 'PORTFOLIO_REFERENCE') return
    const referenceAllocation = parseStrictNumberInput(row.allocation)
    if (referenceAllocation == null) return

    const refName = row.portfolioName.trim()
    const saved = await refreshSavedPortfolioNames()
    const resolvedChildExposures = resolvePortfolioReferenceExposures({
      id: row.id,
      type: 'PORTFOLIO_REFERENCE',
      portfolioName: refName,
      allocation: referenceAllocation,
      normalizationMode: row.normalizationMode,
    }, saved)
    const childRows = resolvedChildExposures.map(child => ({
      id: newId(),
      type: 'HOLDING' as const,
      instrument: child.instrument,
      allocation: String(child.exposure),
    }))

    if (childRows.length === 0) return
    commit({
      ...localRef.current,
      tickers: localRef.current.tickers.flatMap(t => t.id === rowId ? childRows : [t]),
    })
  }

  async function openTickerConfig(rawSymbol: string) {
    const symbol = rawSymbol.trim().toUpperCase()
    if (!symbol || symbol.includes(' ')) return
    setTickerConfig({ symbol, letf: '', groups: '', loading: true, saving: false, error: '' })
    try {
      const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(symbol)}`)
      if (!res.ok) throw new Error('Failed to load ticker config')
      const data = await res.json()
      setTickerConfig({
        symbol,
        letf: data.letf ?? '',
        groups: data.groups ?? '',
        loading: false,
        saving: false,
        error: '',
      })
    } catch (err) {
      setTickerConfig({
        symbol,
        letf: '',
        groups: '',
        loading: false,
        saving: false,
        error: String(err),
      })
    }
  }

  async function saveTickerConfig() {
    if (!tickerConfig || tickerConfig.loading || tickerConfig.saving) return
    setTickerConfig({ ...tickerConfig, saving: true, error: '' })
    try {
      const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(tickerConfig.symbol)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ letf: tickerConfig.letf, groups: tickerConfig.groups }),
      })
      if (!res.ok) throw new Error('Failed to save ticker config')
      setTickerConfig(null)
    } catch (err) {
      setTickerConfig({ ...tickerConfig, saving: false, error: String(err) })
    }
  }

  // ── Margin helpers ────────────────────────────────────────────────────────

  function addMargin(init?: Partial<Omit<MarginRow, 'id'>>) {
    commit({
      ...localRef.current,
      margins: [...localRef.current.margins, {
        id: newId(),
        ratio: '50', spread: '1.5', devUpper: '5', devLower: '5',
        modeUpper: 'PROPORTIONAL', modeLower: 'PROPORTIONAL',
        ...init,
      }],
    })
  }

  function removeMargin(id: string) {
    commit({ ...localRef.current, margins: localRef.current.margins.filter(m => m.id !== id) })
  }

  function addRebalanceStrategy(row: Omit<RebalanceStrategyRow, 'id'>) {
    commit({
      ...localRef.current,
      rebalanceStrategies: [...(localRef.current.rebalanceStrategies ?? []), { id: newId(), ...row }],
    })
  }

  function removeRebalanceStrategy(id: string) {
    commit({
      ...localRef.current,
      rebalanceStrategies: (localRef.current.rebalanceStrategies ?? []).filter(s => s.id !== id),
    })
  }

  function updateRebalanceStrategyConfig(id: string, updater: (strategy: ReturnType<typeof savedConfigToStrategyState>) => ReturnType<typeof savedConfigToStrategyState>) {
    commit({
      ...localRef.current,
      rebalanceStrategies: (localRef.current.rebalanceStrategies ?? []).map(row => {
        if (row.id !== id) return row
        const strategy = savedConfigToStrategyState(row.config, row.name)
        const nextStrategy = updater(strategy)
        return { ...row, config: strategyStateToSavedConfig(nextStrategy) }
      }),
    })
  }

  function toggleRebalanceBase(row: RebalanceStrategyRow) {
    updateRebalanceStrategyConfig(row.id, strategy => {
      const nextBaseEnabled = !(strategy.baseEnabled ?? true)
      return { ...strategy, baseEnabled: nextBaseEnabled }
    })
  }

  function toggleDerivedRebalanceStrategy(row: RebalanceStrategyRow, derivedId: string) {
    updateRebalanceStrategyConfig(row.id, strategy => ({
      ...strategy,
      derivedSubStrategies: (strategy.derivedSubStrategies ?? []).map(d =>
        d.id === derivedId ? { ...d, enabled: !d.enabled } : d,
      ),
    }))
  }

  // ── Save / Clear ──────────────────────────────────────────────────────────

  async function handleSave(overwrite: boolean) {
    const normalized = normalizeBlockSpreadInputs(localRef.current)
    if (normalized !== localRef.current) commit(normalized)
    if (invalidPortfolioEditorRowIds(normalized).size > 0) return
    if (blockStateResolution(normalized, savedPortfolios).issues.length > 0) return
    const name = normalized.label.trim()
    if (!name) return
    if (overwrite) {
      await fetch(`/api/backtest/savedPortfolios?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
    }
    const res = await fetch('/api/backtest/savedPortfolios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config: blockStateToSavedConfig(normalized) }),
    })
    if (res.ok) {
      onSavedRefresh()
      announceSavedPortfoliosChanged()
      setSaveMsg('Saved!')
      setTimeout(() => setSaveMsg(''), 1500)
    }
  }

  function handleClear() {
    commit({ label: '', tickers: [], rebalance: 'YEARLY', margins: [], rebalanceStrategies: [], includeNoMargin: true })
  }

  // ── Drag-and-drop ─────────────────────────────────────────────────────────

  function clearPortfolioRowDrag() {
    draggedPortfolioRowIdRef.current = null
    setDraggedPortfolioRowId(null)
    setPortfolioRowDropTarget(null)
  }

  function portfolioRowDragLabel(row: BlockState['tickers'][number]) {
    if (row.type === 'PORTFOLIO_REFERENCE') {
      return `Drag ${row.portfolioName || 'unnamed'} portfolio reference row`
    }
    if (row.type === 'SWAP') return `Drag ${row.source || 'unnamed'} swap row`
    return `Drag ${row.instrument || 'unnamed'} row`
  }

  function listPortfolioRowDropTarget(list: HTMLDivElement, pointerY: number) {
    return portfolioListDropTarget(pointerY, Array.from(
      list.querySelectorAll<HTMLElement>('[data-portfolio-row-id]'),
      row => {
        const bounds = row.getBoundingClientRect()
        return {
          rowId: row.dataset.portfolioRowId!,
          top: bounds.top,
          height: bounds.height,
        }
      },
    ))
  }

  function dropPortfolioRow(sourceId: string, target: {
    rowId: string
    position: PortfolioRowDropPosition
  } | null) {
    clearPortfolioRowDrag()
    if (!target) return
    const nextRows = reorderPortfolioRows(
      localRef.current.tickers,
      sourceId,
      target.rowId,
      target.position,
    )
    if (nextRows !== localRef.current.tickers) {
      commit({ ...localRef.current, tickers: nextRows })
    }
  }

  function handleDragOver(e: React.DragEvent) {
    const types = e.dataTransfer.types
    if (types.includes('application/x-margin-config') || types.includes('application/x-strategy-chip') || types.includes('application/x-portfolio-chip')) {
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
      if (types.includes('application/x-margin-config') || types.includes('application/x-strategy-chip')) {
        setDragOver('margin')
      } else {
        setDragOver((e.target as HTMLElement | null)?.closest('.ticker-rows') ? 'portfolio-ref' : 'chip')
      }
    }
  }

  function isTickerListDrop(e: React.DragEvent) {
    return e.dataTransfer.types.includes('application/x-portfolio-chip') &&
      !!(e.target as HTMLElement | null)?.closest('.ticker-rows')
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(null)
    if (e.dataTransfer.types.includes('application/x-margin-config')) {
      const cfg = JSON.parse(e.dataTransfer.getData('application/x-margin-config'))
      addMargin({ ratio: cfg.ratio, spread: cfg.spread, devUpper: cfg.devUpper, devLower: cfg.devLower, modeUpper: cfg.modeUpper, modeLower: cfg.modeLower })
    } else if (e.dataTransfer.types.includes('application/x-strategy-chip')) {
      const raw = e.dataTransfer.getData('application/x-strategy-chip')
      if (raw) {
        const { name, config } = JSON.parse(raw)
        addRebalanceStrategy({ name, config })
      }
    } else if (e.dataTransfer.types.includes('application/x-portfolio-chip')) {
      const raw = e.dataTransfer.getData('application/x-portfolio-chip')
      if (raw) {
        const { name, config } = JSON.parse(raw)
        if (isTickerListDrop(e)) addPortfolioRef(name)
        else commit(configToBlockState(config, name))
      }
    }
  }

  const hasLabel = local.label.trim().length > 0
  const invalidRowIds = invalidPortfolioEditorRowIds(local)
  const canSave = hasLabel && invalidRowIds.size === 0 && invalidSwapTextIds.size === 0 && resolution.issues.length === 0
  const swapDialogValidation = swapDialog ? validateSwapDraft(swapDialog.draft) : null
  const summarizeStrategyRow = (row: RebalanceStrategyRow) => {
    const strategy = savedConfigToStrategyState(row.config, row.name)
    return {
      row,
      key: row.id,
      strategy,
      baseEnabled: strategy.baseEnabled ?? true,
      derived: strategy.derivedSubStrategies ?? [],
    }
  }

  const strategySummaries = (local.rebalanceStrategies ?? []).map(summarizeStrategyRow)

  return (
    <div
      className={`portfolio-block${dragOver === 'chip' ? ' drag-over' : dragOver === 'portfolio-ref' ? ' drag-over-portfolio-ref' : dragOver === 'margin' ? ' drag-over-margin' : ''}`}
      data-portfolio-index={idx}
      onDragOver={handleDragOver}
      onDragLeave={() => setDragOver(null)}
      onDrop={handleDrop}
    >
      {/* Label + save buttons */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <input
            type="text"
            className="portfolio-label"
            placeholder="Label"
            value={local.label}
            onChange={e => updateLocal({ ...localRef.current, label: e.target.value })}
            onBlur={commitBlur}
          />
          <button
            className="overwrite-portfolio-btn save-portfolio-btn"
            disabled={!canSave}
            onClick={() => handleSave(true)}
          >
            {saveMsg || 'Save'}
          </button>
          <button className="save-portfolio-btn" disabled={!canSave} onClick={() => handleSave(false)}>
            Save New
          </button>
          <button className="clear-action-btn" type="button" title="Clear portfolio" onClick={handleClear}>
            X
          </button>
        </div>
      </div>

      {/* Tickers */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <span className="ticker-section-title">
            Portfolio rows
            <span
              className="ticker-modifier-hint"
              title={[
                'Synthetic ticker syntax: use multiplier/ticker pairs, e.g. 1 KMLM 1 VT.',
                'Swap syntax: A > B #m + C #n means -A + m*B + n*C plus DUMMY filler; # and multipliers are optional.',
                'Use * as a swap row weight to swap all remaining source weight at that point in swap order.',
                'SWAP(A,B,k) is still supported; k defaults to 1.',
                'S=<spread %>, e.g. S=1.5',
                'R=<rebalance: D/W/M/Q/Y>, e.g. R=Q',
                'E=<expense ratio or credit %>, e.g. E=0.95 or E=-1.5',
                'V=<relative volatility change %>, e.g. V=20 or V=-25',
                'Examples: 2 QQQ S=1.5 R=Q E=-1.5 V=20; CTAP > SSO #1.5 with weight *; SWAP(CTAP,SSO,1.5).',
              ].join('\n')}
              aria-label="Ticker modifier help"
              tabIndex={0}
            >
              ?
            </span>
          </span>
          <div className="ticker-header-actions">
            <button
              className="sort-tickers-btn"
              type="button"
              title="Merge and sort tickers"
              aria-label="Merge and sort tickers"
              onClick={sortAndMergeTickers}
            >
              <ArrowDownAZ size={15} strokeWidth={2.2} aria-hidden="true" />
            </button>
            <button className="add-ticker-btn" type="button" onClick={() => addHolding()}>+Ticker</button>
            <button className="add-ticker-btn" type="button" onClick={addSwap}>+Swap</button>
          </div>
        </div>
        <div className={weightHintCls}>{weightHintText}</div>
        {resolution.issues.length > 0 && (
          <ul className="portfolio-resolution-issues" aria-label="Portfolio resolution issues">
            {resolution.issues.map((issue, index) => (
              <li key={`${issue.rowId}-${issue.code}-${index}`}>
                {issue.referencePath?.length ? `${issue.referencePath.join(' → ')}: ` : ''}
                {issue.message}
              </li>
            ))}
          </ul>
        )}
        <div
          className="ticker-rows"
          onDragOver={e => {
            const sourceId = draggedPortfolioRowIdRef.current
            if (!sourceId || !e.dataTransfer.types.includes(PORTFOLIO_ROW_DRAG_TYPE)) return
            e.preventDefault()
            e.stopPropagation()
            e.dataTransfer.dropEffect = 'move'
            setPortfolioRowDropTarget(listPortfolioRowDropTarget(e.currentTarget, e.clientY))
          }}
          onDrop={e => {
            const sourceId = draggedPortfolioRowIdRef.current
            if (!sourceId || !e.dataTransfer.types.includes(PORTFOLIO_ROW_DRAG_TYPE)) return
            e.preventDefault()
            e.stopPropagation()
            dropPortfolioRow(
              sourceId,
              portfolioRowDropTarget ?? listPortfolioRowDropTarget(e.currentTarget, e.clientY),
            )
          }}
        >
          {local.tickers.map(t => {
            const refName = t.type === 'PORTFOLIO_REFERENCE' ? t.portfolioName.trim() : ''
            const portfolioRefExists = t.type === 'PORTFOLIO_REFERENCE' && refName.length > 0 && savedPortfolioNames.has(refName)
            const portfolioRefMissing = t.type === 'PORTFOLIO_REFERENCE' && savedPortfolioNamesLoaded && !portfolioRefExists
            const canDecomposePortfolioRef = portfolioRefExists &&
              t.type === 'PORTFOLIO_REFERENCE' && isValidNumberInput(t.allocation)
            const portfolioRefStatusTitle = !savedPortfolioNamesLoaded
              ? 'Checking saved portfolio reference'
              : portfolioRefExists
                ? 'Saved portfolio reference exists'
                : 'Saved portfolio reference not found'
            const dragLabel = portfolioRowDragLabel(t)
            const rowClassName = [
              'backtest-ticker-row has-ticker-config',
              t.type === 'PORTFOLIO_REFERENCE' ? 'portfolio-ref-row' : '',
              invalidRowIds.has(t.id) ? 'portfolio-row-invalid' : '',
              portfolioRefExists ? 'portfolio-ref-row-exists' : '',
              portfolioRefMissing ? 'portfolio-ref-row-missing' : '',
              draggedPortfolioRowId === t.id ? 'dragging' : '',
              portfolioRowDropTarget?.rowId === t.id ? `drop-${portfolioRowDropTarget.position}` : '',
            ].filter(Boolean).join(' ')
            return (
              <div
                key={t.id}
                className={rowClassName}
                data-portfolio-row-id={t.id}
                onDragOver={e => {
                  const sourceId = draggedPortfolioRowIdRef.current
                  if (!sourceId || !e.dataTransfer.types.includes(PORTFOLIO_ROW_DRAG_TYPE)) return
                  e.preventDefault()
                  e.stopPropagation()
                  e.dataTransfer.dropEffect = 'move'
                  setPortfolioRowDropTarget({
                    rowId: t.id,
                    position: portfolioRowDropPosition(e.clientY, e.currentTarget.getBoundingClientRect()),
                  })
                }}
                onDrop={e => {
                  const sourceId = draggedPortfolioRowIdRef.current
                  if (!sourceId || !e.dataTransfer.types.includes(PORTFOLIO_ROW_DRAG_TYPE)) return
                  e.preventDefault()
                  e.stopPropagation()
                  const position = portfolioRowDropTarget?.rowId === t.id
                    ? portfolioRowDropTarget.position
                    : portfolioRowDropPosition(e.clientY, e.currentTarget.getBoundingClientRect())
                  dropPortfolioRow(sourceId, { rowId: t.id, position })
                }}
              >
                <button
                  type="button"
                  className="portfolio-row-drag-handle"
                  draggable
                  aria-label={dragLabel}
                  title={dragLabel}
                  onDragStart={e => {
                    draggedPortfolioRowIdRef.current = t.id
                    setDraggedPortfolioRowId(t.id)
                    e.dataTransfer.setData(PORTFOLIO_ROW_DRAG_TYPE, t.id)
                    e.dataTransfer.effectAllowed = 'move'
                    e.stopPropagation()
                  }}
                  onDragEnd={clearPortfolioRowDrag}
                >
                  ⠿
                </button>
                {t.type === 'PORTFOLIO_REFERENCE' ? (
                  <label className="ticker-input portfolio-ref-name" title={portfolioRefStatusTitle}>
                    <label className="portfolio-ref-mode" title="Choose how the referenced portfolio exposure is scaled">
                      <select
                        aria-label={`Reference mode for ${t.portfolioName || 'portfolio'}`}
                        value={t.normalizationMode}
                        onChange={e => commit({
                          ...localRef.current,
                          tickers: localRef.current.tickers.map(row =>
                            row.id === t.id && row.type === 'PORTFOLIO_REFERENCE'
                              ? { ...row, normalizationMode: e.target.value as 'NET_100' | 'PRESERVE' }
                              : row),
                        })}
                      >
                        <option value="NET_100">Ref 100</option>
                        <option value="PRESERVE">Ref 1:1</option>
                      </select>
                      <ChevronDown size={12} aria-hidden="true" />
                    </label>
                    <input
                      type="text"
                      className="portfolio-ref-input"
                      placeholder="Saved portfolio name"
                      value={t.portfolioName}
                      onChange={e => updatePortfolioRef(t.id, e.target.value)}
                      onBlur={() => {
                        commitBlur()
                        refreshSavedPortfolioNames()
                      }}
                    />
                  </label>
                ) : t.type === 'HOLDING' ? (
                <>
                <input
                  type="text"
                  className="ticker-input"
                  placeholder="e.g. VT, CTAP > SSO #1.5, or: 1 KMLM 1 VT S=1.5 R=Q E=-1.5 V=20"
                  value={t.instrument}
                  onChange={e => updateHoldingInstrument(t.id, e.target.value)}
                  onBlur={commitBlur}
                />
                {convertHoldingEditorRowToSwap(t) && (
                  <button
                    type="button"
                    className="convert-swap-btn"
                    onClick={() => {
                      const converted = convertHoldingEditorRowToSwap(t)
                      if (converted) commit({
                        ...localRef.current,
                        tickers: localRef.current.tickers.map(row => row.id === t.id ? converted : row),
                      })
                    }}
                  >
                    Convert to swap
                  </button>
                )}
                </>
              ) : (
                <label className="ticker-input swap-expression-field">
                  <span className="swap-row-badge">SWAP</span>
                  <input
                    type="text"
                    className={`swap-expression-input${invalidSwapTextIds.has(t.id) ? ' input-error' : ''}`}
                    aria-label="Swap structure"
                    aria-invalid={invalidSwapTextIds.has(t.id)}
                    value={swapTextDrafts[t.id] ?? formatSwapRow({
                      source: t.source,
                      legs: t.legs.map(leg => ({ instrument: leg.instrument, multiplier: Number(leg.multiplier) })),
                    })}
                    onChange={e => setSwapTextDrafts(current => ({ ...current, [t.id]: e.target.value }))}
                    onBlur={() => commitSwapText(t)}
                  />
                </label>
              )}
              {t.type === 'SWAP' && (
                <>
                  <label className="swap-amount-field allocation-field">
                    <input
                      type="text"
                      className="weight-input"
                      aria-label="Swap transfer amount"
                      value={t.transferMode === 'ALL_REMAINING' ? '*' : t.transferAmount}
                      onChange={e => updateSwapAmount(t, e.target.value)}
                      onBlur={commitBlur}
                    />
                    {t.transferMode === 'AMOUNT' && <span className="allocation-unit" aria-hidden="true">%</span>}
                  </label>
                  <button className="ticker-config-btn" type="button" title="Edit swap" aria-label="Edit swap" onClick={() => openSwapDialog(t)}>
                    <Pencil size={14} />
                  </button>
                </>
              )}
              {t.type !== 'SWAP' && (
                <label className="allocation-field">
                  <input
                    type="text"
                    className="weight-input"
                    placeholder="Allocation"
                    value={t.allocation}
                    onChange={e => updateLocal({
                      ...localRef.current,
                      tickers: localRef.current.tickers.map(row =>
                        row.id === t.id && row.type !== 'SWAP' ? { ...row, allocation: e.target.value } : row),
                    })}
                    onBlur={commitBlur}
                  />
                  <span className="allocation-unit" aria-hidden="true">%</span>
                </label>
              )}
              {t.type === 'PORTFOLIO_REFERENCE' ? (
                <button
                  className="ticker-config-btn portfolio-decompose-btn"
                  type="button"
                  title={portfolioRefExists ? 'Decompose this portfolio to resolved holdings' : portfolioRefStatusTitle}
                  aria-label={`Decompose ${t.portfolioName || 'portfolio'} to resolved holdings`}
                  disabled={!canDecomposePortfolioRef}
                  onClick={() => decomposePortfolioRef(t.id)}
                >
                  <Ungroup size={14} />
                </button>
              ) : t.type === 'HOLDING' ? (
                <button
                  className="ticker-config-btn"
                  type="button"
                  title="Ticker config"
                  aria-label={`Configure ${t.instrument || 'ticker'}`}
                  disabled={!t.instrument.trim() || t.instrument.includes(' ')}
                  onClick={() => openTickerConfig(t.instrument)}
                >
                  <Settings size={14} />
                </button>
              ) : null}
              <button className="remove-ticker-btn" type="button" title="Remove" onClick={() => removeTicker(t.id)}>
                ✕
              </button>
            </div>
            )
          })}
        </div>
      </div>

      {/* Rebalance strategy */}
      <div className="backtest-section">
        <label>Rebalance Strategy</label>
        <select
          className="rebalance-select"
          value={local.rebalance}
          onChange={e => commit({ ...localRef.current, rebalance: e.target.value })}
        >
          {REBALANCE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </div>

      {/* Margin */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <span>Margin</span>
          <div className="margin-header-btns">
            <button
              className="include-no-margin-btn"
              type="button"
              data-include={String(local.includeNoMargin)}
              onClick={() => commit({ ...localRef.current, includeNoMargin: !localRef.current.includeNoMargin })}
            >
              {local.includeNoMargin ? 'Unlevered: On' : 'Unlevered: Off'}
            </button>
            <button className="add-margin-btn" type="button" onClick={() => addMargin()}>
              + Add Margin
            </button>
          </div>
        </div>
        <div className="margin-col-headers">
          <span /><span>Ratio%</span><span>Spread%</span>
          <span title="Upper deviation band: rebalance if margin ratio rises above target + this %">Dev%↑</span>
          <span title="Lower deviation band: rebalance if margin ratio falls below target − this %">Dev%↓</span>
          <span title="Action when upper band breached (market fell → over-leveraged)">Mode↑</span>
          <span title="Action when lower band breached (market rose → under-leveraged)">Mode↓</span>
          <span />
        </div>
        <div className="margin-config-rows">
          {local.margins.map(m => (
            <div key={m.id} className="margin-config-row">
              <span
                className="margin-drag-handle"
                draggable
                onDragStart={e => {
                  e.dataTransfer.setData('application/x-margin-config', JSON.stringify({
                    ratio: m.ratio, spread: m.spread, devUpper: m.devUpper, devLower: m.devLower,
                    modeUpper: m.modeUpper, modeLower: m.modeLower,
                  }))
                  e.dataTransfer.effectAllowed = 'copy'
                }}
              >⠿</span>
              <input type="text" className="mc-ratio"     value={m.ratio}    onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, ratio:    e.target.value } : x) })} onBlur={commitBlur} title="Margin % of Equity" placeholder="%" />
              <input
                type="text"
                className={`mc-spread${spreadTouched[m.id] && !isValidNumberInput(m.spread, { min: 0 }) ? ' input-error' : ''}`}
                value={m.spread}
                onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, spread: e.target.value } : x) })}
                onBlur={() => commitSpreadBlur(m.id)}
                aria-invalid={spreadTouched[m.id] && !isValidNumberInput(m.spread, { min: 0 })}
                title={spreadTouched[m.id] && !isValidNumberInput(m.spread, { min: 0 }) ? 'Enter a valid non-negative spread percent' : 'Spread % (annualised)'}
                placeholder="%"
              />
              <input type="text" className="mc-dev-upper" value={m.devUpper} onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, devUpper: e.target.value } : x) })} onBlur={commitBlur} title="Upper deviation %" placeholder="%" />
              <input type="text" className="mc-dev-lower" value={m.devLower} onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, devLower: e.target.value } : x) })} onBlur={commitBlur} title="Lower deviation %" placeholder="%" />
              <select
                className="mc-mode mc-mode-upper"
                value={m.modeUpper}
                onChange={e => commit({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, modeUpper: e.target.value } : x) })}
                title="Rebalance action when upper band is breached"
              >
                {marginModeOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <select
                className="mc-mode mc-mode-lower"
                value={m.modeLower}
                onChange={e => commit({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, modeLower: e.target.value } : x) })}
                title="Rebalance action when lower band is breached"
              >
                {marginModeOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <button type="button" className="remove-margin-btn" title="Remove" onClick={() => removeMargin(m.id)}>✕</button>
            </div>
          ))}
          {strategySummaries.map(({ row, key, strategy, baseEnabled, derived }) => (
            <div key={key} className="margin-config-row rebalance-strategy-margin-row">
              <span className="margin-drag-handle" aria-hidden="true">S</span>
              <div className="strategy-margin-info">
                <span className="strategy-margin-name" title={row.name}>{row.name}</span>
                <button
                  type="button"
                  className="strategy-run-pill"
                  data-active={String(baseEnabled)}
                  aria-pressed={baseEnabled}
                  title={`${baseEnabled ? 'Run' : 'Skip'} ${strategy.label || row.name}`}
                  onClick={() => toggleRebalanceBase(row)}
                >
                  Base
                </button>
                {derived.map(d => {
                  const label = d.label?.trim() || 'Derived'
                  const sourceDetail = d.marginReferenceSource === 'STANDALONE_TICKER'
                    ? `Ref ${d.marginReferenceTicker || 'ticker'}`
                    : 'Ref base'
                  return (
                    <button
                      key={d.id}
                      type="button"
                      className="strategy-run-pill"
                      data-active={String(d.enabled)}
                      aria-pressed={d.enabled}
                      title={`${d.enabled ? 'Run' : 'Skip'} ${row.name} / ${label} (${sourceDetail})`}
                      onClick={() => toggleDerivedRebalanceStrategy(row, d.id)}
                    >
                      {label}
                    </button>
                  )
                })}
              </div>
              <button type="button" className="remove-margin-btn" title="Remove" onClick={() => removeRebalanceStrategy(row.id)}>✕</button>
            </div>
          ))}
        </div>
      </div>

      {swapDialog && (
        <div className="ticker-config-overlay" role="dialog" aria-modal="true" aria-labelledby="swap-dialog-title" onMouseDown={e => {
          if (e.target === e.currentTarget) setSwapDialog(null)
        }}>
          <div ref={swapDialogRef} className="ticker-config-dialog swap-config-dialog">
            <div className="ticker-config-header">
              <h2 id="swap-dialog-title">{swapDialog.isNew ? 'Add swap' : 'Edit swap'}</h2>
              <button type="button" className="ticker-config-close" aria-label="Cancel" onClick={() => setSwapDialog(null)}>x</button>
            </div>
            {swapDialog.submitted && swapDialogValidation?.invalid && (
              <div className="ticker-config-error" role="alert">Correct the highlighted swap fields.</div>
            )}
            <label className="ticker-config-field">
              <span>Source</span>
              <input
                autoFocus
                className={swapDialog.submitted && swapDialogValidation?.sourceInvalid ? 'input-error' : ''}
                aria-invalid={swapDialog.submitted && swapDialogValidation?.sourceInvalid}
                value={swapDialog.draft.source}
                onChange={e => setSwapDialog({ ...swapDialog, draft: { ...swapDialog.draft, source: e.target.value.toUpperCase() } })}
              />
              {swapDialog.submitted && swapDialogValidation?.sourceInvalid && (
                <small className="swap-field-error">Enter a valid source instrument.</small>
              )}
            </label>
            <div className="swap-dialog-transfer">
              <label className="ticker-config-field">
                <span>Transfer</span>
                <select value={swapDialog.draft.transferMode} onChange={e => setSwapDialog({
                  ...swapDialog,
                  draft: { ...swapDialog.draft, transferMode: e.target.value as 'AMOUNT' | 'ALL_REMAINING' },
                })}>
                  <option value="AMOUNT">Amount</option>
                  <option value="ALL_REMAINING">All remaining</option>
                </select>
              </label>
              {swapDialog.draft.transferMode === 'AMOUNT' && (
                <label className="ticker-config-field">
                  <span>Amount %</span>
                  <input
                    className={swapDialog.submitted && swapDialogValidation?.amountInvalid ? 'input-error' : ''}
                    aria-invalid={swapDialog.submitted && swapDialogValidation?.amountInvalid}
                    value={swapDialog.draft.transferAmount}
                    onChange={e => setSwapDialog({ ...swapDialog, draft: { ...swapDialog.draft, transferAmount: e.target.value } })}
                  />
                  {swapDialog.submitted && swapDialogValidation?.amountInvalid && (
                    <small className="swap-field-error">Enter an amount greater than zero.</small>
                  )}
                </label>
              )}
            </div>
            <div className="swap-dialog-legs">
              <span>Destinations</span>
              {swapDialog.draft.legs.map((leg, index) => (
                <div className="swap-leg" key={leg.id}>
                  <label className="swap-leg-field">
                    <input
                      aria-label="Swap destination"
                      className={swapDialog.submitted && swapDialogValidation?.legErrors[index]?.instrumentInvalid ? 'input-error' : ''}
                      aria-invalid={swapDialog.submitted && swapDialogValidation?.legErrors[index]?.instrumentInvalid}
                      placeholder="Instrument"
                      value={leg.instrument}
                      onChange={e => setSwapDialog({
                        ...swapDialog,
                        draft: { ...swapDialog.draft, legs: swapDialog.draft.legs.map(item => item.id === leg.id ? { ...item, instrument: e.target.value.toUpperCase() } : item) },
                      })}
                    />
                    {swapDialog.submitted && swapDialogValidation?.legErrors[index]?.instrumentInvalid && (
                      <small className="swap-field-error">Enter a valid destination.</small>
                    )}
                  </label>
                  <label className="swap-leg-field swap-leg-multiplier">
                    <input
                      aria-label="Swap destination multiplier"
                      className={swapDialog.submitted && swapDialogValidation?.legErrors[index]?.multiplierInvalid ? 'input-error' : ''}
                      aria-invalid={swapDialog.submitted && swapDialogValidation?.legErrors[index]?.multiplierInvalid}
                      placeholder="Multiplier"
                      value={leg.multiplier}
                      onChange={e => setSwapDialog({
                        ...swapDialog,
                        draft: { ...swapDialog.draft, legs: swapDialog.draft.legs.map(item => item.id === leg.id ? { ...item, multiplier: e.target.value } : item) },
                      })}
                    />
                    {swapDialog.submitted && swapDialogValidation?.legErrors[index]?.multiplierInvalid && (
                      <small className="swap-field-error">Enter a non-zero multiplier.</small>
                    )}
                  </label>
                  {swapDialog.draft.legs.length > 1 && <button type="button" onClick={() => setSwapDialog({
                    ...swapDialog,
                    draft: { ...swapDialog.draft, legs: swapDialog.draft.legs.filter(item => item.id !== leg.id) },
                  })}>−</button>}
                </div>
              ))}
              <button type="button" onClick={() => setSwapDialog({
                ...swapDialog,
                draft: { ...swapDialog.draft, legs: [...swapDialog.draft.legs, { id: newId(), instrument: '', multiplier: '1' }] },
              })}>+ Destination</button>
            </div>
            <div className="ticker-config-actions">
              <button type="button" onClick={() => setSwapDialog(null)}>Cancel</button>
              <button type="button" className="ticker-config-save" onClick={saveSwapDialog}>Save</button>
            </div>
          </div>
        </div>
      )}

      {tickerConfig && (
        <div className="ticker-config-overlay" role="dialog" aria-modal="true" onMouseDown={e => {
          if (e.target === e.currentTarget) setTickerConfig(null)
        }}>
          <div className="ticker-config-dialog">
            <div className="ticker-config-header">
              <h2>{tickerConfig.symbol}</h2>
              <button type="button" className="ticker-config-close" onClick={() => setTickerConfig(null)}>x</button>
            </div>
            {tickerConfig.loading ? (
              <div className="ticker-config-status">Loading...</div>
            ) : (
              <>
                <label className="ticker-config-field">
                  <span>LETF</span>
                  <input
                    type="text"
                    value={tickerConfig.letf}
                    placeholder="e.g. 2 QQQ S=1.5 R=Q E=-1.5 V=20 or CTAP > SSO #1.5"
                    onChange={e => setTickerConfig({ ...tickerConfig, letf: e.target.value })}
                  />
                </label>
                <label className="ticker-config-field">
                  <span>Groups</span>
                  <input
                    type="text"
                    value={tickerConfig.groups}
                    placeholder="e.g. 1 Equity;0.5 Growth"
                    onChange={e => setTickerConfig({ ...tickerConfig, groups: e.target.value })}
                  />
                </label>
                {tickerConfig.error && <div className="ticker-config-error">{tickerConfig.error}</div>}
                <div className="ticker-config-actions">
                  <button type="button" onClick={() => setTickerConfig(null)}>Cancel</button>
                  <button type="button" className="ticker-config-save" disabled={tickerConfig.saving} onClick={saveTickerConfig}>
                    {tickerConfig.saving ? 'Saving...' : 'Save'}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
})

export default PortfolioBlock
