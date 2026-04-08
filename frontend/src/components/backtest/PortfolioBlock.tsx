// ── PortfolioBlock.tsx — Controlled portfolio block for Backtest & MonteCarlo ─

import { useState } from 'react'
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
}

export default function PortfolioBlock({ idx, value, onChange, onSavedRefresh }: Props) {
  const [dragOver, setDragOver] = useState<'chip' | 'margin' | null>(null)
  const [saveMsg, setSaveMsg] = useState('')

  // ── Weight hint ───────────────────────────────────────────────────────────

  const totalWeight = value.tickers.reduce((sum, t) => sum + (parseFloat(t.weight) || 0), 0)
  const diff = Math.round((totalWeight - 100) * 100) / 100
  let weightHintText = ''
  let weightHintCls = 'backtest-weight-hint'
  if (value.tickers.length > 0) {
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
    onChange({ ...value, tickers: [...value.tickers, { id: newId(), ticker, weight }] })
  }

  function updateTicker(id: string, field: 'ticker' | 'weight', val: string) {
    onChange({ ...value, tickers: value.tickers.map(t => t.id === id ? { ...t, [field]: val } : t) })
  }

  function removeTicker(id: string) {
    onChange({ ...value, tickers: value.tickers.filter(t => t.id !== id) })
  }

  // ── Margin helpers ────────────────────────────────────────────────────────

  function addMargin(init?: Partial<Omit<MarginRow, 'id'>>) {
    onChange({
      ...value,
      margins: [...value.margins, {
        id: newId(),
        ratio: '50', spread: '1.5', devUpper: '5', devLower: '5',
        modeUpper: 'PROPORTIONAL', modeLower: 'PROPORTIONAL',
        ...init,
      }],
    })
  }

  function updateMargin(id: string, field: keyof Omit<MarginRow, 'id'>, val: string) {
    onChange({ ...value, margins: value.margins.map(m => m.id === id ? { ...m, [field]: val } : m) })
  }

  function removeMargin(id: string) {
    onChange({ ...value, margins: value.margins.filter(m => m.id !== id) })
  }

  // ── Save / Clear ──────────────────────────────────────────────────────────

  async function handleSave(overwrite: boolean) {
    const name = value.label.trim()
    if (!name) return
    if (overwrite) {
      await fetch(`/api/backtest/savedPortfolios?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
    }
    const res = await fetch('/api/backtest/savedPortfolios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config: blockStateToSavedConfig(value) }),
    })
    if (res.ok) {
      onSavedRefresh()
      setSaveMsg('Saved!')
      setTimeout(() => setSaveMsg(''), 1500)
    }
  }

  function handleClear() {
    onChange({ label: '', tickers: [], rebalance: 'YEARLY', margins: [], includeNoMargin: true })
  }

  // ── Drag-and-drop ─────────────────────────────────────────────────────────

  function handleDragOver(e: React.DragEvent) {
    const types = e.dataTransfer.types
    if (types.includes('application/x-margin-config') || types.includes('application/x-portfolio-chip')) {
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
      setDragOver(types.includes('application/x-margin-config') ? 'margin' : 'chip')
    }
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
        onChange(configToBlockState(config, name))
      }
    }
  }

  const hasLabel = value.label.trim().length > 0

  return (
    <div
      className={`portfolio-block${dragOver === 'chip' ? ' drag-over' : dragOver === 'margin' ? ' drag-over-margin' : ''}`}
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
            value={value.label}
            onChange={e => onChange({ ...value, label: e.target.value })}
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
          {value.tickers.map(t => (
            <div key={t.id} className="backtest-ticker-row">
              <input
                type="text"
                className="ticker-input"
                placeholder="e.g. VT or: 1 KMLM 1 VT S=1.5"
                value={t.ticker}
                onChange={e => updateTicker(t.id, 'ticker', e.target.value)}
              />
              <input
                type="text"
                className="weight-input"
                placeholder="Weight %"
                value={t.weight}
                onChange={e => updateTicker(t.id, 'weight', e.target.value)}
              />
              <span className="weight-unit">%</span>
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
          value={value.rebalance}
          onChange={e => onChange({ ...value, rebalance: e.target.value })}
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
              data-include={String(value.includeNoMargin)}
              onClick={() => onChange({ ...value, includeNoMargin: !value.includeNoMargin })}
            >
              {value.includeNoMargin ? 'Unlevered: On' : 'Unlevered: Off'}
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
          {value.margins.map(m => (
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
              <input type="text" className="mc-ratio"     value={m.ratio}    onChange={e => updateMargin(m.id, 'ratio',    e.target.value)} title="Margin % of Equity" placeholder="%" />
              <input type="text" className="mc-spread"    value={m.spread}   onChange={e => updateMargin(m.id, 'spread',   e.target.value)} title="Spread % (annualised)" placeholder="%" />
              <input type="text" className="mc-dev-upper" value={m.devUpper} onChange={e => updateMargin(m.id, 'devUpper', e.target.value)} title="Upper deviation %" placeholder="%" />
              <input type="text" className="mc-dev-lower" value={m.devLower} onChange={e => updateMargin(m.id, 'devLower', e.target.value)} title="Lower deviation %" placeholder="%" />
              <select
                className="mc-mode mc-mode-upper"
                value={m.modeUpper}
                onChange={e => updateMargin(m.id, 'modeUpper', e.target.value)}
                title="Rebalance action when upper band is breached"
              >
                {MARGIN_MODE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <select
                className="mc-mode mc-mode-lower"
                value={m.modeLower}
                onChange={e => updateMargin(m.id, 'modeLower', e.target.value)}
                title="Rebalance action when lower band is breached"
              >
                {MARGIN_MODE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <button type="button" className="remove-margin-btn" title="Remove" onClick={() => removeMargin(m.id)}>✕</button>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
