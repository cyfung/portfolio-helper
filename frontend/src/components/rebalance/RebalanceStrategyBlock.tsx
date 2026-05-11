import React, { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  RebalStrategyState,
  DipSurgeState,
  DipSurgeScopeState,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS,
  MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS,
  strategyStateToSavedConfig,
  savedConfigToStrategyState,
} from '@/types/rebalanceStrategy'
import { REBALANCE_MARGIN_MODE_OPTIONS } from '@/types/backtest'
import DipSurgeSection from './DipSurgeSection'

interface Props {
  idx: number
  value: RebalStrategyState
  onChange: (s: RebalStrategyState) => void
  sliderMax?: number
  onSavedRefresh?: () => void
}

export interface RebalanceStrategyBlockRef {
  getValue: () => RebalStrategyState
  commit: () => void
}

const DEFAULT_POINTS = ['40', '45', '50', '55', '60']

function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}

function parsePoint(v: string | undefined, fallback: number) {
  const n = parseInt(v ?? '', 10)
  return Number.isFinite(n) ? n : fallback
}

function normalizeMarginPoints(points: string[] | undefined, max: number) {
  const sliderMax = Math.max(4, Math.floor(max))
  const values = DEFAULT_POINTS.map((def, i) => clamp(parsePoint(points?.[i], parseInt(def, 10)), 0, sliderMax))

  values[0] = clamp(values[0], 0, sliderMax - 4)
  values[1] = clamp(values[1], values[0] + 1, sliderMax - 3)
  values[2] = clamp(values[2], values[1] + 1, sliderMax - 2)
  values[3] = clamp(values[3], values[2] + 1, sliderMax - 1)
  values[4] = clamp(values[4], values[3] + 1, sliderMax)

  for (let i = 3; i >= 0; i -= 1) {
    values[i] = Math.min(values[i], values[i + 1] - 1)
  }
  for (let i = 1; i < values.length; i += 1) {
    values[i] = Math.max(values[i], values[i - 1] + 1)
  }

  return values
}

function adjustMarginPoint(points: number[], index: number, value: number, max: number) {
  const sliderMax = Math.max(4, Math.floor(max))
  const next = [...points]
  const target = clamp(Math.round(value), index, sliderMax - (next.length - 1 - index))

  next[index] = target

  for (let i = index - 1; i >= 0; i -= 1) {
    if (next[i] >= next[i + 1]) next[i] = next[i + 1] - 1
  }
  for (let i = index + 1; i < next.length; i += 1) {
    if (next[i] <= next[i - 1]) next[i] = next[i - 1] + 1
  }

  return next.map(v => clamp(v, 0, sliderMax))
}

function samePoints(a: string[], b: string[]) {
  return a.length === b.length && a.every((point, i) => point === b[i])
}

function keepSectionOpen(e: React.SyntheticEvent<HTMLElement>) {
  e.preventDefault()
}

function marginValueFromLegacyPoint(points: string[], index: string | undefined, offset = 0) {
  const pointIndex = parseInt(index ?? '', 10) - offset
  if (!Number.isFinite(pointIndex) || pointIndex === 2) return ''
  return points[pointIndex] ?? ''
}

