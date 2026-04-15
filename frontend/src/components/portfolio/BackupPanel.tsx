// ── BackupPanel.tsx — Port of initBackupPanel from backup.js ─────────────────
import { useEffect, useRef, useState } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { formatSavedAt } from '@/lib/portfolio-utils'
import type { BackupEntry } from '@/types/portfolio'
import { showConfirm } from '@/components/ConfirmDialog'

interface Props {
  onClose: () => void
  onImportSuccess?: (json: any) => void
}

type GroupedBackups = Array<{ tabName: string; entries: BackupEntry[] }>

export default function BackupPanel({ onClose, onImportSuccess }: Props) {
  const { portfolioId } = usePortfolioStore()
  const [groups, setGroups] = useState<GroupedBackups>([])
  const [activeTab, setActiveTab] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    load()
  }, [portfolioId])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      await fetch(`/api/backup/trigger?portfolio=${portfolioId}`, { method: 'POST' }).catch(() => {})
      const resp = await fetch(`/api/backup/list-db?portfolio=${portfolioId}`)
      const entries: BackupEntry[] = await resp.json()
      const groupMap = new Map<string, BackupEntry[]>()
      for (const e of entries) {
        const tab = e.label === '' ? 'Daily' : e.label.charAt(0).toUpperCase() + e.label.slice(1)
        if (!groupMap.has(tab)) groupMap.set(tab, [])
        groupMap.get(tab)!.push(e)
      }
      const grouped: GroupedBackups = Array.from(groupMap.entries()).map(([tabName, ents]) => ({ tabName, entries: ents }))
      setGroups(grouped)
      setActiveTab(grouped[0]?.tabName ?? '')
    } catch (e) {
      setError('Failed to load backup list.')
    } finally {
      setLoading(false)
    }
  }

  async function handleRestore(id: number) {
    try {
      const r = await fetch(`/api/backup/restore-db?portfolio=${portfolioId}&id=${id}`, { method: 'POST' })
      const json = await r.json()
      if (json.status === 'ok') { onClose(); window.location.reload() }
      else alert('Restore failed: ' + (json.message ?? 'Unknown error'))
    } catch { alert('Restore failed.') }
  }

  async function handleDelete(id: number) {
    try {
      await fetch(`/api/backup/delete-db?portfolio=${portfolioId}&id=${id}`, { method: 'DELETE' })
      setGroups(prev => prev.map(g => ({
        ...g,
        entries: g.entries.filter(e => e.id !== id),
      })).filter(g => g.entries.length > 0))
    } catch {}
  }

  async function handleDeleteAll() {
    if (!await showConfirm('Delete all backups for this portfolio? This cannot be undone.', 'Delete All')) return
    try {
      await fetch(`/api/backup/delete-all?portfolio=${portfolioId}`, { method: 'DELETE' })
      setGroups([])
    } catch {}
  }

  async function handleExport() {
    try {
      const resp = await fetch(`/api/backup/export-json?portfolio=${portfolioId}`)
      if (!resp.ok) { alert('Export failed.'); return }
      const blob = await resp.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `backup-${portfolioId}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    } catch (e) { alert(`Export failed: ${e}`) }
  }

  async function handleImport(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    try {
      const resp = await fetch(`/api/backup/import-file?portfolio=${portfolioId}`, { method: 'POST', body: fd })
      const json = await resp.json()
      if (json.error) { alert('Import error: ' + json.error); return }
      if (onImportSuccess) {
        onImportSuccess(json)
      } else {
        await load()
      }
    } catch (e) { alert('Import failed: ' + e) }
  }

  const allEntries = groups.flatMap(g => g.entries)

  return (
    <div
      className="backup-modal-overlay"
      onClick={e => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="backup-modal" role="dialog" aria-modal="true">
        <div className="backup-modal-header">
          <p className="backup-modal-title">Backups</p>
          {allEntries.length > 0 && (
            <button className="backup-modal-remove-all" onClick={handleDeleteAll}>
              Remove All
            </button>
          )}
        </div>

        {loading && <p className="backup-modal-empty">Loading…</p>}
        {error && <p className="backup-modal-empty" style={{ color: 'var(--color-negative)' }}>{error}</p>}

        {!loading && !error && allEntries.length === 0 && (
          <p className="backup-modal-empty">No backups available.</p>
        )}

        {!loading && groups.length > 0 && (
          <div className="backup-modal-body">
            {groups.length > 1 && (
              <div className="backup-modal-tabs">
                {groups.map(g => (
                  <button
                    key={g.tabName}
                    className={`backup-modal-tab${activeTab === g.tabName ? ' active' : ''}`}
                    onClick={() => setActiveTab(g.tabName)}
                  >
                    {g.tabName}
                  </button>
                ))}
              </div>
            )}
            {groups.map(g => (
              <div
                key={g.tabName}
                className="backup-modal-panel"
                hidden={g.tabName !== activeTab}
              >
                <ul className="backup-modal-list">
                  {g.entries.map(entry => (
                    <li key={entry.id} className="backup-modal-item">
                      <span>{formatSavedAt(entry.createdAt)}</span>
                      <div className="backup-modal-item-actions">
                        <button onClick={() => handleRestore(entry.id)}>Restore</button>
                        <button
                          className="backup-modal-item-del"
                          title="Delete this backup"
                          onClick={() => handleDelete(entry.id)}
                        >
                          ✕
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}

        <div className="backup-modal-footer">
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.txt,.zip,.json"
            style={{ display: 'none' }}
            onChange={e => { if (e.target.files?.[0]) handleImport(e.target.files[0]) }}
          />
          <button
            className="backup-modal-import btn-outline-accent"
            onClick={() => fileInputRef.current?.click()}
          >
            Import
          </button>
          <button className="backup-modal-export btn-outline-accent" onClick={handleExport}>
            Export
          </button>
          <button className="backup-modal-close" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  )
}
