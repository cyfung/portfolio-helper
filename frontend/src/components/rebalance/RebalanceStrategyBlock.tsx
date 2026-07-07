import React, { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react'
import { useAllocStrategyOptions } from '@/hooks/useAllocStrategyOptions'
import {
  DipSurgeScopeState,
  DipSurgeState,
  DrawdownMarginOverrideState,
  RebalStrategyState,
  VmTimingMrState,
  emptyDipSurge,
  emptyDrawdownMarginOverride,
  emptyDrawdownMarginTrigger,
  emptyVmTimingMr,
  normalizeStrategySpreadInput,
  savedConfigToStrategyState,
  strategyStateToSavedConfig,
} from '@/types/rebalanceStrategy'
import DerivedSubStrategiesSection from './DerivedSubStrategiesSection'
import DipSurgeSection from './DipSurgeSection'
import DrawdownMarginTriggerSection from './DrawdownMarginTriggerSection'
import {
  CashflowSection,
  MarginSection,
  OptionalStrategySectionPicker,
  StrategyHeader,
} from './RebalanceStrategyPrimarySections'
import {
  DrawdownMarginOverrideSection,
  MarginRebalanceSection,
  VmTimingMrSection,
} from './RebalanceStrategyMarginSections'
import { BuyLowSection, SellHighSection } from './RebalanceStrategyTriggerSections'
import {
  DEFAULT_POINTS,
  OPTIONAL_STRATEGY_SECTIONS,
  OptionalStrategySectionKey,
  isOptionalStrategySectionEnabled,
  marginValueFromLegacyPoint,
  samePoints,
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

function safeDipSurgeScopes(value: DipSurgeScopeState | null | undefined): DipSurgeScopeState {
  return {
    basePortfolio: value?.basePortfolio ?? null,
    individualStock: value?.individualStock ?? null,
  }
}

function nextWithOptionalSection(current: RebalStrategyState, key: OptionalStrategySectionKey): Partial<RebalStrategyState> {
  const buyTheDip = safeDipSurgeScopes(current.buyTheDip)
  const sellOnSurge = safeDipSurgeScopes(current.sellOnSurge)

  switch (key) {
    case 'marginRebalance':
      return { marginRebalanceEnabled: true }
    case 'drawdownMarginOverride':
      return {
        marginRebalanceEnabled: true,
        drawdownMarginOverride: { ...(current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()), enabled: true },
      }
    case 'vmTimingMr':
      return { vmTimingMr: { ...(current.vmTimingMr ?? emptyVmTimingMr()), enabled: true } }
    case 'buyLow':
      return { buyLowEnabled: true }
    case 'sellHigh':
      return { sellHighEnabled: true }
    case 'drawdownBuyOnLowMargin':
      return {
        drawdownBuyOnLowMargin: { ...(current.drawdownBuyOnLowMargin ?? emptyDrawdownMarginTrigger('buy')), enabled: true },
      }
    case 'buyTheDipPortfolio':
      return { buyTheDip: { ...buyTheDip, basePortfolio: buyTheDip.basePortfolio ?? emptyDipSurge('BASE_PORTFOLIO') } }
    case 'buyTheDipIndividual':
      return { buyTheDip: { ...buyTheDip, individualStock: buyTheDip.individualStock ?? emptyDipSurge('INDIVIDUAL_STOCK') } }
    case 'sellOnSurgePortfolio':
      return { sellOnSurge: { ...sellOnSurge, basePortfolio: sellOnSurge.basePortfolio ?? emptyDipSurge('BASE_PORTFOLIO') } }
    case 'sellOnSurgeIndividual':
      return { sellOnSurge: { ...sellOnSurge, individualStock: sellOnSurge.individualStock ?? emptyDipSurge('INDIVIDUAL_STOCK') } }
  }
}

function nextWithoutOptionalSection(current: RebalStrategyState, key: OptionalStrategySectionKey): Partial<RebalStrategyState> {
  const buyTheDip = safeDipSurgeScopes(current.buyTheDip)
  const sellOnSurge = safeDipSurgeScopes(current.sellOnSurge)

  switch (key) {
    case 'marginRebalance':
      return {
        marginRebalanceEnabled: false,
        drawdownMarginOverride: { ...(current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()), enabled: false },
      }
    case 'drawdownMarginOverride':
      return { drawdownMarginOverride: { ...(current.drawdownMarginOverride ?? emptyDrawdownMarginOverride()), enabled: false } }
    case 'vmTimingMr':
      return { vmTimingMr: { ...(current.vmTimingMr ?? emptyVmTimingMr()), enabled: false } }
    case 'buyLow':
      return { buyLowEnabled: false }
    case 'sellHigh':
      return { sellHighEnabled: false }
    case 'drawdownBuyOnLowMargin':
      return {
        drawdownBuyOnLowMargin: { ...(current.drawdownBuyOnLowMargin ?? emptyDrawdownMarginTrigger('buy')), enabled: false },
      }
    case 'buyTheDipPortfolio':
      return { buyTheDip: { ...buyTheDip, basePortfolio: null } }
    case 'buyTheDipIndividual':
      return { buyTheDip: { ...buyTheDip, individualStock: null } }
    case 'sellOnSurgePortfolio':
      return { sellOnSurge: { ...sellOnSurge, basePortfolio: null } }
    case 'sellOnSurgeIndividual':
      return { sellOnSurge: { ...sellOnSurge, individualStock: null } }
  }
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
  const cashflowScalingMargin = s.cashflowScalingMargin ?? marginValueFromLegacyPoint(marginPoints, s.cashflowScalingPointIndex, 1)
  const buyLowTriggerMargin = s.buyLowTriggerMargin ?? marginValueFromLegacyPoint(marginPoints, s.buyLowTriggerPointIndex)
  const buyLowRestoreMargin = s.buyLowRestoreMargin ?? marginValueFromLegacyPoint(marginPoints, s.buyLowRestorePointIndex)
  const sellHighTriggerMargin = s.sellHighTriggerMargin ?? marginValueFromLegacyPoint(marginPoints, s.sellHighTriggerPointIndex)
  const sellHighRestoreMargin = s.sellHighRestoreMargin ?? marginValueFromLegacyPoint(marginPoints, s.sellHighRestorePointIndex)
  const buyTheDip = safeDipSurgeScopes(s.buyTheDip)
  const sellOnSurge = safeDipSurgeScopes(s.sellOnSurge)
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
    set({ [key]: { ...safeDipSurgeScopes(localRef.current[key]), [scope]: value } } as Partial<RebalStrategyState>)
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
    set(nextWithOptionalSection(localRef.current, key))
  }, [set])

  const removeOptionalStrategySection = useCallback((key: OptionalStrategySectionKey) => {
    set(nextWithoutOptionalSection(localRef.current, key))
  }, [set])

  async function handleSave(overwrite: boolean) {
    const current = normalizeStrategySpreadInput(localRef.current)
    if (current !== localRef.current) updateLocal(current)
    commit(current)
    const name = current.label.trim()
    if (!name) return
    if (overwrite) await fetch(`/api/rebalance-strategy/savedStrategies?name=${encodeURIComponent(name)}`, { method: 'DELETE' })
    const res = await fetch('/api/rebalance-strategy/savedStrategies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config: strategyStateToSavedConfig(current) }),
    })
    if (res.ok) {
      onSavedRefresh?.()
      setSaveMsg('Saved!')
      setTimeout(() => setSaveMsg(''), 1500)
    }
  }

  function handleDragOver(e: React.DragEvent) {
    if (e.dataTransfer.types.includes('application/x-strategy-chip')) {
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
      setDragOver(true)
    }
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(false)
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
      <StrategyHeader
        idx={idx}
        label={s.label}
        saveMsg={saveMsg}
        onLabelChange={label => set({ label })}
        onCommit={() => commit()}
        onSave={handleSave}
      />

      <MarginSection
        strategy={s}
        marginPoints={marginPoints}
        sliderMax={sliderMax}
        spreadTouched={spreadTouched}
        onSet={set}
        onSpreadTouched={() => setSpreadTouched(true)}
        onCommit={() => commit()}
        onMarginChange={handleMarginChange}
        onMarginCommit={commitMarginPoints}
      />

      <OptionalStrategySectionPicker sections={availableOptionalSections} onAdd={addOptionalStrategySection} />

      {(s.marginRebalanceEnabled ?? true) && (
        <MarginRebalanceSection
          strategy={s}
          allocOptions={allocOptions}
          marginRebalanceRestoreMargin={marginRebalanceRestoreMargin}
          midMarginPoint={midMarginPoint}
          sliderMax={sliderMax}
          onSet={set}
          onRemove={removeOptionalStrategySection}
          onCommit={() => commit()}
        />
      )}

      {drawdownMarginOverride.enabled && (s.marginRebalanceEnabled ?? true) && (
        <DrawdownMarginOverrideSection
          value={drawdownMarginOverride}
          allocOptions={allocOptions}
          sliderMax={sliderMax}
          onChange={updateDrawdownMarginOverride}
          onRemove={removeOptionalStrategySection}
          onCommit={() => commit()}
        />
      )}

      {vmTimingMr.enabled && (
        <VmTimingMrSection
          value={vmTimingMr}
          allocOptions={allocOptions}
          sliderMax={sliderMax}
          onChange={updateVmTimingMr}
          onRemove={removeOptionalStrategySection}
          onCommit={() => commit()}
        />
      )}

      <CashflowSection
        strategy={s}
        cashflowScalingMargin={cashflowScalingMargin}
        midMarginPoint={midMarginPoint}
        sliderMax={sliderMax}
        onSet={set}
        onCommit={() => commit()}
      />

      {s.buyLowEnabled && (
        <BuyLowSection
          strategy={s}
          allocOptions={allocOptions}
          triggerMargin={buyLowTriggerMargin}
          restoreMargin={buyLowRestoreMargin}
          marginPoints={marginPoints}
          midMarginPoint={midMarginPoint}
          sliderMax={sliderMax}
          onSet={set}
          onRemove={removeOptionalStrategySection}
          onCommit={() => commit()}
        />
      )}

      {s.sellHighEnabled && (
        <SellHighSection
          strategy={s}
          allocOptions={allocOptions}
          triggerMargin={sellHighTriggerMargin}
          restoreMargin={sellHighRestoreMargin}
          marginPoints={marginPoints}
          midMarginPoint={midMarginPoint}
          sliderMax={sliderMax}
          onSet={set}
          onRemove={removeOptionalStrategySection}
          onCommit={() => commit()}
        />
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

      {(buyTheDip.basePortfolio || buyTheDip.individualStock) && (
        <div className="strategy-subsection">
          {buyTheDip.basePortfolio && (
            <DipSurgeSection
              direction="buy"
              title="Buy the Dip - Portfolio Trigger"
              scope="BASE_PORTFOLIO"
              value={buyTheDip.basePortfolio}
              onChange={(value: DipSurgeState | null) => updateDipSurgeScope('buyTheDip', 'basePortfolio', value)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
          {buyTheDip.individualStock && (
            <DipSurgeSection
              direction="buy"
              title="Buy the Dip - Individual Stocks"
              scope="INDIVIDUAL_STOCK"
              value={buyTheDip.individualStock}
              onChange={(value: DipSurgeState | null) => updateDipSurgeScope('buyTheDip', 'individualStock', value)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
        </div>
      )}

      {(sellOnSurge.basePortfolio || sellOnSurge.individualStock) && (
        <div className="strategy-subsection">
          {sellOnSurge.basePortfolio && (
            <DipSurgeSection
              direction="sell"
              title="Sell on Surge - Portfolio Trigger"
              scope="BASE_PORTFOLIO"
              value={sellOnSurge.basePortfolio}
              onChange={(value: DipSurgeState | null) => updateDipSurgeScope('sellOnSurge', 'basePortfolio', value)}
              marginPoints={marginPoints}
              sliderMax={sliderMax}
              removable
            />
          )}
          {sellOnSurge.individualStock && (
            <DipSurgeSection
              direction="sell"
              title="Sell on Surge - Individual Stocks"
              scope="INDIVIDUAL_STOCK"
              value={sellOnSurge.individualStock}
              onChange={(value: DipSurgeState | null) => updateDipSurgeScope('sellOnSurge', 'individualStock', value)}
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
