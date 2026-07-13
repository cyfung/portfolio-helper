import { useEffect, useMemo, useState } from 'react'
import { Plus, RotateCcw, Save, Search, X } from 'lucide-react'
import { ConfigButton, HeaderRight, PageNavTabs, PrivacyToggleButton, ThemeToggle } from '@/components/Layout'
import { compressToCode, decompressFromCode } from '@/lib/compress'

interface TickerConfigDto {
  symbol: string
  letf: string
  groups: string
}

interface TickerRow extends TickerConfigDto {
  id: string
  original: TickerConfigDto | null
  isNew: boolean
  saving: boolean
}

type StatusKind = 'ok' | 'warn' | 'error' | ''

interface TickerConfigImportPayload {
  tickerConfigs?: unknown
}

function normalizeSymbol(value: string) {
  return value.replace(/\s+/g, '').toUpperCase().slice(0, 32)
}

function trimConfig(row: TickerConfigDto): TickerConfigDto {
  return {
    symbol: normalizeSymbol(row.symbol),
    letf: row.letf.trim(),
    groups: row.groups.trim(),
  }
}

function rowKey() {
  return `ticker-row-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function isDirty(row: TickerRow) {
  const current = trimConfig(row)
  if (!current.symbol && !current.letf && !current.groups) return false
  if (!row.original) return !!current.symbol && (!!current.letf || !!current.groups)
  return (
    current.symbol !== row.original.symbol ||
    current.letf !== row.original.letf ||
    current.groups !== row.original.groups
  )
}

function countWithMetadata(rows: TickerRow[]) {
  return rows.filter(row => row.letf.trim() || row.groups.trim()).length
}

function toRows(
  configs: TickerConfigDto[],
  existingBySymbol = new Map<string, TickerRow>(),
  assumeExisting = true,
): TickerRow[] {
  return configs.map(item => {
    const config = trimConfig(item)
    const existing = existingBySymbol.get(config.symbol)
    return {
      ...config,
      id: existing?.id ?? `ticker-${config.symbol}`,
      original: existing?.original ?? (assumeExisting ? config : null),
      isNew: existing?.isNew ?? !assumeExisting,
      saving: false,
    }
  })
}

function parseTickerConfigs(value: unknown): TickerConfigDto[] {
  const source: unknown[] = Array.isArray(value)
    ? value
    : Array.isArray((value as TickerConfigImportPayload | null)?.tickerConfigs)
      ? (value as { tickerConfigs: unknown[] }).tickerConfigs
      : []

  const bySymbol = new Map<string, TickerConfigDto>()
  source.forEach(item => {
    if (!item || typeof item !== 'object') return
    const record = item as Record<string, unknown>
    const symbol = normalizeSymbol(String(record.symbol ?? ''))
    const letf = String(record.letf ?? '').trim()
    const groups = String(record.groups ?? '').trim()
    if (!symbol) return
    if (!letf && !groups) return
    bySymbol.set(symbol, {
      symbol,
      letf,
      groups,
    })
  })
  return [...bySymbol.values()].sort((a, b) => a.symbol.localeCompare(b.symbol))
}

export default function TickerEditPage() {
  const [rows, setRows] = useState<TickerRow[]>([])
  const [query, setQuery] = useState('')
  const [importCode, setImportCode] = useState('')
  const [configError, setConfigError] = useState('')
  const [loading, setLoading] = useState(false)
  const [savingAll, setSavingAll] = useState(false)
  const [status, setStatus] = useState<{ kind: StatusKind; text: string }>({ kind: '', text: '' })

  useEffect(() => { loadRows() }, [])

  const dirtyCount = rows.filter(isDirty).length
  const filteredRows = useMemo(() => {
    const q = query.trim().toUpperCase()
    if (!q) return rows
    return rows.filter(row =>
      row.symbol.toUpperCase().includes(q) ||
      row.letf.toUpperCase().includes(q) ||
      row.groups.toUpperCase().includes(q)
    )
  }, [query, rows])

  async function loadRows() {
    setLoading(true)
    setStatus({ kind: '', text: '' })
    try {
      const res = await fetch('/api/ticker-config')
      if (!res.ok) throw new Error(await res.text() || `HTTP ${res.status}`)
      const data = await res.json() as TickerConfigDto[]
      const nextRows = toRows(data)
      setRows(nextRows)
      setStatus({ kind: 'ok', text: `Loaded ${nextRows.length} ticker${nextRows.length === 1 ? '' : 's'}.` })
    } catch (err) {
      setStatus({ kind: 'error', text: err instanceof Error ? err.message : 'Failed to load ticker configs.' })
    } finally {
      setLoading(false)
    }
  }

  async function handleExport() {
    const tickerConfigs = rows
      .map(row => trimConfig(row))
      .filter(row => row.symbol && (row.letf || row.groups))
      .sort((a, b) => a.symbol.localeCompare(b.symbol))
    const code = await compressToCode({ tickerConfigs })
    setImportCode(code)
    try {
      await navigator.clipboard.writeText(code)
      setStatus({ kind: 'ok', text: 'Export code copied.' })
    } catch {
      setStatus({ kind: 'ok', text: 'Export code generated.' })
    }
  }

  async function handleImport() {
    if (!importCode.trim()) return
    try {
      const payload = await decompressFromCode(importCode.trim())
      const importedConfigs = parseTickerConfigs(payload)
      if (importedConfigs.length === 0) throw new Error('No ticker configs found.')

      setRows(current => {
        const existingBySymbol = new Map(current.map(row => [normalizeSymbol(row.symbol), row]))
        const importedBySymbol = new Map(importedConfigs.map(config => [config.symbol, config]))
        const preservedRows = current.filter(row => !importedBySymbol.has(normalizeSymbol(row.symbol)))
        return [
          ...toRows(importedConfigs, existingBySymbol, false),
          ...preservedRows,
        ]
      })
      setConfigError('')
      setStatus({ kind: 'ok', text: `Imported ${importedConfigs.length} ticker${importedConfigs.length === 1 ? '' : 's'}. Review and save changes.` })
    } catch {
      setConfigError('Invalid config code.')
      setTimeout(() => setConfigError(''), 3000)
    }
  }

  function updateRow(id: string, patch: Partial<TickerConfigDto>) {
    setRows(current => current.map(row => row.id === id ? { ...row, ...patch } : row))
  }

  function addRow() {
    setRows(current => [
      { id: rowKey(), symbol: '', letf: '', groups: '', original: null, isNew: true, saving: false },
      ...current,
    ])
  }

  function removeNewRow(id: string) {
    setRows(current => current.filter(row => row.id !== id))
  }

  async function removeRow(id: string): Promise<boolean> {
    const row = rows.find(item => item.id === id)
    if (!row) return false
    const symbol = normalizeSymbol(row.symbol)
    if (!symbol || !row.original) {
      removeNewRow(id)
      return true
    }

    setRows(current => current.map(item => item.id === id ? { ...item, saving: true } : item))
    setStatus({ kind: '', text: '' })
    try {
      const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(symbol)}`, { method: 'DELETE' })
      if (!res.ok) throw new Error(await res.text() || `HTTP ${res.status}`)
      setRows(current => current.filter(item => item.id !== id))
      setStatus({ kind: 'ok', text: `Removed ${symbol}.` })
      return true
    } catch (err) {
      setRows(current => current.map(item => item.id === id ? { ...item, saving: false } : item))
      setStatus({ kind: 'error', text: err instanceof Error ? err.message : `Failed to remove ${symbol}.` })
      return false
    }
  }

  function resetRow(id: string) {
    setRows(current => current.map(row => {
      if (row.id !== id) return row
      if (!row.original) return { ...row, symbol: '', letf: '', groups: '' }
      return { ...row, ...row.original }
    }))
  }

  function duplicateSymbol(row: TickerRow) {
    const symbol = normalizeSymbol(row.symbol)
    return !!symbol && rows.some(other => other.id !== row.id && normalizeSymbol(other.symbol) === symbol)
  }

  async function saveRow(id: string) {
    const row = rows.find(item => item.id === id)
    if (!row) return false

    const payload = trimConfig(row)
    if (!payload.symbol) {
      setStatus({ kind: 'warn', text: 'Symbol is required.' })
      return false
    }
    if (!payload.letf && !payload.groups) {
      if (!row.original) {
        removeNewRow(id)
        return true
      }
      return await removeRow(id)
    }
    if (duplicateSymbol(row)) {
      setStatus({ kind: 'warn', text: `${payload.symbol} appears more than once.` })
      return false
    }

    setRows(current => current.map(item => item.id === id ? { ...item, saving: true } : item))
    setStatus({ kind: '', text: '' })
    try {
      const res = await fetch(`/api/ticker-config?symbol=${encodeURIComponent(payload.symbol)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ letf: payload.letf, groups: payload.groups }),
      })
      if (!res.ok) throw new Error(await res.text() || `HTTP ${res.status}`)
      const saved = trimConfig(await res.json() as TickerConfigDto)
      setRows(current => current.map(item => item.id === id
        ? { ...item, ...saved, id: `ticker-${saved.symbol}`, original: saved, isNew: false, saving: false }
        : item
      ))
      setStatus({ kind: 'ok', text: `Saved ${saved.symbol}.` })
      return true
    } catch (err) {
      setRows(current => current.map(item => item.id === id ? { ...item, saving: false } : item))
      setStatus({ kind: 'error', text: err instanceof Error ? err.message : `Failed to save ${payload.symbol}.` })
      return false
    }
  }

  async function saveAll() {
    if (savingAll) return
    const dirtyRows = rows.filter(isDirty)
    if (dirtyRows.length === 0) {
      setStatus({ kind: 'ok', text: 'No changes to save.' })
      return
    }

    setSavingAll(true)
    let savedCount = 0
    for (const row of dirtyRows) {
      const ok = await saveRow(row.id)
      if (!ok) {
        setSavingAll(false)
        return
      }
      savedCount += 1
    }
    setSavingAll(false)
    setStatus({ kind: 'ok', text: `Saved ${savedCount} ticker${savedCount === 1 ? '' : 's'}.` })
  }

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/ticker-edit" />
        </div>
        <HeaderRight>
          <PrivacyToggleButton />
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      <div className="ticker-edit-config-card">
        <div className="backtest-config-controls">
          <label htmlFor="ticker-edit-import-code">Config Code</label>
          <div className="backtest-config-group">
            <input
              type="text"
              id="ticker-edit-import-code"
              placeholder="Paste code..."
              spellCheck={false}
              value={importCode}
              onChange={e => setImportCode(e.target.value)}
            />
            <button className="backtest-config-btn" type="button" onClick={handleImport}>Import</button>
            <button className="backtest-config-btn" type="button" onClick={handleExport}>Export</button>
            {configError && <div className="backtest-config-error">{configError}</div>}
          </div>
        </div>
      </div>

      <div className="ticker-edit-toolbar">
        <div className="ticker-edit-title">
          <h1>Ticker Editor</h1>
          <span>{countWithMetadata(rows)} configured / {rows.length} total</span>
        </div>
        <div className="ticker-edit-actions">
          <label className="ticker-edit-search">
            <Search size={15} aria-hidden="true" />
            <input
              type="search"
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Filter"
            />
          </label>
          <button className="h-btn subtle" type="button" onClick={addRow}>
            <Plus size={15} aria-hidden="true" />
            Add
          </button>
          <button className="h-btn subtle" type="button" onClick={loadRows} disabled={loading || savingAll}>
            <RotateCcw size={15} aria-hidden="true" />
            Reload
          </button>
          <button className="h-btn primary" type="button" onClick={saveAll} disabled={savingAll || dirtyCount === 0}>
            <Save size={15} aria-hidden="true" />
            {savingAll ? 'Saving...' : `Save All${dirtyCount ? ` (${dirtyCount})` : ''}`}
          </button>
        </div>
      </div>

      {status.text && <div className={`ticker-edit-status ticker-edit-status-${status.kind}`}>{status.text}</div>}

      <div className="ticker-edit-table-wrap">
        <table className="ticker-edit-table">
          <thead>
            <tr>
              <th>Symbol</th>
              <th>LETF</th>
              <th>Groups</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {filteredRows.map(row => {
              const dirty = isDirty(row)
              const duplicate = duplicateSymbol(row)
              return (
                <tr key={row.id} className={dirty ? 'ticker-row-dirty' : undefined}>
                  <td className="ticker-symbol-cell">
                    <input
                      className={duplicate ? 'ticker-edit-invalid' : undefined}
                      value={row.symbol}
                      onChange={e => updateRow(row.id, { symbol: normalizeSymbol(e.target.value) })}
                      disabled={!row.isNew}
                      placeholder="SPY"
                    />
                  </td>
                  <td>
                    <input
                      value={row.letf}
                      onChange={e => updateRow(row.id, { letf: e.target.value })}
                      placeholder="3,SPY"
                    />
                  </td>
                  <td>
                    <input
                      value={row.groups}
                      onChange={e => updateRow(row.id, { groups: e.target.value })}
                      placeholder="1 Equity;0.5 Hedge"
                    />
                  </td>
                  <td className="ticker-edit-state-cell">
                    {duplicate ? 'Duplicate' : row.saving ? 'Saving' : dirty ? 'Unsaved' : 'Saved'}
                  </td>
                  <td className="ticker-edit-row-actions">
                    <button
                      className="h-btn subtle icon-only"
                      type="button"
                      onClick={() => resetRow(row.id)}
                      disabled={row.saving || !dirty}
                      aria-label={`Reset ${row.symbol || 'ticker'}`}
                      title="Reset"
                    >
                      <RotateCcw size={15} aria-hidden="true" />
                    </button>
                    <button
                      className="h-btn subtle icon-only ticker-edit-remove-btn"
                      type="button"
                      onClick={() => removeRow(row.id)}
                      disabled={row.saving}
                      aria-label={`Remove ${row.symbol || 'ticker'}`}
                      title="Remove"
                    >
                      <X size={15} aria-hidden="true" />
                    </button>
                    <button
                      className="h-btn primary icon-only"
                      type="button"
                      onClick={() => saveRow(row.id)}
                      disabled={row.saving || !dirty || duplicate}
                      aria-label={`Save ${row.symbol || 'ticker'}`}
                      title="Save"
                    >
                      <Save size={15} aria-hidden="true" />
                    </button>
                  </td>
                </tr>
              )
            })}
            {!loading && filteredRows.length === 0 && (
              <tr>
                <td className="ticker-edit-empty" colSpan={5}>No tickers match the current filter.</td>
              </tr>
            )}
            {loading && (
              <tr>
                <td className="ticker-edit-empty" colSpan={5}>Loading...</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
