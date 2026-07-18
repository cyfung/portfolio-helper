import React, { useCallback, useEffect, useMemo, useRef } from 'react'
import { adjustMarginPoint, clamp, normalizeMarginPoints, parsePoint } from './RebalanceStrategyControlUtils'
import { useMarginWheelAdjustEnabled, useUnlockMarginWheelAdjust } from './MarginWheelAdjustContext'

export const MarginPercentInput = React.memo(function MarginPercentInput({
  value, placeholder, max, ariaLabel, compact = false, invalid = false, onChange, onCommit,
}: {
  value: string
  placeholder: string
  max: number
  ariaLabel: string
  compact?: boolean
  invalid?: boolean
  onChange: (value: string) => void
  onCommit?: () => void
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const idleCommitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const wheelAdjustEnabled = useMarginWheelAdjustEnabled()
  const unlockMarginWheelAdjust = useUnlockMarginWheelAdjust()

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

  const handleLockedInputWheel = useCallback((e: React.WheelEvent<HTMLInputElement>) => {
    if (!wheelAdjustEnabled) e.currentTarget.blur()
  }, [wheelAdjustEnabled])

  const stepBy = useCallback((delta: number) => {
    emit(parseBase() + delta)
    scheduleCommit()
  }, [emit, parseBase, scheduleCommit])

  useEffect(() => {
    const el = wrapRef.current
    if (!el || !wheelAdjustEnabled) {
      return () => {
        if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
      }
    }
    const handleWheel = (e: WheelEvent) => {
      e.preventDefault()
      stepBy(e.deltaY < 0 ? 5 : -5)
    }
    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => {
      el.removeEventListener('wheel', handleWheel)
      if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    }
  }, [stepBy, wheelAdjustEnabled])

  return (
    <div className={`margin-point-endpoint margin-percent-input${compact ? ' margin-percent-input-compact' : ''}`} ref={wrapRef}>
      {!compact && (
        <button type="button" className="margin-point-step" tabIndex={-1} aria-label="Decrease" onClick={() => { unlockMarginWheelAdjust(); stepBy(-1) }}>-</button>
      )}
      <input
        className={`margin-point-number-input${invalid ? ' input-error' : ''}`}
        type="number"
        min="0"
        max={max}
        step="1"
        value={value}
        placeholder={placeholder}
        aria-label={ariaLabel}
        onFocus={unlockMarginWheelAdjust}
        onClick={unlockMarginWheelAdjust}
        onWheel={handleLockedInputWheel}
        onChange={e => onChange(e.target.value)}
        onBlur={onCommit}
        aria-invalid={invalid || undefined}
      />
      {!compact && (
        <button type="button" className="margin-point-step" tabIndex={-1} aria-label="Increase" onClick={() => { unlockMarginWheelAdjust(); stepBy(1) }}>+</button>
      )}
    </div>
  )
})

export const MarginPointSlider = React.memo(function MarginPointSlider({
  points, max, showComfortPoints, onChange, onCommit,
}: { points: string[]; max: number; showComfortPoints: boolean; onChange: (points: string[]) => void; onCommit?: (points: string[]) => void }) {
  const idleCommitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inputRefs = useRef<(HTMLInputElement | null)[]>([null, null, null, null, null])
  const endpointDivRefs = useRef<(HTMLDivElement | null)[]>([null, null, null, null, null])
  const wheelAdjustEnabled = useMarginWheelAdjustEnabled()
  const unlockMarginWheelAdjust = useUnlockMarginWheelAdjust()
  const safePoints = useMemo(() => normalizeMarginPoints(points, max), [points, max])
  const safePointsRef = useRef(safePoints)

  useEffect(() => {
    safePointsRef.current = safePoints
  }, [safePoints])

  const emit = useCallback((values: number[]) => {
    onChange(values.map(String))
  }, [onChange])

  const updatePoint = useCallback((i: number, value: number) => {
    const next = adjustMarginPoint(safePointsRef.current, i, value, max)
    safePointsRef.current = next
    emit(next)
  }, [emit, max])

  const commitCurrentPoints = useCallback(() => {
    onCommit?.(safePointsRef.current.map(String))
  }, [onCommit])

  const scheduleIdleCommit = useCallback(() => {
    if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    idleCommitTimerRef.current = setTimeout(commitCurrentPoints, 180)
  }, [commitCurrentPoints])

  const handleLockedInputWheel = useCallback((e: React.WheelEvent<HTMLInputElement>) => {
    if (!wheelAdjustEnabled) e.currentTarget.blur()
  }, [wheelAdjustEnabled])

  useEffect(() => {
    if (!wheelAdjustEnabled) {
      return () => {
        if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
      }
    }
    const handlers: { el: HTMLDivElement; fn: (e: WheelEvent) => void }[] = []
    for (let i = 0; i < 5; i++) {
      const el = endpointDivRefs.current[i]
      if (!el) continue
      const fn = (e: WheelEvent) => {
        e.preventDefault()
        updatePoint(i, safePointsRef.current[i] + (e.deltaY < 0 ? 5 : -5))
        scheduleIdleCommit()
      }
      el.addEventListener('wheel', fn, { passive: false })
      handlers.push({ el, fn })
    }
    return () => {
      handlers.forEach(({ el, fn }) => el.removeEventListener('wheel', fn))
      if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    }
  }, [scheduleIdleCommit, updatePoint, wheelAdjustEnabled])

  function renderFlanked(i: number) {
    const p = safePoints[i]
    return (
      <div key={i} className="margin-point-endpoint" ref={el => { endpointDivRefs.current[i] = el }}>
        <button type="button" className="margin-point-step" tabIndex={-1} aria-label="Decrease" onClick={() => { unlockMarginWheelAdjust(); updatePoint(i, p - 1); scheduleIdleCommit() }}>-</button>
        <input
          className="margin-point-number-input"
          ref={el => { inputRefs.current[i] = el }}
          type="number"
          min="0"
          max={max}
          step="1"
          value={p}
          aria-label={`Margin point ${i + 1} value`}
          onFocus={unlockMarginWheelAdjust}
          onClick={unlockMarginWheelAdjust}
          onWheel={handleLockedInputWheel}
          onChange={e => updatePoint(i, parsePoint(e.target.value, p))}
          onBlur={commitCurrentPoints}
        />
        <button type="button" className="margin-point-step" tabIndex={-1} aria-label="Increase" onClick={() => { unlockMarginWheelAdjust(); updatePoint(i, p + 1); scheduleIdleCommit() }}>+</button>
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
      {showComfortPoints && (
        <div className="margin-point-values margin-point-comfort-values">
          {[1, 3].map(i => renderFlanked(i))}
        </div>
      )}
    </div>
  )
})
