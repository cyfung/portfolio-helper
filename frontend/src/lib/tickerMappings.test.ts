import { describe, expect, it } from 'vitest'
import {
  BUILTIN_TICKER_MAPPING_SETS,
  SIMULATED_HISTORY_BUILTIN_ID,
  exportableSavedTickerMappings,
  mapTickerExpressionWithWarnings,
  normalizeTickerMappingSettings,
  resolveTickerMappingSet,
  selectedTickerMappingSet,
  type TickerMapping,
  type TickerMappingSet,
} from './tickerMappings'

function row(id: string, from: string, to: string): TickerMapping {
  return { id, from, to, mode: 'replaceAll', applyTo: 'ticker' }
}

function builtinRef(id = 'ref-sim'): TickerMapping {
  return {
    id,
    from: '',
    to: '',
    mode: 'prepend',
    applyTo: 'expression',
    isMappingRef: true,
    mappingRefKind: 'builtin',
    mappingRef: SIMULATED_HISTORY_BUILTIN_ID,
  }
}

describe('Use Simulated History built-in ticker mapping', () => {
  it('has a stable immutable runtime definition for every declared instrument', () => {
    const builtin = BUILTIN_TICKER_MAPPING_SETS[0]
    expect(builtin.id).toBe(SIMULATED_HISTORY_BUILTIN_ID)
    expect(builtin.storage).toBe('builtin')
    expect(Object.isFrozen(BUILTIN_TICKER_MAPPING_SETS)).toBe(true)
    expect(Object.isFrozen(builtin)).toBe(true)
    expect(Object.isFrozen(builtin.mappings)).toBe(true)
    expect(builtin.mappings.every(mapping => Object.isFrozen(mapping))).toBe(true)
    expect(builtin.mappings).toHaveLength(14)
    expect(builtin.mappings.find(mapping => mapping.from === 'SPY')).toEqual({
      id: 'builtin:simulated-history:SPY',
      from: 'SPY',
      to: 'SPY$',
      mode: 'replaceAll',
      applyTo: 'expression',
    })
    expect(builtin.mappings.some(mapping => mapping.from === 'EFFRX')).toBe(false)
  })

  it('can be selected directly without being copied into saved sets', () => {
    const settings = normalizeTickerMappingSettings({
      selectedSetId: SIMULATED_HISTORY_BUILTIN_ID,
      savedSets: [],
    })
    expect(settings.selectedSetId).toBe(SIMULATED_HISTORY_BUILTIN_ID)
    expect(settings.savedSets).toEqual([])
    expect(selectedTickerMappingSet(settings)?.storage).toBe('builtin')
  })

  it('runs after tax drag and preserves the expense modifier', () => {
    const set: TickerMappingSet = {
      id: 'composed',
      name: 'Composed',
      mappings: [row('tax', 'SPY', 'SPY E=0.513012'), builtinRef()],
    }
    const resolved = resolveTickerMappingSet(set, [])
    const result = mapTickerExpressionWithWarnings('SPY', resolved)
    expect(result.value).toBe('1 SPY$ E=0.513012')
    expect(result.warnings).toEqual([])
  })

  it('converts eligible components throughout a compound expression', () => {
    const resolved = resolveTickerMappingSet({
      id: 'compound', name: 'Compound', mappings: [builtinRef()],
    }, [])
    expect(mapTickerExpressionWithWarnings('0.6 SPY 0.4 VXUS E=0.2', resolved).value)
      .toBe('0.6 SPY$ 0.4 VXUS$ E=0.2')
  })

  it('treats legacy references without a kind as saved references', () => {
    const settings = normalizeTickerMappingSettings({
      savedSets: [{
        id: 'legacy', name: 'Legacy', mappings: [{
          id: 'ref', isMappingRef: true, mappingRef: 'Tax Drag',
        }],
      }],
    })
    expect(settings.savedSets[0].mappings[0].mappingRefKind).toBe('saved')
  })

  it('retains an unavailable built-in reference and reports it during resolution', () => {
    const set = normalizeTickerMappingSettings({
      savedSets: [{
        id: 'imported', name: 'Imported', mappings: [{
          id: 'missing', isMappingRef: true, mappingRefKind: 'builtin', mappingRef: 'builtin:not-installed',
        }],
      }],
    }).savedSets[0]
    expect(set.mappings[0].mappingRef).toBe('builtin:not-installed')
    expect(resolveTickerMappingSet(set, [set]).resolveWarnings).toContain(
      'Missing built-in ticker mapping reference: builtin:not-installed',
    )
  })

  it('warns unless the built-in is absolutely last in resolved nested order', () => {
    const child: TickerMappingSet = {
      id: 'child',
      name: 'Child',
      mappings: [builtinRef()],
    }
    const parent: TickerMappingSet = {
      id: 'parent',
      name: 'Parent',
      mappings: [
        {
          id: 'child-ref', from: '', to: '', mode: 'prepend', applyTo: 'expression',
          isMappingRef: true, mappingRef: 'Child', mappingRefKind: 'saved',
        },
        row('after', 'ABC', 'DEF'),
      ],
    }
    expect(resolveTickerMappingSet(parent, [child]).resolveWarnings).toContain(
      'Use Simulated History should be the final item in the resolved mapping order.',
    )
  })

  it('exports only the stable built-in reference, never its generated definition', () => {
    const saved: TickerMappingSet = {
      id: 'saved',
      name: 'Saved',
      mappings: [builtinRef()],
    }
    const exported = exportableSavedTickerMappings({ selectedSetId: '', sets: [], savedSets: [saved] })
    expect(exported).toHaveLength(1)
    expect(exported[0].mappings).toHaveLength(1)
    expect(exported[0].mappings[0]).toMatchObject({
      mappingRefKind: 'builtin',
      mappingRef: SIMULATED_HISTORY_BUILTIN_ID,
    })
  })
})
