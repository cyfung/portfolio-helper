import { useEffect, useMemo, useState } from 'react'
import type { CSSProperties } from 'react'
import {
  rewriteImportConfigPortfolioRefs,
  type ImportDependencyAction,
  type ImportDependencyPreview,
} from '@/lib/configImportExport'

interface ImportDependenciesDialogProps {
  preview: ImportDependencyPreview
  config: Record<string, unknown>
  applying: boolean
  error: string
  onCancel: () => void
  onConfirm: (preview: ImportDependencyPreview, config: Record<string, unknown>) => void
}

type NamedSection = 'savedPortfolios' | 'savedStrategies' | 'savedTickerMappings'

function actionLabel(action: ImportDependencyAction) {
  return action === 'replace' ? 'Replace' : 'Add'
}

function emptyValue(value: string) {
  return value || 'empty'
}

function lowerName(value: string) {
  return value.trim().toLowerCase()
}

function statusClass(status: string) {
  if (status === 'Add') return 'add'
  if (status === 'Replace') return 'replace'
  if (status === 'No import') return 'disabled'
  return 'error'
}

function currentAction(currentNames: string[], name: string): ImportDependencyAction {
  const normalized = lowerName(name)
  return currentNames.some(currentName => lowerName(currentName) === normalized) ? 'replace' : 'add'
}

function changedPreviewAction(
  preview: ImportDependencyPreview,
  section: NamedSection,
  originalName: string,
  nextName: string,
) {
  const action = currentAction(preview.currentNames[section], nextName)
  return {
    ...preview,
    [section]: preview[section].map(item => (
      item.originalName === originalName ? { ...item, name: nextName, action } : item
    )),
  }
}

