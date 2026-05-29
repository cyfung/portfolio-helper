import React, { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  RebalStrategyState,
  DipSurgeState,
  DipSurgeScopeState,
  REBALANCE_PERIOD_OVERRIDE_OPTIONS,
  MARGIN_REBALANCE_TRADE_DIRECTION_OPTIONS,
  PORTFOLIO_TRIGGER_SOURCE_OPTIONS,
  DrawdownMarginOverrideState,
  DrawdownMarginTriggerState,
  DrawdownMarginTriggerTierState,
  emptyDipSurge,
  emptyDrawdownMarginOverride,
  emptyDrawdownMarginTrigger,
  emptyDrawdownMarginTriggerTier,
  emptyDerivedTargetStep,
  emptyDerivedSubStrategy,
  emptyVmTimingMr,
  drawdownMarginTriggerIssues,
  normalizeStrategySpreadInput,
  strategyStateToSavedConfig,
  savedConfigToStrategyState,
  VmTimingMrState,
  DerivedSubStrategyState,
} from '@/types/rebalanceStrategy'
import { isValidNumberInput } from '@/lib/numberInputs'
import DipSurgeSection from './DipSurgeSection'
import { useAllocStrategyOptions } from '@/hooks/useAllocStrategyOptions'

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

type OptionalStrategySectionKey =
  | 'marginRebalance'
  | 'drawdownMarginOverride'
  | 'vmTimingMr'
  | 'buyLow'
  | 'sellHigh'
  | 'drawdownBuyOnLowMargin'
  | 'buyTheDipPortfolio'
  | 'buyTheDipIndividual'
  | 'sellOnSurgePortfolio'
  | 'sellOnSurgeIndividual'

const OPTIONAL_STRATEGY_SECTIONS: { key: OptionalStrategySectionKey; label: string }[] = [
  { key: 'marginRebalance', label: 'Margin Rebalance' },
  { key: 'drawdownMarginOverride', label: 'Drawdown MR Override' },
  { key: 'vmTimingMr', label: 'VM-timing-MR' },
  { key: 'buyLow', label: 'BL' },
  { key: 'sellHigh', label: 'SH' },
  { key: 'drawdownBuyOnLowMargin', label: 'BL on Drawdown' },
  { key: 'buyTheDipPortfolio', label: 'Buy the Dip - Portfolio Trigger' },
  { key: 'buyTheDipIndividual', label: 'Buy the Dip - Individual Stocks' },
  { key: 'sellOnSurgePortfolio', label: 'Sell on Surge - Portfolio Trigger' },
  { key: 'sellOnSurgeIndividual', label: 'Sell on Surge - Individual Stocks' },
]

