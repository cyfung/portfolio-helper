import { describe, expect, it } from 'vitest'
import {
  BUILTIN_TICKER_MAPPING_SETS,
  SIMULATED_HISTORY_BUILTIN_ID,
  exportableSavedTickerMappings,
  mapTickerExpressionWithWarnings,
  mergeSavedTickerMappings,
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

  it('keeps the selected mapping resolvable after re-importing a set with the same name', () => {
    const saved: TickerMappingSet = {
      id: 'set-original-id',
      name: 'Use Simulated History Mapping',
      mappings: [builtinRef()],
    }
    const settings = normalizeTickerMappingSettings({
      selectedSetId: saved.id,
      savedSets: [saved],
    })
    expect(selectedTickerMappingSet(settings)).not.toBeNull()

    // Re-importing (e.g. from an export/import round trip) rebuilds the set with a
    // blank id, matched back to the existing one only by name.
    const reimported: TickerMappingSet = {
      id: '',
      name: saved.name,
      mappings: [builtinRef('ref-sim-2')],
    }
    const merged = mergeSavedTickerMappings(settings, [reimported])

    expect(merged.savedSets).toHaveLength(1)
    expect(merged.savedSets[0].id).toBe(saved.id)
    expect(merged.selectedSetId).toBe(saved.id)
    const resolved = selectedTickerMappingSet(merged)
    expect(resolved).not.toBeNull()
    expect(resolved?.resolveWarnings).toEqual([])
    expect(mapTickerExpressionWithWarnings('SPY', resolved).value).toBe('SPY$')
  })

  it('never lets a saved/imported set claim a built-in id', () => {
    const settings = normalizeTickerMappingSettings({
      savedSets: [{
        id: SIMULATED_HISTORY_BUILTIN_ID,
        name: 'Legacy Duplicate',
        mappings: [row('legacy', 'SPY', 'SPY_STALE')],
      }],
    })
    expect(settings.savedSets).toHaveLength(1)
    expect(settings.savedSets[0].id).not.toBe(SIMULATED_HISTORY_BUILTIN_ID)
  })

  it('always resolves the real built-in even if a saved set collides on id', () => {
    // Bypasses normalization to simulate stale/legacy data that predates the
    // built-in-id guard, and confirms selection precedence still protects it.
    const shadowSet: TickerMappingSet = {
      id: SIMULATED_HISTORY_BUILTIN_ID,
      name: 'Legacy Duplicate',
      storage: 'server',
      mappings: [row('legacy', 'SPY', 'SPY_STALE')],
    }
    const settings = {
      selectedSetId: SIMULATED_HISTORY_BUILTIN_ID,
      sets: [],
      savedSets: [shadowSet],
    }
    const resolved = selectedTickerMappingSet(settings)
    expect(resolved?.storage).toBe('builtin')
    expect(mapTickerExpressionWithWarnings('SPY', resolved).value).toBe('SPY$')
  })

  it('never lets a saved/imported set claim a built-in name, so name-based references cannot be shadowed', () => {
    const settings = normalizeTickerMappingSettings({
      savedSets: [
        {
          id: 'impostor',
          name: 'Use Simulated History',
          mappings: [row('stale', 'SPY', 'SPY_STALE')],
        },
        {
          id: 'consumer',
          name: 'Consumer',
          mappings: [{
            id: 'ref', isMappingRef: true, mappingRefKind: 'saved', mappingRef: 'Use Simulated History',
          }],
        },
      ],
    })

    const impostor = settings.savedSets.find(set => set.id === 'impostor')
    expect(impostor?.name).not.toBe('Use Simulated History')

    const consumer = settings.savedSets.find(set => set.id === 'consumer')!
    const resolved = resolveTickerMappingSet(consumer, settings.savedSets)
    expect(resolved.resolveWarnings).toContain('Missing ticker mapping reference: Use Simulated History')
    expect(mapTickerExpressionWithWarnings('SPY', resolved).value).not.toBe('SPY_STALE')
  })
})
