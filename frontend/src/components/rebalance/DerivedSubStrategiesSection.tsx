import React, { useCallback } from 'react'
import { PlusCircle } from 'lucide-react'
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

const STEP_SCALE_FUNCTIONS = ['STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET']
const HYSTERESIS_SCALE_FUNCTIONS = ['HYSTERESIS_STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET']
const RESET_ABOVE_SCALE_FUNCTIONS = ['HYSTERESIS_STEP', 'HYSTERESIS_STAIRS']
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
      <div className="strategy-section-body strategy-derived-section-body">
        {value.map((derived, derivedIdx) => (
          <div key={derived.id} className="strategy-derived-card">
            <div className="strategy-derived-card-header">
              <div className="strategy-derived-card-title">Derived Rebalancing</div>
              <div className="strategy-derived-card-actions">
                <label className="strategy-derived-enabled">
                  <span>Enabled</span>
                  <input
                    type="checkbox"
                    checked={derived.enabled}
                    onChange={e => updateSubStrategy(derived.id, { enabled: e.target.checked })}
                  />
                </label>
                <button
                  type="button"
                  className="clear-action-btn strategy-derived-remove"
                  title="Remove derived strategy"
                  onClick={() => removeSubStrategy(derived.id)}
                >
                  X
                </button>
              </div>
            </div>

            <div className="strategy-derived-grid strategy-derived-grid-primary">
              <DerivedField label="Derived Name">
                <input
                  type="text"
                  value={derived.label}
                  placeholder={`Derived ${derivedIdx + 1}`}
                  aria-label={`Derived strategy ${derivedIdx + 1} label`}
                  onChange={e => updateSubStrategy(derived.id, { label: e.target.value })}
                  onBlur={onCommit}
                />
              </DerivedField>
              <DerivedField label="Ref Margin Source">
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
              </DerivedField>
              <DerivedField label="Scale Function">
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
                  <option value="HYSTERESIS_STAIRS_REF_BL_RESET">Hysteresis Stairs Ref BL Reset</option>
                </select>
              </DerivedField>
              {(derived.marginReferenceSource ?? 'BASE_STRATEGY') === 'STANDALONE_TICKER' && (
                <DerivedField label="Ref Ticker">
                  <input
                    type="text"
                    value={derived.marginReferenceTicker ?? ''}
                    placeholder="SPY"
                    aria-label={`Derived strategy ${derivedIdx + 1} reference margin ticker`}
                    className="strategy-ticker-input"
                    onChange={e => updateSubStrategy(derived.id, { marginReferenceTicker: e.target.value.toUpperCase() })}
                    onBlur={onCommit}
                  />
                </DerivedField>
              )}
            </div>

            <div className="strategy-derived-divider" />

            <div className="strategy-derived-grid strategy-derived-grid-params">
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
              {RESET_ABOVE_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
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
              {STEP_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (derived.scale.function ?? 'SIGMOID') === 'STEP' && (
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
              <DerivedMarginInput
                label="Max Margin"
                value={derived.maxMargin ?? ''}
                placeholder={marginPoints[4] ?? DEFAULT_POINTS[4]}
                sliderMax={sliderMax}
                ariaLabel={`Derived strategy ${derivedIdx + 1} max margin`}
                onChange={margin => updateSubStrategy(derived.id, { maxMargin: margin })}
                onCommit={onCommit}
              />
            </div>

            {STEP_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
              <div className="strategy-derived-steps">
                {(derived.scale.steps?.length ? derived.scale.steps : [emptyDerivedTargetStep(0)]).map((step, stepIdx) => (
                  <div key={step.id} className="strategy-derived-step-row">
                    <DerivedMarginInput
                      label={['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') ? `Stair ${stepIdx + 1} Below` : `Step ${stepIdx + 1} Above`}
                      value={step.referenceMargin}
                      placeholder={String(60 + stepIdx * 10)}
                      sliderMax={sliderMax}
                      ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} reference margin`}
                      onChange={margin => updateStep(derived.id, step.id, { referenceMargin: margin })}
                      onCommit={onCommit}
                    />
                    <DerivedMarginInput
                      label={['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') ? `Stair ${stepIdx + 1} Target` : `Step ${stepIdx + 1} Target`}
                      value={step.targetMargin}
                      placeholder={String(50 + stepIdx * 10)}
                      sliderMax={sliderMax}
                      ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} target margin`}
                      onChange={margin => updateStep(derived.id, step.id, { targetMargin: margin })}
                      onCommit={onCommit}
                    />
                    <button
                      type="button"
                      className="remove-ticker-btn strategy-derived-step-remove"
                      disabled={(derived.scale.steps?.length ?? 0) <= 1}
                      onClick={() => removeStep(derived.id, step.id)}
                    >
                      Remove Step
                    </button>
                  </div>
                ))}
                <button type="button" className="add-ticker-btn strategy-derived-step-add" onClick={() => addStep(derived.id)}>
                  + Add Step
                </button>
              </div>
            )}

            <div className="strategy-derived-divider" />

            <div className="strategy-derived-grid strategy-derived-grid-deviation">
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
              <NumberInputRow
                label="Timeout"
                value={derived.timeoutDays ?? '10'}
                min="0"
                step="1"
                onChange={next => updateSubStrategy(derived.id, { timeoutDays: next })}
                onCommit={onCommit}
                suffix="D"
              />
            </div>

            <div className="strategy-derived-grid strategy-derived-grid-alloc">
              <DerivedField label="BL Alloc Type">
                <select
                  value={derived.buyAllocStrategy ?? derived.allocStrategy ?? 'PROPORTIONAL'}
                  onChange={e => updateSubStrategy(derived.id, { buyAllocStrategy: e.target.value, allocStrategy: e.target.value })}
                >
                  {allocOptions.map(o => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </DerivedField>
              <DerivedField label="SH Alloc Type">
                <select
                  value={derived.sellAllocStrategy ?? derived.allocStrategy ?? 'PROPORTIONAL'}
                  onChange={e => updateSubStrategy(derived.id, { sellAllocStrategy: e.target.value })}
                >
                  {allocOptions.map(o => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </DerivedField>
            </div>
          </div>
        ))}
        <div className="strategy-derived-add-row">
          <button type="button" className="strategy-derived-add-btn" onClick={addSubStrategy}>
            <PlusCircle size={18} strokeWidth={2.2} aria-hidden="true" />
            <span>Add Derived Strategy Component</span>
          </button>
        </div>
      </div>
    </details>
  )
}

function DerivedField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="strategy-derived-field">
      <span>{label}</span>
      {children}
    </label>
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
    <div className="strategy-derived-field">
      <span>{label}</span>
      <MarginPercentInput
        value={value}
        placeholder={placeholder}
        max={sliderMax}
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
  suffix?: string
  onChange: (value: string) => void
  onCommit: () => void
}

function NumberInputRow({ label, value, min, step, suffix, onChange, onCommit }: NumberInputRowProps) {
  return (
    <label className="strategy-derived-field">
      <span>{label}</span>
      <span className="strategy-derived-input-with-suffix">
        <input
          type="number"
          min={min}
          step={step}
          value={value}
          className="strategy-number-input"
          onChange={e => onChange(e.target.value)}
          onBlur={onCommit}
        />
        {suffix && <span>{suffix}</span>}
      </span>
    </label>
  )
}