function isOptionalStrategySectionEnabled(s: RebalStrategyState, key: OptionalStrategySectionKey) {
  switch (key) {
    case 'marginRebalance':
      return s.marginRebalanceEnabled ?? true
    case 'drawdownMarginOverride':
      return (s.marginRebalanceEnabled ?? true) && (s.drawdownMarginOverride?.enabled ?? false)
    case 'vmTimingMr':
      return s.vmTimingMr?.enabled ?? false
    case 'buyLow':
      return s.buyLowEnabled
    case 'sellHigh':
      return s.sellHighEnabled
    case 'drawdownBuyOnLowMargin':
      return s.drawdownBuyOnLowMargin?.enabled ?? false
    case 'buyTheDipPortfolio':
      return s.buyTheDip?.basePortfolio != null
    case 'buyTheDipIndividual':
      return s.buyTheDip?.individualStock != null
    case 'sellOnSurgePortfolio':
      return s.sellOnSurge?.basePortfolio != null
    case 'sellOnSurgeIndividual':
      return s.sellOnSurge?.individualStock != null
  }
}

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
  value, placeholder, max, ariaLabel, compact = false, onChange, onCommit,
}: {
  value: string
  placeholder: string
  max: number
  ariaLabel: string
  compact?: boolean
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
    <div className={`margin-point-endpoint margin-percent-input${compact ? ' margin-percent-input-compact' : ''}`} ref={wrapRef}>
      {!compact && (
        <button type="button" className="margin-point-step" aria-label="Decrease" onClick={() => stepBy(-1)}>-</button>
      )}
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
      {!compact && (
        <button type="button" className="margin-point-step" aria-label="Increase" onClick={() => stepBy(1)}>+</button>
      )}
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

  const updateDrawdownMarginTrigger = useCallback((
    key: 'drawdownBuyOnLowMargin',
    direction: 'buy' | 'sell',
    patch: Partial<DrawdownMarginTriggerState>,
  ) => {
    const current = localRef.current[key] ?? emptyDrawdownMarginTrigger(direction)
    const next = { ...current, ...patch }
    if (next.portfolioSource !== 'REFERENCE_PORTFOLIO') next.referenceTicker = ''
    set({ [key]: next } as Partial<RebalStrategyState>)
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

  const updateDerivedSubStrategy = useCallback((id: string, patch: Partial<DerivedSubStrategyState>) => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({
      derivedSubStrategies: items.map(item => item.id === id ? { ...item, ...patch } : item),
    })
  }, [set])

  const updateDerivedScale = useCallback((id: string, patch: Partial<DerivedSubStrategyState['scale']>) => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({
      derivedSubStrategies: items.map(item => item.id === id ? { ...item, scale: { ...item.scale, ...patch } } : item),
    })
  }, [set])

  const updateDerivedStep = useCallback((
    derivedId: string,
    stepId: string,
    patch: Partial<DerivedSubStrategyState['scale']['steps'][number]>,
  ) => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({
      derivedSubStrategies: items.map(item => item.id === derivedId
        ? {
          ...item,
          scale: {
            ...item.scale,
            steps: (item.scale.steps ?? []).map(step => step.id === stepId ? { ...step, ...patch } : step),
          },
        }
        : item),
    })
  }, [set])

  const addDerivedStep = useCallback((derivedId: string) => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({
      derivedSubStrategies: items.map(item => item.id === derivedId
        ? { ...item, scale: { ...item.scale, steps: [...(item.scale.steps ?? []), emptyDerivedTargetStep(item.scale.steps?.length ?? 0)] } }
        : item),
    })
  }, [set])

  const removeDerivedStep = useCallback((derivedId: string, stepId: string) => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({
      derivedSubStrategies: items.map(item => item.id === derivedId
        ? { ...item, scale: { ...item.scale, steps: (item.scale.steps ?? []).filter(step => step.id !== stepId) } }
        : item),
    })
  }, [set])

  const addDerivedSubStrategy = useCallback(() => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({ derivedSubStrategies: [...items, emptyDerivedSubStrategy(items.length)] })
  }, [set])

  const removeDerivedSubStrategy = useCallback((id: string) => {
    const items = localRef.current.derivedSubStrategies ?? []
    set({ derivedSubStrategies: items.filter(item => item.id !== id) })
  }, [set])

  function syncFirstDrawdownTier(
    state: DrawdownMarginTriggerState,
    tiers: DrawdownMarginTriggerTierState[],
    direction: 'buy' | 'sell',
  ): DrawdownMarginTriggerState {
    const first = tiers[0] ?? emptyDrawdownMarginTriggerTier(direction)
    return {
      ...state,
      enterDrawdownPct: first.enterDrawdownPct,
      exitDrawdownPct: first.exitDrawdownPct,
      triggerPointIndex: first.triggerPointIndex,
      triggerMargin: first.triggerMargin,
      allocStrategy: first.allocStrategy,
      restorePointIndex: first.restorePointIndex,
      restoreMargin: first.restoreMargin,
      tiers,
    }
  }

  const updateDrawdownMarginTriggerTier = useCallback((
    key: 'drawdownBuyOnLowMargin',
    direction: 'buy' | 'sell',
    tierId: string,
    patch: Partial<DrawdownMarginTriggerTierState>,
  ) => {
    const current = localRef.current[key] ?? emptyDrawdownMarginTrigger(direction)
    const tiers = (current.tiers?.length ? current.tiers : [emptyDrawdownMarginTriggerTier(direction)])
      .map(tier => tier.id === tierId ? { ...tier, ...patch } : tier)
    set({ [key]: syncFirstDrawdownTier(current, tiers, direction) } as Partial<RebalStrategyState>)
  }, [set])

  const addDrawdownMarginTriggerTier = useCallback((
    key: 'drawdownBuyOnLowMargin',
    direction: 'buy' | 'sell',
  ) => {
    const current = localRef.current[key] ?? emptyDrawdownMarginTrigger(direction)
    const tiers = current.tiers?.length ? current.tiers : [emptyDrawdownMarginTriggerTier(direction)]
    const prev = tiers[tiers.length - 1]
    const prevEnter = parseFloat(prev?.enterDrawdownPct ?? '')
    const prevExit = parseFloat(prev?.exitDrawdownPct ?? '')
    const nextEnter = Number.isFinite(prevEnter) ? prevEnter + 5 : 10
    const nextExit = Number.isFinite(prevExit) ? prevExit : Math.max(0, nextEnter - 5)
    const tier = {
      ...(prev ?? emptyDrawdownMarginTriggerTier(direction)),
      id: emptyDrawdownMarginTriggerTier(direction).id,
      enterDrawdownPct: String(nextEnter),
      exitDrawdownPct: String(nextExit),
    }
    const nextTiers = [...tiers, tier]
    set({ [key]: syncFirstDrawdownTier(current, nextTiers, direction) } as Partial<RebalStrategyState>)
  }, [set])

  const removeDrawdownMarginTriggerTier = useCallback((
    key: 'drawdownBuyOnLowMargin',
    direction: 'buy' | 'sell',
    tierId: string,
  ) => {
    const current = localRef.current[key] ?? emptyDrawdownMarginTrigger(direction)
    if ((current.tiers?.length ?? 0) <= 1) return
    const nextTiers = (current.tiers ?? []).filter(tier => tier.id !== tierId)
    set({ [key]: syncFirstDrawdownTier(current, nextTiers, direction) } as Partial<RebalStrategyState>)
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

  function renderDrawdownMarginTrigger(
    key: 'drawdownBuyOnLowMargin',
    direction: 'buy' | 'sell',
    title: string,
    value: DrawdownMarginTriggerState,
    triggerPlaceholder: string,
  ) {
    const tiers = value.tiers?.length ? value.tiers : [emptyDrawdownMarginTriggerTier(direction)]
    const issues = drawdownMarginTriggerIssues(value, direction, title)
    return (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>
          {title}
          {renderRemoveSectionButton(key, title)}
        </summary>
        <div className="strategy-section-body">
            <div className="strategy-row">
              <label>Reference</label>
              <select
                value={value.portfolioSource ?? 'REFERENCE_PORTFOLIO'}
                onChange={e => updateDrawdownMarginTrigger(key, direction, {
                  portfolioSource: e.target.value,
                  referenceTicker: e.target.value === 'REFERENCE_PORTFOLIO' ? (value.referenceTicker ?? '') : '',
                })}
              >
                {PORTFOLIO_TRIGGER_SOURCE_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            {(value.portfolioSource ?? 'REFERENCE_PORTFOLIO') === 'REFERENCE_PORTFOLIO' && (
              <div className="strategy-row">
                <label>Reference Ticker</label>
                <input
                  type="text"
                  value={value.referenceTicker ?? ''}
                  placeholder="Portfolio"
                  aria-label={`${title} reference ticker`}
                  onChange={e => updateDrawdownMarginTrigger(key, direction, { referenceTicker: e.target.value.toUpperCase() })}
                  onBlur={() => commit()}
                />
              </div>
            )}
            {direction === 'buy' && (
              <>
                <div className="strategy-row">
                  <label>Momentum Months</label>
                  <input
                    type="number"
                    min="1"
                    step="1"
                    value={value.momentumLookbackMonths ?? ''}
                    placeholder="Optional"
                    aria-label={`${title} momentum lookback months`}
                    onChange={e => updateDrawdownMarginTrigger(key, direction, { momentumLookbackMonths: e.target.value })}
                    onBlur={() => commit()}
                  />
                </div>
                <div className="strategy-row">
                  <label>Extend Exit Months</label>
                  <input
                    type="number"
                    min="0"
                    step="1"
                    value={value.exitExtensionMonths ?? ''}
                    placeholder="0"
                    aria-label={`${title} exit extension months`}
                    onChange={e => updateDrawdownMarginTrigger(key, direction, { exitExtensionMonths: e.target.value })}
                    onBlur={() => commit()}
                  />
                </div>
                <div className="strategy-row">
                  <label>Exit Target Margin</label>
                  <MarginPercentInput
                    value={value.exitTargetMargin ?? ''}
                    placeholder="Optional"
                    max={sliderMax}
                    compact
                    ariaLabel={`${title} exit target margin`}
                    onChange={margin => updateDrawdownMarginTrigger(key, direction, { exitTargetMargin: margin })}
                    onCommit={() => commit()}
                  />
                </div>
              </>
            )}
            <div className="drawdown-tier-table">
              <div className="drawdown-tier-header">
                <span title="Enter drawdown percent">DD In</span>
                <span title="Exit drawdown percent">Out</span>
                <span>Trigger</span>
                <span>Restore</span>
                <span>Alloc</span>
                <span />
              </div>
              {tiers.map(tier => (
                <div key={tier.id} className="drawdown-tier-row">
                  <input
                    type="number"
                    min="0"
                    step="1"
                    value={tier.enterDrawdownPct}
                    aria-label={`${title} tier enter drawdown`}
                    onChange={e => updateDrawdownMarginTriggerTier(key, direction, tier.id, { enterDrawdownPct: e.target.value })}
                    onBlur={() => commit()}
                  />
                  <input
                    type="number"
                    step="1"
                    value={tier.exitDrawdownPct}
                    aria-label={`${title} tier exit drawdown`}
                    onChange={e => updateDrawdownMarginTriggerTier(key, direction, tier.id, { exitDrawdownPct: e.target.value })}
                    onBlur={() => commit()}
                  />
                  <MarginPercentInput
                    value={tier.triggerMargin}
                    placeholder={triggerPlaceholder}
                    max={sliderMax}
                    compact
                    ariaLabel={`${title} tier trigger margin`}
                    onChange={margin => updateDrawdownMarginTriggerTier(key, direction, tier.id, { triggerMargin: margin, triggerPointIndex: '' })}
                    onCommit={() => commit()}
                  />
                  <MarginPercentInput
                    value={tier.restoreMargin}
                    placeholder={midMarginPoint}
                    max={sliderMax}
                    compact
                    ariaLabel={`${title} tier restore margin`}
                    onChange={margin => updateDrawdownMarginTriggerTier(key, direction, tier.id, { restoreMargin: margin, restorePointIndex: '' })}
                    onCommit={() => commit()}
                  />
                  <select
                    value={tier.allocStrategy ?? 'PROPORTIONAL'}
                    aria-label={`${title} tier allocation strategy`}
                    onChange={e => updateDrawdownMarginTriggerTier(key, direction, tier.id, { allocStrategy: e.target.value })}
                  >
                    {allocOptions.map(o => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                  <button
                    type="button"
                    className="remove-ticker-btn drawdown-tier-remove-btn"
                    title="Remove tier"
                    aria-label={`Remove ${title} tier`}
                    disabled={tiers.length <= 1}
                    onClick={() => removeDrawdownMarginTriggerTier(key, direction, tier.id)}
                  >
                    x
                  </button>
                </div>
              ))}
            </div>
            {issues.length > 0 && <div className="strategy-hint input-error-text">{issues[0]}</div>}
            <div className="strategy-row">
              <label />
              <button
                type="button"
                className="add-ticker-btn"
                onClick={() => addDrawdownMarginTriggerTier(key, direction)}
              >
                + Add Tier
              </button>
            </div>
          </div>
      </details>
    )
  }

  function renderDerivedSubStrategies() {
    return (
      <details open className="strategy-subsection">
        <summary className="strategy-section-title" onClick={keepSectionOpen}>Derived</summary>
        <div className="strategy-section-body">
          {(s.derivedSubStrategies ?? []).map((derived, derivedIdx) => (
            <div key={derived.id} className="strategy-derived-card">
              <div className="strategy-row">
                <label>Derived {derivedIdx + 1}</label>
                <input
                  type="text"
                  value={derived.label}
                  placeholder={`Derived ${derivedIdx + 1}`}
                  aria-label={`Derived strategy ${derivedIdx + 1} label`}
                  onChange={e => updateDerivedSubStrategy(derived.id, { label: e.target.value })}
                  onBlur={() => commit()}
                />
              </div>
              <div className="strategy-row">
                <label>Enable</label>
                <input
                  type="checkbox"
                  checked={derived.enabled}
                  onChange={e => updateDerivedSubStrategy(derived.id, { enabled: e.target.checked })}
                />
              </div>
              <div className="strategy-row">
                <label>Ref Margin</label>
                <select
                  value={derived.marginReferenceSource ?? 'BASE_STRATEGY'}
                  onChange={e => {
                    const source = e.target.value as DerivedSubStrategyState['marginReferenceSource']
                    updateDerivedSubStrategy(derived.id, {
                      marginReferenceSource: source,
                      ...(source === 'BASE_STRATEGY' ? { marginReferenceTicker: '' } : {}),
                    })
                  }}
                >
                  <option value="BASE_STRATEGY">Base Strategy</option>
                  <option value="STANDALONE_TICKER">Standalone Ticker</option>
                </select>
              </div>
              {(derived.marginReferenceSource ?? 'BASE_STRATEGY') === 'STANDALONE_TICKER' && (
                <div className="strategy-row">
                  <label>Ref Ticker</label>
                  <input
                    type="text"
                    value={derived.marginReferenceTicker ?? ''}
                    placeholder="SPY"
                    aria-label={`Derived strategy ${derivedIdx + 1} reference margin ticker`}
                    onChange={e => updateDerivedSubStrategy(derived.id, { marginReferenceTicker: e.target.value.toUpperCase() })}
                    onBlur={() => commit()}
                    style={{ width: '6rem' }}
                  />
                </div>
              )}
              <div className="strategy-row">
                <label>Scale Function</label>
                <select
                  value={derived.scale.function ?? 'SIGMOID'}
                  onChange={e => {
                    const fn = e.target.value as DerivedSubStrategyState['scale']['function']
                    const refHigh = parseFloat(derived.scale.referenceUpper)
                    const currentReset = parseFloat(derived.scale.stepBaseTarget ?? '')
                    const isHysteresis = fn === 'HYSTERESIS_STEP' || fn === 'HYSTERESIS_STAIRS' || fn === 'HYSTERESIS_STAIRS_REF_BL_RESET'
                    updateDerivedScale(derived.id, {
                      function: fn,
                      ...(isHysteresis && (!Number.isFinite(currentReset) || currentReset <= refHigh)
                        ? { stepBaseTarget: String(Math.min(sliderMax, Math.max(0, Math.round((Number.isFinite(refHigh) ? refHigh : 100) + 10)))) }
                        : {}),
                    })
                  }}
                >
                  <option value="SIGMOID">Sigmoid</option>
                  <option value="ADAPTIVE_LOW_SIGMOID">Adaptive Low Sigmoid</option>
                  <option value="LINEAR">Linear</option>
                  <option value="STEP">Step</option>
                  <option value="HYSTERESIS_STEP">Hysteresis Step</option>
                  <option value="HYSTERESIS_STAIRS">Hysteresis Stairs</option>
                  <option value="HYSTERESIS_STAIRS_REF_BL_RESET">Hysteresis Stairs Ref BL Reset</option>
                </select>
              </div>
              {!['STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') && (
                <>
                  <div className="strategy-row">
                    <label>Ref Low</label>
                    <MarginPercentInput
                      value={derived.scale.referenceLower}
                      placeholder="50"
                      max={sliderMax}
                      compact
                      ariaLabel={`Derived strategy ${derivedIdx + 1} reference lower margin`}
                      onChange={value => updateDerivedScale(derived.id, { referenceLower: value })}
                      onCommit={() => commit()}
                    />
                  </div>
                  <div className="strategy-row">
                    <label>Ref High</label>
                    <MarginPercentInput
                      value={derived.scale.referenceUpper}
                      placeholder="100"
                      max={sliderMax}
                      compact
                      ariaLabel={`Derived strategy ${derivedIdx + 1} reference upper margin`}
                      onChange={value => updateDerivedScale(derived.id, { referenceUpper: value })}
                      onCommit={() => commit()}
                    />
                  </div>
                  <div className="strategy-row">
                    <label>Target Low</label>
                    <MarginPercentInput
                      value={derived.scale.targetLower}
                      placeholder="30"
                      max={sliderMax}
                      compact
                      ariaLabel={`Derived strategy ${derivedIdx + 1} target lower margin`}
                      onChange={value => updateDerivedScale(derived.id, { targetLower: value })}
                      onCommit={() => commit()}
                    />
                  </div>
                  <div className="strategy-row">
                    <label>Target High</label>
                    <MarginPercentInput
                      value={derived.scale.targetUpper}
                      placeholder="100"
                      max={sliderMax}
                      compact
                      ariaLabel={`Derived strategy ${derivedIdx + 1} target upper margin`}
                      onChange={value => updateDerivedScale(derived.id, { targetUpper: value })}
                      onCommit={() => commit()}
                    />
                  </div>
                </>
              )}
              {(['HYSTERESIS_STEP', 'HYSTERESIS_STAIRS'].includes(derived.scale.function ?? 'SIGMOID')) && (
                <div className="strategy-row">
                  <label>Reset Above</label>
                  <MarginPercentInput
                    value={derived.scale.stepBaseTarget ?? ''}
                    placeholder="110"
                    max={sliderMax}
                    compact
                    ariaLabel={`Derived strategy ${derivedIdx + 1} reset threshold`}
                    onChange={value => updateDerivedScale(derived.id, { stepBaseTarget: value })}
                    onCommit={() => commit()}
                  />
                </div>
              )}
              {(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' && (
                <div className="strategy-row">
                  <label>Reset Target</label>
                  <MarginPercentInput
                    value={derived.scale.targetUpper}
                    placeholder="100"
                    max={sliderMax}
                    compact
                    ariaLabel={`Derived strategy ${derivedIdx + 1} reset target margin`}
                    onChange={value => updateDerivedScale(derived.id, { targetUpper: value })}
                    onCommit={() => commit()}
                  />
                </div>
              )}
              {(['SIGMOID', 'ADAPTIVE_LOW_SIGMOID'].includes(derived.scale.function ?? 'SIGMOID')) && (
                <div className="strategy-row">
                  <label>Sigmoid K</label>
                  <input
                    type="number"
                    min="0.1"
                    step="0.5"
                    value={derived.scale.sigmoidSteepness}
                    onChange={e => updateDerivedScale(derived.id, { sigmoidSteepness: e.target.value })}
                    onBlur={() => commit()}
                    style={{ width: '5rem' }}
                  />
                </div>
              )}
              {(['STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID')) && (
                <>
                  {(derived.scale.function ?? 'SIGMOID') === 'STEP' && (
                    <div className="strategy-row">
                      <label>Base Target</label>
                      <MarginPercentInput
                        value={derived.scale.stepBaseTarget ?? '50'}
                        placeholder={midMarginPoint}
                        max={sliderMax}
                        compact
                        ariaLabel={`Derived strategy ${derivedIdx + 1} base target margin`}
                        onChange={value => updateDerivedScale(derived.id, { stepBaseTarget: value })}
                        onCommit={() => commit()}
                      />
                    </div>
                  )}
                  {(derived.scale.steps?.length ? derived.scale.steps : [emptyDerivedTargetStep(0)]).map((step, stepIdx) => (
                    <React.Fragment key={step.id}>
                      <div className="strategy-row">
                        <label>{['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') ? `Stair ${stepIdx + 1} Below` : `Step ${stepIdx + 1} Above`}</label>
                        <MarginPercentInput
                          value={step.referenceMargin}
                          placeholder={String(60 + stepIdx * 10)}
                          max={sliderMax}
                          compact
                          ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} reference margin`}
                          onChange={value => updateDerivedStep(derived.id, step.id, { referenceMargin: value })}
                          onCommit={() => commit()}
                        />
                      </div>
                      <div className="strategy-row">
                        <label>{['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') ? `Stair ${stepIdx + 1} Target` : `Step ${stepIdx + 1} Target`}</label>
                        <MarginPercentInput
                          value={step.targetMargin}
                          placeholder={String(50 + stepIdx * 10)}
                          max={sliderMax}
                          compact
                          ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} target margin`}
                          onChange={value => updateDerivedStep(derived.id, step.id, { targetMargin: value })}
                          onCommit={() => commit()}
                        />
                      </div>
                      <div className="strategy-row">
                        <label />
                        <button
                          type="button"
                          className="remove-ticker-btn"
                          disabled={(derived.scale.steps?.length ?? 0) <= 1}
                          onClick={() => removeDerivedStep(derived.id, step.id)}
                        >
                          Remove Step
                        </button>
                      </div>
                    </React.Fragment>
                  ))}
                  <div className="strategy-row">
                    <label />
                    <button type="button" className="add-ticker-btn" onClick={() => addDerivedStep(derived.id)}>
                      + Add Step
                    </button>
                  </div>
                </>
              )}
              <div className="strategy-row">
                <label>BL Dev %</label>
                <input
                  type="number"
                  min="0"
                  step="0.5"
                  value={derived.buyDeviationPct ?? derived.absoluteDeviationPct}
                  onChange={e => updateDerivedSubStrategy(derived.id, {
                    buyDeviationPct: e.target.value,
                    absoluteDeviationPct: e.target.value,
                  })}
                  onBlur={() => commit()}
                  style={{ width: '5rem' }}
                />
              </div>
              <div className="strategy-row">
                <label>SH Dev %</label>
                <input
                  type="number"
                  min="0"
                  step="0.5"
                  value={derived.sellDeviationPct ?? derived.absoluteDeviationPct}
                  onChange={e => updateDerivedSubStrategy(derived.id, { sellDeviationPct: e.target.value })}
                  onBlur={() => commit()}
                  style={{ width: '5rem' }}
                />
              </div>
              <div className="strategy-row">
                <label>BL Alloc</label>
                <select
                  value={derived.buyAllocStrategy ?? derived.allocStrategy ?? 'PROPORTIONAL'}
                  onChange={e => updateDerivedSubStrategy(derived.id, { buyAllocStrategy: e.target.value, allocStrategy: e.target.value })}
                >
                  {allocOptions.map(o => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </div>
              <div className="strategy-row">
                <label>SH Alloc</label>
                <select
                  value={derived.sellAllocStrategy ?? derived.allocStrategy ?? 'PROPORTIONAL'}
                  onChange={e => updateDerivedSubStrategy(derived.id, { sellAllocStrategy: e.target.value })}
                >
                  {allocOptions.map(o => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </div>
              <div className="strategy-row">
                <label>Timeout Days</label>
                <input
                  type="number"
                  min="0"
                  step="1"
                  value={derived.timeoutDays ?? '10'}
                  onChange={e => updateDerivedSubStrategy(derived.id, { timeoutDays: e.target.value })}
                  onBlur={() => commit()}
                  style={{ width: '5rem' }}
                />
              </div>
              <div className="strategy-row">
                <label>Max Margin</label>
                <MarginPercentInput
                  value={derived.maxMargin ?? ''}
                  placeholder={marginPoints[4] ?? DEFAULT_POINTS[4]}
                  max={sliderMax}
                  compact
                  ariaLabel={`Derived strategy ${derivedIdx + 1} max margin`}
                  onChange={value => updateDerivedSubStrategy(derived.id, { maxMargin: value })}
                  onCommit={() => commit()}
                />
              </div>
              <div className="strategy-row">
                <label />
                <button
                  type="button"
                  className="remove-ticker-btn"
                  onClick={() => removeDerivedSubStrategy(derived.id)}
                >
                  Remove
                </button>
              </div>
            </div>
          ))}
          <div className="strategy-row">
            <label />
            <button type="button" className="add-ticker-btn" onClick={addDerivedSubStrategy}>
              + Add Derived
            </button>
          </div>
        </div>
      </details>
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

      {drawdownBuyOnLowMargin.enabled && renderDrawdownMarginTrigger(
        'drawdownBuyOnLowMargin',
        'buy',
        'BL on Drawdown',
        drawdownBuyOnLowMargin,
        marginPoints[0] ?? DEFAULT_POINTS[0],
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
      {renderDerivedSubStrategies()}
    </div>
  )
}))

export default RebalanceStrategyBlock
