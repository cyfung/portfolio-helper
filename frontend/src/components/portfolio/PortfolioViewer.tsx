// ── PortfolioViewer.tsx — Port of PortfolioRenderer.kt body structure ─────────
import { useState, useCallback, useRef, type CSSProperties } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import type { StockData, CashData } from '@/types/portfolio'
import { computeDisplay, getRebalTotal } from '@/lib/rebalance'
import { TWS_CASH_LABEL, isTwsManagedCashLabel } from '@/lib/twsCashLabels'

/** Parse a cash key-value pair (e.g. "Cash.USD.M" / "1000") into a CashData entry. */
function parseCashKey(key: string, value: string): CashData | null {
  const parts = key.split('.')
  const mut = [...parts]
  let marginFlag = false
  while (mut.length > 0 && mut[mut.length - 1].toUpperCase() === 'M') {
    marginFlag = true
    mut.pop()
  }
  if (mut.length < 2) return null
  const currency = mut[mut.length - 1].toUpperCase()
  const label = mut.slice(0, -1).join('.')
  if (currency === 'P') {
    const trimmed = String(value).trim()
    const signNeg = trimmed.startsWith('-')
    const portfolioRef = trimmed.replace(/^[+-]/, '').toLowerCase()
    return { label, currency: 'P', amount: signNeg ? -1 : 1, marginFlag, portfolioRef }
  }
  return { label, currency, amount: parseFloat(value) || 0, marginFlag }
}

function stockKey(symbol: string): string {
  return symbol.trim().toUpperCase()
}

function appendMissingStocksWithZeroQty(imported: StockData[], current: StockData[]): StockData[] {
  const importedSymbols = new Set(imported.map(s => stockKey(s.label)).filter(Boolean))
  const missing = current
    .filter(s => !importedSymbols.has(stockKey(s.label)))
    .map(s => ({ ...s, originalAmount: 0 }))
  return [...imported, ...missing]
}
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import PortfolioTabs from './PortfolioTabs'
import SummaryTable from './SummaryTable'
import CashEditTable from './CashEditTable'
import IbkrRatesSection from './IbkrRatesSection'
import RebalanceControls from './RebalanceControls'
import StockTable from './StockTable'
import GroupsView from './GroupsView'
import EditMode from './EditMode'
import BackupPanel from './BackupPanel'

