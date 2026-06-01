import React, { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { adjustMarginPoint, clamp, normalizeMarginPoints, parsePoint } from './RebalanceStrategyControlUtils'

export const MarginPercentInput = React.memo(function MarginPercentInput({
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

export const MarginPointSlider = React.memo(function MarginPointSlider({
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

  const emit = useCallback((values: number[]) => {
    onChange(values.map(String))
  }, [onChange])

  const updatePoint = useCallback((i: number, value: number) => {
    emit(adjustMarginPoint(safePoints, i, value, max))
  }, [emit, max, safePoints])

  const scheduleIdleCommit = useCallback(() => {
    if (idleCommitTimerRef.current) clearTimeout(idleCommitTimerRef.current)
    idleCommitTimerRef.current = setTimeout(() => onCommit?.(), 180)
  }, [onCommit])

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
  }, [updatePoint, valueFromClientX])

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
  }, [safePoints, scheduleIdleCommit, updatePoint])

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