const MarginPercentInput = React.memo(function MarginPercentInput({
  value, placeholder, max, ariaLabel, onChange, onCommit,
}: {
  value: string
  placeholder: string
  max: number
  ariaLabel: string
  onChange: (value: string) => void
  onCommit?: () => void
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const idleCommitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const parseBase = useCallback(() => {
    const current = parseFloat(value)
    if (Number.isFinite(current)) return current
    const fallback = parseFloat(placeholder)
    return Number.isFinite(fallback) ? fallback : 0
  }, [placeholder, value])

  const emit = useCallback((next: number) => {
    onChange(String(clamp(Math.round(next), 0, Math.max(0, Math.floor(max)))))
  }, [max, onChange])

  const scheduleCommit = useCallback(() => {
    if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    idleCommitTimerRef.current = setTimeout(() => onCommit?.(), 180)
  }, [onCommit])

  const stepBy = useCallback((delta: number) => {
    emit(parseBase() + delta)
    scheduleCommit()
  }, [emit, parseBase, scheduleCommit])

  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const handleWheel = (e: WheelEvent) => {
      e.preventDefault()
      stepBy(e.deltaY < 0 ? 5 : -5)
    }
    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => {
      el.removeEventListener('wheel', handleWheel)
      if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    }
  }, [stepBy])

  return (
    <div className="margin-point-endpoint margin-percent-input" ref={wrapRef}>
      <button type="button" className="margin-point-step" aria-label="Decrease" onClick={() => stepBy(-1)}>-</button>
      <input
        className="margin-point-number-input"
        type="number"
        min="0"
        max={max}
        step="1"
        value={value}
        placeholder={placeholder}
        aria-label={ariaLabel}
        onChange={e => onChange(e.target.value)}
        onBlur={onCommit}
      />
      <button type="button" className="margin-point-step" aria-label="Increase" onClick={() => stepBy(1)}>+</button>
    </div>
  )
})

const MarginPointSlider = React.memo(function MarginPointSlider({
  points, max, showComfortPoints, onChange, onCommit,
}: { points: string[]; max: number; showComfortPoints: boolean; onChange: (points: string[]) => void; onCommit?: () => void }) {
  const trackRef = useRef<HTMLDivElement>(null)
  const draggingRef = useRef<number | null>(null)
  const idleCommitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inputRefs = useRef<(HTMLInputElement | null)[]>([null, null, null, null, null])
  const endpointDivRefs = useRef<(HTMLDivElement | null)[]>([null, null, null, null, null])
  const [activeThumb, setActiveThumb] = useState<number | null>(null)
  const safePoints = useMemo(() => normalizeMarginPoints(points, max), [points, max])
  const [minPoint, maxPoint] = [safePoints[0], safePoints[4]]
  const span = Math.max(maxPoint - minPoint, 1)

  function emit(values: number[]) {
    onChange(values.map(String))
  }

  function updatePoint(i: number, value: number) {
    emit(adjustMarginPoint(safePoints, i, value, max))
  }

  function scheduleIdleCommit() {
    if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    idleCommitTimerRef.current = setTimeout(() => onCommit?.(), 180)
  }

  const valueFromClientX = useCallback((clientX: number) => {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect) return minPoint
    const pct = clamp((clientX - rect.left) / rect.width, 0, 1)
    return Math.round(minPoint + pct * span)
  }, [minPoint, span])

  const handlePointerMove = useCallback((event: PointerEvent) => {
    const i = draggingRef.current
    if (i == null) return
    updatePoint(i, valueFromClientX(event.clientX))
  }, [safePoints, valueFromClientX])

  useEffect(() => {
    const handlePointerUp = () => {
      if (draggingRef.current != null) onCommit?.()
      draggingRef.current = null
      setActiveThumb(null)
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp)
    window.addEventListener('pointercancel', handlePointerUp)
    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
      window.removeEventListener('pointercancel', handlePointerUp)
    }
  }, [handlePointerMove, onCommit])

  useEffect(() => {
    const handlers: { el: HTMLDivElement; fn: (e: WheelEvent) => void }[] = []
    for (let i = 0; i < 5; i++) {
      const el = endpointDivRefs.current[i]
      if (!el) continue
      const fn = (e: WheelEvent) => {
        e.preventDefault()
        updatePoint(i, safePoints[i] + (e.deltaY < 0 ? 5 : -5))
        scheduleIdleCommit()
      }
      el.addEventListener('wheel', fn, { passive: false })
      handlers.push({ el, fn })
    }
    return () => {
      handlers.forEach(({ el, fn }) => el.removeEventListener('wheel', fn))
      if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    }
  }, [safePoints, onCommit])

  function startDrag(i: number, event: ReactPointerEvent<HTMLButtonElement>) {
    event.preventDefault()
    draggingRef.current = i
    setActiveThumb(i)
    updatePoint(i, valueFromClientX(event.clientX))
  }

  function pctFromValue(value: number) {
    return `${((value - minPoint) / span) * 100}%`
  }

  function renderFlanked(i: number) {
    const p = safePoints[i]
    return (
      <div key={i} className="margin-point-endpoint" ref={el => { endpointDivRefs.current[i] = el }}>
        <button type="button" className="margin-point-step" aria-label="Decrease" onClick={() => { updatePoint(i, p - 1); scheduleIdleCommit() }}>-</button>
        <input
          className="margin-point-number-input"
          ref={el => { inputRefs.current[i] = el }}
          type="number"
          min="0"
          max={max}
          step="1"
          value={p}
          aria-label={`Margin point ${i + 1} value`}
          onChange={e => updatePoint(i, parsePoint(e.target.value, p))}
          onBlur={onCommit}
        />
        <button type="button" className="margin-point-step" aria-label="Increase" onClick={() => { updatePoint(i, p + 1); scheduleIdleCommit() }}>+</button>
      </div>
    )
  }

  return (
    <div className="margin-point-slider">
      <div className="margin-point-endpoints-row">
        {renderFlanked(0)}
        {renderFlanked(2)}
        {renderFlanked(4)}
      </div>
      <div className="margin-point-range-stack" ref={trackRef}>
        <div className="margin-point-track" />
        <div
          className="margin-point-track-active"
          style={{ left: pctFromValue(safePoints[1]), right: `${100 - parseFloat(pctFromValue(safePoints[3]))}%` }}
        />
        {[1, 2, 3].map(i => (
          <button
            key={i}
            type="button"
            className={`margin-point-thumb${activeThumb === i ? ' active' : ''}`}
            style={{ left: pctFromValue(safePoints[i]) }}
            aria-label={`Margin point ${i + 1}`}
            onPointerDown={e => startDrag(i, e)}
          />
        ))}
      </div>
      {showComfortPoints && (
        <div className="margin-point-values margin-point-comfort-values">
          {[1, 3].map(i => renderFlanked(i))}
        </div>
      )}
    </div>
  )
})

