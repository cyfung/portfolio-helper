// ── PortfolioViewer.tsx — Port of PortfolioRenderer.kt body structure ─────────
import { useState, useCallback, type CSSProperties } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import type { StockData, CashData, StockDisplayItem } from '@/types/portfolio'
import { computeDisplay } from '@/lib/rebalance'
import { TWS_CASH_LABEL, isTwsManagedCashLabel } from '@/lib/twsCashLabels'
import TransientToast from '@/components/TransientToast'
import { durationForToastType, useTransientToast, type ToastType } from '@/hooks/useTransientToast'

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
    const multiplierMatch = trimmed.match(/^([+-]?(?:\d+(?:\.\d+)?|\.\d+))\s+(.+)$/)
    const multiplier = multiplierMatch
      ? parseFloat(multiplierMatch[1])
      : trimmed.startsWith('-') ? -1 : 1
    const portfolioRef = (multiplierMatch?.[2] ?? trimmed.replace(/^[+-]/, '')).trim().toLowerCase()
    return { label, currency: 'P', amount: Number.isFinite(multiplier) ? multiplier : 1, marginFlag, portfolioRef }
  }
  return { label, currency, amount: parseFloat(value) || 0, marginFlag }
}

function stockKey(symbol: string): string {
  return symbol.trim().toUpperCase()
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function appendMissingStocksWithZeroQty(imported: StockData[], current: StockData[]): StockData[] {
  const importedSymbols = new Set(imported.map(s => stockKey(s.label)).filter(Boolean))
  const missing = current
    .filter(s => !importedSymbols.has(stockKey(s.label)))
    .map(s => ({ ...s, originalAmount: 0, targetWeight: 0 }))
  return [...imported, ...missing]
}

function getLivePriceUsd(live: StockDisplayItem | undefined, fxRates: Record<string, number>): number | null {
  const price = live?.markPrice ?? live?.estPriceNative ?? live?.closePrice ?? live?.lastNav ?? null
  if (price === null || price <= 0) return null
  const currency = live?.currency ?? 'USD'
  const fxRate = currency === 'USD' ? 1 : fxRates[currency]
  if (!Number.isFinite(fxRate) || fxRate <= 0) return null
  return price * fxRate
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
import ImportDependenciesDialog from '@/components/backtest/ImportDependenciesDialog'
import {
  applyImportDependencyPreview,
  buildImportDependencyPreview,
  hasImportDependencyPreview,
  type ImportDependencyPreview,
} from '@/lib/configImportExport'
import {
  getPortfolioColumnMode,
  normalizePortfolioColumnModes,
  portfolioModeHasMoreInfo,
  portfolioModeHasRebal,
} from '@/lib/portfolioColumns'

type BackupImportPayload = {
  stocks?: Array<any> | null
  cash?: Array<{ key: string; value: string }> | null
  dividendStartDate?: string | null
}

type PendingBackupDependencyImport = {
  json: BackupImportPayload
  preview: ImportDependencyPreview
  mode: 'fileImport' | 'dbRestore'
}

type HeaderHoverMenuOption = {
  id: string
  label: string
  active: boolean
  onSelect: () => void
}

type HeaderHoverMenuId = 'currency' | 'columnMode'

export default function PortfolioViewer() {
  const navigate = useNavigate()
  const store = usePortfolioStore()
  const {
    portfolioId, cash, lastPortfolioTotals,
    currentDisplayCurrency, portfolioColumnModeId,
    groupViewActive, editModeActive, afterHoursGray,
    portfolioContentScale,
    allPortfolios, appConfig, lastStockDisplay, fxRates,
    rebalTargetUsd, marginTargetUsd, allocReduceMode,
    setGroupViewActive, setEditModeActive,
    setPortfolioContentScale,
    setPortfolioColumnModeId,
  } = store

  const [backupOpen, setBackupOpen]           = useState(false)
  const [saveKey, setSaveKey] = useState(0)
  const [editResetKey, setEditResetKey] = useState(0)
  const [dividendDate, setDividendDate] = useState(store.config.dividendStartDate ?? '')
  const [twsSyncing, setTwsSyncing] = useState(false)
  const { toast: syncToast, showToast: showSyncToastBase, clearToast: clearSyncToast } = useTransientToast()
  const [stagedEditStocks, setStagedEditStocks] = useState<StockData[] | null>(null)
  const [stagedEditCash, setStagedEditCash] = useState<CashData[] | null>(null)
  const [pendingBackupDependencyImport, setPendingBackupDependencyImport] = useState<PendingBackupDependencyImport | null>(null)
  const [importDependencyApplying, setImportDependencyApplying] = useState(false)
  const [importDependencyError, setImportDependencyError] = useState('')
  const [suppressedHoverMenu, setSuppressedHoverMenu] = useState<HeaderHoverMenuId | null>(null)

  const showSyncToast = useCallback((msg: string, type: ToastType) => {
    showSyncToastBase(msg, type, durationForToastType(type))
  }, [showSyncToastBase])

  const hasMargin = cash.some(c => c.marginFlag)
  const virtualBalance = store.config.virtualBalanceEnabled

  function clearStagedEditData() {
    setStagedEditStocks(null)
    setStagedEditCash(null)
  }

  function enterEditMode(
    stocks?: StockData[] | null,
    cashEntries?: CashData[] | null,
    dividendDateOverride?: string | null
  ) {
    setStagedEditStocks(stocks ?? null)
    setStagedEditCash(cashEntries ?? null)
    setDividendDate(dividendDateOverride ?? store.config.dividendStartDate ?? '')
    setEditResetKey(k => k + 1)
    setEditModeActive(true)
  }

  function toggleEditMode() {
    if (editModeActive) {
      clearStagedEditData()
      setDividendDate(store.config.dividendStartDate ?? '')
      setEditModeActive(false)
    } else {
      enterEditMode()
    }
  }

  function dependencyPayloadFromBackupImport(json: BackupImportPayload) {
    const tickerConfigs = new Map<string, { symbol: string; letf: string; groups: string }>()
    ;(json.stocks ?? []).forEach(stock => {
      const symbol = stockKey(String(stock.symbol ?? stock.label ?? ''))
      const letf = String(stock.letf ?? '').trim()
      const groups = String(stock.groups ?? '').trim()
      if (!symbol || (!letf && !groups)) return
      tickerConfigs.set(symbol, { symbol, letf, groups })
    })
    return { tickerConfigs: [...tickerConfigs.values()] }
  }

  function importedStocksFromBackup(
    json: BackupImportPayload,
    preview?: ImportDependencyPreview,
  ): StockData[] | null {
    if (!json.stocks) return null

    const currentStocksBySymbol = new Map(store.stocks.map(s => [stockKey(s.label), s]))
    const previewBySymbol = new Map((preview?.tickerConfigs ?? []).map(row => [stockKey(row.symbol), row]))
    const parsedStocks = json.stocks.map(s => {
      const symbol = String(s.symbol ?? s.label ?? '')
      const key = stockKey(symbol)
      const current = currentStocksBySymbol.get(key)
      const previewRow = previewBySymbol.get(key)
      const importedLetf = String(s.letf ?? '').trim()
      const importedGroups = String(s.groups ?? '').trim()
      const letf = previewRow
        ? (previewRow.enabled === false ? previewRow.current.letf : previewRow.next.letf)
        : (importedLetf || current?.letf || '')
      const groups = previewRow
        ? (previewRow.enabled === false ? previewRow.current.groups : previewRow.next.groups)
        : (importedGroups || current?.groups || '')

      return {
        label: symbol,
        amount: s.amount ?? 0,
        originalAmount: s.amount ?? 0,
        targetWeight: s.targetWeight ?? 0,
        letf,
        groups,
      }
    })
    return appendMissingStocksWithZeroQty(parsedStocks, store.stocks)
  }

  function importedCashFromBackup(json: BackupImportPayload): CashData[] | null {
    if (!json.cash) return null
    return json.cash
      .map(c => parseCashKey(c.key, c.value))
      .filter((c): c is CashData => c !== null)
  }

  function enterBackupImportEditMode(json: BackupImportPayload, preview?: ImportDependencyPreview) {
    const importedStocks = importedStocksFromBackup(json, preview)
    const importedCash = importedCashFromBackup(json)
    const importedDividendDate = typeof json.dividendStartDate === 'string'
      ? json.dividendStartDate
      : undefined
    enterEditMode(importedStocks, importedCash, importedDividendDate)
  }

  async function maybeShowBackupDependencyDialog(
    json: BackupImportPayload,
    mode: PendingBackupDependencyImport['mode'],
  ) {
    const dependencyPayload = dependencyPayloadFromBackupImport(json)
    const preview = await buildImportDependencyPreview(dependencyPayload)
    if (hasImportDependencyPreview(preview)) {
      setPendingBackupDependencyImport({ json, preview, mode })
      setImportDependencyError('')
      return true
    }
    return false
  }

  const displayCurrencies = appConfig?.displayCurrencies ?? ['USD']
  const portfolioColumnModes = normalizePortfolioColumnModes(appConfig?.portfolioColumnModes)
  const activeColumnMode = getPortfolioColumnMode(portfolioColumnModes, portfolioColumnModeId)
  const modeHasMoreInfo = portfolioModeHasMoreInfo(activeColumnMode.columns)
  const modeHasRebal = portfolioModeHasRebal(activeColumnMode.columns)

  function renderHeaderHoverMenu(menuId: HeaderHoverMenuId, options: HeaderHoverMenuOption[], ariaLabel: string) {
    return (
      <div className="header-hover-menu" role="menu" aria-label={ariaLabel}>
        {options.map(option => (
          <button
            key={option.id}
            type="button"
            className={`header-hover-menu-item${option.active ? ' active' : ''}`}
            role="menuitemradio"
            aria-checked={option.active}
            onClick={e => {
              option.onSelect()
              setSuppressedHoverMenu(menuId)
              e.currentTarget.blur()
            }}
          >
            {option.label}
          </button>
        ))}
      </div>
    )
  }

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
  }, [portfolioId, store, setEditModeActive, showSyncToast])

  async function restoreDbBackupFromPayload(json: BackupImportPayload, preview?: ImportDependencyPreview) {
    const importedStocks = importedStocksFromBackup(json, preview) ?? store.stocks
    const importedCash = importedCashFromBackup(json) ?? store.cash
    const dividendDateToSave = typeof json.dividendStartDate === 'string'
      ? json.dividendStartDate
      : store.config.dividendStartDate ?? ''

    const r = await fetch(`/api/portfolio/save-all?portfolio=${portfolioId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        stocks: importedStocks.map(s => ({
          symbol: s.label.trim().toUpperCase(),
          amount: s.originalAmount ?? s.amount ?? 0,
          targetWeight: s.targetWeight ?? 0,
          letf: s.letf ?? '',
          groups: s.groups ?? '',
        })),
        cash: importedCash,
        dividendStartDate: dividendDateToSave || null,
      }),
    })
    if (!r.ok) throw new Error(await r.text())
    navigate(0)
  }

  // ── Import success callback (from BackupPanel) ─────────────────────────────
  const handleImportSuccess = useCallback(async (json: BackupImportPayload) => {
    try {
      setBackupOpen(false)
      if (await maybeShowBackupDependencyDialog(json, 'fileImport')) return
      enterBackupImportEditMode(json)
    } catch (e) {
      showSyncToast(`Import failed: ${errorMessage(e)}`, 'error')
    }
  }, [store, showSyncToast])

  const handleRestorePreview = useCallback(async (json: BackupImportPayload, _backupId: number) => {
    try {
      setBackupOpen(false)
      if (await maybeShowBackupDependencyDialog(json, 'dbRestore')) return
      await restoreDbBackupFromPayload(json)
    } catch (e) {
      showSyncToast(`Restore failed: ${errorMessage(e)}`, 'error')
    }
  }, [store, portfolioId, navigate, showSyncToast])

  async function confirmPendingBackupDependencyImport(previewArg?: ImportDependencyPreview) {
    if (!pendingBackupDependencyImport || importDependencyApplying) return
    const preview = previewArg ?? pendingBackupDependencyImport.preview
    setImportDependencyApplying(true)
    setImportDependencyError('')
    try {
      await applyImportDependencyPreview(preview)
      if (pendingBackupDependencyImport.mode === 'dbRestore') {
        await restoreDbBackupFromPayload(pendingBackupDependencyImport.json, preview)
      } else {
        enterBackupImportEditMode(pendingBackupDependencyImport.json, preview)
        showSyncToast('Import staged.', 'ok')
      }
      setPendingBackupDependencyImport(null)
    } catch (e) {
      setImportDependencyError(errorMessage(e))
    } finally {
      setImportDependencyApplying(false)
    }
  }

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
      marginStrategies: marginTargetPct !== null && marginTargetPct >= 0 ? [{
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
  }, [store, portfolioId, allPortfolios, showSyncToast])

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
    const currencyOptions = displayCurrencies.map(currency => ({
      id: currency,
      label: currency,
      active: currency === currentDisplayCurrency,
      onSelect: () => store.setDisplayCurrency(currency),
    }))
    return (
      <div
        className={`header-hover-menu-wrap${suppressedHoverMenu === 'currency' ? ' is-suppressed' : ''}`}
        onMouseLeave={() => {
          if (suppressedHoverMenu === 'currency') setSuppressedHoverMenu(null)
        }}
      >
        <button
          className="currency-toggle active"
          type="button"
          aria-haspopup="menu"
          title="Switch display currency"
          onClick={e => {
            const idx = displayCurrencies.indexOf(currentDisplayCurrency)
            const next = displayCurrencies[(idx + 1) % displayCurrencies.length]
            store.setDisplayCurrency(next)
            setSuppressedHoverMenu('currency')
            e.currentTarget.blur()
          }}
        >
          <span className="currency-toggle-icon">⇄</span>
          <span className="toggle-label">{currentDisplayCurrency}</span>
        </button>
        {renderHeaderHoverMenu('currency', currencyOptions, 'Display currencies')}
      </div>
    )
  }

  function renderColumnModeControl() {
    if (portfolioColumnModes.length === 1) {
      return <span className="portfolio-column-mode-pill">{activeColumnMode.name}</span>
    }
    const columnModeOptions = portfolioColumnModes.map(mode => ({
      id: mode.id,
      label: mode.name,
      active: mode.id === activeColumnMode.id,
      onSelect: () => setPortfolioColumnModeId(mode.id),
    }))
    return (
      <div
        className={`header-hover-menu-wrap${suppressedHoverMenu === 'columnMode' ? ' is-suppressed' : ''}`}
        onMouseLeave={() => {
          if (suppressedHoverMenu === 'columnMode') setSuppressedHoverMenu(null)
        }}
      >
        <button
          className="portfolio-column-mode-toggle active"
          type="button"
          aria-haspopup="menu"
          title="Switch portfolio column mode"
          onClick={e => {
            const idx = portfolioColumnModes.findIndex(mode => mode.id === activeColumnMode.id)
            const next = portfolioColumnModes[(idx + 1) % portfolioColumnModes.length]
            setPortfolioColumnModeId(next.id)
            setSuppressedHoverMenu('columnMode')
            e.currentTarget.blur()
          }}
        >
          <span className="currency-toggle-icon">⇄</span>
          <span className="toggle-label">{activeColumnMode.name}</span>
        </button>
        {renderHeaderHoverMenu('columnMode', columnModeOptions, 'Portfolio display modes')}
      </div>
    )
  }

  const contentScalePct = Math.round(portfolioContentScale * 100)
  const contentScaleStyle = {
    '--portfolio-content-scale': String(portfolioContentScale),
  } as CSSProperties

  return (
    <div className={`container${afterHoursGray ? ' after-hours-gray' : ''}${modeHasMoreInfo ? ' more-info-visible' : ''}${modeHasRebal ? ' rebalancing-visible' : ''}${editModeActive ? ' editing-active' : ''}`}>
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

          {renderColumnModeControl()}

          <button
            className={`groups-toggle h-btn subtle${groupViewActive ? ' active-edit' : ''}`}
            id="groups-toggle"
            type="button"
            title="Switch between stock view and group view"
            onClick={() => setGroupViewActive(!groupViewActive)}
          >Groups</button>

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
      <TransientToast msg={syncToast.msg} type={syncToast.type} onDismiss={clearSyncToast} />

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
                  const computedDelta = result.rebalQty[s.label]
                  const delta = computedDelta && computedDelta !== 0
                    ? computedDelta
                    : (result.rebalDollars[s.label] ?? 0) / (getLivePriceUsd(liveBySymbol.get(s.label), fxRates) ?? Infinity)
                  if (!delta || delta === 0) return s
                  const nextAmount = parseFloat(((s.originalAmount ?? s.amount) + delta).toFixed(2))
                  return { ...s, originalAmount: nextAmount }
                })
                const pendingDate = store.config.dividendCalcUpToDate || store.config.dividendStartDate || ''
                enterEditMode(updated, null, pendingDate)
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
          onImportSuccess={handleImportSuccess}
          onRestorePreview={handleRestorePreview}
        />
      )}

      {pendingBackupDependencyImport && (
        <ImportDependenciesDialog
          preview={pendingBackupDependencyImport.preview}
          config={dependencyPayloadFromBackupImport(pendingBackupDependencyImport.json)}
          applying={importDependencyApplying}
          error={importDependencyError}
          onCancel={() => setPendingBackupDependencyImport(null)}
          onConfirm={preview => confirmPendingBackupDependencyImport(preview)}
        />
      )}
    </div>
  )
}
