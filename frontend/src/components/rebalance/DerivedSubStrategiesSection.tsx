import React, { useCallback } from 'react'
import { GripVertical, PlusCircle } from 'lucide-react'
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

const STEP_SCALE_FUNCTIONS = ['STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_MOMENTUM', 'HYSTERESIS_STAIRS_REF_BL_RESET']
const HYSTERESIS_SCALE_FUNCTIONS = ['HYSTERESIS_STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_MOMENTUM', 'HYSTERESIS_STAIRS_REF_BL_RESET']
const RESET_ABOVE_SCALE_FUNCTIONS = ['HYSTERESIS_STEP', 'HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_MOMENTUM']
const STAIRS_RESET_TARGET_FUNCTIONS = ['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_MOMENTUM']
const SIGMOID_SCALE_FUNCTIONS = ['SIGMOID', 'ADAPTIVE_LOW_SIGMOID']
const MARGIN_COVERAGE_REFERENCE_MAX = 1000

const DERIVED_REFERENCE_METRIC_OPTIONS: { value: DerivedSubStrategyState['marginReferenceMetric']; label: string }[] = [
  { value: 'MARGIN', label: 'Margin' },
  { value: 'EQUITY_CUSHION', label: 'Equity Cushion' },
  { value: 'MARGIN_COVERAGE', label: 'Margin Coverage' },
]

const HYSTERESIS_STAIRS_REFERENCE_MODE_OPTIONS: { value: DerivedSubStrategyState['scale']['hysteresisStairsReferenceMode']; label: string }[] = [
  { value: 'RESET_REF', label: 'Reset Ref' },
  { value: 'BUY_LOW_INTENTION', label: 'BL Intention' },
]

const HYSTERESIS_STAIRS_FALL_MODE_OPTIONS: { value: DerivedSubStrategyState['scale']['hysteresisStairsFallMode']; label: string }[] = [
  { value: 'DIRECT', label: 'Direct' },
  { value: 'MOMENTUM', label: 'Momentum' },
  { value: 'MOMENTUM_WITH_RECOVERY', label: 'With Recovery' },
]

const HYSTERESIS_STAIRS_MOMENTUM_FALL_MODES: DerivedSubStrategyState['scale']['hysteresisStairsFallMode'][] = [
  'MOMENTUM',
  'MOMENTUM_WITH_RECOVERY',
]

function referenceMetricToMargin(value: number, metric: DerivedSubStrategyState['marginReferenceMetric']) {
  switch (metric) {
    case 'MARGIN':
      return value
    case 'EQUITY_CUSHION':
      return value > 0 ? Math.max(0, 1 / value - 1) : null
    case 'MARGIN_COVERAGE':
      return value > 0 ? 1 / value : null
  }
}

function marginToReferenceMetric(value: number, metric: DerivedSubStrategyState['marginReferenceMetric']) {
  switch (metric) {
    case 'MARGIN':
      return value
    case 'EQUITY_CUSHION':
      return value >= 0 ? 1 / (value + 1) : null
    case 'MARGIN_COVERAGE':
      return value > 0 ? 1 / value : null
  }
}

function formatConvertedReferenceValue(value: number) {
  const rounded = Math.round(value * 100) / 100
  return String(rounded)
}

