// ── PortfolioViewer.tsx — Port of PortfolioRenderer.kt body structure ─────────
import { useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight } from '@/components/Layout'
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
    portfolioId, cash, sseStatus, lastPortfolioTotals,
    currentDisplayCurrency, moreInfoVisible, rebalVisible,
    groupViewActive, editModeActive, afterHoursGray,
    allPortfolios, appConfig,
    setMoreInfoVisible, setRebalVisible, setGroupViewActive, setEditModeActive,
  } = store

  const [backupOpen, setBackupOpen] = useState(false)
  const [saveKey, setSaveKey] = useState(0)

  const hasMargin = cash.some(c => c.marginFlag)
  const virtualBalance = store.config.virtualBalanceEnabled
  const displayCurrencies = appConfig?.displayCurrencies ?? ['USD']

  // ── TWS sync ──────────────────────────────────────────────────────────────
  const handleTwsSync = useCallback(async () => {
    try {
      const r = await fetch(`/api/tws/snapshot?portfolio=${portfolioId}`)
      if (!r.ok) throw new Error(await r.text())
      const snap = await r.json()
      // Merge TWS quantities into current stocks via the edit mode save flow
      // For now: alert with account info then reload
      alert(`TWS sync: account ${snap.account}, ${snap.positions.length} positions`)
    } catch (e) {
      alert(`TWS sync failed: ${e}`)
    }
  }, [portfolioId])

  // ── Save to backtest ───────────────────────────────────────────────────────
  const handleSaveToBacktest = useCallback(async () => {
    const { stocks } = store
    const tickers = stocks
      .filter(s => s.targetWeight > 0)
      .map(s => ({ ticker: s.label, weight: s.targetWeight }))
    if (!tickers.length) { alert('No target weights set'); return }
    const name = prompt('Save as backtest preset:')
    if (!name) return
    await fetch('/api/backtest/savedPortfolios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, config: { portfolios: [{ label: name, tickers, rebalanceStrategy: 'YEARLY' }] } }),
    })
  }, [store, portfolioId])

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

  // ── SSE status dot ────────────────────────────────────────────────────────
  const sseDotClass = `sse-dot${sseStatus === 'live' ? ' sse-dot--ok' : sseStatus === 'error' ? ' sse-dot--err' : ''}`
  const sseDotTitle = sseStatus === 'live' ? 'Live' : sseStatus === 'error' ? 'Disconnected' : 'Connecting…'

  const lastUpdateTime = lastPortfolioTotals
    ? new Date().toLocaleTimeString()
    : new Date().toLocaleTimeString()

  return (
    <div className={`container${afterHoursGray ? ' after-hours-gray' : ''}${moreInfoVisible ? ' more-info-visible' : ''}${rebalVisible ? ' rebalancing-visible' : ''}${editModeActive ? ' editing-active' : ''}`}>
      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/portfolio/" />
          <span className="header-timestamp" id="last-update-time">{lastUpdateTime}</span>
          <span className={sseDotClass} id="sse-status-dot" title={sseDotTitle} />
          <button
            className="tws-sync-btn"
            id="tws-sync-btn"
            type="button"
            title="Sync Qty and Cash from Interactive Brokers TWS"
            onClick={handleTwsSync}
          >
            Sync TWS
          </button>
        </div>

        <HeaderRight>
          <button
            className="restore-backup-btn"
            id="restore-backup-btn"
            type="button"
            title="Backup and restore portfolio"
            onClick={() => setBackupOpen(true)}
          >
            <span className="toggle-label">Backups</span>
          </button>

          <button
            className="save-btn"
            id="save-btn"
            type="button"
            title="Save changes"
            style={{ display: editModeActive ? 'inline-flex' : 'none' }}
            onClick={() => setSaveKey(k => k + 1)}
          >
            <span className="toggle-label">Save</span>
          </button>

          <button
            className={`edit-toggle${editModeActive ? ' active' : ''}`}
            id="edit-toggle"
            type="button"
            title="Edit Qty and Target Weight"
            aria-label="Toggle edit mode"
            onClick={() => setEditModeActive(!editModeActive)}
          >
            <span className="toggle-label">Edit</span>
          </button>

          <button
            className={`more-info-toggle${moreInfoVisible ? ' active' : ''}`}
            id="more-info-toggle"
            type="button"
            title="Show/Hide Last NAV, Last, and Mkt Val columns"
            onClick={() => setMoreInfoVisible(!moreInfoVisible)}
          >
            <span className="toggle-label">More Info</span>
          </button>

          <button
            className={`groups-toggle${groupViewActive ? ' active' : ''}`}
            id="groups-toggle"
            type="button"
            title="Switch between stock view and group view"
            onClick={() => setGroupViewActive(!groupViewActive)}
          >
            <span className="toggle-label">Groups</span>
          </button>

          {virtualBalance && (
            <button
              className="virtual-rebal-btn"
              id="virtual-rebal-btn"
              type="button"
              title="Apply rebalancing quantities to the portfolio (virtual — requires Save to persist)"
            >
              <span className="toggle-label">Virtual Rebalance</span>
            </button>
          )}

          <button
            className={`rebal-toggle${rebalVisible ? ' active' : ''}`}
            id="rebal-toggle"
            type="button"
            title="Show/Hide Weight and Rebalancing columns"
            aria-label="Toggle rebalancing columns"
            onClick={() => setRebalVisible(!rebalVisible)}
          >
            <span className="toggle-label">Rebal</span>
          </button>

          {renderCurrencyControl()}
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      {/* ── Portfolio tabs ────────────────────────────────────────────── */}
      <PortfolioTabs onSaveToBacktest={handleSaveToBacktest} />

      {/* ── Main content ──────────────────────────────────────────────── */}
      <div className="portfolio-tables-wrapper">
        <div className="summary-and-rates">
          <SummaryTable />
          <CashEditTable allPortfolios={allPortfolios} />
          {virtualBalance && (
            <div className="dividend-from-section">
              <label htmlFor="dividend-from-input">Dividend From</label>
              <input
                type="date"
                id="dividend-from-input"
                className="dividend-from-input"
                defaultValue={store.config.dividendStartDate}
                autoComplete="off"
              />
            </div>
          )}
          {hasMargin && <IbkrRatesSection />}
        </div>

        <div className="stock-section">
          <RebalanceControls />
          {editModeActive ? (
            <EditMode saveKey={saveKey} onSaved={handleSaved} />
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
              onClick={() => setEditModeActive(true)}
            >
              + Add Stock
            </button>
          </div>
        )}
      </div>

      {/* ── Backup panel modal ────────────────────────────────────────── */}
      {backupOpen && (
        <BackupPanel onClose={() => setBackupOpen(false)} />
      )}
    </div>
  )
}
