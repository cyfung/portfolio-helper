import type { ImportDependencyPreview } from '@/lib/configImportExport'

interface ImportDependenciesDialogProps {
  preview: ImportDependencyPreview
  applying: boolean
  error: string
  onCancel: () => void
  onConfirm: () => void
}

function actionLabel(action: 'add' | 'replace') {
  return action === 'replace' ? 'Replace' : 'Add'
}

function emptyValue(value: string) {
  return value || 'empty'
}

export default function ImportDependenciesDialog({
  preview,
  applying,
  error,
  onCancel,
  onConfirm,
}: ImportDependenciesDialogProps) {
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
          {preview.savedPortfolios.length > 0 && (
            <section>
              <h3>Child Portfolios</h3>
              <ul>
                {preview.savedPortfolios.map(portfolio => (
                  <li key={portfolio.name}>
                    <span>{portfolio.name}</span>
                    <strong>{actionLabel(portfolio.action)}</strong>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {preview.tickerConfigs.length > 0 && (
            <section>
              <h3>Ticker Settings</h3>
              <div className="import-dependencies-table-wrap">
                <table className="portfolio-table import-dependencies-table">
                  <thead>
                    <tr>
                      <th>Symbol</th>
                      <th>Current LETF</th>
                      <th>Import LETF</th>
                      <th>Current Groups</th>
                      <th>Import Groups</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.tickerConfigs.map(row => (
                      <tr key={row.symbol}>
                        <td>{row.symbol}</td>
                        <td>{emptyValue(row.current.letf)}</td>
                        <td>{emptyValue(row.next.letf)}</td>
                        <td>{emptyValue(row.current.groups)}</td>
                        <td>{emptyValue(row.next.groups)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {preview.savedStrategies.length > 0 && (
            <section>
              <h3>Strategies</h3>
              <ul>
                {preview.savedStrategies.map(strategy => (
                  <li key={strategy.name}>
                    <span>{strategy.name}</span>
                    <strong>{actionLabel(strategy.action)}</strong>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {preview.savedTickerMappings.length > 0 && (
            <section>
              <h3>Ticker Mappings</h3>
              <ul>
                {preview.savedTickerMappings.map(mappingSet => (
                  <li key={mappingSet.name}>
                    <span>{mappingSet.name}</span>
                    <strong>{actionLabel(mappingSet.action)}</strong>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        {error && <div className="ticker-config-error">{error}</div>}
        <div className="ticker-config-actions">
          <button type="button" disabled={applying} onClick={onCancel}>Cancel</button>
          <button type="button" className="ticker-config-save" disabled={applying} onClick={onConfirm}>
            {applying ? 'Applying...' : 'Confirm Import'}
          </button>
        </div>
      </div>
    </div>
  )
}