function convertReferenceValue(
  value: string,
  fromMetric: DerivedSubStrategyState['marginReferenceMetric'],
  toMetric: DerivedSubStrategyState['marginReferenceMetric'],
) {
  if (fromMetric === toMetric) return value
  const parsed = parseFloat(value)
  if (!Number.isFinite(parsed)) return value

  const margin = referenceMetricToMargin(parsed / 100, fromMetric)
  if (margin == null || !Number.isFinite(margin)) return value

  const converted = marginToReferenceMetric(margin, toMetric)
  if (converted == null || !Number.isFinite(converted)) return value

  return formatConvertedReferenceValue(converted * 100)
}

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

  const handleDragStart = useCallback((e: React.DragEvent, derived: DerivedSubStrategyState) => {
    e.dataTransfer.setData('application/x-derived-sub-strategy', JSON.stringify(derived))
    e.dataTransfer.effectAllowed = 'copy'
  }, [])

  const handleScaleFunctionChange = (
    derived: DerivedSubStrategyState,
    fn: DerivedSubStrategyState['scale']['function'],
  ) => {
    const previousFn = derived.scale.function ?? 'SIGMOID'
    const refHigh = parseFloat(derived.scale.referenceUpper)
    const currentReset = parseFloat(derived.scale.stepBaseTarget ?? '')
    const shouldResetHysteresisTarget =
      HYSTERESIS_SCALE_FUNCTIONS.includes(fn) &&
      !STAIRS_RESET_TARGET_FUNCTIONS.includes(previousFn) &&
      (!Number.isFinite(currentReset) || currentReset <= refHigh)
    updateScale(derived.id, {
      function: fn,
      ...(shouldResetHysteresisTarget
        ? { stepBaseTarget: String(Math.min(sliderMax, Math.max(0, Math.round((Number.isFinite(refHigh) ? refHigh : 100) + 10)))) }
        : {}),
    })
  }

  const handleReferenceMetricChange = (
    derived: DerivedSubStrategyState,
    nextMetric: DerivedSubStrategyState['marginReferenceMetric'],
  ) => {
    const currentMetric = derived.marginReferenceMetric ?? 'MARGIN'
    const convert = (value: string) => convertReferenceValue(value, currentMetric, nextMetric)
    const scaleFunction = derived.scale.function ?? 'SIGMOID'
    updateSubStrategy(derived.id, {
      marginReferenceMetric: nextMetric,
      scale: {
        ...derived.scale,
        referenceLower: convert(derived.scale.referenceLower),
        referenceUpper: convert(derived.scale.referenceUpper),
        stepBaseTarget: RESET_ABOVE_SCALE_FUNCTIONS.includes(scaleFunction)
          ? convert(derived.scale.stepBaseTarget)
          : derived.scale.stepBaseTarget,
        steps: derived.scale.steps.map(step => ({
          ...step,
          referenceMargin: convert(step.referenceMargin),
        })),
      },
    })
  }

  return (
    <details open className="strategy-subsection">
      <summary className="strategy-section-title" onClick={keepSectionOpen}>Derived</summary>
      <div className="strategy-section-body strategy-derived-section-body">
        {value.map((derived, derivedIdx) => {
          const referenceMetric = derived.marginReferenceMetric ?? 'MARGIN'
          const referenceInputMax =
            referenceMetric === 'MARGIN_COVERAGE'
              ? MARGIN_COVERAGE_REFERENCE_MAX
              : (referenceMetric === 'EQUITY_CUSHION' ? 100 : sliderMax)

          return (
          <div key={derived.id} className="strategy-derived-card">
            <div className="strategy-derived-card-header">
              <div className="strategy-derived-card-title">
                <span
                  className="margin-drag-handle strategy-derived-drag-handle"
                  draggable
                  title="Drag to copy this derived strategy component"
                  aria-label={`Drag derived strategy ${derivedIdx + 1}`}
                  onDragStart={e => handleDragStart(e, derived)}
                >
                  <GripVertical size={14} strokeWidth={2.2} aria-hidden="true" />
                </span>
                <span>Derived Rebalancing</span>
              </div>
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
                  className="remove-margin-btn strategy-derived-remove"
                  title="Remove derived strategy"
                  onClick={() => removeSubStrategy(derived.id)}
                >
                  ✕
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
              <DerivedField label="Ref Metric">
                <select
                  value={referenceMetric}
                  onChange={e => handleReferenceMetricChange(derived, e.target.value as DerivedSubStrategyState['marginReferenceMetric'])}
                >
                  {DERIVED_REFERENCE_METRIC_OPTIONS.map(o => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </DerivedField>
              <DerivedField label="Ref Source">
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
                    aria-label={`Derived strategy ${derivedIdx + 1} reference ticker`}
                    className="strategy-ticker-input"
                    onChange={e => updateSubStrategy(derived.id, { marginReferenceTicker: e.target.value.toUpperCase() })}
                    onBlur={onCommit}
                  />
                </DerivedField>
              )}
              {(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' && (
                <DerivedField label="Ref Mode">
                  <select
                    value={derived.scale.hysteresisStairsReferenceMode ?? 'RESET_REF'}
                    onChange={e => updateScale(derived.id, {
                      hysteresisStairsReferenceMode: e.target.value as DerivedSubStrategyState['scale']['hysteresisStairsReferenceMode'],
                    })}
                  >
                    {HYSTERESIS_STAIRS_REFERENCE_MODE_OPTIONS.map(o => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                </DerivedField>
              )}
            </div>

            <div className="strategy-derived-divider" />

            <div className="strategy-derived-grid strategy-derived-grid-params">
              {!STEP_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
                <>
                  <DerivedMarginInput
                    label="Ref Min"
                    value={derived.scale.referenceLower}
                    placeholder="50"
                    sliderMax={referenceInputMax}
                    ariaLabel={`Derived strategy ${derivedIdx + 1} reference lower threshold`}
                    onChange={margin => updateScale(derived.id, { referenceLower: margin })}
                    onCommit={onCommit}
                  />
                  <DerivedMarginInput
                    label="Ref Max"
                    value={derived.scale.referenceUpper}
                    placeholder="100"
                    sliderMax={referenceInputMax}
                    ariaLabel={`Derived strategy ${derivedIdx + 1} reference upper threshold`}
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
              {RESET_ABOVE_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') &&
                  ((derived.scale.function ?? 'SIGMOID') !== 'HYSTERESIS_STAIRS' ||
                    (derived.scale.hysteresisStairsReferenceMode ?? 'RESET_REF') === 'RESET_REF') && (
                <DerivedMarginInput
                  label="Reset Ref"
                  value={derived.scale.stepBaseTarget ?? ''}
                  placeholder="110"
                  sliderMax={referenceInputMax}
                  ariaLabel={`Derived strategy ${derivedIdx + 1} reset threshold`}
                  onChange={margin => updateScale(derived.id, { stepBaseTarget: margin })}
                  onCommit={onCommit}
                />
              )}
              {STAIRS_RESET_TARGET_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') &&
                  ((derived.scale.function ?? 'SIGMOID') !== 'HYSTERESIS_STAIRS' ||
                    (derived.scale.hysteresisStairsReferenceMode ?? 'RESET_REF') === 'RESET_REF') && (
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
              {(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' && (
                <DerivedField label="Fall Mode">
                  <select
                    value={derived.scale.hysteresisStairsFallMode ?? 'DIRECT'}
                    onChange={e => updateScale(derived.id, {
                      hysteresisStairsFallMode: e.target.value as DerivedSubStrategyState['scale']['hysteresisStairsFallMode'],
                    })}
                  >
                    {HYSTERESIS_STAIRS_FALL_MODE_OPTIONS.map(o => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                </DerivedField>
              )}
              {(derived.scale.function ?? 'SIGMOID') === 'HYSTERESIS_STAIRS' &&
                  HYSTERESIS_STAIRS_MOMENTUM_FALL_MODES.includes(derived.scale.hysteresisStairsFallMode ?? 'DIRECT') && (
                <NumberInputRow
                  label="Momentum Months"
                  value={derived.scale.momentumLookbackMonths ?? '12'}
                  min="1"
                  step="1"
                  onChange={next => updateScale(derived.id, { momentumLookbackMonths: next })}
                  onCommit={onCommit}
                />
              )}
            </div>

            {STEP_SCALE_FUNCTIONS.includes(derived.scale.function ?? 'SIGMOID') && (
              <div className="strategy-derived-steps">
                {(derived.scale.steps?.length ? derived.scale.steps : [emptyDerivedTargetStep(0)]).map((step, stepIdx) => (
                  <div key={step.id} className="strategy-derived-step-row">
                    <DerivedMarginInput
                      label={['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_MOMENTUM', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') ? `Stair ${stepIdx + 1} Ref` : `Step ${stepIdx + 1} Ref`}
                      value={step.referenceMargin}
                      placeholder={String(60 + stepIdx * 10)}
                      sliderMax={referenceInputMax}
                      ariaLabel={`Derived strategy ${derivedIdx + 1} step ${stepIdx + 1} reference threshold`}
                      onChange={margin => updateStep(derived.id, step.id, { referenceMargin: margin })}
                      onCommit={onCommit}
                    />
                    <DerivedMarginInput
                      label={['HYSTERESIS_STAIRS', 'HYSTERESIS_STAIRS_MOMENTUM', 'HYSTERESIS_STAIRS_REF_BL_RESET'].includes(derived.scale.function ?? 'SIGMOID') ? `Stair ${stepIdx + 1} Target` : `Step ${stepIdx + 1} Target`}
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
          )
        })}
        <div className="strategy-derived-add-row">
          <button type="button" className="strategy-derived-add-btn" onClick={() => addSubStrategy()}>
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