export default function ImportDependenciesDialog({
  preview,
  config,
  applying,
  error,
  onCancel,
  onConfirm,
}: ImportDependenciesDialogProps) {
  const [draft, setDraft] = useState(preview)

  useEffect(() => setDraft(preview), [preview])

  const savedPortfolioByName = useMemo(
    () => new Map(draft.savedPortfolios.map(portfolio => [portfolio.originalName, portfolio])),
    [draft.savedPortfolios],
  )

  const duplicateNames = useMemo(() => {
    const duplicates: Record<NamedSection, Set<string>> = {
      savedPortfolios: new Set(),
      savedStrategies: new Set(),
      savedTickerMappings: new Set(),
    }

    ;(['savedPortfolios', 'savedStrategies', 'savedTickerMappings'] as NamedSection[]).forEach(section => {
      const counts = new Map<string, number>()
      draft[section]
        .filter(item => item.enabled !== false)
        .forEach(item => {
          const name = lowerName(item.name)
          if (!name) return
          counts.set(name, (counts.get(name) ?? 0) + 1)
        })
      counts.forEach((count, name) => {
        if (count > 1) duplicates[section].add(name)
      })
    })

    return duplicates
  }, [draft])

  function portfolioEffectiveEnabled(originalName: string, stack: string[] = []): boolean {
    const portfolio = savedPortfolioByName.get(originalName)
    if (!portfolio || portfolio.enabled === false) return false
    if (stack.includes(originalName)) return true
    return portfolio.parentNames.every(parentName => portfolioEffectiveEnabled(parentName, [...stack, originalName]))
  }

  function namedStatus(section: NamedSection, item: { name: string; action: ImportDependencyAction; enabled?: boolean }) {
    if (item.enabled === false) return 'No import'
    const name = lowerName(item.name)
    if (!name) return 'Name required'
    if (duplicateNames[section].has(name)) return 'Duplicate'
    return actionLabel(item.action)
  }

  const invalidDraft = useMemo(
    () => (['savedPortfolios', 'savedStrategies', 'savedTickerMappings'] as NamedSection[]).some(section =>
      draft[section].some(item => {
        if (item.enabled === false) return false
        const name = lowerName(item.name)
        return !name || duplicateNames[section].has(name)
      }),
    ),
    [draft, duplicateNames],
  )

  const portfolioOrder = useMemo(() => {
    const byName = new Map(draft.savedPortfolios.map(portfolio => [portfolio.originalName, portfolio]))
    const seen = new Set<string>()
    const ordered: { name: string; depth: number }[] = []
    const roots = draft.savedPortfolios
      .filter(portfolio => portfolio.referencedByImport || portfolio.parentNames.length === 0)
      .sort((a, b) => Number(b.referencedByImport) - Number(a.referencedByImport) || a.originalName.localeCompare(b.originalName))

    function visit(name: string, depth: number, stack: string[] = []) {
      if (seen.has(name) || stack.includes(name)) return
      const portfolio = byName.get(name)
      if (!portfolio) return
      seen.add(name)
      ordered.push({ name, depth })
      portfolio.childNames.forEach(childName => visit(childName, depth + 1, [...stack, name]))
    }

    roots.forEach(portfolio => visit(portfolio.originalName, 0))
    draft.savedPortfolios.forEach(portfolio => visit(portfolio.originalName, 0))
    return ordered
  }, [draft.savedPortfolios])

  function setTickerConfigEnabled(symbol: string, enabled: boolean) {
    setDraft(current => ({
      ...current,
      tickerConfigs: current.tickerConfigs.map(row => row.symbol === symbol ? { ...row, enabled } : row),
    }))
  }

  function setNamedEnabled(section: NamedSection, originalName: string, enabled: boolean) {
    setDraft(current => ({
      ...current,
      [section]: current[section].map(item => item.originalName === originalName ? { ...item, enabled } : item),
    }))
  }

  function setPortfolioEnabled(originalName: string, enabled: boolean) {
    setDraft(current => {
      const byName = new Map(current.savedPortfolios.map(portfolio => [portfolio.originalName, portfolio]))
      const changed = new Set<string>()

      function visit(name: string) {
        if (changed.has(name)) return
        changed.add(name)
        if (!enabled) byName.get(name)?.childNames.forEach(visit)
      }

      visit(originalName)
      return {
        ...current,
        savedPortfolios: current.savedPortfolios.map(portfolio =>
          changed.has(portfolio.originalName) ? { ...portfolio, enabled } : portfolio,
        ),
      }
    })
  }

  function setNamedName(section: NamedSection, originalName: string, nextName: string) {
    setDraft(current => changedPreviewAction(current, section, originalName, nextName))
  }

  function confirm() {
    const editedConfig = rewriteImportConfigPortfolioRefs(config, draft)
    onConfirm(draft, editedConfig)
  }

  function renderNamedRow(section: NamedSection, item: {
    originalName: string
    name: string
    action: ImportDependencyAction
    enabled?: boolean
  }, options: { disabled?: boolean; depth?: number; relation?: string } = {}) {
    const enabled = item.enabled !== false && !options.disabled
    const status = options.disabled ? 'No import' : namedStatus(section, item)
    return (
      <li
        key={item.originalName}
        className={`import-dependency-row${enabled ? '' : ' disabled'}`}
        style={{ '--dependency-depth': String(options.depth ?? 0) } as CSSProperties}
      >
        <label className="import-dependency-toggle">
          <input
            type="checkbox"
            checked={item.enabled !== false}
            disabled={applying || options.disabled}
            onChange={e => section === 'savedPortfolios'
              ? setPortfolioEnabled(item.originalName, e.target.checked)
              : setNamedEnabled(section, item.originalName, e.target.checked)}
          />
        </label>
        <div className="import-dependency-name-cell">
          <input
            type="text"
            value={item.name}
            disabled={applying || !enabled}
            onChange={e => setNamedName(section, item.originalName, e.target.value)}
            aria-label={`Import name for ${item.originalName}`}
          />
          {options.relation && <span>{options.relation}</span>}
        </div>
        <strong className={`import-dependency-status ${statusClass(status)}`}>{status}</strong>
      </li>
    )
  }

  return (
    <div className="ticker-config-overlay" role="dialog" aria-modal="true" onMouseDown={e => {
      if (e.target === e.currentTarget && !applying) onCancel()
    }}>
      <div className="ticker-config-dialog import-dependencies-dialog" onClick={e => e.stopPropagation()}>
        <div className="ticker-config-header">
          <h2>Import Dependencies</h2>
          <button type="button" className="ticker-config-close" disabled={applying} onClick={onCancel}>x</button>
        </div>

        <div className="import-dependencies-body">
          {draft.savedPortfolios.length > 0 && (
            <section>
              <h3>Child Portfolios</h3>
              <ul className="import-dependencies-list">
                {portfolioOrder.map(({ name, depth }) => {
                  const portfolio = savedPortfolioByName.get(name)!
                  const parentsDisabled = portfolio.parentNames.some(parentName => !portfolioEffectiveEnabled(parentName))
                  const relation = [
                    portfolio.referencedByImport ? 'Used by imported config' : '',
                    portfolio.parentNames.length > 0 ? `Parent: ${portfolio.parentNames.join(', ')}` : '',
                    portfolio.childNames.length > 0 ? `Children: ${portfolio.childNames.join(', ')}` : '',
                  ].filter(Boolean).join(' | ')
                  return renderNamedRow('savedPortfolios', portfolio, { disabled: parentsDisabled, depth, relation })
                })}
              </ul>
            </section>
          )}

          {draft.tickerConfigs.length > 0 && (
            <section>
              <h3>Ticker Settings</h3>
              <div className="import-dependencies-table-wrap">
                <table className="portfolio-table import-dependencies-table">
                  <thead>
                    <tr>
                      <th>Import</th>
                      <th>Symbol</th>
                      <th>Current LETF</th>
                      <th>Import LETF</th>
                      <th>Current Groups</th>
                      <th>Import Groups</th>
                    </tr>
                  </thead>
                  <tbody>
                    {draft.tickerConfigs.map(row => {
                      const enabled = row.enabled !== false
                      return (
                        <tr key={row.symbol} className={enabled ? '' : 'import-dependency-disabled-row'}>
                          <td>
                            <input
                              type="checkbox"
                              checked={enabled}
                              disabled={applying}
                              onChange={e => setTickerConfigEnabled(row.symbol, e.target.checked)}
                              aria-label={`Import ticker settings for ${row.symbol}`}
                            />
                          </td>
                          <td>{row.symbol}</td>
                          <td>{emptyValue(row.current.letf)}</td>
                          <td>{emptyValue(row.next.letf)}</td>
                          <td>{emptyValue(row.current.groups)}</td>
                          <td>{emptyValue(row.next.groups)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {draft.savedStrategies.length > 0 && (
            <section>
              <h3>Strategies</h3>
              <ul className="import-dependencies-list">
                {draft.savedStrategies.map(strategy => renderNamedRow('savedStrategies', strategy))}
              </ul>
            </section>
          )}

          {draft.savedTickerMappings.length > 0 && (
            <section>
              <h3>Ticker Mappings</h3>
              <ul className="import-dependencies-list">
                {draft.savedTickerMappings.map(mappingSet => renderNamedRow('savedTickerMappings', mappingSet))}
              </ul>
            </section>
          )}
        </div>

        {error && <div className="ticker-config-error">{error}</div>}
        <div className="ticker-config-actions">
          <button type="button" disabled={applying} onClick={onCancel}>Cancel</button>
          <button type="button" className="ticker-config-save" disabled={applying || invalidDraft} onClick={confirm}>
            {applying ? 'Applying...' : 'Confirm Import'}
          </button>
        </div>
      </div>
    </div>
  )
}
