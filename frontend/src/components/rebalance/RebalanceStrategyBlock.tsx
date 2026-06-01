import React, { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react'
import {
  RebalStrategyState,
  DipSurgeState,
  DipSurgeScopeState,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS,
  MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS,
  PORTFOLIO_TRIGGER_SOURCE_OPTIONS,
  DrawdownMarginOverrideState,
  emptyDipSurge,
  emptyDrawdownMarginOverride,
  emptyDrawdownMarginTrigger,
  emptyVmTimingMr,
  normalizeStrategySpreadInput,
  strategyStateToSavedConfig,
  savedConfigToStrategyState,
  VmTimingMrState,
} from '@/types/rebalanceStrategy'
import { isValidNumberInput } from '@/lib/numberInputs'
import DipSurgeSection from './DipSurgeSection'
import DerivedSubStrategiesSection from './DerivedSubStrategiesSection'
import DrawdownMarginTriggerSection from './DrawdownMarginTriggerSection'
import { useAllocStrategyOptions } from '@/hooks/useAllocStrategyOptions'
import { MarginPercentInput, MarginPointSlider } from './RebalanceStrategyControls'
import {
  DEFAULT_POINTS,
  OPTIONAL_STRATEGY_SECTIONS,
  isOptionalStrategySectionEnabled,
  keepSectionOpen,
  marginValueFromLegacyPoint,
  samePoints,
  type OptionalStrategySectionKey,
} from './RebalanceStrategyControlUtils'

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

const RebalanceStrategyBlock = React.memo(React.forwardRef<RebalanceStrategyBlockRef, Props>(function RebalanceStrategyBlock(
  { idx, value, onChange, sliderMax = 150, onSavedRefresh },
  ref,
) {
  const allocOptions = useAllocStrategyOptions(false)
  const [local, setLocal] = useState<RebalStrategyState>(value)
  const [spreadTouched, setSpreadTouched] = useState(false)
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
      setSpreadTouched(false)
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
  const drawdownMarginOverride = s.drawdownMarginOverride ?? emptyDrawdownMarginOverride()
  const drawdownBuyOnLowMargin = s.drawdownBuyOnLowMargin ?? emptyDrawdownMarginTrigger('buy')
  const vmTimingMr = s.vmTimingMr ?? emptyVmTimingMr()
  const drawdownOverrideTargetMargin = drawdownMarginOverride.targetMargin || '95'
  const cashflowScalingMargin = s.cashflowScalingMargin ?? marginValueFromLegacyPoint(marginPoints, s.cashflowScalingPointIndex, 1)
  const buyLowTriggerMargin = s.buyLowTriggerMargin ?? marginValueFromLegacyPoint(marginPoints, s.buyLowTriggerPointIndex)
  const buyLowRestoreMargin = s.buyLowRestoreMargin ?? marginValueFromLegacyPoint(marginPoints, s.buyLowRestorePointIndex)
  const sellHighTriggerMargin = s.sellHighTriggerMargin ?? marginValueFromLegacyPoint(marginPoints, s.sellHighTriggerPointIndex)
  const sellHighRestoreMargin = s.sellHighRestoreMargin ?? marginValueFromLegacyPoint(marginPoints, s.sellHighRestorePointIndex)
  const [saveMsg, setSaveMsg] = useState('')
  const [dragOver, setDragOver] = useState(false)
  const availableOptionalSections = useMemo(
    () => OPTIONAL_STRATEGY_SECTIONS.filter(section => {
      if (section.key === 'drawdownMarginOverride' && !(s.marginRebalanceEnabled ?? true)) return false
      return !isOptionalStrategySectionEnabled(s, section.key)
    }),
    [s],
  )

  const updateDipSurgeScope = useCallback((
    key: 'buyTheDip' | 'sellOnSurge',
    scope: keyof DipSurgeScopeState,
    value: DipSurgeState | null,
  ) => {
    set({ [key]: { ...localRef.current[key], [scope]: value } } as Partial<RebalStrategyState>)
  }, [set])

  const updateDrawdownMarginOverride = useCallback((patch: Partial<DrawdownMarginOverrideState>) => {
    const current = localRef.current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()
    const next = { ...current, ...patch }
    if (next.portfolioSource !== 'REFERENCE_PORTFOLIO') next.referenceTicker = ''
    set({ drawdownMarginOverride: next })
  }, [set])

  const updateVmTimingMr = useCallback((patch: Partial<VmTimingMrState>) => {
    const current = localRef.current.vmTimingMr ?? emptyVmTimingMr()
    set({ vmTimingMr: { ...current, ...patch } })
  }, [set])

  const addOptionalStrategySection = useCallback((key: OptionalStrategySectionKey) => {
    const current = localRef.current
    switch (key) {
      case 'marginRebalance':
        set({ marginRebalanceEnabled: true })
        break
      case 'drawdownMarginOverride':
        set({
          marginRebalanceEnabled: true,
          drawdownMarginOverride: { ...(current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()), enabled: true },
        })
        break
      case 'vmTimingMr':
        set({ vmTimingMr: { ...(current.vmTimingMr ?? emptyVmTimingMr()), enabled: true } })
        break
      case 'buyLow':
        set({ buyLowEnabled: true })
        break
      case 'sellHigh':
        set({ sellHighEnabled: true })
        break
      case 'drawdownBuyOnLowMargin':
        set({
          drawdownBuyOnLowMargin: { ...(current.drawdownBuyOnLowMargin ?? emptyDrawdownMarginTrigger('buy')), enabled: true },
        })
        break
      case 'buyTheDipPortfolio':
        set({ buyTheDip: { ...current.buyTheDip, basePortfolio: current.buyTheDip.basePortfolio ?? emptyDipSurge('BASE_PORTFOLIO') } })
        break
      case 'buyTheDipIndividual':
        set({ buyTheDip: { ...current.buyTheDip, individualStock: current.buyTheDip.individualStock ?? emptyDipSurge('INDIVIDUAL_STOCK') } })
        break
      case 'sellOnSurgePortfolio':
        set({ sellOnSurge: { ...current.sellOnSurge, basePortfolio: current.sellOnSurge.basePortfolio ?? emptyDipSurge('BASE_PORTFOLIO') } })
        break
      case 'sellOnSurgeIndividual':
        set({ sellOnSurge: { ...current.sellOnSurge, individualStock: current.sellOnSurge.individualStock ?? emptyDipSurge('INDIVIDUAL_STOCK') } })
        break
    }
  }, [set])

  const removeOptionalStrategySection = useCallback((key: OptionalStrategySectionKey) => {
    const current = localRef.current
    switch (key) {
      case 'marginRebalance':
        set({
          marginRebalanceEnabled: false,
          drawdownMarginOverride: { ...(current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()), enabled: false },
        })
        break
      case 'drawdownMarginOverride':
        set({ drawdownMarginOverride: { ...(current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()), enabled: false } })
        break
      case 'vmTimingMr':
        set({ vmTimingMr: { ...(current.vmTimingMr ?? emptyVmTimingMr()), enabled: false } })
        break
      case 'buyLow':
        set({ buyLowEnabled: false })
        break
      case 'sellHigh':
        set({ sellHighEnabled: false })
        break
      case 'drawdownBuyOnLowMargin':
        set({
          drawdownBuyOnLowMargin: { ...(current.drawdownBuyOnLowMargin ?? emptyDrawdownMarginTrigger('buy')), enabled: false },
        })
        break
      case 'buyTheDipPortfolio':
        set({ buyTheDip: { ...current.buyTheDip, basePortfolio: null } })
        break
      case 'buyTheDipIndividual':
        set({ buyTheDip: { ...current.buyTheDip, individualStock: null } })
        break
      case 'sellOnSurgePortfolio':
        set({ sellOnSurge: { ...current.sellOnSurge, basePortfolio: null } })
        break
      case 'sellOnSurgeIndividual':
        set({ sellOnSurge: { ...current.sellOnSurge, individualStock: null } })
        break
    }
  }, [set])

  async function handleSave(overwrite: boolean) {
    const current = normalizeStrategySpreadInput(localRef.current)
    if (current !== localRef.current) updateLocal(current)
    commit(current)
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

  function renderRemoveSectionButton(key: OptionalStrategySectionKey, label: string) {
    return (
      <button
        type="button"
        className="btn-remove strategy-section-remove"
        title={`Remove ${label}`}
        aria-label={`Remove ${label}`}
        onClick={e => {
          e.stopPropagation()
          removeOptionalStrategySection(key)
        }}
      >
        x
      </button>
    )
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
            className={spreadTouched && !isValidNumberInput(s.marginSpread, { min: 0 }) ? 'input-error' : undefined}
            onChange={e => set({ marginSpread: e.target.value })}
            onBlur={() => { setSpreadTouched(true); commit() }}
            aria-invalid={spreadTouched && !isValidNumberInput(s.marginSpread, { min: 0 })}
            title={spreadTouched && !isValidNumberInput(s.marginSpread, { min: 0 }) ? 'Enter a valid non-negative spread percent' : undefined}
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

      {availableOptionalSections.length > 0 && (
        <div className="strategy-subsection">
          <div className="strategy-row strategy-section-picker">
            <label>Add Section</label>
            <select
              value=""
              aria-label="Add strategy section"
              onChange={e => {
                const key = e.target.value as OptionalStrategySectionKey
                if (key) addOptionalStrategySection(key)
              }}
            >
              <option value="" disabled>Choose section...</option>
              {availableOptionalSections.map(section => (
                <option key={section.key} value={section.key}>{section.label}</option>
              ))}
            </select>
          </div>
        </div>
      )}

      {(s.marginRebalanceEnabled ?? true) && (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          Margin Rebalance
          {renderRemoveSectionButton('marginRebalance', 'Margin Rebalance')}
        </summary>
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
                {allocOptions.map(o => (
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
      </details>
      )}

      {drawdownMarginOverride.enabled && (s.marginRebalanceEnabled ?? true) && (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          Drawdown MR Override
          {renderRemoveSectionButton('drawdownMarginOverride', 'Drawdown MR Override')}
        </summary>
        <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Trigger Source</label>
              <select
                value={drawdownMarginOverride.portfolioSource ?? 'REFERENCE_PORTFOLIO'}
                onChange={e => updateDrawdownMarginOverride({
                  portfolioSource: e.target.value,
                  referenceTicker: e.target.value === 'REFERENCE_PORTFOLIO' ? (drawdownMarginOverride.referenceTicker ?? '') : '',
                })}
              >
                {PORTFOLIO_TRIGGER_SOURCE_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            {(drawdownMarginOverride.portfolioSource ?? 'REFERENCE_PORTFOLIO') === 'REFERENCE_PORTFOLIO' && (
              <div className="strategy-row">
                <label>Reference Ticker</label>
                <input
                  type="text"
                  value={drawdownMarginOverride.referenceTicker ?? ''}
                  placeholder="Portfolio"
                  aria-label="Drawdown MR override reference ticker"
                  onChange={e => updateDrawdownMarginOverride({ referenceTicker: e.target.value.toUpperCase() })}
                  onBlur={() => commit()}
                />
              </div>
            )}
            <div className="strategy-row">
              <label>Enter DD %</label>
              <input
                type="number"
                min="0"
                step="1"
                value={drawdownMarginOverride.enterDrawdownPct}
                onChange={e => updateDrawdownMarginOverride({ enterDrawdownPct: e.target.value })}
                onBlur={() => commit()}
                style={{ width: '5rem' }}
              />
            </div>
            <div className="strategy-row">
              <label>Exit DD %</label>
              <input
                type="number"
                min="0"
                step="1"
                value={drawdownMarginOverride.exitDrawdownPct}
                onChange={e => updateDrawdownMarginOverride({ exitDrawdownPct: e.target.value })}
                onBlur={() => commit()}
                style={{ width: '5rem' }}
              />
            </div>
            <div className="strategy-row">
              <label>Override MR</label>
              <select
                value={drawdownMarginOverride.rebalancePeriod}
                onChange={e => updateDrawdownMarginOverride({ rebalancePeriod: e.target.value })}
              >
                {REBALANCE_PERIOD_OVERRIDE_OPTIONS.filter(o => o.value !== 'INHERIT').map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Rebalance On Enter</label>
              <input
                type="checkbox"
                checked={drawdownMarginOverride.rebalanceOnEnter ?? true}
                onChange={e => updateDrawdownMarginOverride({ rebalanceOnEnter: e.target.checked })}
              />
            </div>
            <div className="strategy-row">
              <label>Target Margin</label>
              <MarginPercentInput
                value={drawdownOverrideTargetMargin}
                placeholder="95"
                max={sliderMax}
                ariaLabel="Drawdown MR override target margin"
                onChange={value => updateDrawdownMarginOverride({ targetMargin: value })}
                onCommit={() => commit()}
              />
            </div>
            <div className="strategy-row">
              <label>Trade Direction</label>
              <select
                value={drawdownMarginOverride.tradeDirection ?? 'BOTH'}
                onChange={e => updateDrawdownMarginOverride({ tradeDirection: e.target.value as RebalStrategyState['marginRebalanceTradeDirection'] })}
              >
                {MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            {(drawdownMarginOverride.tradeDirection ?? 'BOTH') === 'BOTH' ? (
              <>
                <div className="strategy-row">
                  <label>Buy Alloc Strategy</label>
                  <select
                    value={drawdownMarginOverride.buyAllocStrategy ?? drawdownMarginOverride.allocStrategy ?? 'PROPORTIONAL'}
                    onChange={e => updateDrawdownMarginOverride({ buyAllocStrategy: e.target.value })}
                  >
                    {allocOptions.map(o => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                </div>
                <div className="strategy-row">
                  <label>Sell Alloc Strategy</label>
                  <select
                    value={drawdownMarginOverride.sellAllocStrategy ?? drawdownMarginOverride.allocStrategy ?? 'PROPORTIONAL'}
                    onChange={e => updateDrawdownMarginOverride({ sellAllocStrategy: e.target.value })}
                  >
                    {allocOptions.map(o => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                </div>
              </>
            ) : (
              <div className="strategy-row">
                <label>Alloc Strategy</label>
                <select
                  value={
                    (drawdownMarginOverride.tradeDirection ?? 'BOTH') === 'SELL_ONLY'
                      ? (drawdownMarginOverride.sellAllocStrategy ?? drawdownMarginOverride.allocStrategy ?? 'PROPORTIONAL')
                      : (drawdownMarginOverride.buyAllocStrategy ?? drawdownMarginOverride.allocStrategy ?? 'PROPORTIONAL')
                  }
                  onChange={e => updateDrawdownMarginOverride(
                    (drawdownMarginOverride.tradeDirection ?? 'BOTH') === 'SELL_ONLY'
                      ? { sellAllocStrategy: e.target.value, allocStrategy: e.target.value }
                      : { buyAllocStrategy: e.target.value, allocStrategy: e.target.value },
                  )}
                >
                  {allocOptions.map(o => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </div>
            )}
          </div>
      </details>
      )}

      {vmTimingMr.enabled && (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          VM-timing-MR
          {renderRemoveSectionButton('vmTimingMr', 'VM-timing-MR')}
        </summary>
        <div className="strategy-section-body">
            <div className="strategy-row">
              <label>CAPE Source</label>
              <select
                value={vmTimingMr.capeSource}
                onChange={e => updateVmTimingMr({ capeSource: e.target.value as VmTimingMrState['capeSource'] })}
              >
                <option value="WORLD">World CAPE</option>
                <option value="US">US CAPE</option>
              </select>
            </div>
            <div className="strategy-row">
              <label>Lower Margin %</label>
              <input
                type="number"
                min="-100"
                max={sliderMax}
                step="5"
                value={vmTimingMr.lowerMargin}
                onChange={e => updateVmTimingMr({ lowerMargin: e.target.value })}
                onBlur={() => commit()}
                style={{ width: '5rem' }}
              />
            </div>
            <div className="strategy-row">
              <label>Upper Margin %</label>
              <input
                type="number"
                min="-100"
                max={sliderMax}
                step="5"
                value={vmTimingMr.upperMargin}
                onChange={e => updateVmTimingMr({ upperMargin: e.target.value })}
                onBlur={() => commit()}
                style={{ width: '5rem' }}
              />
            </div>
            <div className="strategy-row">
              <label>Momentum Months</label>
              <input
                type="number"
                min="1"
                max="120"
                step="1"
                value={vmTimingMr.momentumLookbackMonths}
                onChange={e => updateVmTimingMr({ momentumLookbackMonths: e.target.value })}
                onBlur={() => commit()}
                style={{ width: '5rem' }}
              />
            </div>
            <div className="strategy-row">
              <label>Momentum Ref</label>
              <select
                value={vmTimingMr.momentumSource ?? 'REFERENCE_PORTFOLIO'}
                onChange={e => updateVmTimingMr({
                  momentumSource: e.target.value,
                  momentumReferenceTicker: e.target.value === 'REFERENCE_PORTFOLIO' ? (vmTimingMr.momentumReferenceTicker ?? '') : '',
                })}
              >
                {PORTFOLIO_TRIGGER_SOURCE_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            {(vmTimingMr.momentumSource ?? 'REFERENCE_PORTFOLIO') === 'REFERENCE_PORTFOLIO' && (
              <div className="strategy-row">
                <label>Reference Ticker</label>
                <input
                  type="text"
                  value={vmTimingMr.momentumReferenceTicker ?? ''}
                  placeholder="Portfolio"
                  aria-label="VM timing momentum reference ticker"
                  onChange={e => updateVmTimingMr({ momentumReferenceTicker: e.target.value.toUpperCase() })}
                  onBlur={() => commit()}
                />
              </div>
            )}
            <div className="strategy-row">
              <label>Rebalance Period</label>
              <select
                value={vmTimingMr.rebalancePeriod}
                onChange={e => updateVmTimingMr({ rebalancePeriod: e.target.value })}
              >
                {REBALANCE_PERIOD_OVERRIDE_OPTIONS.filter(o => o.value !== 'INHERIT').map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select
                value={vmTimingMr.allocStrategy}
                onChange={e => updateVmTimingMr({ allocStrategy: e.target.value })}
              >
                {allocOptions.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
          </div>
      </details>
      )}

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

      {s.buyLowEnabled && (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          BL
          {renderRemoveSectionButton('buyLow', 'BL')}
        </summary>
        <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Trigger At</label>
              <MarginPercentInput
                value={buyLowTriggerMargin}
                placeholder={marginPoints[0] ?? DEFAULT_POINTS[0]}
                max={sliderMax}
                ariaLabel="BL trigger margin"
                onChange={value => set({ buyLowTriggerMargin: value, buyLowTriggerPointIndex: '' })}
                onCommit={() => commit()}
              />
            </div>
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.buyLowAllocStrategy}
                onChange={e => set({ buyLowAllocStrategy: e.target.value })}>
                {allocOptions.map(o => (
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
      </details>
      )}

      {s.sellHighEnabled && (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          SH
          {renderRemoveSectionButton('sellHigh', 'SH')}
        </summary>
        <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Trigger At</label>
              <MarginPercentInput
                value={sellHighTriggerMargin}
                placeholder={marginPoints[4] ?? DEFAULT_POINTS[4]}
                max={sliderMax}
                ariaLabel="SH trigger margin"
                onChange={value => set({ sellHighTriggerMargin: value, sellHighTriggerPointIndex: '' })}
                onCommit={() => commit()}
              />
            </div>
            <div className="strategy-row">
              <label>Alloc Strategy</label>
              <select value={s.sellHighAllocStrategy}
                onChange={e => set({ sellHighAllocStrategy: e.target.value })}>
                {allocOptions.map(o => (
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
      </details>
      )}

      {drawdownBuyOnLowMargin.enabled && (
        <DrawdownMarginTriggerSection
          direction="buy"
          title="BL on Drawdown"
          value={drawdownBuyOnLowMargin}
          triggerPlaceholder={marginPoints[0] ?? DEFAULT_POINTS[0]}
          midMarginPoint={midMarginPoint}
          sliderMax={sliderMax}
          allocOptions={allocOptions}
          onChange={value => set({ drawdownBuyOnLowMargin: value })}
          onRemove={() => removeOptionalStrategySection('drawdownBuyOnLowMargin')}
          onCommit={() => commit()}
        />
      )}

      {(s.buyTheDip.basePortfolio || s.buyTheDip.individualStock) && (
        <div className="strategy-subsection">
          {s.buyTheDip.basePortfolio && (
            <DipSurgeSection
              direction="buy"
              title="Buy the Dip - Portfolio Trigger"
              scope="BASE_PORTFOLIO"
              value={s.buyTheDip.basePortfolio}
              onChange={(v: DipSurgeState | null) => updateDipSurgeScope('buyTheDip', 'basePortfolio', v)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
          {s.buyTheDip.individualStock && (
            <DipSurgeSection
              direction="buy"
              title="Buy the Dip - Individual Stocks"
              scope="INDIVIDUAL_STOCK"
              value={s.buyTheDip.individualStock}
              onChange={(v: DipSurgeState | null) => updateDipSurgeScope('buyTheDip', 'individualStock', v)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
        </div>
      )}
      {(s.sellOnSurge.basePortfolio || s.sellOnSurge.individualStock) && (
        <div className="strategy-subsection">
          {s.sellOnSurge.basePortfolio && (
            <DipSurgeSection
              direction="sell"
              title="Sell on Surge - Portfolio Trigger"
              scope="BASE_PORTFOLIO"
              value={s.sellOnSurge.basePortfolio}
              onChange={(v: DipSurgeState | null) => updateDipSurgeScope('sellOnSurge', 'basePortfolio', v)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
          {s.sellOnSurge.individualStock && (
            <DipSurgeSection
              direction="sell"
              title="Sell on Surge - Individual Stocks"
              scope="INDIVIDUAL_STOCK"
              value={s.sellOnSurge.individualStock}
              onChange={(v: DipSurgeState | null) => updateDipSurgeScope('sellOnSurge', 'individualStock', v)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
        </div>
      )}
      <DerivedSubStrategiesSection
        value={s.derivedSubStrategies ?? []}
        marginPoints={marginPoints}
        midMarginPoint={midMarginPoint}
        sliderMax={sliderMax}
        allocOptions={allocOptions}
        onChange={set}
        onCommit={() => commit()}
      />
    </div>
  )
}))

export default RebalanceStrategyBlock
