// ── SummaryTable.tsx — Port of buildSummaryRows from PortfolioRenderer.kt ────
import { useRef, useState, useEffect, useMemo } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { formatCurrency, formatDisplayCurrency, formatSignedCurrency, formatSignedDisplayCurrency, hasFxRate } from '@/lib/portfolio-utils'
import { buildSortedCcys, getCcyClass } from '@/lib/ccy-colors'

export default function SummaryTable() {
  const store = usePortfolioStore()
  const {
    cash, fxRates, currentDisplayCurrency,
    lastPortfolioTotals, lastCashDisplay, lastStockDisplay,
    rebalTargetUsd, marginTargetPct, marginTargetUsd,
    setRebalTargetUsd, setMarginTargetPct, setMarginTargetUsd,
    portfolioId, appConfig,
  } = store

  const sortedCcys = useMemo(() => buildSortedCcys(
    appConfig?.displayCurrencies ?? [],
    (lastStockDisplay?.stocks ?? []).map(s => s.currency).filter(Boolean),
  ), [appConfig?.displayCurrencies, lastStockDisplay?.stocks])

  const hasMargin = cash.some(c => c.marginFlag)
  const hasCash = cash.length > 0

  const rebalInputRef = useRef<HTMLInputElement>(null)
  const marginPctInputRef = useRef<HTMLInputElement>(null)
  const marginUsdInputRef = useRef<HTMLInputElement>(null)

  // Track which input is focused so useEffect won't overwrite while user is typing
  const focusedField = useRef<'rebal' | 'marginPct' | 'marginUsd' | null>(null)

  // ── Controlled input state ────────────────────────────────────────────────
  function usdToDisplay(usd: number): number {
    const rate = fxRates[currentDisplayCurrency] ?? 1
    return rate !== 0 ? usd / rate : usd
  }

  function formatUsdForInput(usd: number | null): string {
    if (usd === null || usd < 0) return ''
    if (!hasFxRate(fxRates, currentDisplayCurrency)) return ''
    return usdToDisplay(usd).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }

  const [rebalInput, setRebalInput] = useState('')
  const [marginPctInput, setMarginPctInput] = useState('')
  const [marginUsdInput, setMarginUsdInput] = useState('')

  // Sync inputs when store values or display currency change (e.g. on portfolio switch)
  useEffect(() => {
    if (rebalTargetUsd !== null) {
      if (focusedField.current !== 'rebal') setRebalInput(formatUsdForInput(rebalTargetUsd))
      setMarginPctInput('')
      setMarginUsdInput('')
    } else if (marginTargetPct !== null) {
      setRebalInput('')
      if (focusedField.current !== 'marginPct') setMarginPctInput(String(marginTargetPct))
      setMarginUsdInput('')
    } else if (marginTargetUsd !== null) {
      setRebalInput('')
      setMarginPctInput('')
      if (focusedField.current !== 'marginUsd') setMarginUsdInput(formatUsdForInput(marginTargetUsd))
    } else {
      setRebalInput('')
      setMarginPctInput('')
      setMarginUsdInput('')
    }
  }, [rebalTargetUsd, marginTargetPct, marginTargetUsd, currentDisplayCurrency, fxRates[currentDisplayCurrency]])

  // ── Formatted display values ──────────────────────────────────────────────
  const fmtSignedDisplay = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatSignedDisplayCurrency(usd, fxRates, currentDisplayCurrency)
      : '—'

  const fmt = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatDisplayCurrency(usd, fxRates, currentDisplayCurrency)
      : '—'

  const stockGrossKnown = lastPortfolioTotals?.stockGrossKnown ?? false
  const grandTotalKnown = !!(lastPortfolioTotals?.grandTotalKnown && stockGrossKnown)
  const grandTotal = grandTotalKnown ? fmt(lastPortfolioTotals!.grandTotalUsd) : '—'

  const dayChangeUsd = grandTotalKnown ? (lastPortfolioTotals?.dayChangeUsd ?? null) : null
  const grandTotalUsdRaw = lastPortfolioTotals?.grandTotalUsd ?? null
  const dayChangePct = grandTotalKnown && dayChangeUsd !== null && grandTotalUsdRaw !== null
    ? (dayChangeUsd / (grandTotalUsdRaw - dayChangeUsd) * 100) : null
  const dayChangeStr = dayChangeUsd !== null ? fmtSignedDisplay(dayChangeUsd) : ''
  const isAfterHours = (lastStockDisplay?.stocks ?? []).length > 0
    && (lastStockDisplay?.stocks ?? []).every(s => s.isMarketClosed)
  const dayChangeColor = dayChangeUsd !== null && dayChangeUsd > 0
    ? 'positive' : dayChangeUsd !== null && dayChangeUsd < 0 ? 'negative' : ''

  const cashTotal = lastCashDisplay?.totalKnown
    ? fmt(lastCashDisplay.totalBaseUsd) : '—'

  const marginUsdVal = lastPortfolioTotals?.marginUsd ?? 0
  const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
  const stockGross = lastPortfolioTotals?.stockGrossKnown
    ? fmt(stockGrossUsd) : '—'

  const stockDayChangeUsd = stockGrossKnown ? (lastPortfolioTotals?.dayChangeUsd ?? null) : null
  const stockDayChangePct = stockGrossKnown && stockDayChangeUsd !== null && stockGrossUsd !== 0
    ? (stockDayChangeUsd / (stockGrossUsd - stockDayChangeUsd) * 100) : null
  const stockDayChangeStr = stockDayChangeUsd !== null ? fmtSignedDisplay(stockDayChangeUsd) : ''
  const stockDayChangeColor = stockDayChangeUsd !== null && stockDayChangeUsd > 0
    ? 'positive' : stockDayChangeUsd !== null && stockDayChangeUsd < 0 ? 'negative' : ''

  // Correct formula: margin / (stockGross - margin); margin may be negative (debt)
  const absMargin = Math.abs(marginUsdVal)
  const marginPct = absMargin > 0 && stockGrossUsd > absMargin
    ? ((absMargin / (stockGrossUsd - absMargin)) * 100).toFixed(2) + '%' : ''

  // ── Active target detection ───────────────────────────────────────────────
  const activeIsRebal     = rebalTargetUsd !== null
  const activeIsMarginPct = !activeIsRebal && marginTargetPct !== null
  const activeIsMarginUsd = !activeIsRebal && !activeIsMarginPct && marginTargetUsd !== null

  // ── Single underlying rebal target; all placeholders derive from it ───────
  // equity = stocks + marginUsdVal: subtracts debt (negative) or adds credit (positive)
  const equity = stockGrossUsd + marginUsdVal

  const underlyingUsd: number | null = (() => {
    if (!stockGrossKnown) return null
    if (activeIsRebal)     return rebalTargetUsd!
    if (activeIsMarginPct) return equity * (1 + marginTargetPct! / 100)
    if (activeIsMarginUsd) return equity + marginTargetUsd!
    return null
  })()

  // implied margin amount and % from the underlying target
  const impliedMargin    = underlyingUsd !== null ? Math.max(0, underlyingUsd - equity) : absMargin
  const impliedMarginPct = equity > 0
    ? (underlyingUsd !== null ? underlyingUsd - equity : absMargin) / equity * 100
    : 0

  // Placeholders: active field shows its value; others show derived equivalents.
  // When nothing is active, fall back to live actuals.
  const rebalPlaceholder     = underlyingUsd !== null
    ? formatUsdForInput(underlyingUsd)
    : stockGrossKnown ? formatUsdForInput(stockGrossUsd) : ''
  const targetImpliesNoMargin = underlyingUsd !== null && underlyingUsd <= equity
  const noCurrentMargin = underlyingUsd === null && marginUsdVal >= 0
  const marginPctPlaceholder = noCurrentMargin ? '-' : impliedMarginPct > 0 ? impliedMarginPct.toFixed(2) : (targetImpliesNoMargin ? '-' : '')
  const marginUsdPlaceholder = noCurrentMargin ? '-' : impliedMargin > 0 ? formatUsdForInput(impliedMargin) : (targetImpliesNoMargin ? '-' : '')

  // ── Debounce timers (one per field) ──────────────────────────────────────
  const rebalTimer     = useRef<ReturnType<typeof setTimeout> | null>(null)
  const marginPctTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const marginUsdTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // ── Commit helpers: store update immediate; API POST debounced 500 ms ─────
  // Only clear a field's store value when that field is currently active —
  // blurring an empty inactive field must not wipe another field's value.
  function displayToUsd(displayVal: number): number {
    const rate = fxRates[currentDisplayCurrency] ?? 1
    return displayVal * rate
  }

  function commitRebalTarget(val: string, flush = false) {
    const num = parseFloat(val.replace(/,/g, ''))
    const valid = !isNaN(num) && num >= 0
    if (valid) setRebalTargetUsd(displayToUsd(num))
    else if (activeIsRebal) setRebalTargetUsd(null)
    clearTimeout(rebalTimer.current!)
    const post = () => {
      const v = valid ? String(displayToUsd(num)) : ''
      fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=rebalTarget`, { method: 'POST', body: v })
    }
    if (flush) post()
    else rebalTimer.current = setTimeout(post, 500)
  }

  function commitMarginPct(val: string, flush = false) {
    const num = parseFloat(val.replace(/%/g, ''))
    const valid = !isNaN(num) && num >= 0
    if (valid) setMarginTargetPct(num)
    else if (activeIsMarginPct) setMarginTargetPct(null)
    clearTimeout(marginPctTimer.current!)
    const post = () => {
      const v = valid ? String(num) : ''
      fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=marginTarget`, { method: 'POST', body: v })
    }
    if (flush) post()
    else marginPctTimer.current = setTimeout(post, 500)
  }

  function commitMarginUsd(val: string, flush = false) {
    const num = parseFloat(val.replace(/,/g, ''))
    const valid = !isNaN(num) && num >= 0
    const usd = valid ? displayToUsd(num) : 0
    if (valid) setMarginTargetUsd(usd)
    else if (activeIsMarginUsd) setMarginTargetUsd(null)
    clearTimeout(marginUsdTimer.current!)
    const post = () => {
      const v = valid ? String(usd) : ''
      fetch(`/api/portfolio-config/save?portfolio=${portfolioId}&key=marginTargetUsd`, { method: 'POST', body: v })
    }
    if (flush) post()
    else marginUsdTimer.current = setTimeout(post, 500)
  }

  function formatDisplayNum(val: string): string {
    const num = parseFloat(val.replace(/,/g, ''))
    return isNaN(num) || num < 0 ? ''
      : num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }

  return (
    // 5 columns: Label | Badges | Currency | Amount | Value
    <table className="portfolio-cash-table">
      <tbody>
        {/* ── Grand total ──────────────────────────────────────────────── */}
        <tr className="grand-total-row">
          <td>Portfolio Value</td>
          <td /><td /><td />
          <td>
            <span id="portfolio-total">{grandTotal}</span>
            <div className={`summary-subvalue ${grandTotalKnown && dayChangeStr ? dayChangeColor : ''}${isAfterHours ? ' after-hours' : ''}`} id="total-day-change">
              {grandTotalKnown && dayChangeStr
                ? <>{dayChangeStr}{dayChangePct !== null && ` (${dayChangePct >= 0 ? '+' : ''}${dayChangePct.toFixed(2)}%)`}</>
                : '— (—)'}
            </div>
          </td>
        </tr>

        {/* ── Hidden anchor row (kept for JS compatibility) ────────────── */}
        <tr id="cash-rows-anchor" style={{ display: 'none' }} />

        {/* ── Cash rows from SSE ────────────────────────────────────────── */}
        {lastCashDisplay?.entries.map((entry, i, arr) => {
          const showLabel = i === 0 || arr[i - 1].label !== entry.label
          const isRef = !!entry.portfolioRef
          return (
            <tr key={entry.entryId} className={`leading-[1.4] ${entry.isMarginEntry ? 'cash-margin-entry' : ''}`}>
              {/* Col 1: Label */}
              <td>{showLabel ? entry.label : ''}</td>

              {/* Col 2: Badges */}
              <td className="align-middle">
                <span className="inline-flex items-center gap-0.5">
                  {isRef && (
                    <span className="cash-type-badge cash-badge-ref">
                      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M9 5v2h6.59L4 18.59 5.41 20 17 8.41V15h2V5z"/>
                      </svg>
                    </span>
                  )}
                  {entry.isMarginEntry && (
                    <span className="cash-type-badge cash-badge-margin">M</span>
                  )}
                </span>
              </td>

              {/* Col 3: Currency pill */}
              <td className="align-middle">
                <span className={`ccy-pill ccy-color-${getCcyClass(isRef ? 'USD' : entry.currency, sortedCcys)}`}>
                  {isRef ? 'USD' : entry.currency}
                </span>
              </td>

              {/* Col 4: Raw amount in own currency (right-aligned) */}
              <td className="text-sm text-muted-foreground text-right">
                {isRef
                  ? (entry.baseUsd !== null ? formatCurrency(entry.baseUsd) : '')
                  : (entry.rawCcyAmount !== 0 ? formatCurrency(entry.rawCcyAmount) : '')}
              </td>

              {/* Col 5: Display-currency value */}
              <td>
                {entry.baseUsd !== null ? fmt(entry.baseUsd) : '—'}
              </td>
            </tr>
          )
        })}

        {/* ── Cash summary rows ─────────────────────────────────────────── */}
        {hasCash && (
          <>
            <tr className="summary-divider"><td colSpan={5} /></tr>
            <tr className="total-cash-row">
              <td>Total Cash</td>
              <td /><td /><td />
              <td><span id="cash-total-usd">{cashTotal}</span></td>
            </tr>

            {hasMargin && (
              <tr
                className="margin-row"
                data-margin-row="true"
              >
                <td>Margin</td>
                <td /><td />
                {/* Col 4: current margin % */}
                <td className="text-right text-sm text-muted-foreground">
                  <span id="margin-percent">{marginUsdVal < 0 ? marginPct : '-'}</span>
                </td>
                {/* Col 5: USD value */}
                <td>
                  <span id="margin-total-usd">
                    {marginUsdVal < 0 ? fmt(Math.abs(marginUsdVal)) : '-'}
                  </span>
                </td>
              </tr>
            )}

            <tr className="summary-section-break"><td colSpan={5} /></tr>

            <tr className="stock-gross-row">
              <td>Stock Gross Value</td>
              <td /><td /><td />
              <td>
                <div id="stock-gross-total">{stockGross}</div>
                <div className={`summary-subvalue ${stockGrossKnown && stockDayChangeStr ? stockDayChangeColor : ''}${isAfterHours ? ' after-hours' : ''}`} id="portfolio-day-change">
                  {stockGrossKnown && stockDayChangeStr
                    ? <>{stockDayChangeStr}{stockDayChangePct !== null && ` (${stockDayChangePct >= 0 ? '+' : ''}${stockDayChangePct.toFixed(2)}%)`}</>
                    : '— (—)'}
                </div>
              </td>
            </tr>
          </>
        )}

        {/* ── Rebalance target input ────────────────────────────────────── */}
        <tr className="rebal-target-row">
          <td>Rebalance Target</td>
          <td /><td /><td />
          <td>
            <input
              ref={rebalInputRef}
              type="text"
              id="rebal-target-input"
              className="rebal-target-input h-6 py-0 text-sm"
              autoComplete="off"
              placeholder={rebalPlaceholder}
              value={rebalInput}
              onFocus={() => { focusedField.current = 'rebal' }}
              onChange={e => {
                const v = e.target.value.replace(/[^\d.,]/g, '')
                setRebalInput(v)
                commitRebalTarget(v)
              }}
              onBlur={() => {
                focusedField.current = null
                commitRebalTarget(rebalInput, true)
                if (rebalInput) setRebalInput(formatDisplayNum(rebalInput))
              }}
              onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
            />
          </td>
        </tr>

        {/* ── Margin target inputs ──────────────────────────────────────── */}
        {hasMargin && (
          <tr className="margin-row" id="margin-target-row">
            <td>Margin Target</td>
            <td /><td />
            {/* Col 4: % input */}
            <td>
              <input
                ref={marginPctInputRef}
                type="text"
                id="margin-target-input"
                className="margin-target-input h-6 py-0 text-sm"
                autoComplete="off"
                placeholder={marginPctPlaceholder}
                value={marginPctInput}
                onFocus={() => { focusedField.current = 'marginPct' }}
                onChange={e => {
                  const v = e.target.value.replace(/[^\d.]/g, '')
                  setMarginPctInput(v)
                  commitMarginPct(v)
                }}
                onBlur={() => {
                  focusedField.current = null
                  commitMarginPct(marginPctInput, true)
                }}
                onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
              />
              {' %'}
            </td>
            {/* Col 5: USD amount input */}
            <td>
              <input
                ref={marginUsdInputRef}
                type="text"
                id="margin-target-usd-input"
                className="rebal-target-input h-6 py-0 text-sm"
                autoComplete="off"
                placeholder={marginUsdPlaceholder}
                value={marginUsdInput}
                onFocus={() => { focusedField.current = 'marginUsd' }}
                onChange={e => {
                  const v = e.target.value.replace(/[^\d.,]/g, '')
                  setMarginUsdInput(v)
                  commitMarginUsd(v)
                }}
                onBlur={() => {
                  focusedField.current = null
                  commitMarginUsd(marginUsdInput, true)
                  if (marginUsdInput) setMarginUsdInput(formatDisplayNum(marginUsdInput))
                }}
                onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
              />
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}
