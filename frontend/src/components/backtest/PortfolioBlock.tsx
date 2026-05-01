// ── PortfolioBlock.tsx — Controlled portfolio block for Backtest & MonteCarlo ─

import React, { useState, useEffect, useRef } from 'react'
import { Settings } from 'lucide-react'
import {
  BlockState, MarginRow, newId,
  blockStateToSavedConfig, configToBlockState,
  REBALANCE_OPTIONS, MARGIN_MODE_OPTIONS,
} from '@/types/backtest'

interface Props {
  idx: number
  value: BlockState
  onChange: (s: BlockState) => void
  onSavedRefresh: () => void
  showTickerConfig?: boolean
}

const PortfolioBlock = React.memo(function PortfolioBlock({ idx, value, onChange, onSavedRefresh, showTickerConfig = false }: Props) {
  const [dragOver, setDragOver] = useState<'chip' | 'portfolio-ref' | 'margin' | null>(null)
  const [saveMsg, setSaveMsg] = useState('')
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
  const localRef = useRef<BlockState>(local)
  const prevValueRef = useRef<BlockState>(value)

  // Sync from parent when value changes externally (import, drag-drop, clear, load)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      localRef.current = value
      setLocal(value)
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
    commit({ ...localRef.current, tickers: [...localRef.current.tickers, { id: newId(), ticker, weight }] })
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

  // ── Save / Clear ──────────────────────────────────────────────────────────

  async function handleSave(overwrite: boolean) {
    const name = localRef.current.label.trim()
    if (!name) return
    if (overwrite) {
      await fetch(`/api/backtest/savedPortfolios?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
    }
    const res = await fetch('/api/backtest/savedPortfolios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config: blockStateToSavedConfig(localRef.current) }),
    })
    if (res.ok) {
      onSavedRefresh()
      setSaveMsg('Saved!')
      setTimeout(() => setSaveMsg(''), 1500)
    }
  }

  function handleClear() {
    commit({ label: '', tickers: [], rebalance: 'YEARLY', margins: [], includeNoMargin: true })
  }

  // ── Drag-and-drop ─────────────────────────────────────────────────────────

  function handleDragOver(e: React.DragEvent) {
    const types = e.dataTransfer.types
    if (types.includes('application/x-margin-config') || types.includes('application/x-portfolio-chip')) {
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
      if (types.includes('application/x-margin-config')) {
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
          <button className="clear-portfolio-btn" type="button" title="Clear portfolio" onClick={handleClear}>
            ✕
          </button>
        </div>
      </div>

      {/* Tickers */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <span>Tickers &amp; Weights</span>
          <button className="add-ticker-btn" type="button" onClick={() => addTicker()}>+ Add Ticker</button>
        </div>
        <div className={weightHintCls}>{weightHintText}</div>
        <div className="ticker-rows">
          {local.tickers.map(t => (
            <div key={t.id} className={`backtest-ticker-row${t.isPortfolioRef ? ' portfolio-ref-row' : ''}`}>
              {t.isPortfolioRef ? (
                <div className="ticker-input portfolio-ref-name" title="Saved portfolio reference">
                  <span className="portfolio-ref-badge">Portfolio</span>
                  <span>{t.ticker}</span>
                </div>
              ) : (
                <input
                  type="text"
                  className="ticker-input"
                  placeholder="e.g. VT or: 1 KMLM 1 VT S=1.5"
                  value={t.ticker}
                  onChange={e => updateLocal({ ...localRef.current, tickers: localRef.current.tickers.map(x => x.id === t.id ? { ...x, ticker: e.target.value } : x) })}
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
              {t.isPortfolioRef || !showTickerConfig ? (
                <span />
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
          ))}
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
              <input type="text" className="mc-spread"    value={m.spread}   onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, spread:   e.target.value } : x) })} onBlur={commitBlur} title="Spread % (annualised)" placeholder="%" />
              <input type="text" className="mc-dev-upper" value={m.devUpper} onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, devUpper: e.target.value } : x) })} onBlur={commitBlur} title="Upper deviation %" placeholder="%" />
              <input type="text" className="mc-dev-lower" value={m.devLower} onChange={e => updateLocal({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, devLower: e.target.value } : x) })} onBlur={commitBlur} title="Lower deviation %" placeholder="%" />
              <select
                className="mc-mode mc-mode-upper"
                value={m.modeUpper}
                onChange={e => commit({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, modeUpper: e.target.value } : x) })}
                title="Rebalance action when upper band is breached"
              >
                {MARGIN_MODE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <select
                className="mc-mode mc-mode-lower"
                value={m.modeLower}
                onChange={e => commit({ ...localRef.current, margins: localRef.current.margins.map(x => x.id === m.id ? { ...x, modeLower: e.target.value } : x) })}
                title="Rebalance action when lower band is breached"
              >
                {MARGIN_MODE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <button type="button" className="remove-margin-btn" title="Remove" onClick={() => removeMargin(m.id)}>✕</button>
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
                <label>
                  <span>LETF</span>
                  <textarea
                    value={tickerConfig.letf}
                    placeholder="e.g. 2 QQQ S=1.5 R=Q"
                    rows={3}
                    onChange={e => setTickerConfig({ ...tickerConfig, letf: e.target.value })}
                  />
                </label>
                <label>
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