const RebalanceStrategyBlock = React.memo(React.forwardRef<RebalanceStrategyBlockRef, Props>(function RebalanceStrategyBlock(
  { idx, value, onChange, sliderMax = 150, onSavedRefresh },
  ref,
) {
  const [local, setLocal] = useState<RebalStrategyState>(value)
  const localRef = useRef(local)
  const prevValueRef = useRef(value)
  const s = local

  const commit = useCallback((next = localRef.current) => {
    prevValueRef.current = next
    onChange(next)
  }, [onChange])

  const updateLocal = useCallback((next: RebalStrategyState, sync = false) => {
    localRef.current = next
    setLocal(next)
    if (sync) commit(next)
  }, [commit])

  const set = useCallback((patch: Partial<RebalStrategyState>) => {
    updateLocal({ ...localRef.current, ...patch })
  }, [updateLocal])

  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      localRef.current = value
      setLocal(value)
    }
  }, [value])

  useImperativeHandle(ref, () => ({
    getValue: () => localRef.current,
    commit: () => commit(localRef.current),
  }), [commit])

  const valueMarginPoints = useMemo(
    () => DEFAULT_POINTS.map((def, i) => s.marginPoints?.[i] ?? def),
    [s.marginPoints],
  )
  const [draftMarginPoints, setDraftMarginPoints] = useState(valueMarginPoints)
  const draftMarginPointsRef = useRef(draftMarginPoints)

  useEffect(() => {
    if (!samePoints(valueMarginPoints, draftMarginPointsRef.current)) {
      draftMarginPointsRef.current = valueMarginPoints
      setDraftMarginPoints(valueMarginPoints)
    }
  }, [valueMarginPoints])

  const commitMarginPoints = useCallback((points = draftMarginPointsRef.current) => {
    commit({ ...localRef.current, marginPoints: points, marginRatio: points[2] })
  }, [commit])

  const handleMarginChange = useCallback((points: string[]) => {
    draftMarginPointsRef.current = points
    setDraftMarginPoints(points)
    updateLocal({ ...localRef.current, marginPoints: points, marginRatio: points[2] })
  }, [updateLocal])
  const marginPoints = draftMarginPoints
  const midMarginPoint = marginPoints[2] ?? DEFAULT_POINTS[2]
  const marginRebalanceRestoreMargin = s.marginRebalanceRestoreMargin ?? midMarginPoint
  const cashflowScalingMargin = s.cashflowScalingMargin ?? marginValueFromLegacyPoint(marginPoints, s.cashflowScalingPointIndex, 1)
  const buyLowRestoreMargin = s.buyLowRestoreMargin ?? marginValueFromLegacyPoint(marginPoints, s.buyLowRestorePointIndex)
  const sellHighRestoreMargin = s.sellHighRestoreMargin ?? marginValueFromLegacyPoint(marginPoints, s.sellHighRestorePointIndex)
  const [saveMsg, setSaveMsg] = useState('')
  const [dragOver, setDragOver] = useState(false)

  const updateDipSurgeScope = useCallback((
    key: 'buyTheDip' | 'sellOnSurge',
    scope: keyof DipSurgeScopeState,
    value: DipSurgeState | null,
  ) => {
    set({ [key]: { ...localRef.current[key], [scope]: value } } as Partial<RebalStrategyState>)
  }, [set])

  async function handleSave(overwrite: boolean) {
    commit()
    const current = localRef.current
    const name = current.label.trim(); if (!name) return
    if (overwrite) await fetch(`/api/rebalance-strategy/savedStrategies?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
    const res = await fetch('/api/rebalance-strategy/savedStrategies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config: strategyStateToSavedConfig(current) }),
    })
    if (res.ok) { onSavedRefresh?.(); setSaveMsg('Saved!'); setTimeout(() => setSaveMsg(''), 1500) }
  }

  function handleDragOver(e: React.DragEvent) {
    if (e.dataTransfer.types.includes('application/x-strategy-chip')) {
      e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; setDragOver(true)
    }
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault(); setDragOver(false)
    if (e.dataTransfer.types.includes('application/x-strategy-chip')) {
      const { name, config } = JSON.parse(e.dataTransfer.getData('application/x-strategy-chip'))
      updateLocal(savedConfigToStrategyState(config, name))
    }
  }

  return (
    <div
      className={`portfolio-block${dragOver ? ' drag-over' : ''}`}
      onDragOver={handleDragOver}
      onDragLeave={() => setDragOver(false)}
      onDrop={handleDrop}
    >
      <div className="block-header">
        <input
          className="block-label-input"
          type="text"
          placeholder={`Strategy ${idx + 1}`}
          value={s.label}
          onChange={e => set({ label: e.target.value })}
          onBlur={() => commit()}
        />
        <button
          type="button"
          className="overwrite-portfolio-btn save-portfolio-btn"
          disabled={!s.label.trim()}
          onClick={() => handleSave(true)}
        >
          {saveMsg || 'Save'}
        </button>
        <button
          type="button"
          className="save-portfolio-btn"
          disabled={!s.label.trim()}
          onClick={() => handleSave(false)}
        >
          Save New
        </button>
      </div>

      <details open className="strategy-subsection strategy-margin-section">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>Margin</summary>
        <div className="strategy-section-body">
        <div className="strategy-row">
          <label>Margin Points %</label>
          <MarginPointSlider
            points={marginPoints}
            max={sliderMax}
            showComfortPoints
            onChange={handleMarginChange}
            onCommit={commitMarginPoints}
          />
        </div>
        <div className="strategy-row">
          <label>Spread %</label>
          <input type="number" min="0" step="0.1" value={s.marginSpread}
            onChange={e => set({ marginSpread: e.target.value })}
            onBlur={() => commit()}
            style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Rebalance Period</label>
          <select value={s.portfolioRebalancePeriod ?? 'INHERIT'} onChange={e => set({ portfolioRebalancePeriod: e.target.value })}>
            {REBALANCE_PERIOD_OVERRIDE_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <div className="strategy-row">
          <label>Use Comfort Zone</label>
          <input
            type="checkbox"
            checked={s.portfolioRebalanceUseComfortZone ?? true}
            onChange={e => set({ portfolioRebalanceUseComfortZone: e.target.checked })}
          />
        </div>
        <div className="strategy-row">
          <label>Buy Cooldown After SH</label>
          <input type="number" min="0" step="1" value={s.buyCooldownAfterSellHighDays ?? '10'}
            onChange={e => set({ buyCooldownAfterSellHighDays: e.target.value })}
            onBlur={() => commit()}
            style={{ width: '5rem' }} />
        </div>
        <div className="strategy-row">
          <label>Sell Cooldown After BL</label>
          <input type="number" min="0" step="1" value={s.sellCooldownAfterBuyLowDays ?? '10'}
            onChange={e => set({ sellCooldownAfterBuyLowDays: e.target.value })}
            onBlur={() => commit()}
            style={{ width: '5rem' }} />
        </div>
        </div>
      </details>

      <details open={s.marginRebalanceEnabled ?? true} className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          Margin Rebalance
          <label className="dip-surge-toggle" onClick={e => e.stopPropagation()}>
            <input type="checkbox" checked={s.marginRebalanceEnabled ?? true}
              onChange={e => set({ marginRebalanceEnabled: e.target.checked })} />
            {' '}Enable
          </label>
        </summary>
        {(s.marginRebalanceEnabled ?? true) && (
          <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Margin Rebalance</label>
              <select value={s.rebalancePeriod} onChange={e => set({ rebalancePeriod: e.target.value })}>
                {REBALANCE_PERIOD_OVERRIDE_OPTIONS.filter(o => o.value !== 'INHERIT').map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Trade Direction</label>
              <select value={s.marginRebalanceTradeDirection ?? 'BOTH'}
                onChange={e => set({ marginRebalanceTradeDirection: e.target.value as RebalStrategyState['marginRebalanceTradeDirection'] })}>
                {MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.rebalanceAllocStrategy ?? 'PROPORTIONAL'}
                onChange={e => set({ rebalanceAllocStrategy: e.target.value })}>
                {REBALANCE_MARGIN_MODE_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            {(s.marginRebalanceTradeDirection ?? 'BOTH') === 'BOTH' ? (
              <div className="strategy-row">
                <label>Use Comfort Zone</label>
                <input
                  type="checkbox"
                  checked={s.useComfortZone ?? true}
                  onChange={e => set({ useComfortZone: e.target.checked })}
                />
              </div>
            ) : (
              <div className="strategy-row">
                <label>Restore To</label>
                <MarginPercentInput
                  value={marginRebalanceRestoreMargin}
                  placeholder={midMarginPoint}
                  max={sliderMax}
                  ariaLabel="Margin rebalance restore margin"
                  onChange={value => set({ marginRebalanceRestoreMargin: value })}
                  onCommit={() => commit()}
                />
              </div>
            )}
          </div>
        )}
      </details>

      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>Cashflow</summary>
        <div className="strategy-section-body">
          <div className="strategy-row">
            <label>Cashflow Immediate Invest %</label>
            <input type="number" min="0" max="100" step="5" value={s.cashflowImmediateInvestPct}
              onChange={e => set({ cashflowImmediateInvestPct: e.target.value })}
              onBlur={() => commit()}
              style={{ width: '5rem' }} />
          </div>
          <div className="strategy-row">
            <label>Cashflow Scaling</label>
            <MarginPercentInput
              value={cashflowScalingMargin}
              placeholder={midMarginPoint}
              max={sliderMax}
              ariaLabel="Cashflow scaling margin"
              onChange={value => set({ cashflowScalingMargin: value, cashflowScalingPointIndex: '' })}
              onCommit={() => commit()}
            />
          </div>
        </div>
      </details>

      <details open={s.buyLowEnabled} className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          Buy on Low Margin
          <label className="dip-surge-toggle" onClick={e => e.stopPropagation()}>
            <input type="checkbox" checked={s.buyLowEnabled}
              onChange={e => set({ buyLowEnabled: e.target.checked })} />
            {' '}Enable
          </label>
        </summary>
        {s.buyLowEnabled && (
          <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.buyLowAllocStrategy}
                onChange={e => set({ buyLowAllocStrategy: e.target.value })}>
                {REBALANCE_MARGIN_MODE_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Restore To</label>
              <MarginPercentInput
                value={buyLowRestoreMargin}
                placeholder={midMarginPoint}
                max={sliderMax}
                ariaLabel="Buy low restore margin"
                onChange={value => set({ buyLowRestoreMargin: value, buyLowRestorePointIndex: '' })}
                onCommit={() => commit()}
              />
            </div>
          </div>
        )}
      </details>

      <details open={s.sellHighEnabled} className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          Sell on High Margin
          <label className="dip-surge-toggle" onClick={e => e.stopPropagation()}>
            <input type="checkbox" checked={s.sellHighEnabled}
              onChange={e => set({ sellHighEnabled: e.target.checked })} />
            {' '}Enable
          </label>
        </summary>
        {s.sellHighEnabled && (
          <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.sellHighAllocStrategy}
                onChange={e => set({ sellHighAllocStrategy: e.target.value })}>
                {REBALANCE_MARGIN_MODE_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Restore To</label>
              <MarginPercentInput
                value={sellHighRestoreMargin}
                placeholder={midMarginPoint}
                max={sliderMax}
                ariaLabel="Sell high restore margin"
                onChange={value => set({ sellHighRestoreMargin: value, sellHighRestorePointIndex: '' })}
                onCommit={() => commit()}
              />
            </div>
          </div>
        )}
      </details>

      <div className="strategy-subsection">
        <DipSurgeSection
          direction="buy"
          title="Buy the Dip - Daily-Rebalanced Base Reference"
          scope="BASE_PORTFOLIO"
          value={s.buyTheDip.basePortfolio}
          onChange={(v: DipSurgeState | null) => updateDipSurgeScope('buyTheDip', 'basePortfolio', v)}
          marginPoints={marginPoints}
          sliderMax={sliderMax}
        />
        <DipSurgeSection
          direction="buy"
          title="Buy the Dip - Individual Stocks"
          scope="INDIVIDUAL_STOCK"
          value={s.buyTheDip.individualStock}
          onChange={(v: DipSurgeState | null) => updateDipSurgeScope('buyTheDip', 'individualStock', v)}
          marginPoints={marginPoints}
          sliderMax={sliderMax}
        />
      </div>
      <div className="strategy-subsection">
        <DipSurgeSection
          direction="sell"
          title="Sell on Surge - Daily-Rebalanced Base Reference"
          scope="BASE_PORTFOLIO"
          value={s.sellOnSurge.basePortfolio}
          onChange={(v: DipSurgeState | null) => updateDipSurgeScope('sellOnSurge', 'basePortfolio', v)}
          marginPoints={marginPoints}
          sliderMax={sliderMax}
        />
        <DipSurgeSection
          direction="sell"
          title="Sell on Surge - Individual Stocks"
          scope="INDIVIDUAL_STOCK"
          value={s.sellOnSurge.individualStock}
          onChange={(v: DipSurgeState | null) => updateDipSurgeScope('sellOnSurge', 'individualStock', v)}
          marginPoints={marginPoints}
          sliderMax={sliderMax}
        />
      </div>
    </div>
  )
}))

export default RebalanceStrategyBlock
