import { useEffect, useState } from 'react'
import { Download, GripVertical, Save, Settings, Trash2, X } from 'lucide-react'
import { compressToCode } from '@/lib/compress'
import { buildSavedTickerMappingsExportPayload } from '@/lib/configImportExport'
import {
  loadTickerMappingSettings,
  mappingSetSummary,
  saveTickerMappingSettings,
  selectedTickerMappingSet,
  tickerMappingSetHash,
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
  const [draggedMapping, setDraggedMapping] = useState<{ setId: string; mappingId: string } | null>(null)
  const [dragOverMapping, setDragOverMapping] = useState<{
    setId: string
    mappingId: string
    position: MappingDropPosition
  } | null>(null)

  useEffect(() => {
    setDraft(value)
  }, [value])

  function persist(next: TickerMappingSettings) {
    saveTickerMappingSettings(next)
    onChange(next)
  }

  function updateDraft(next: TickerMappingSettings) {
    setDraft(next)
    persist(next)
  }

  function selectSet(selectedSetId: string) {
    persist({ ...value, selectedSetId })
  }

  function openEditor() {
    setDraft(loadTickerMappingSettings())
    setError('')
    setExportStatus('')
    setEditorOpen(true)
  }

  async function exportSavedMappings() {
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

  function addMapping(setId: string) {
    updateDraft({
      ...draft,
      sets: draft.sets.map(set => set.id === setId
        ? { ...set, mappings: [...set.mappings, { id: newMappingId(), from: '', to: '', mode: 'prepend', applyTo: 'expression' }] }
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

    const existing = draft.savedSets.find(saved => saved.name.trim().toLowerCase() === name.toLowerCase())
    const savedSet: TickerMappingSet = {
      id: existing?.id ?? newMappingSetId(),
      name,
      mappings: cloneMappings(mappings),
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

  const selectedSet = selectedTickerMappingSet(value)
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
          >
            <option value="">None</option>
            {value.savedSets.length > 0 && (
              <optgroup label="Saved">
                {value.savedSets.map(set => (
                  <option key={set.id} value={set.id}>{set.name} ({mappingSetSummary(set)})</option>
                ))}
              </optgroup>
            )}
          </select>
          <button type="button" className="ticker-config-btn ticker-mapping-config-btn" onClick={openEditor} title="Configure ticker mappings" aria-label="Configure ticker mappings">
            <Settings size={15} />
          </button>
          <button type="button" className="backtest-config-btn ticker-mapping-export-btn" onClick={exportSavedMappings} title="Export saved ticker mappings" aria-label="Export saved ticker mappings">
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
                <div className="saved-portfolios-bar ticker-mapping-saved-bar">
                  {draft.savedSets.map(savedSet => (
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
                      onDragEnd={() => setDragOverSetId('')}
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
                      if (e.dataTransfer.types.includes('application/x-ticker-mapping-set')) {
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
                        <button type="button" className="add-ticker-btn" onClick={() => addMapping(set.id)}>+ Add Mapping</button>
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

                    <div className="ticker-mapping-rows">
                      {set.mappings.length ? set.mappings.map((mapping, mappingIndex) => {
                        const isDragged = draggedMapping?.setId === set.id && draggedMapping.mappingId === mapping.id
                        const dropPosition = dragOverMapping?.setId === set.id && dragOverMapping.mappingId === mapping.id
                          ? dragOverMapping.position
                          : null
                        return (
                        <div
                          className={`ticker-mapping-row${isDragged ? ' dragging' : ''}${dropPosition ? ` drop-${dropPosition}` : ''}`}
                          key={mapping.id}
                          onDragOver={e => {
                            if (!e.dataTransfer.types.includes('application/x-ticker-mapping-row')) return
                            e.preventDefault()
                            e.dataTransfer.dropEffect = 'move'
                            const rect = e.currentTarget.getBoundingClientRect()
                            const position = e.clientY - rect.top > rect.height / 2 ? 'after' : 'before'
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
                            const source = draggedMapping
                            if (!source || source.setId !== set.id) return
                            e.preventDefault()
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
                            }}
                          >
                            <GripVertical size={14} />
                          </button>
                          <span className="ticker-mapping-row-order">{mappingIndex + 1}</span>
                          <select
                            className="ticker-mapping-row-mode"
                            value={mapping.mode}
                            aria-label="Mapping mode"
                            onChange={e => updateMapping(set.id, mapping.id, { mode: e.target.value === 'replaceAll' ? 'replaceAll' : 'prepend' })}
                          >
                            <option value="prepend">Add Chain Entry</option>
                            <option value="replaceAll">Replace All</option>
                          </select>
                          <select
                            className="ticker-mapping-row-apply-to"
                            value={mapping.applyTo}
                            aria-label="Apply mapping to"
                            onChange={e => updateMapping(set.id, mapping.id, { applyTo: e.target.value === 'ticker' ? 'ticker' : 'expression' })}
                          >
                            <option value="expression">Full Expression</option>
                            <option value="ticker">Ticker Only</option>
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
                          <button
                            type="button"
                            className="ticker-mapping-row-remove"
                            title="Remove mapping"
                            aria-label={`Remove ${mapping.from || 'mapping'}`}
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
