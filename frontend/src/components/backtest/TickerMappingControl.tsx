import { useEffect, useState, type DragEvent } from 'react'
import { Download, GripVertical, Save, Settings, Trash2, X } from 'lucide-react'
import { compressToCode } from '@/lib/compress'
import { buildSavedTickerMappingsExportPayload } from '@/lib/configImportExport'
import {
  hydrateTickerMappingSettings,
  isTickerMappingSettingsHydrated,
  loadTickerMappingSettings,
  mappingSetSummary,
  saveTickerMappingSettings,
  selectedTickerMappingSet,
  isTickerMappingRef,
  tickerMappingSetHash,
  tickerMappingRefName,
  usableTickerMappings,
  type TickerMapping,
  type TickerMappingSettings,
  type TickerMappingSet,
} from '@/lib/tickerMappings'

interface Props {
  idPrefix: string
  value: TickerMappingSettings
  onChange: (settings: TickerMappingSettings) => void
  onExportCode?: (code: string) => void
  onToast?: (message: string, type?: 'ok' | 'warn' | 'error') => void
}

function newMappingId() {
  return `mapping-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function newMappingSetId() {
  return `set-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function normalizeSource(value: string) {
  return value.trim().toUpperCase()
}

function normalizeTarget(value: string) {
  return value.trim().replace(/\s+/g, ' ')
}

function cloneMappings(mappings: TickerMapping[]) {
  return mappings.map(mapping => ({ ...mapping, id: newMappingId() }))
}

function cloneMappingSet(set: TickerMappingSet) {
  return {
    id: newMappingSetId(),
    name: set.name,
    updatedAt: set.updatedAt,
    mappings: cloneMappings(set.mappings),
  }
}

function sourceMetadata(saved: TickerMappingSet) {
  return {
    sourceSavedSetId: saved.id,
    sourceSavedSetName: saved.name,
    sourceSavedSetHash: tickerMappingSetHash(saved),
    sourceSavedSetUpdatedAt: saved.updatedAt,
  }
}

function editableFromSaved(saved: TickerMappingSet, targetSetId: string): TickerMappingSet {
  return {
    ...cloneMappingSet(saved),
    id: targetSetId,
    ...sourceMetadata(saved),
  }
}

type MappingDropPosition = 'before' | 'after'

export default function TickerMappingControl({ idPrefix, value, onChange, onExportCode, onToast }: Props) {
  const [editorOpen, setEditorOpen] = useState(false)
  const [draft, setDraft] = useState<TickerMappingSettings>(value)
  const [error, setError] = useState('')
  const [exportStatus, setExportStatus] = useState('')
  const [dragOverSetId, setDragOverSetId] = useState('')
  const [dragOverRowsSetId, setDragOverRowsSetId] = useState('')
  const [draggedMapping, setDraggedMapping] = useState<{ setId: string; mappingId: string } | null>(null)
  const [dragOverMapping, setDragOverMapping] = useState<{
    setId: string
    mappingId: string
    position: MappingDropPosition
  } | null>(null)
  const [hydrated, setHydrated] = useState(() => isTickerMappingSettingsHydrated())

  useEffect(() => {
    setDraft(value)
    setHydrated(isTickerMappingSettingsHydrated())
  }, [value])

  function persist(next: TickerMappingSettings) {
    if (!hydrated) return
    saveTickerMappingSettings(next)
    onChange(next)
  }

  function updateDraft(next: TickerMappingSettings) {
    setDraft(next)
    persist(next)
  }

  function selectSet(selectedSetId: string) {
    if (!hydrated) return
    persist({ ...value, selectedSetId })
  }

  async function openEditor() {
    const settings = isTickerMappingSettingsHydrated()
      ? loadTickerMappingSettings()
      : await hydrateTickerMappingSettings()
    setHydrated(isTickerMappingSettingsHydrated())
    if (!isTickerMappingSettingsHydrated()) return
    setDraft(settings)
    setError('')
    setExportStatus('')
    setEditorOpen(true)
  }

  async function exportSavedMappings() {
    if (!hydrated) return
    setExportStatus('')
    const payload = await buildSavedTickerMappingsExportPayload()
    if (payload.savedTickerMappings.length === 0) {
      setExportStatus('No saved mappings to export.')
      onToast?.('No saved mappings to export.', 'warn')
      return
    }

    const code = await compressToCode(payload)
    onExportCode?.(code)
    try {
      await navigator.clipboard.writeText(code)
      setExportStatus(onExportCode ? 'Mappings export code copied and placed in Config Code.' : 'Mappings export code copied.')
      onToast?.('Mappings export code copied.')
    } catch {
      setExportStatus(onExportCode ? 'Mappings export code placed in Config Code.' : 'Mappings export code generated.')
      onToast?.('Mappings export code generated.')
    }
  }

  function updateSet(setId: string, patch: Partial<TickerMappingSet>) {
    updateDraft({
      ...draft,
      sets: draft.sets.map(set => set.id === setId ? { ...set, ...patch } : set),
    })
  }

  function newMapping(): TickerMapping {
    return { id: newMappingId(), from: '', to: '', mode: 'prepend', applyTo: 'expression' }
  }

  function newMappingRef(savedSet: TickerMappingSet): TickerMapping {
    return {
      id: newMappingId(),
      from: '',
      to: '',
      mode: 'prepend',
      applyTo: 'expression',
      isMappingRef: true,
      mappingRef: savedSet.name,
    }
  }

  function addMapping(setId: string, position: 'start' | 'end') {
    updateDraft({
      ...draft,
      sets: draft.sets.map(set => set.id === setId
        ? { ...set, mappings: position === 'start' ? [newMapping(), ...set.mappings] : [...set.mappings, newMapping()] }
        : set
      ),
    })
    setError('')
  }

  function removeMapping(setId: string, mappingId: string) {
    updateDraft({
      ...draft,
      sets: draft.sets.map(set => set.id === setId
        ? { ...set, mappings: set.mappings.filter(mapping => mapping.id !== mappingId) }
        : set
      ),
    })
  }

  function updateMapping(setId: string, mappingId: string, patch: Partial<TickerMapping>) {
    updateDraft({
      ...draft,
      sets: draft.sets.map(set => set.id === setId
        ? {
            ...set,
            mappings: set.mappings.map(mapping => mapping.id === mappingId
              ? { ...mapping, ...patch }
              : mapping
            ),
          }
        : set
      ),
    })
    setError('')
  }

  function moveMapping(
    setId: string,
    mappingId: string,
    targetMappingId: string,
    position: MappingDropPosition,
  ) {
    const targetSet = draft.sets.find(set => set.id === setId)
    if (!targetSet) return

    const sourceIndex = targetSet.mappings.findIndex(mapping => mapping.id === mappingId)
    const targetIndex = targetSet.mappings.findIndex(mapping => mapping.id === targetMappingId)
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return

    const reordered = [...targetSet.mappings]
    const [moved] = reordered.splice(sourceIndex, 1)
    let insertIndex = targetIndex + (position === 'after' ? 1 : 0)
    if (sourceIndex < insertIndex) insertIndex -= 1
    reordered.splice(insertIndex, 0, moved)

    updateSet(setId, { mappings: reordered })
    setError('')
  }

  function referencedMappingNames(mappings: TickerMapping[]) {
    return mappings
      .filter(isTickerMappingRef)
      .map(tickerMappingRefName)
      .filter(Boolean)
  }

  function wouldCreateMappingReferenceCycle(parentSet: TickerMappingSet, childSet: TickerMappingSet) {
    const parentName = parentSet.name.trim().toLowerCase()
    if (!parentName) return false
    const savedByName = new Map(draft.savedSets.map(set => [set.name.trim().toLowerCase(), set]))

    function visit(set: TickerMappingSet, stack: string[]): boolean {
      return referencedMappingNames(set.mappings).some(name => {
        const key = name.toLowerCase()
        if (key === parentName) return true
        if (stack.includes(key)) return false
        const child = savedByName.get(key)
        return child ? visit(child, [...stack, key]) : false
      })
    }

    const childName = childSet.name.trim().toLowerCase()
    return childName === parentName || visit(childSet, childName ? [childName] : [])
  }

  function insertMappingRef(
    setId: string,
    savedSetId: string,
    targetMappingId?: string,
    position: MappingDropPosition = 'after',
  ) {
    const set = draft.sets.find(item => item.id === setId)
    const savedSet = draft.savedSets.find(item => item.id === savedSetId)
    if (!set || !savedSet) return
    if (wouldCreateMappingReferenceCycle(set, savedSet)) {
      setError('That child mapping would create a circular mapping reference.')
      return
    }

    const nextMappings = [...set.mappings]
    const targetIndex = targetMappingId ? nextMappings.findIndex(mapping => mapping.id === targetMappingId) : -1
    const insertIndex = targetIndex < 0
      ? nextMappings.length
      : targetIndex + (position === 'after' ? 1 : 0)
    nextMappings.splice(insertIndex, 0, newMappingRef(savedSet))
    updateSet(setId, { mappings: nextMappings })
    setError('')
  }

  function saveSetAsSaved(setId: string) {
    const set = draft.sets.find(item => item.id === setId)
    const name = set?.name.trim() ?? ''
    if (!set || !name) {
      setError('Name the mapping set before saving it.')
      return
    }
    const mappings = usableTickerMappings(set.mappings)
    if (mappings.length === 0) {
      setError('Add at least one mapping before saving it.')
      return
    }

    const existing = draft.savedSets.find(saved =>
      saved.storage !== 'local' && saved.name.trim().toLowerCase() === name.toLowerCase()
    )
    const savedSet: TickerMappingSet = {
      id: existing?.id ?? newMappingSetId(),
      name,
      mappings: cloneMappings(mappings),
      storage: 'server',
      persistentId: existing?.persistentId,
      updatedAt: new Date().toISOString(),
    }
    updateDraft({
      ...draft,
      selectedSetId: savedSet.id,
      sets: draft.sets.map(item => item.id === setId
        ? { ...set, name, mappings: cloneMappings(mappings), ...sourceMetadata(savedSet) }
        : item
      ),
      savedSets: [
        ...draft.savedSets.filter(saved => saved.name.trim().toLowerCase() !== name.toLowerCase()),
        savedSet,
      ],
    })
    setError('')
  }

  function deleteSavedSet(savedSetId: string) {
    updateDraft({
      ...draft,
      selectedSetId: draft.selectedSetId === savedSetId ? '' : draft.selectedSetId,
      savedSets: draft.savedSets.filter(set => set.id !== savedSetId),
    })
    setError('')
  }

  function loadSavedSetIntoActive(savedSetId: string, targetSetId: string) {
    const saved = draft.savedSets.find(set => set.id === savedSetId)
    if (!saved) return
    updateDraft({
      ...draft,
      sets: draft.sets.map(set => set.id === targetSetId
        ? editableFromSaved(saved, targetSetId)
        : set
      ),
    })
    setError('')
  }

  function isSavedMappingRowsDrop(e: DragEvent) {
    return e.dataTransfer.types.includes('application/x-ticker-mapping-set') &&
      !!(e.target as HTMLElement | null)?.closest('.ticker-mapping-rows')
  }

  const selectedSet = selectedTickerMappingSet(value)
  const serverSavedSets = value.savedSets.filter(set => set.storage !== 'local')
  const localSavedSets = value.savedSets.filter(set => set.storage === 'local')
  const draftServerSavedSets = draft.savedSets.filter(set => set.storage !== 'local')
  const draftLocalSavedSets = draft.savedSets.filter(set => set.storage === 'local')
  const hasLocalSavedSets = localSavedSets.length > 0
  const draftHasLocalSavedSets = draftLocalSavedSets.length > 0
  const editSet = draft.sets[0]
  const hasSourceReference = !!editSet?.sourceSavedSetHash && (!!editSet.sourceSavedSetId || !!editSet.sourceSavedSetName)
  const referencedSavedSet = editSet && hasSourceReference
    ? draft.savedSets.find(set => set.id === editSet.sourceSavedSetId) ??
      draft.savedSets.find(set => set.name.trim().toLowerCase() === editSet.sourceSavedSetName?.trim().toLowerCase())
    : null
  const editSetHash = editSet ? tickerMappingSetHash(editSet) : ''
  const referenceHash = referencedSavedSet ? tickerMappingSetHash(referencedSavedSet) : ''
  const referenceLost = !!editSet && (!hasSourceReference || !referencedSavedSet)
  const referenceUpdated = hasSourceReference && !!referencedSavedSet && referenceHash !== editSet.sourceSavedSetHash
  const localUnsaved = !!editSet && hasSourceReference && editSetHash !== editSet.sourceSavedSetHash
  const editSetStatus = referenceLost && localUnsaved
    ? 'Unsaved changes + reference lost'
    : referenceLost
      ? 'Reference lost'
      : referenceUpdated && localUnsaved
        ? 'Unsaved changes + saved copy updated'
        : referenceUpdated
          ? 'Saved copy updated'
          : localUnsaved
            ? 'Unsaved changes'
            : 'Saved'

  return (
    <>
      <div className="backtest-section ticker-mapping-control">
        <label htmlFor={`${idPrefix}-ticker-mapping-set`}>Ticker Mappings</label>
        <div className="ticker-mapping-control-row">
          <select
            id={`${idPrefix}-ticker-mapping-set`}
            value={value.selectedSetId}
            onChange={e => selectSet(e.target.value)}
            disabled={!hydrated}
          >
            <option value="">None</option>
            {hasLocalSavedSets ? (
              <>
                {serverSavedSets.length > 0 && (
                  <optgroup label="Saved">
                    {serverSavedSets.map(set => (
                      <option key={set.id} value={set.id}>{set.name} ({mappingSetSummary(set)})</option>
                    ))}
                  </optgroup>
                )}
                <optgroup label="Local legacy">
                  {localSavedSets.map(set => (
                    <option key={set.id} value={set.id}>{set.name} ({mappingSetSummary(set)})</option>
                  ))}
                </optgroup>
              </>
            ) : serverSavedSets.map(set => (
              <option key={set.id} value={set.id}>{set.name} ({mappingSetSummary(set)})</option>
            ))}
          </select>
          <button type="button" className="ticker-config-btn ticker-mapping-config-btn" onClick={openEditor} disabled={!hydrated} title="Configure ticker mappings" aria-label="Configure ticker mappings">
            <Settings size={15} />
          </button>
          <button type="button" className="backtest-config-btn ticker-mapping-export-btn" onClick={exportSavedMappings} disabled={!hydrated} title="Export saved ticker mappings" aria-label="Export saved ticker mappings">
            <Download size={15} />
            <span>Export Mappings</span>
          </button>
        </div>
        {selectedSet && usableTickerMappings(selectedSet.mappings).length > 0 ? (
          <div className="ticker-mapping-active-summary">
            {usableTickerMappings(selectedSet.mappings).slice(0, 4).map(mapping => `${mapping.from}->${mapping.to}`).join(', ')}
            {usableTickerMappings(selectedSet.mappings).length > 4 ? `, +${usableTickerMappings(selectedSet.mappings).length - 4}` : ''}
          </div>
        ) : null}
        {exportStatus && <div className="ticker-mapping-export-status">{exportStatus}</div>}
      </div>

      {editorOpen && (
        <div className="ticker-config-overlay" role="dialog" aria-modal="true" onMouseDown={e => {
          if (e.target === e.currentTarget) setEditorOpen(false)
        }}>
          <div className="ticker-config-dialog ticker-mapping-dialog" onMouseDown={e => e.stopPropagation()}>
            <div className="ticker-config-header">
              <h2>Ticker Mappings</h2>
              <button type="button" className="ticker-config-close" onClick={() => setEditorOpen(false)}>x</button>
            </div>

            <div className="ticker-mapping-saved-section">
              <div className="ticker-mapping-saved-title">Saved Mappings</div>
              {draft.savedSets.length ? (
                <>
                  {draftServerSavedSets.length > 0 && (
                    <>
                      {draftHasLocalSavedSets && <div className="ticker-mapping-saved-title">Saved</div>}
                      <div className="saved-portfolios-bar ticker-mapping-saved-bar">
                        {draftServerSavedSets.map(savedSet => (
                          <div
                            key={savedSet.id}
                            className="saved-portfolio-chip ticker-mapping-saved-chip"
                            draggable
                            title={`${savedSet.name} (${mappingSetSummary(savedSet)})`}
                            onClick={() => editSet && loadSavedSetIntoActive(savedSet.id, editSet.id)}
                            onDragStart={e => {
                              e.dataTransfer.setData('application/x-ticker-mapping-set', savedSet.id)
                              e.dataTransfer.effectAllowed = 'copy'
                            }}
                            onDragEnd={() => {
                              setDragOverSetId('')
                              setDragOverRowsSetId('')
                              setDragOverMapping(null)
                            }}
                          >
                            <span>{savedSet.name}</span>
                            <span className="ticker-mapping-chip-count">{savedSet.mappings.length}</span>
                            <button
                              className="saved-portfolio-chip-del"
                              type="button"
                              title="Delete"
                              onClick={e => { e.stopPropagation(); deleteSavedSet(savedSet.id) }}
                            >
                              <Trash2 size={12} />
                            </button>
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                  {draftLocalSavedSets.length > 0 && (
                    <>
                      <div className="ticker-mapping-saved-title">Local Legacy</div>
                      <div className="saved-portfolios-bar ticker-mapping-saved-bar">
                        {draftLocalSavedSets.map(savedSet => (
                          <div
                            key={savedSet.id}
                            className="saved-portfolio-chip ticker-mapping-saved-chip"
                            draggable
                            title={`${savedSet.name} (${mappingSetSummary(savedSet)})`}
                            onClick={() => editSet && loadSavedSetIntoActive(savedSet.id, editSet.id)}
                            onDragStart={e => {
                              e.dataTransfer.setData('application/x-ticker-mapping-set', savedSet.id)
                              e.dataTransfer.effectAllowed = 'copy'
                            }}
                            onDragEnd={() => {
                              setDragOverSetId('')
                              setDragOverRowsSetId('')
                              setDragOverMapping(null)
                            }}
                          >
                            <span>{savedSet.name}</span>
                            <span className="ticker-mapping-chip-count">{savedSet.mappings.length}</span>
                            <button
                              className="saved-portfolio-chip-del"
                              type="button"
                              title="Delete local legacy mapping"
                              onClick={e => { e.stopPropagation(); deleteSavedSet(savedSet.id) }}
                            >
                              <Trash2 size={12} />
                            </button>
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                </>
              ) : (
                <div className="ticker-mapping-saved-empty">No saved mappings yet.</div>
              )}
            </div>

            <div className="ticker-mapping-editor-grid">
              {editSet ? [editSet].map(set => {
                return (
                  <section
                    className={`ticker-mapping-editor-set${dragOverSetId === set.id ? ' drag-over' : ''}`}
                    key={set.id}
                    onDragOver={e => {
                      if (e.dataTransfer.types.includes('application/x-ticker-mapping-set') && !isSavedMappingRowsDrop(e)) {
                        e.preventDefault()
                        e.dataTransfer.dropEffect = 'copy'
                        setDragOverSetId(set.id)
                      }
                    }}
                    onDragLeave={e => {
                      const nextTarget = e.relatedTarget
                      if (!(nextTarget instanceof Node) || !e.currentTarget.contains(nextTarget)) {
                        setDragOverSetId('')
                      }
                    }}
                    onDrop={e => {
                      if (isSavedMappingRowsDrop(e)) return
                      const savedSetId = e.dataTransfer.getData('application/x-ticker-mapping-set')
                      if (savedSetId) {
                        e.preventDefault()
                        setDragOverSetId('')
                        loadSavedSetIntoActive(savedSetId, set.id)
                      }
                    }}
                  >
                    <div className="ticker-mapping-set-heading">
                      <label className="ticker-mapping-set-name">
                        <span>Name</span>
                        <input
                          value={set.name}
                          onChange={e => updateSet(set.id, { name: e.target.value })}
                        />
                      </label>
                      <span className={`ticker-mapping-save-state${localUnsaved ? ' unsaved' : ''}${referenceUpdated ? ' reference-updated' : ''}${referenceLost ? ' reference-lost' : ''}`}>
                        {editSetStatus}
                      </span>
                      <div className="ticker-mapping-set-actions">
                        <button type="button" className="add-ticker-btn" onClick={() => addMapping(set.id, 'start')}>+ Add to Start</button>
                        <button type="button" className="add-ticker-btn" onClick={() => addMapping(set.id, 'end')}>+ Add to End</button>
                        <button
                          type="button"
                          className="ticker-mapping-save-set"
                          title="Save mapping set"
                          aria-label={`Save ${set.name || 'mapping set'}`}
                          onClick={() => saveSetAsSaved(set.id)}
                        >
                          <Save size={14} />
                        </button>
                      </div>
                    </div>
                    <div className="ticker-mapping-order-note">Applied top to bottom. Drag rows to reorder chained mappings.</div>

                    <div
                      className={`ticker-mapping-rows${dragOverRowsSetId === set.id ? ' drag-over-child' : ''}`}
                      onDragOver={e => {
                        if (!e.dataTransfer.types.includes('application/x-ticker-mapping-set')) return
                        e.preventDefault()
                        e.dataTransfer.dropEffect = 'copy'
                        setDragOverSetId('')
                        setDragOverRowsSetId(set.id)
                      }}
                      onDragLeave={e => {
                        const nextTarget = e.relatedTarget
                        if (!(nextTarget instanceof Node) || !e.currentTarget.contains(nextTarget)) {
                          setDragOverRowsSetId('')
                        }
                      }}
                      onDrop={e => {
                        const savedSetId = e.dataTransfer.getData('application/x-ticker-mapping-set')
                        if (!savedSetId) return
                        e.preventDefault()
                        e.stopPropagation()
                        setDragOverRowsSetId('')
                        setDragOverMapping(null)
                        insertMappingRef(set.id, savedSetId)
                      }}
                    >
                      {set.mappings.length ? set.mappings.map((mapping, mappingIndex) => {
                        const isDragged = draggedMapping?.setId === set.id && draggedMapping.mappingId === mapping.id
                        const dropPosition = dragOverMapping?.setId === set.id && dragOverMapping.mappingId === mapping.id
                          ? dragOverMapping.position
                          : null
                        const mappingRef = isTickerMappingRef(mapping)
                        const mappingRefName = mappingRef ? tickerMappingRefName(mapping) : ''
                        const mappingRefExists = !!mappingRefName && draft.savedSets.some(savedSet => savedSet.name.trim().toLowerCase() === mappingRefName.toLowerCase())
                        return (
                        <div
                          className={`ticker-mapping-row${mappingRef ? ' mapping-ref-row' : ''}${mappingRef && mappingRefExists ? ' mapping-ref-row-exists' : ''}${mappingRef && !mappingRefExists ? ' mapping-ref-row-missing' : ''}${isDragged ? ' dragging' : ''}${dropPosition ? ` drop-${dropPosition}` : ''}`}
                          key={mapping.id}
                          onDragOver={e => {
                            if (!e.dataTransfer.types.includes('application/x-ticker-mapping-row') && !e.dataTransfer.types.includes('application/x-ticker-mapping-set')) return
                            e.preventDefault()
                            const isSavedSetDrop = e.dataTransfer.types.includes('application/x-ticker-mapping-set')
                            e.dataTransfer.dropEffect = isSavedSetDrop ? 'copy' : 'move'
                            const rect = e.currentTarget.getBoundingClientRect()
                            const position = e.clientY - rect.top > rect.height / 2 ? 'after' : 'before'
                            setDragOverRowsSetId(isSavedSetDrop ? set.id : '')
                            setDragOverMapping({ setId: set.id, mappingId: mapping.id, position })
                          }}
                          onDragLeave={e => {
                            const nextTarget = e.relatedTarget
                            if (!(nextTarget instanceof Node) || !e.currentTarget.contains(nextTarget)) {
                              setDragOverMapping(current => (
                                current?.setId === set.id && current.mappingId === mapping.id ? null : current
                              ))
                            }
                          }}
                          onDrop={e => {
                            const savedSetId = e.dataTransfer.getData('application/x-ticker-mapping-set')
                            if (savedSetId) {
                              e.preventDefault()
                              e.stopPropagation()
                              const position = dropPosition ?? 'before'
                              insertMappingRef(set.id, savedSetId, mapping.id, position)
                              setDragOverRowsSetId('')
                              setDragOverMapping(null)
                              return
                            }
                            const source = draggedMapping
                            if (!source || source.setId !== set.id) return
                            e.preventDefault()
                            e.stopPropagation()
                            const position = dropPosition ?? 'before'
                            moveMapping(set.id, source.mappingId, mapping.id, position)
                            setDraggedMapping(null)
                            setDragOverMapping(null)
                          }}
                        >
                          <button
                            type="button"
                            className="ticker-mapping-row-grip"
                            draggable
                            title={`Drag to reorder. Row ${mappingIndex + 1} runs ${mappingIndex === 0 ? 'first' : `after row ${mappingIndex}`}.`}
                            aria-label={`Drag mapping row ${mappingIndex + 1}`}
                            onDragStart={e => {
                              setDraggedMapping({ setId: set.id, mappingId: mapping.id })
                              e.dataTransfer.setData('application/x-ticker-mapping-row', `${set.id}:${mapping.id}`)
                              e.dataTransfer.effectAllowed = 'move'
                            }}
                            onDragEnd={() => {
                              setDraggedMapping(null)
                              setDragOverMapping(null)
                              setDragOverRowsSetId('')
                            }}
                          >
                            <GripVertical size={14} />
                          </button>
                          <span className="ticker-mapping-row-order">{mappingIndex + 1}</span>
                          {mappingRef ? (
                            <label className="ticker-mapping-ref-name" title={mappingRefExists ? 'Saved ticker mapping reference exists' : 'Saved ticker mapping reference not found'}>
                              <span className="ticker-mapping-ref-badge">Mapping</span>
                              <input
                                value={mappingRefName}
                                placeholder="Saved mapping name"
                                aria-label="Child mapping name"
                                onChange={e => updateMapping(set.id, mapping.id, { mappingRef: e.target.value })}
                                onBlur={e => updateMapping(set.id, mapping.id, { mappingRef: normalizeTarget(e.currentTarget.value) })}
                              />
                            </label>
                          ) : (
                            <>
                          <select
                            className="ticker-mapping-row-mode"
                            value={mapping.mode}
                            aria-label="Mapping mode"
                            onChange={e => updateMapping(set.id, mapping.id, { mode: e.target.value === 'replaceAll' ? 'replaceAll' : 'prepend' })}
                          >
                            <option value="prepend">🟢 Add Chain Entry</option>
                            <option value="replaceAll">🔴 Replace All</option>
                          </select>
                          <select
                            className="ticker-mapping-row-apply-to"
                            value={mapping.applyTo}
                            aria-label="Apply mapping to"
                            onChange={e => updateMapping(set.id, mapping.id, { applyTo: e.target.value === 'ticker' ? 'ticker' : 'expression' })}
                          >
                            <option value="expression">🟣 Full Expression</option>
                            <option value="ticker">🔵 Ticker Only</option>
                          </select>
                          <input
                            value={mapping.from}
                            placeholder="CTAP"
                            aria-label="Source ticker"
                            onChange={e => updateMapping(set.id, mapping.id, { from: e.target.value.toUpperCase() })}
                            onBlur={e => updateMapping(set.id, mapping.id, { from: normalizeSource(e.currentTarget.value) })}
                          />
                          <span>-&gt;</span>
                          <input
                            value={mapping.to}
                            placeholder="1 CTA 1 SPY E=1.5"
                            aria-label="Mapped ticker expression"
                            onChange={e => updateMapping(set.id, mapping.id, { to: e.target.value })}
                            onBlur={e => updateMapping(set.id, mapping.id, { to: normalizeTarget(e.currentTarget.value) })}
                          />
                            </>
                          )}
                          <button
                            type="button"
                            className="ticker-mapping-row-remove"
                            title="Remove mapping"
                            aria-label={`Remove ${mappingRefName || mapping.from || 'mapping'}`}
                            onClick={() => removeMapping(set.id, mapping.id)}
                          >
                            <X size={13} />
                          </button>
                        </div>
                      )}) : <div className="ticker-mapping-empty">No mappings saved.</div>}
                    </div>
                  </section>
                )
              }) : null}
            </div>

            {error && <div className="ticker-config-error">{error}</div>}
          </div>
        </div>
      )}
    </>
  )
}
