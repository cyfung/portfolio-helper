// ── PortfolioBlock.tsx — Controlled portfolio block for Backtest & MonteCarlo ─

import React, { useState, useEffect, useRef } from 'react'
import { ArrowDownAZ, Settings, Ungroup } from 'lucide-react'
import {
  BlockState, MarginRow, RebalanceStrategyRow, newId,
  blockStateToSavedConfig, configToBlockState, normalizeBlockSpreadInputs,
  REBALANCE_OPTIONS,
} from '@/types/backtest'
import { savedConfigToStrategyState } from '@/types/rebalanceStrategy'
import { isValidNumberInput, parseStrictNumberInput } from '@/lib/numberInputs'
import { useAllocStrategyOptions } from '@/hooks/useAllocStrategyOptions'
import { SAVED_PORTFOLIOS_CHANGED_EVENT, fetchSavedPortfolios, savedPortfolioConfig } from '@/lib/portfolioRefs'

interface Props {
  idx: number
  value: BlockState
  onChange: (s: BlockState) => void
  onSavedRefresh: () => void
  showTickerConfig?: boolean
}

const PortfolioBlock = React.memo(function PortfolioBlock({ idx, value, onChange, onSavedRefresh }: Props) {
  const marginModeOptions = useAllocStrategyOptions(true)
  const [dragOver, setDragOver] = useState<'chip' | 'portfolio-ref' | 'margin' | null>(null)
  const [saveMsg, setSaveMsg] = useState('')
  const [savedPortfolioNames, setSavedPortfolioNames] = useState<Set<string>>(() => new Set())
  const [savedPortfolioNamesLoaded, setSavedPortfolioNamesLoaded] = useState(false)
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
  const localRef = useRef<BlockState>(local)
  const prevValueRef = useRef<BlockState>(value)

  // Sync from parent when value changes externally (import, drag-drop, clear, load)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      localRef.current = value
      setLocal(value)
      setSpreadTouched({})
    }
  }, [value])

  function updateLocal(next: BlockState) {
    localRef.current = next
    setLocal(next)
  }

  // Structural changes → update local AND notify parent immediately
  function commit(next: BlockState) {
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
    const saved = await fetchSavedPortfolios()
    setSavedPortfolioNames(new Set(saved.map(p => p.name)))
    setSavedPortfolioNamesLoaded(true)
    return saved
  }

  useEffect(() => {
    let cancelled = false
    function loadSavedPortfolioNames() {
      fetchSavedPortfolios().then(saved => {
        if (cancelled) return
        setSavedPortfolioNames(new Set(saved.map(p => p.name)))
        setSavedPortfolioNamesLoaded(true)
      })
    }
    loadSavedPortfolioNames()
    window.addEventListener(SAVED_PORTFOLIOS_CHANGED_EVENT, loadSavedPortfolioNames)
    return () => {
      cancelled = true
      window.removeEventListener(SAVED_PORTFOLIOS_CHANGED_EVENT, loadSavedPortfolioNames)
    }
  }, [])

  // ── Weight hint ───────────────────────────────────────────────────────────

  const totalWeight = local.tickers.reduce((sum, t) => sum + (parseFloat(t.weight) || 0), 0)
  const diff = Math.round((totalWeight - 100) * 100) / 100
  let weightHintText = ''
  let weightHintCls = 'backtest-weight-hint'
  if (local.tickers.length > 0) {
    if (Math.abs(diff) < 0.001) {
      weightHintText = 'Total: 100% ✓'
      weightHintCls = 'backtest-weight-hint hint-ok'
    } else {
      weightHintText = `Total: ${totalWeight.toFixed(2)}% (${diff > 0 ? '+' : ''}${diff.toFixed(2)}%)`
      weightHintCls = 'backtest-weight-hint hint-warn'
    }
  }

  // ── Ticker helpers ────────────────────────────────────────────────────────

  function addTicker(ticker = '', weight = '') {
    commit({ ...localRef.current, tickers: [...localRef.current.tickers, { id: newId(), ticker: ticker.toUpperCase(), weight }] })
  }

  function sortAndMergeTickers() {
    type TickerRowState = BlockState['tickers'][number]
    type TickerGroup = {
      key: string
      label: string
      isPortfolioRef: boolean
      rows: TickerRowState[]
      weight: number
      firstIndex: number
    }

    const groups = new Map<string, TickerGroup>()

    localRef.current.tickers.forEach((row, index) => {
      const isPortfolioRef = row.isPortfolioRef === true
      const label = isPortfolioRef ? row.ticker.trim() : row.ticker.trim().toUpperCase()
      const key = label ? `${isPortfolioRef ? 'portfolio' : 'ticker'}:${label}` : `empty:${row.id}`
      const weight = parseStrictNumberInput(row.weight) ?? 0
      const group = groups.get(key)
      if (group) {
        group.rows.push(row)
        group.weight += weight
        return
      }
      groups.set(key, {
        key,
        label,
        isPortfolioRef,
        rows: [row],
        weight,
        firstIndex: index,
      })
    })

    const formatWeight = (weight: number) => String(Math.round(weight * 10000000000) / 10000000000)
    const sorted = [...groups.values()]
      .sort((a, b) => {
        if (!a.label && b.label) return 1
        if (a.label && !b.label) return -1
        return a.label.localeCompare(b.label) || Number(a.isPortfolioRef) - Number(b.isPortfolioRef) || a.firstIndex - b.firstIndex
      })
      .map(group => {
        const first = group.rows[0]
        if (group.rows.length === 1) {
          return group.label && !group.isPortfolioRef ? { ...first, ticker: group.label } : first
        }
        return {
          id: first.id,
          ticker: group.label,
          weight: formatWeight(group.weight),
          ...(group.isPortfolioRef ? { isPortfolioRef: true } : {}),
        }
      })

    commit({ ...localRef.current, tickers: sorted })
  }

  function updateTicker(id: string, ticker: string) {
    updateLocal({
      ...localRef.current,
      tickers: localRef.current.tickers.map(x => x.id === id ? { ...x, ticker: ticker.toUpperCase() } : x),
    })
  }

  function updatePortfolioRef(id: string, name: string) {
    updateLocal({
      ...localRef.current,
      tickers: localRef.current.tickers.map(x => x.id === id ? { ...x, ticker: name } : x),
    })
  }

  function addPortfolioRef(name: string, weight = '') {
    commit({
      ...localRef.current,
      tickers: [...localRef.current.tickers, { id: newId(), ticker: name, weight, isPortfolioRef: true }],
    })
  }

  function removeTicker(id: string) {
    commit({ ...localRef.current, tickers: localRef.current.tickers.filter(t => t.id !== id) })
  }

  async function decomposePortfolioRef(rowId: string) {
    const row = localRef.current.tickers.find(t => t.id === rowId)
    const rowWeight = parseStrictNumberInput(row?.weight)
    if (!row?.isPortfolioRef || rowWeight == null) return

    const refName = row.ticker.trim()
    const saved = await refreshSavedPortfolioNames()
    const savedConfig = savedPortfolioConfig(saved.find(p => p.name === refName)?.config)
    if (!savedConfig) return
    const childRows = (savedConfig?.tickers ?? [])
      .map((child: any) => {
        const childWeight = parseStrictNumberInput(child?.weight)
        if (childWeight == null) return null
        const isPortfolioRef = child?.isPortfolioRef === true || child?.type === 'PORTFOLIO_REF' || !!child?.portfolioRef
        const ticker = isPortfolioRef
          ? String(child?.portfolioRef || child?.ticker || '').trim()
          : String(child?.ticker || '').trim().toUpperCase()
        if (!ticker) return null
        return {
          id: newId(),
          ticker,
          weight: String(rowWeight * childWeight / 100),
          ...(isPortfolioRef ? { isPortfolioRef: true } : {}),
        }
      })
      .filter(Boolean) as BlockState['tickers']

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

  // ── Save / Clear ──────────────────────────────────────────────────────────

  async function handleSave(overwrite: boolean) {
    const normalized = normalizeBlockSpreadInputs(localRef.current)
    if (normalized !== localRef.current) commit(normalized)
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
      refreshSavedPortfolioNames()
      setSaveMsg('Saved!')
      setTimeout(() => setSaveMsg(''), 1500)
    }
  }

  function handleClear() {
    commit({ label: '', tickers: [], rebalance: 'YEARLY', margins: [], rebalanceStrategies: [], includeNoMargin: true })
  }

  // ── Drag-and-drop ─────────────────────────────────────────────────────────

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
  const strategySummaries = (local.rebalanceStrategies ?? []).map(row => {
    const strategy = savedConfigToStrategyState(row.config, row.name)
    const points = strategy.marginPoints ?? []
    const btd = strategy.buyTheDip
    const sos = strategy.sellOnSurge
    const dmo = strategy.drawdownMarginOverride
    const ddBl = strategy.drawdownBuyOnLowMargin
    return {
      row,
      low: points[0] ?? '40',
      mid: points[2] ?? strategy.marginRatio ?? '50',
      high: points[4] ?? '60',
      marginEnabled: strategy.marginRebalanceEnabled ?? true,
      buyLowEnabled: strategy.buyLowEnabled,
      sellHighEnabled: strategy.sellHighEnabled,
      ddBl: ddBl?.enabled,
      ddBlSgp: ddBl?.enabled && ddBl.portfolioSource === 'STRATEGY_GROSS',
      ddBlPv: ddBl?.enabled && ddBl.portfolioSource === 'STRATEGY_VALUE',
      ddBlR: ddBl?.enabled && ddBl.portfolioSource !== 'STRATEGY_GROSS' && ddBl.portfolioSource !== 'STRATEGY_VALUE',
      ddMr: dmo?.enabled,
      ddMrSgp: dmo?.enabled && dmo.portfolioSource === 'STRATEGY_GROSS',
      ddMrPv: dmo?.enabled && dmo.portfolioSource === 'STRATEGY_VALUE',
      ddMrR: dmo?.enabled && dmo.portfolioSource !== 'STRATEGY_GROSS' && dmo.portfolioSource !== 'STRATEGY_VALUE',
      bdSgp: btd?.basePortfolio != null && btd.basePortfolio.portfolioSource === 'STRATEGY_GROSS',
      bdPv:  btd?.basePortfolio != null && btd.basePortfolio.portfolioSource === 'STRATEGY_VALUE',
      bdR:   btd?.basePortfolio != null && btd.basePortfolio.portfolioSource !== 'STRATEGY_GROSS' && btd.basePortfolio.portfolioSource !== 'STRATEGY_VALUE',
      bdI:   btd?.individualStock != null,
      ssSgp: sos?.basePortfolio != null && sos.basePortfolio.portfolioSource === 'STRATEGY_GROSS',
      ssPv:  sos?.basePortfolio != null && sos.basePortfolio.portfolioSource === 'STRATEGY_VALUE',
      ssR:   sos?.basePortfolio != null && sos.basePortfolio.portfolioSource !== 'STRATEGY_GROSS' && sos.basePortfolio.portfolioSource !== 'STRATEGY_VALUE',
      ssI:   sos?.individualStock != null,
    }
  })

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
            disabled={!hasLabel}
            onClick={() => handleSave(true)}
          >
            {saveMsg || 'Save'}
          </button>
          <button className="save-portfolio-btn" disabled={!hasLabel} onClick={() => handleSave(false)}>
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
            Tickers &amp; Weights
            <span
              className="ticker-modifier-hint"
              title={[
                'Synthetic ticker syntax: use multiplier/ticker pairs, e.g. 1 KMLM 1 VT.',
                'Swap syntax: SWAP(A,B,k) means -A + k*B plus DUMMY filler; k defaults to 1.',
                'S=<spread %>, e.g. S=1.5',
                'R=<rebalance: D/W/M/Q/Y>, e.g. R=Q',
                'E=<expense ratio or credit %>, e.g. E=0.95 or E=-1.5',
                'V=<relative volatility change %>, e.g. V=20 or V=-25',
                'Examples: 2 QQQ S=1.5 R=Q E=-1.5 V=20; SWAP(CTAP,SSO); SWAP(CTAP,SSO,1.5).',
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
            <button className="add-ticker-btn" type="button" onClick={() => addTicker()}>+ Add Ticker</button>
          </div>
        </div>
        <div className={weightHintCls}>{weightHintText}</div>
        <div className="ticker-rows">
          {local.tickers.map(t => {
            const refName = t.ticker.trim()
            const portfolioRefExists = t.isPortfolioRef === true && refName.length > 0 && savedPortfolioNames.has(refName)
            const portfolioRefMissing = t.isPortfolioRef === true && savedPortfolioNamesLoaded && !portfolioRefExists
            const canDecomposePortfolioRef = portfolioRefExists && isValidNumberInput(t.weight)
            const portfolioRefStatusTitle = !savedPortfolioNamesLoaded
              ? 'Checking saved portfolio reference'
              : portfolioRefExists
                ? 'Saved portfolio reference exists'
                : 'Saved portfolio reference not found'
            const rowClassName = [
              'backtest-ticker-row has-ticker-config',
              t.isPortfolioRef ? 'portfolio-ref-row' : '',
              portfolioRefExists ? 'portfolio-ref-row-exists' : '',
              portfolioRefMissing ? 'portfolio-ref-row-missing' : '',
            ].filter(Boolean).join(' ')
            return (
              <div key={t.id} className={rowClassName}>
                {t.isPortfolioRef ? (
                  <label className="ticker-input portfolio-ref-name" title={portfolioRefStatusTitle}>
                    <span className="portfolio-ref-badge">Portfolio</span>
                    <input
                      type="text"
                      className="portfolio-ref-input"
                      placeholder="Saved portfolio name"
                      value={t.ticker}
                      onChange={e => updatePortfolioRef(t.id, e.target.value)}
                      onBlur={() => {
                        commitBlur()
                        refreshSavedPortfolioNames()
                      }}
                    />
                  </label>
                ) : (
                <input
                  type="text"
                  className="ticker-input"
                  placeholder="e.g. VT, SWAP(CTAP,SSO,1.5), or: 1 KMLM 1 VT S=1.5 R=Q E=-1.5 V=20"
                  value={t.ticker}
                  onChange={e => updateTicker(t.id, e.target.value)}
                  onBlur={commitBlur}
                />
              )}
              <input
                type="text"
                className="weight-input"
                placeholder="Weight %"
                value={t.weight}
                onChange={e => updateLocal({ ...localRef.current, tickers: localRef.current.tickers.map(x => x.id === t.id ? { ...x, weight: e.target.value } : x) })}
                onBlur={commitBlur}
              />
              <span className="weight-unit">%</span>
              {t.isPortfolioRef ? (
                <button
                  className="ticker-config-btn portfolio-decompose-btn"
                  type="button"
                  title={portfolioRefExists ? 'Decompose this portfolio one layer' : portfolioRefStatusTitle}
                  aria-label={`Decompose ${t.ticker || 'portfolio'} one layer`}
                  disabled={!canDecomposePortfolioRef}
                  onClick={() => decomposePortfolioRef(t.id)}
                >
                  <Ungroup size={14} />
                </button>
              ) : (
                <button
                  className="ticker-config-btn"
                  type="button"
                  title="Ticker config"
                  aria-label={`Configure ${t.ticker || 'ticker'}`}
                  disabled={!t.ticker.trim() || t.ticker.includes(' ')}
                  onClick={() => openTickerConfig(t.ticker)}
                >
                  <Settings size={14} />
                </button>
              )}
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
          {strategySummaries.map(({ row, low, mid, high, marginEnabled, buyLowEnabled, sellHighEnabled, ddBl, ddBlSgp, ddBlPv, ddBlR, ddMr, ddMrSgp, ddMrPv, ddMrR, bdSgp, bdPv, bdR, bdI, ssSgp, ssPv, ssR, ssI }) => (
            <div key={row.id} className="margin-config-row rebalance-strategy-margin-row">
              <span className="margin-drag-handle" aria-hidden="true">S</span>
              <div className="strategy-margin-info">
                <span className="strategy-margin-name" title={row.name}>{row.name}</span>
                <span>L {low}%</span>
                <span>M {mid}%</span>
                <span>H {high}%</span>
                {marginEnabled && <span>MR</span>}
                {ddMr && <span>DD-MR</span>}
                {ddMrSgp && <span>DD-SGP</span>}
                {ddMrPv && <span>DD-PV</span>}
                {ddMrR && <span>DD-R</span>}
                {buyLowEnabled && <span>BL</span>}
                {sellHighEnabled && <span>SH</span>}
                {ddBl && <span>DD-BL</span>}
                {ddBlSgp && <span>DD-BL-SGP</span>}
                {ddBlPv && <span>DD-BL-PV</span>}
                {ddBlR && <span>DD-BL-R</span>}
                {bdSgp && <span>BD-SGP</span>}
                {bdPv && <span>BD-PV</span>}
                {bdR && <span>BD-R</span>}
                {bdI && <span>BD-I</span>}
                {ssSgp && <span>SS-SGP</span>}
                {ssPv && <span>SS-PV</span>}
                {ssR && <span>SS-R</span>}
                {ssI && <span>SS-I</span>}
              </div>
              <button type="button" className="remove-margin-btn" title="Remove" onClick={() => removeRebalanceStrategy(row.id)}>✕</button>
            </div>
          ))}
        </div>
      </div>

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
                    placeholder="e.g. 2 QQQ S=1.5 R=Q E=-1.5 V=20 or SWAP(CTAP,SSO,1.5)"
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