export default function PortfolioViewer() {
  const navigate = useNavigate()
  const store = usePortfolioStore()
  const {
    portfolioId, cash, lastPortfolioTotals,
    currentDisplayCurrency, moreInfoVisible, rebalVisible,
    groupViewActive, editModeActive, afterHoursGray,
    portfolioContentScale,
    allPortfolios, appConfig, lastStockDisplay,
    rebalTargetUsd, marginTargetUsd, allocReduceMode,
    setMoreInfoVisible, setRebalVisible, setGroupViewActive, setEditModeActive,
    setPortfolioContentScale,
  } = store

  const [backupOpen, setBackupOpen]           = useState(false)
  const [saveKey, setSaveKey] = useState(0)
  const [editResetKey, setEditResetKey] = useState(0)
  const [dividendDate, setDividendDate] = useState(store.config.dividendStartDate ?? '')
  const [twsSyncing, setTwsSyncing] = useState(false)
  const [syncToast, setSyncToast] = useState({ msg: '', type: '' })
  const [stagedEditStocks, setStagedEditStocks] = useState<StockData[] | null>(null)
  const [stagedEditCash, setStagedEditCash] = useState<CashData[] | null>(null)
  const syncToastTimer = useRef<number | null>(null)

  function showSyncToast(msg: string, type: string) {
    setSyncToast({ msg, type })
    if (syncToastTimer.current) clearTimeout(syncToastTimer.current)
    syncToastTimer.current = window.setTimeout(
      () => setSyncToast({ msg: '', type: '' }),
      type === 'ok' ? 2500 : 5000
    )
  }

  const hasMargin = cash.some(c => c.marginFlag)
  const virtualBalance = store.config.virtualBalanceEnabled

  function clearStagedEditData() {
    setStagedEditStocks(null)
    setStagedEditCash(null)
  }

  function enterEditMode(stocks?: StockData[] | null, cashEntries?: CashData[] | null) {
    setStagedEditStocks(stocks ?? null)
    setStagedEditCash(cashEntries ?? null)
    setEditResetKey(k => k + 1)
    setEditModeActive(true)
  }

  function toggleEditMode() {
    if (editModeActive) {
      clearStagedEditData()
      setEditModeActive(false)
    } else {
      enterEditMode()
    }
  }

  async function saveDividendDate(date: string) {
    await fetch(`/api/portfolio/dividend-start-date?portfolio=${portfolioId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ date: date || null }),
    })
  }
  const displayCurrencies = appConfig?.displayCurrencies ?? ['USD']

  // ── TWS sync ──────────────────────────────────────────────────────────────
  const handleTwsSync = useCallback(async () => {
    setTwsSyncing(true)
    try {
      const r = await fetch(`/api/tws/snapshot?portfolio=${portfolioId}`)
      if (!r.ok) throw new Error(await r.text())
      const snap = await r.json()
      if (snap.error) throw new Error(snap.error)

      // Stage TWS positions for edit mode without changing display quantities.
      const positions = snap.positions as Array<{ symbol: string; qty: number }>
      const qtyBySymbol = new Map(positions.map(p => [stockKey(p.symbol), p.qty]))
      const updatedStocks: StockData[] = store.stocks.map(s => ({
        ...s,
        originalAmount: qtyBySymbol.get(stockKey(s.label)) ?? 0,
      }))
      const existingSymbols = new Set(store.stocks.map(s => stockKey(s.label)))
      for (const pos of positions) {
        if (!existingSymbols.has(stockKey(pos.symbol))) {
          updatedStocks.push({ label: pos.symbol, amount: pos.qty, originalAmount: pos.qty, targetWeight: 0, letf: '', groups: '' })
        }
      }

      // TWS owns these reserved labels. Replace the whole reserved subset so
      // stale currencies and zero balances disappear from the staged cash rows.
      const updatedCash: CashData[] = store.cash.filter(c => !isTwsManagedCashLabel(c.label))
      const addTwsCash = (label: string, currency: string, amount: number, marginFlag: boolean) => {
        if (!Number.isFinite(amount) || amount === 0) return
        updatedCash.push({ label, currency, amount, marginFlag })
      }
      for (const [ccy, amt] of Object.entries(snap.cashBalances as Record<string, number>)) {
        addTwsCash(TWS_CASH_LABEL.CASH, ccy, amt, true)
      }
      for (const [ccy, amt] of Object.entries(snap.accruedCash as Record<string, number>)) {
        addTwsCash(TWS_CASH_LABEL.MTD_INTEREST, ccy, amt, false)
      }
      for (const [ccy, amt] of Object.entries((snap.pendingDividends ?? {}) as Record<string, number>)) {
        addTwsCash(TWS_CASH_LABEL.PENDING_DIVIDEND, ccy, amt, false)
      }

      enterEditMode(updatedStocks, updatedCash)
    } catch (e) {
      showSyncToast(`TWS sync failed: ${e}`, 'error')
    } finally {
      setTwsSyncing(false)
    }
  }, [portfolioId, store, setEditModeActive])

  // ── Import success callback (from BackupPanel) ─────────────────────────────
  const handleImportSuccess = useCallback((json: any) => {
    let importedStocks: StockData[] | null = null
    let importedCash: CashData[] | null = null
    if (json.stocks) {
      const parsedStocks = (json.stocks as Array<any>).map(s => ({
        label: s.symbol ?? s.label ?? '',
        amount: s.amount ?? 0,
        originalAmount: s.amount ?? 0,
        targetWeight: s.targetWeight ?? 0,
        letf: s.letf ?? '',
        groups: s.groups ?? '',
      }))
      importedStocks = appendMissingStocksWithZeroQty(parsedStocks, store.stocks)
    }
    if (json.cash) {
      importedCash = (json.cash as Array<{ key: string; value: string }>)
        .map(c => parseCashKey(c.key, c.value))
        .filter((c): c is CashData => c !== null)
    }
    enterEditMode(importedStocks, importedCash)
  }, [store, setEditModeActive])

  // ── Save to backtest ───────────────────────────────────────────────────────
  const handleSaveToBacktest = useCallback(async () => {
    const { stocks, marginTargetPct, allocAddMode } = store
    const tickers = stocks
      .filter(s => s.targetWeight > 0)
      .map(s => ({ ticker: s.label, weight: s.targetWeight }))
    if (!tickers.length) { alert('No target weights set'); return }
    const name = allPortfolios.find(p => p.slug === portfolioId)?.name || portfolioId
    const config = {
      tickers,
      rebalanceStrategy: 'YEARLY',
      marginStrategies: marginTargetPct && marginTargetPct > 0 ? [{
        marginRatio:          marginTargetPct / 100,
        marginSpread:         0.015,
        marginDeviationUpper: 0.05,
        marginDeviationLower: 0.05,
        upperRebalanceMode:   allocAddMode,
        lowerRebalanceMode:   allocAddMode,
      }] : [],
      includeNoMargin: true,
    }
    const res = await fetch('/api/backtest/savedPortfolios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config }),
    })
    if (res.ok) showSyncToast('Saved to backtest!', 'ok')
  }, [store, portfolioId, allPortfolios])

  // ── After save callback ────────────────────────────────────────────────────
  const handleSaved = useCallback(() => {
    setSaveKey(k => k + 1)
    navigate(0)  // reload current route
  }, [navigate])

  // ── Currency toggle ───────────────────────────────────────────────────────
  function renderCurrencyControl() {
    if (displayCurrencies.length === 1) {
      return <span className="currency-pill">{displayCurrencies[0]}</span>
    }
    if (displayCurrencies.length <= 3) {
      return (
        <button
          className="currency-toggle active"
          type="button"
          title="Switch display currency"
          onClick={() => {
            const idx = displayCurrencies.indexOf(currentDisplayCurrency)
            const next = displayCurrencies[(idx + 1) % displayCurrencies.length]
            store.setDisplayCurrency(next)
          }}
        >
          <span className="currency-toggle-icon">⇄</span>
          <span className="toggle-label">{currentDisplayCurrency}</span>
        </button>
      )
    }
    return (
      <select
        className="currency-select"
        value={currentDisplayCurrency}
        onChange={e => store.setDisplayCurrency(e.target.value)}
      >
        {displayCurrencies.map(c => <option key={c} value={c}>{c}</option>)}
      </select>
    )
  }

  const contentScalePct = Math.round(portfolioContentScale * 100)
  const contentScaleStyle = {
    '--portfolio-content-scale': String(portfolioContentScale),
  } as CSSProperties

  return (
    <div className={`container${afterHoursGray ? ' after-hours-gray' : ''}${moreInfoVisible ? ' more-info-visible' : ''}${rebalVisible ? ' rebalancing-visible' : ''}${editModeActive ? ' editing-active' : ''}`}>
      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs
            active="/portfolio/"
            contextLabel={allPortfolios.find(p => p.slug === portfolioId)?.name}
            contextChildren={<PortfolioTabs basePath="/portfolio/" />}
          />
        </div>

        <HeaderRight>
          {/* Left group — view toggles */}
          <button
            className="restore-backup-btn h-btn subtle"
            id="restore-backup-btn"
            type="button"
            title="Backup and restore portfolio"
            onClick={() => setBackupOpen(true)}
          >Backups</button>

          <button
            className={`edit-toggle h-btn subtle${editModeActive ? ' active-edit' : ''}`}
            id="edit-toggle"
            type="button"
            title="Edit Qty and Target Weight"
            aria-label="Toggle edit mode"
            onClick={toggleEditMode}
          >Edit</button>

          {editModeActive && (
            <button
              className="save-btn h-btn primary"
              id="save-btn"
              type="button"
              title="Save changes"
              onClick={() => setSaveKey(k => k + 1)}
            >
              <svg width={13} height={13} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
                <path d="M17 21v-8H7v8M7 3v5h8"/>
              </svg>
              Save
            </button>
          )}

          <button
            className={`more-info-toggle h-btn subtle${moreInfoVisible ? ' active-edit' : ''}`}
            id="more-info-toggle"
            type="button"
            title="Show/Hide Last NAV, Last, and Mkt Val columns"
            onClick={() => setMoreInfoVisible(!moreInfoVisible)}
          >More Info</button>

          <button
            className={`groups-toggle h-btn subtle${groupViewActive ? ' active-edit' : ''}`}
            id="groups-toggle"
            type="button"
            title="Switch between stock view and group view"
            onClick={() => setGroupViewActive(!groupViewActive)}
          >Groups</button>

          <button
            className={`rebal-toggle h-btn subtle${rebalVisible ? ' active-edit' : ''}`}
            id="rebal-toggle"
            type="button"
            title="Show/Hide Weight and Rebalancing columns"
            aria-label="Toggle rebalancing columns"
            onClick={() => setRebalVisible(!rebalVisible)}
          >Rebal</button>

          {/* Right group — data actions */}
          <div className="header-buttons-right">
            <button
              className="h-btn subtle"
              id="tws-sync-btn"
              type="button"
              title="Sync Qty and Cash from Interactive Brokers TWS"
              onClick={handleTwsSync}
              disabled={twsSyncing}
            >{twsSyncing ? 'Syncing…' : 'Sync TWS'}</button>

            <button
              className="h-btn subtle"
              id="save-to-backtest-btn"
              type="button"
              title="Save current portfolio as a backtest preset"
              onClick={handleSaveToBacktest}
            >Save to Backtest</button>

            <span className="h-divider" />
            <div className="portfolio-content-scale-control" title="Zoom portfolio contents">
              <input
                id="portfolio-content-scale"
                type="range"
                aria-label="Portfolio zoom"
                min="70"
                max="130"
                step="5"
                value={contentScalePct}
                onChange={e => setPortfolioContentScale(parseInt(e.target.value, 10) / 100)}
              />
              <button
                type="button"
                className="portfolio-content-scale-value"
                title="Reset portfolio zoom"
                aria-label="Reset portfolio zoom"
                onClick={() => setPortfolioContentScale(1)}
              >
                {contentScalePct}%
              </button>
            </div>

            <span className="h-divider" />
            {renderCurrencyControl()}
          </div>

          <PrivacyToggleButton />
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>
      <div className={`config-status config-status-${syncToast.type}${syncToast.msg ? ' visible' : ''}`}>
        {syncToast.msg}
      </div>

      {/* ── Main content ──────────────────────────────────────────────── */}
      <div className="portfolio-tables-wrapper" style={contentScaleStyle}>
        <div className="summary-and-rates">
          <SummaryTable />
          <CashEditTable key={editResetKey} allPortfolios={allPortfolios} entries={stagedEditCash ?? undefined} />
          {virtualBalance && (
            <div className="dividend-from-section">
              <label htmlFor="dividend-from-input">Dividend From</label>
              <input
                type="date"
                id="dividend-from-input"
                className="dividend-from-input"
                value={dividendDate}
                autoComplete="off"
                onChange={e => setDividendDate(e.target.value)}
                onBlur={e => saveDividendDate(e.target.value)}
              />
            </div>
          )}
          {hasMargin && <IbkrRatesSection />}
        </div>

        <div className="stock-section">
          {!editModeActive && <RebalanceControls showGroupBy={!groupViewActive} />}
          {editModeActive ? (
            <EditMode
              key={editResetKey}
              saveKey={saveKey}
              onSaved={handleSaved}
              pendingDividendDate={dividendDate}
              initialStocks={stagedEditStocks ?? undefined}
            />
          ) : groupViewActive ? (
            <GroupsView />
          ) : (
            <StockTable />
          )}
        </div>

        {!editModeActive && (
          <div className="edit-add-buttons">
            <button
              type="button"
              className="add-stock-btn"
              id="add-stock-btn"
              onClick={() => enterEditMode()}
            >
              + Add Stock
            </button>
          </div>
        )}

        {!editModeActive && virtualBalance && (
          <div className="virtual-rebal-row">
            <button
              className="virtual-rebal-btn"
              id="virtual-rebal-btn"
              type="button"
              title="Apply rebalancing quantities to the portfolio (virtual — requires Save to persist)"
              onClick={async () => {
                try {
                  await fetch(`/api/backup/trigger?portfolio=${portfolioId}&label=pre-rebal&force=true`, { method: 'POST' })
                } catch (_) { /* non-fatal */ }
                const liveBySymbol = new Map((lastStockDisplay?.stocks ?? []).map(s => [s.symbol, s]))
                const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
                const stockGrossKnown = lastPortfolioTotals?.stockGrossKnown ?? false
                const marginUsd = lastPortfolioTotals?.marginUsd ?? 0
                if (!stockGrossKnown || stockGrossUsd <= 0) {
                  enterEditMode()
                  return
                }
                const result = computeDisplay(
                  store.stocks.map(s => ({
                    symbol: s.label,
                    qty: s.originalAmount ?? s.amount,
                    targetWeight: s.targetWeight ?? 0,
                    positionValueUsd: liveBySymbol.get(s.label)?.positionValueUsd ?? 0,
                  })),
                  rebalTargetUsd,
                  store.marginTargetPct,
                  store.allocAddMode,
                  allocReduceMode,
                  stockGrossUsd,
                  marginUsd,
                  store.marginTargetUsd,
                  undefined,
                  store.appConfig?.hybridAllocStrategies,
                )
                const updated = store.stocks.map(s => {
                  const delta = result.rebalQty[s.label]
                  if (!delta || delta === 0) return s
                  const nextAmount = parseFloat(((s.originalAmount ?? s.amount) + delta).toFixed(2))
                  return { ...s, originalAmount: nextAmount }
                })
                const pendingDate = store.config.dividendCalcUpToDate || store.config.dividendStartDate || ''
                setDividendDate(pendingDate)
                enterEditMode(updated)
              }}
            >
              <span className="toggle-label">Virtual Rebalance</span>
            </button>
          </div>
        )}
      </div>

      {/* ── Backup panel modal ────────────────────────────────────────── */}
      {backupOpen && (
        <BackupPanel
          onClose={() => setBackupOpen(false)}
          onImportSuccess={json => { setBackupOpen(false); handleImportSuccess(json) }}
        />
      )}
    </div>
  )
}
