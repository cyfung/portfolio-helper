// ── IbkrRatesSection.tsx — Port of IbkrRatesRenderer.kt + renderIbkrDisplay ──
import { useState } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { formatDisplayCurrency, formatTime } from '@/lib/portfolio-utils'

// The actual server payload shape (from backup.js renderIbkrDisplay)
interface IbkrData {
  perCurrency: { currency: string; displayRateText: string }[]
  currentDailyUsd: number
  cheapestCcy: string | null
  cheapestDailyUsd: number
  savingsUsd: number
  label: string
  lastFetch?: number
}

export default function IbkrRatesSection() {
  const { lastIbkrData, portfolioId, fxRates, currentDisplayCurrency } = usePortfolioStore()
  const [reloading, setReloading] = useState(false)

  // Cast to actual server shape
  const data = lastIbkrData as unknown as IbkrData | null

  const fmt = (usd: number) => formatDisplayCurrency(usd, fxRates, currentDisplayCurrency)

  async function handleReload() {
    setReloading(true)
    try {
      await fetch(`/api/margin-rates/reload?portfolio=${portfolioId}`, { method: 'POST' })
    } finally {
      setReloading(false)
    }
  }

  const lastFetchMs = (lastIbkrData as { lastFetch?: number } | null)?.lastFetch ?? 0

  return (
    <div className="ibkr-rates-wrapper">
      <div id="ibkr-display">
        {data && data.perCurrency && data.perCurrency.length > 0 && (
          <>
            <table className="ibkr-rates-table">
              <thead>
                <tr><th>CCY</th><th>IBKR Pro Rate</th></tr>
              </thead>
              <tbody>
                {data.perCurrency.map(ci => (
                  <tr key={ci.currency}>
                    <td className="ibkr-rate-currency">{ci.currency}</td>
                    <td className="ibkr-rate-value">{ci.displayRateText}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            {(() => {
              const showSavings = data.savingsUsd >= 0.005
              const labelMatch = data.label?.match(/^([^(]+?)(?:\s*\((.+)\))?$/)
              const labelBase = labelMatch?.[1]?.trim() ?? data.label
              const labelAction = labelMatch?.[2] ?? null

              return (
                <table className="ibkr-interest-summary">
                  <tbody>
                    <tr>
                      <td>Current Daily Interest</td>
                      <td className="ibkr-value-muted">
                        {data.currentDailyUsd > 0 ? fmt(data.currentDailyUsd) : '—'}
                      </td>
                    </tr>
                    <tr>
                      <td>
                        {data.cheapestCcy != null
                          ? <>Cheapest <span>({data.cheapestCcy})</span></>
                          : 'Cheapest'}
                      </td>
                      <td className="ibkr-value-muted">
                        {data.cheapestCcy != null ? fmt(data.cheapestDailyUsd) : '—'}
                      </td>
                    </tr>
                    <tr>
                      <td>{labelBase}</td>
                      <td className={showSavings ? 'ibkr-rate-diff' : ''}>
                        {showSavings ? fmt(data.savingsUsd) : '—'}
                      </td>
                    </tr>
                    {labelAction && (
                      <tr>
                        <td colSpan={2} className="ibkr-action-hint">{labelAction}</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              )
            })()}
          </>
        )}
      </div>

      <div className="ibkr-rates-footer">
        <span className="ibkr-last-fetch" id="ibkr-last-fetch">
          {lastFetchMs > 0 ? formatTime(lastFetchMs) : '—'}
        </span>
        <button
          id="ibkr-reload-btn"
          className="ibkr-reload-btn"
          type="button"
          title="Reload IBKR margin rates"
          onClick={handleReload}
          disabled={reloading}
        >
          {reloading ? '…' : '↻'}
        </button>
      </div>
    </div>
  )
}
