// ── CashEditTable.tsx — Port of CashEditRenderer.kt ──────────────────────────
import { usePortfolioStore } from '@/stores/portfolioStore'
import type { PortfolioOption } from '@/types/portfolio'

interface Props {
  allPortfolios: PortfolioOption[]
}

export default function CashEditTable({ allPortfolios }: Props) {
  const { cash } = usePortfolioStore()

  const sorted = [...cash].sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()))

  return (
    <div className="cash-edit-table-wrapper">
      <table className="cash-edit-table">
        <tbody>
          {sorted.map((entry, idx) => {
            const isRef = entry.currency === 'P'
            const entryType = isRef && entry.marginFlag ? 'ref-margin'
              : isRef ? 'ref'
              : entry.marginFlag ? 'margin'
              : 'normal'

            return (
              <tr
                key={`${entry.label}-${idx}`}
                data-cash-edit-row="true"
                data-entry-type={entryType}
                data-original-label={entry.label}
                data-original-currency={entry.currency}
                data-original-amount={entry.amount.toString()}
                data-original-margin={entry.marginFlag.toString()}
                data-original-is-ref={isRef.toString()}
                data-original-portfolio-ref={isRef ? (entry.portfolioRef ?? '') : undefined}
                data-original-multiplier={isRef ? entry.amount.toString() : undefined}
              >
                {/* Label */}
                <td>
                  <input
                    type="text"
                    className="edit-input cash-edit-label"
                    defaultValue={entry.label}
                    placeholder="Label"
                  />
                </td>

                {/* Ref toggle */}
                <td>
                  <label className="cash-ref-toggle-label">
                    <input
                      type="checkbox"
                      className="cash-edit-is-ref"
                      defaultChecked={isRef}
                      onChange={e => {
                        const tr = e.currentTarget.closest('tr') as HTMLElement | null
                        if (!tr) return
                        const normal = tr.querySelector('.cash-normal-fields') as HTMLElement | null
                        const ref = tr.querySelector('.cash-ref-fields') as HTMLElement | null
                        if (normal) normal.style.display = e.currentTarget.checked ? 'none' : ''
                        if (ref) ref.style.display = e.currentTarget.checked ? '' : 'none'
                      }}
                    />
                    <span className="cash-type-badge cash-badge-ref">
                      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M9 5v2h6.59L4 18.59 5.41 20 17 8.41V15h2V5z"/>
                      </svg>
                    </span>
                  </label>
                </td>

                {/* Normal fields */}
                <td className="cash-normal-fields" style={isRef ? { display: 'none' } : undefined}>
                  <input
                    type="text"
                    className="edit-input cash-edit-currency"
                    defaultValue={isRef ? '' : entry.currency}
                    placeholder="USD"
                    autoComplete="off"
                  />
                  <input
                    type="number"
                    className="edit-input cash-edit-amount"
                    defaultValue={isRef ? '' : entry.amount.toString()}
                    placeholder="0"
                    step="any"
                  />
                </td>

                {/* Ref fields */}
                <td className="cash-ref-fields" style={!isRef ? { display: 'none' } : undefined}>
                  <select className="cash-edit-portfolio-ref" defaultValue={entry.portfolioRef ?? ''}>
                    {isRef && entry.portfolioRef == null && (
                      <option value="" disabled>— (portfolio deleted) —</option>
                    )}
                    {allPortfolios.map(p => (
                      <option key={p.slug} value={p.slug}>{p.name}</option>
                    ))}
                  </select>
                  <input
                    type="number"
                    className="edit-input cash-edit-multiplier"
                    defaultValue={isRef ? entry.amount.toString() : '1'}
                    placeholder="1"
                    step="any"
                  />
                </td>

                {/* Margin toggle */}
                <td>
                  <label className="cash-margin-toggle">
                    <input
                      type="checkbox"
                      className="cash-edit-margin"
                      defaultChecked={entry.marginFlag}
                    />
                    <span className="cash-type-badge cash-badge-margin">M</span>
                  </label>
                </td>

                {/* Delete */}
                <td>
                  <button
                    type="button"
                    className="delete-cash-btn"
                    onClick={e => {
                      const tr = (e.currentTarget as HTMLElement).closest('tr')
                      if (tr) { tr.setAttribute('data-deleted', 'true'); tr.style.display = 'none' }
                    }}
                  >
                    ×
                  </button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <p className="edit-hint">Label · Ref · Currency · Amount · Margin</p>
      <button type="button" id="add-cash-btn" className="add-cash-btn">+ Add Entry</button>
    </div>
  )
}
