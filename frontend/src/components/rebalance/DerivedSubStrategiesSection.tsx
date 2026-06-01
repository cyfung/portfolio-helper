import React, { useCallback } from 'react'
import type { AllocStrategyOption } from '@/lib/allocStrategies'
import {
  DerivedSubStrategyState,
  RebalStrategyState,
  emptyDerivedSubStrategy,
  emptyDerivedTargetStep,
} from '@/types/rebalanceStrategy'
import { DEFAULT_POINTS, keepSectionOpen } from './RebalanceStrategyControlUtils'
import { MarginPercentInput } from './RebalanceStrategyControls'

interface Props {
  value: DerivedSubStrategyState[]
  marginPoints: string[]
  midMarginPoint: string
  sliderMax: number
  allocOptions: AllocStrategyOption[]
  onChange: (patch: Partial<RebalStrategyState>) => void
  onCommit: () => void
}

const STEP_SCALE_FUNCTIONS = ['STEP', 'HYSTERESIS_STAIRS']
const HYSTERESIS_SCALE_FUNCTIONS = ['HYSTERESIS_STEP', 'HYSTERESIS_STAIRS']
const SIGMOID_SCALE_FUNCTIONS = ['SIGMOID', 'ADAPTIVE_LOW_SIGMOID']

export default function DerivedSubStrategiesSection({
  value,
  marginPoints,
  midMarginPoint,
  sliderMax,
  allocOptions,
  onChange,
  onCommit,
}: Props) {
  const updateSubStrategy = useCallback((id: string, patch: Partial<DerivedSubStrategyState>) => {
    onChange({
      derivedSubStrategies: value.map(item => item.id === id ? { ...item, ...patch } : item),
    })
  }, [onChange, value])

  const updateScale = useCallback((id: string, patch: Partial<DerivedSubStrategyState['scale']>) => {
    onChange({
      derivedSubStrategies: value.map(item => item.id === id ? { ...item, scale: { ...item.scale, ...patch } } : item),
    })
  }, [onChange, value])

  const updateStep = useCallback((
    derivedId: string,
    stepId: string,
    patch: Partial<DerivedSubStrategyState['scale']['steps'][number]>,
  ) => {
    onChange({
      derivedSubStrategies: value.map(item => item.id === derivedId
        ? {
          ...item,
          scale: {
            ...item.scale,
            steps: (item.scale.steps ?? []).map(step => step.id === stepId ? { ...step, ...patch } : step),
          },
        }
        : item),
    })
  }, [onChange, value])

  const addStep = useCallback((derivedId: string) => {
    onChange({
      derivedSubStrategies: value.map(item => item.id === derivedId
        ? { ...item, scale: { ...item.scale, steps: [...(item.scale.steps ?? []), emptyDerivedTargetStep(item.scale.steps?.length ?? 0)] } }
        : item),
    })
  }, [onChange, value])

  const removeStep = useCallback((derivedId: string, stepId: string) => {
    onChange({
      derivedSubStrategies: value.map(item => item.id === derivedId
        ? { ...item, scale: { ...item.scale, steps: (item.scale.steps ?? []).filter(step => step.id !== stepId) } }
        : item),
    })
  }, [onChange, value])

  const addSubStrategy = useCallback(() => {
    onChange({ derivedSubStrategies: [...value, emptyDerivedSubStrategy(value.length)] })
  }, [onChange, value])

  const removeSubStrategy = useCallback((id: string) => {
    onChange({ derivedSubStrategies: value.filter(item => item.id !== id) })
  }, [onChange, value])

  const handleScaleFunctionChange = (
    derived: DerivedSubStrategyState,
    fn: DerivedSubStrategyState['scale']['function'],
  ) => {
    const refHigh = parseFloat(derived.scale.referenceUpper)
    const currentReset = parseFloat(derived.scale.stepBaseTarget ?? '')
    const shouldResetHysteresisTarget =
      HYSTERESIS_SCALE_FUNCTIONS.includes(fn) && (!Number.isFinite(currentReset) || currentReset <= refHigh)
    updateScale(derived.id, {
      function: fn,
      ...(shouldResetHysteresisTarget
        ? { stepBaseTarget: String(Math.min(sliderMax, Math.max(0, Math.round((Number.isFinite(refHigh) ? refHigh : 100) + 10)))) }
        : {}),
    })
  }

  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>Derived</summary>
      <div className="strategy-section-body">
        {value.map((derived, derivedIdx) => (
          <div key={derived.id} className="strategy-derived-card">
            <div className="strategy-row">
              <label>Derived {derivedIdx + 1}</label>
              <input
                type="text"
                value={derived.label}
                placeholder={`Derived ${derivedIdx + 1}`}
                aria-label={`Derived strategy ${derivedIdx + 1} label`}
                onChange={e => updateSubStrategy(derived.id, { label: e.target.value })}
                onBlur={onCommit}
              />
            </div>
            <div className="strategy-row">
              <label>Enable</label>
              <input
                type="checkbox"
                checked={derived.enabled}
                onChange={e => updateSubStrategy(derived.id, { enabled: e.target.checked })}
              />
            </div>
            <div className="strategy-row">
              <label>Ref Margin</label>
              <select
                value={derived.marginReferenceSource ?? 'BASE_STRATEGY'}
                onChange={e => {
                  const source = e.target.value as DerivedSubStrategyState['marginReferenceSource']
                  updateSubStrategy(derived.id, {
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
                  className="strategy-ticker-input"
                  onChange={e => updateSubStrategy(derived.id, { marginReferenceTicker: e.target.value.toUpperCase() })}
                  onBlur={onCommit}
                />
              </div>
            )}
            <div className="strategy-row">
              <label>Scale Function</label>
              <select
                value={derived.scale.function ?? 'SIGMOID'}
                onChange={e => handleScaleFunctionChange(derived, e.target.value as DerivedSubStrategyState['scale']['function'])}
              >
                <option value="SIGMOID">Sigmoid</option>
                <option value="ADAPTIVE_LOW_SIGMOID">Adaptive Low Sigmoid</option>
                <option value="LINEAR">Linear</option>
                <option value="STEP">Step</option>
                <option value="HYSTERESIS_STEP">Hysteresis Step</option>
                <option value="HYSTERESIS_STAIRS">Hysteresis Stairs</option>
              </select>
            </div>
            {!STEP_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
              <>
                <DerivedMarginInput
                  label="Ref Low"
                  value={derived.scale.referenceLower}
                  placeholder="50"
                  sliderMax={sliderMax}
                  ariaLabel={`Derived strategy ${derivedIdx + 1} reference lower margin`}
                  onChange={margin => updateScale(derived.id, { referenceLower: margin })}
                  onCommit={onCommit}
                />
                <DerivedMarginInput
                  label="Ref High"
                  value={derived.scale.referenceUpper}
                  placeholder="100"
                  sliderMax={sliderMax}
                  ariaLabel={`Derived strategy ${derivedIdx + 1} reference upper margin`}
                  onChange={margin => updateScale(derived.id, { referenceUpper: margin })}
                  onCommit={onCommit}
                />
                <DerivedMarginInput
                  label="Target Low"
                  value={derived.scale.targetLower}
                  placeholder="30"
                  sliderMax={sliderMax}
                  ariaLabel={`Derived strategy ${derivedIdx + 1} target lower margin`}
                  onChange={margin => updateScale(derived.id, { targetLower: margin })}
                  onCommit={onCommit}
                />
                <DerivedMarginInput
                  label="Target High"
                  value={derived.scale.targetUpper}
                  placeholder="100"
                  sliderMax={sliderMax}
                  ariaLabel={`Derived strategy ${derivedIdx + 1} target upper margin`}
                  onChange={margin => updateScale(derived.id, { targetUpper: margin })}
                  onCommit={onCommit}
                />
              </>
            )}
            {HYSTERESIS_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
              <DerivedMarginInput
                label="Reset Above"
                value={derived.scale.stepBaseTarget ?? ''}
                placeholder="110"
                sliderMax={sliderMax}
                ariaLabel={`Derived strategy ${derivedIdx + 1} reset threshold`}
                onChange={margin => updateScale(derived.id, { stepBaseTarget: margin })}
                onCommit={onCommit}
              />
            )}
            {(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' && (
              <DerivedMarginInput
                label="Reset Target"
                value={derived.scale.targetUpper}
                placeholder="100"
                sliderMax={sliderMax}
                ariaLabel={`Derived strategy ${derivedIdx + 1} reset target margin`}
                onChange={margin => updateScale(derived.id, { targetUpper: margin })}
                onCommit={onCommit}
              />
            )}
            {SIGMOID_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
              <NumberInputRow
                label="Sigmoid K"
                value={derived.scale.sigmoidSteepness}
                min="0.1"
                step="0.5"
                onChange={next => updateScale(derived.id, { sigmoidSteepness: next })}
                onCommit={onCommit}
              />
            )}
            {STEP_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
              <>
                {(derived.scale.function ?? 'SIGMOID') === 'STEP' && (
                  <DerivedMarginInput
                    label="Base Target"
                    value={derived.scale.stepBaseTarget ?? '50'}
                    placeholder={midMarginPoint}
                    sliderMax={sliderMax}
                    ariaLabel={`Derived strategy ${derivedIdx + 1} base target margin`}
                    onChange={margin => updateScale(derived.id, { stepBaseTarget: margin })}
                    onCommit={onCommit}
                  />
                )}
                {(derived.scale.steps?.length ? derived.scale.steps : [emptyDerivedTargetStep(0)]).map((step, stepIdx) => (
                  <React.Fragment key={step.id}>
                    <DerivedMarginInput
                      label={(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' ? `Stair ${stepIdx + 1} Below` : `Step ${stepIdx + 1} Above`}
                      value={step.referenceMargin}
                      placeholder={String(60 + stepIdx * 10)}
                      sliderMax={sliderMax}
                      ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} reference margin`}
                      onChange={margin => updateStep(derived.id, step.id, { referenceMargin: margin })}
                      onCommit={onCommit}
                    />
                    <DerivedMarginInput
                      label={(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' ? `Stair ${stepIdx + 1} Target` : `Step ${stepIdx + 1} Target`}
                      value={step.targetMargin}
                      placeholder={String(50 + stepIdx * 10)}
                      sliderMax={sliderMax}
                      ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} target margin`}
                      onChange={margin => updateStep(derived.id, step.id, { targetMargin: margin })}
                      onCommit={onCommit}
                    />
                    <div className="strategy-row">
                      <label />
                      <button
                        type="button"
                        className="remove-ticker-btn"
                        disabled={(derived.scale.steps?.length ?? 0) <= 1}
                        onClick={() => removeStep(derived.id, step.id)}
                      >
                        Remove Step
                      </button>
                    </div>
                  </React.Fragment>
                ))}
                <div className="strategy-row">
                  <label />
                  <button type="button" className="add-ticker-btn" onClick={() => addStep(derived.id)}>
                    + Add Step
                  </button>
                </div>
              </>
            )}
            <NumberInputRow
              label="BL Dev %"
              value={derived.buyDeviationPct ?? derived.absoluteDeviationPct}
              min="0"
              step="0.5"
              onChange={next => updateSubStrategy(derived.id, { buyDeviationPct: next, absoluteDeviationPct: next })}
              onCommit={onCommit}
            />
            <NumberInputRow
              label="SH Dev %"
              value={derived.sellDeviationPct ?? derived.absoluteDeviationPct}
              min="0"
              step="0.5"
              onChange={next => updateSubStrategy(derived.id, { sellDeviationPct: next })}
              onCommit={onCommit}
            />
            <div className="strategy-row">
              <label>BL Alloc</label>
              <select
                value={derived.buyAllocStrategy ?? derived.allocStrategy ?? 'PROPORTIONAL'}
                onChange={e => updateSubStrategy(derived.id, { buyAllocStrategy: e.target.value, allocStrategy: e.target.value })}
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
                onChange={e => updateSubStrategy(derived.id, { sellAllocStrategy: e.target.value })}
              >
                {allocOptions.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <NumberInputRow
              label="Timeout Days"
              value={derived.timeoutDays ?? '10'}
              min="0"
              step="1"
              onChange={next => updateSubStrategy(derived.id, { timeoutDays: next })}
              onCommit={onCommit}
            />
            <DerivedMarginInput
              label="Max Margin"
              value={derived.maxMargin ?? ''}
              placeholder={marginPoints[4] ?? DEFAULT_POINTS[4]}
              sliderMax={sliderMax}
              ariaLabel={`Derived strategy ${derivedIdx + 1} max margin`}
              onChange={margin => updateSubStrategy(derived.id, { maxMargin: margin })}
              onCommit={onCommit}
            />
            <div className="strategy-row">
              <label />
              <button
                type="button"
                className="remove-ticker-btn"
                onClick={() => removeSubStrategy(derived.id)}
              >
                Remove
              </button>
            </div>
          </div>
        ))}
        <div className="strategy-row">
          <label />
          <button type="button" className="add-ticker-btn" onClick={addSubStrategy}>
            + Add Derived
          </button>
        </div>
      </div>
    </details>
  )
}

interface DerivedMarginInputProps {
  label: string
  value: string
  placeholder: string
  sliderMax: number
  ariaLabel: string
  onChange: (value: string) => void
  onCommit: () => void
}

function DerivedMarginInput({
  label,
  value,
  placeholder,
  sliderMax,
  ariaLabel,
  onChange,
  onCommit,
}: DerivedMarginInputProps) {
  return (
    <div className="strategy-row">
      <label>{label}</label>
      <MarginPercentInput
        value={value}
        placeholder={placeholder}
        max={sliderMax}
        compact
        ariaLabel={ariaLabel}
        onChange={onChange}
        onCommit={onCommit}
      />
    </div>
  )
}

interface NumberInputRowProps {
  label: string
  value: string
  min: string
  step: string
  onChange: (value: string) => void
  onCommit: () => void
}

function NumberInputRow({ label, value, min, step, onChange, onCommit }: NumberInputRowProps) {
  return (
    <div className="strategy-row">
      <label>{label}</label>
      <input
        type="number"
        min={min}
        step={step}
        value={value}
        className="strategy-number-input"
        onChange={e => onChange(e.target.value)}
        onBlur={onCommit}
      />
    </div>
  )
}
