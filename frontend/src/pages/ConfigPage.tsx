// ── ConfigPage.tsx — App Settings (full React port, no vanilla JS) ────────────

import { useCallback, useEffect, useRef, useState } from 'react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import { showConfirm } from '@/components/ConfirmDialog'
import IbkrConfigDialog from '@/components/portfolio/IbkrConfigDialog'

// ── Types ─────────────────────────────────────────────────────────────────────

interface ConfigValues {
  showStockDisplayCurrency?: string
  privacyScalePct?: string
  afterHoursGray?: string
  openBrowser?: string
  twsHost?: string
  twsPort?: string
  exchangeSuffixes?: string
  githubRepo?: string
  navUpdateInterval?: string
  ibkrRateInterval?: string
  dividendSafeLagDays?: string
  updateCheckInterval?: string
  autoUpdate?: string
  _version?: string
  _latestVersion?: string
  _hasUpdate?: string
  _downloadPhase?: string
  _isJpackageInstall?: string
  _releaseUrl?: string
  _lastCheckError?: string
}

interface PortfolioRow {
  slug: string
  name: string
  virtualBalance: boolean
}

interface IbkrConfig {
  token: string
  queryId: string
  twsAccount: string
}

interface SessionInfo {
  token: string
  userAgent: string
  ip: string
  createdAt: number
  isCurrent: boolean
}

interface DeviceInfo {
  serverAssignedId: string
  name?: string
  lastIp?: string
  pairedAt: number
}

// ── Shared layout helpers ─────────────────────────────────────────────────────

function ConfigSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="config-section">
      <div className="config-section-header"><h2>{title}</h2></div>
      <div className="config-section-body">{children}</div>
    </div>
  )
}

function ConfigField({
  label, description, inputId, badge, children,
}: { label: string; description: string; inputId: string; badge?: string | null; children: React.ReactNode }) {
  const badgeText = badge === 'restart' ? 'restart required'
    : badge === 'live' ? 'live'
    : badge === 'readonly' ? 'read-only'
    : badge === 'next-launch' ? 'next launch'
    : badge ?? null
  return (
    <div className="config-field">
      <div className="config-field-label-row">
        <label htmlFor={inputId}>{label}</label>
        {badgeText && <span className={`config-badge config-badge-${badge}`}>{badgeText}</span>}
      </div>
      <span className="config-field-description">{description}</span>
      <div className="config-field-input-col">{children}</div>
    </div>
  )
}

function ReadOnlyField({ label, description, value }: { label: string; description: string; value: string }) {
  return (
    <div className="config-field">
      <div className="config-field-label-row">
        <span>{label}</span>
        <span className="config-badge config-badge-readonly">read-only</span>
      </div>
      <span className="config-field-description">{description}</span>
      <div className="config-field-input-col">
        <input type="text" value={value} disabled readOnly />
      </div>
    </div>
  )
}

function fmtDate(ms: number) {
  return new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

// ── Pairing section ───────────────────────────────────────────────────────────

function PairingSection({ onPaired }: { onPaired?: () => void }) {
  const [state, setState] = useState<'idle' | 'loading' | 'showing'>('idle')
  const [pin, setPin] = useState('')
  const pollRef = useRef<number | null>(null)

  function stopPoll() {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null }
  }

  async function generatePin() {
    stopPoll()
    setState('loading')
    try {
      const r = await fetch('/api/pairing/generate', { method: 'POST' })
      const { pin: p } = await r.json()
      setPin(p)
      setState('showing')
      pollRef.current = window.setInterval(async () => {
        try {
          const sr = await fetch(`/api/pairing/status?pin=${encodeURIComponent(p)}`)
          const { status } = await sr.json()
          if (status === 'active') return
          stopPoll()
          if (status === 'used') onPaired?.()
          setState('idle')
        } catch (_) {}
      }, 3000)
    } catch (_) {
      setState('idle')
    }
  }

  useEffect(() => () => stopPoll(), [])

  return (
    <div className="pairing-pin-container">
      {state === 'idle' && (
        <button className="config-restore-btn" type="button" onClick={generatePin}>Generate Pairing Code</button>
      )}
      {state === 'loading' && (
        <span className="config-env-override-note">Generating…</span>
      )}
      {state === 'showing' && (
        <div className="pin-display-group">
          <div className="pin-number-display">{pin}</div>
          <button className="config-restore-btn" type="button" onClick={generatePin}>Generate New PIN</button>
        </div>
      )}
    </div>
  )
}

// ── Sessions list ─────────────────────────────────────────────────────────────

function SessionsList() {
  const [sessions, setSessions] = useState<SessionInfo[]>([])
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const r = await fetch('/api/admin/sessions')
      setSessions(await r.json())
    } catch (_) {}
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  if (loading) return <p className="config-env-override-note">Loading…</p>
  if (!sessions.length) return <p className="config-env-override-note">No sessions found.</p>

  return (
    <table className="management-table">
      <thead><tr><th>Trusted Browser</th><th>IP</th><th>Added</th><th /></tr></thead>
      <tbody>
        {sessions.map(s => (
          <tr key={s.token}>
            <td title={s.userAgent}>{s.userAgent.length > 60 ? s.userAgent.slice(0, 60) + '…' : s.userAgent}</td>
            <td>{s.ip}</td>
            <td>{fmtDate(s.createdAt)}</td>
            <td className="management-table-action-col">
              {s.isCurrent
                ? <span className="config-badge config-badge-live">this browser</span>
                : (
                  <button
                    className="management-table-remove-btn"
                    onClick={async () => {
                      await fetch(`/api/admin/session?token=${encodeURIComponent(s.token)}`, { method: 'DELETE' })
                      load()
                    }}
                  >
                    Remove
                  </button>
                )
              }
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

// ── Devices list ──────────────────────────────────────────────────────────────

function DevicesList() {
  const [devices, setDevices] = useState<DeviceInfo[]>([])
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const r = await fetch('/api/paired-devices')
      setDevices(await r.json())
    } catch (_) {}
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  if (loading) return <p className="config-env-override-note">Loading devices…</p>
  if (!devices.length) return <p className="config-env-override-note">No devices paired.</p>

  return (
    <table className="management-table">
      <thead>
        <tr>
          <th>Paired Device</th>
          <th>Last IP</th>
          <th>Paired</th>
          <th className="management-table-action-col">
            <button
              className="management-table-remove-btn"
              onClick={async () => {
                if (!await showConfirm('Remove all paired devices?', 'Remove All')) return
                await fetch('/api/unpair-all', { method: 'POST' })
                load()
              }}
            >
              Remove All
            </button>
          </th>
        </tr>
      </thead>
      <tbody>
        {devices.map(d => (
          <tr key={d.serverAssignedId}>
            <td>{d.name || '(unnamed)'}</td>
            <td>{d.lastIp || '—'}</td>
            <td>{fmtDate(d.pairedAt)}</td>
            <td className="management-table-action-col">
              <button
                className="management-table-remove-btn"
                onClick={async () => {
                  await fetch(`/api/unpair?id=${encodeURIComponent(d.serverAssignedId)}`, { method: 'DELETE' })
                  load()
                }}
              >
                Remove
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

// ── Main component ────────────────────────────────────────────────────────────

export default function ConfigPage() {
  const [cfg, setCfg]               = useState<ConfigValues>({})
  const [loaded, setLoaded]         = useState(false)
  const [portfolios, setPortfolios] = useState<PortfolioRow[]>([])
  const [status, setStatus]         = useState({ msg: '', type: '' })
  const [updateStatus, setUpdateStatus] = useState({ msg: '', type: '' })
  const [updateProgress, setUpdateProgress] = useState<{ phase: string; received: number; total: number } | null>(null)
  const [latestVersion, setLatestVersion] = useState('')
  const [hasUpdate, setHasUpdate]   = useState(false)
  const [pairingRefreshKey, setPairingRefreshKey] = useState(0)
  const [newPortfolioName, setNewPortfolioName] = useState('')
  const [addStatus, setAddStatus]   = useState('')
  const [renameErrors, setRenameErrors] = useState<Record<string, string>>({})
  const [pendingRenames, setPendingRenames] = useState<Record<string, string>>({})
  const [ibkrConfigSlug, setIbkrConfigSlug] = useState<string | null>(null)
  const [ibkrConfigs, setIbkrConfigs] = useState<Record<string, IbkrConfig>>({})
  const saveTimers = useRef<Map<string, number>>(new Map())
  const statusTimer = useRef<number>(0)
  const downloadPollRef = useRef<number | null>(null)

  const isJpackage   = cfg._isJpackageInstall === 'true'
  const downloadPhase = cfg._downloadPhase ?? 'IDLE'
  const version       = cfg._version ?? ''

  useEffect(() => {
    fetch('/api/admin/config-values')
      .then(r => r.json())
      .then((data: ConfigValues) => {
        setCfg(data)
        setLoaded(true)
        setHasUpdate(data._hasUpdate === 'true')
        setLatestVersion(data._latestVersion ?? '')
        if (data._downloadPhase === 'DOWNLOADING') startDownloadPoll()
      })
      .catch(() => {})

    fetch('/api/portfolio/data')
      .then(r => r.json())
      .then((data: any) => {
        const allPortfolios = data.allPortfolios || []
        Promise.all([
          Promise.all(allPortfolios.map((p: any) =>
            fetch(`/api/admin/config-values?portfolio=${encodeURIComponent(p.slug)}`).then(r => r.json()).catch(() => ({}))
          )),
          Promise.all(allPortfolios.map((p: any) =>
            fetch(`/api/portfolio/${p.slug}/ibkr-config`).then(r => r.json()).then((d: IbkrConfig) => [p.slug, d] as const).catch(() => null)
          )),
        ]).then(([configs, ibkrResults]) => {
          setPortfolios(allPortfolios.map((p: any, i: number) => ({
            slug: p.slug,
            name: p.name,
            virtualBalance: configs[i]?.virtualBalance === 'true',
          })))
          const map: Record<string, IbkrConfig> = {}
          for (const r of ibkrResults) { if (r) map[r[0]] = r[1] }
          setIbkrConfigs(map)
        })
      })
      .catch(() => {})
  }, [])

  // ── Auto-save helpers ─────────────────────────────────────────────────────

  function showStatus(msg: string, type: string) {
    setStatus({ msg, type })
    clearTimeout(statusTimer.current)
    statusTimer.current = window.setTimeout(() => setStatus({ msg: '', type: '' }), type === 'ok' ? 2500 : 5000)
  }

  const saveField = useCallback((key: string, value: string, portfolioId?: string) => {
    const timerKey = portfolioId ? `${portfolioId}:${key}` : key
    if (saveTimers.current.has(timerKey)) clearTimeout(saveTimers.current.get(timerKey)!)
    saveTimers.current.set(timerKey, window.setTimeout(async () => {
      try {
        const url = portfolioId
          ? `/api/portfolio-config/save?portfolio=${encodeURIComponent(portfolioId)}`
          : '/api/config/save'
        const r = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ [key]: value }),
        })
        if (!r.ok) throw new Error(r.statusText)
        showStatus('Saved.', 'ok')
      } catch (err: any) {
        showStatus('Error: ' + err.message, 'error')
      }
    }, 600))
  }, [])

  function refreshIbkrConfig(slug: string) {
    fetch(`/api/portfolio/${slug}/ibkr-config`)
      .then(r => r.json())
      .then((d: IbkrConfig) => setIbkrConfigs(prev => ({ ...prev, [slug]: d })))
      .catch(() => {})
  }

  // ── Portfolio management ──────────────────────────────────────────────────

  async function handleAddPortfolio() {
    const name = newPortfolioName.trim()
    if (!name) { setAddStatus('Enter a portfolio name.'); return }
    setAddStatus('')
    try {
      const r = await fetch('/api/portfolio/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
      const data = await r.json()
      if (!r.ok) { setAddStatus(data.message || 'Failed to create portfolio.'); return }
      location.reload()
    } catch (err: any) {
      setAddStatus('Error: ' + err.message)
    }
  }

  async function handleRename(slug: string) {
    const newName = (pendingRenames[slug] ?? '').trim()
    if (!newName) return
    setRenameErrors(e => ({ ...e, [slug]: '' }))
    try {
      const r = await fetch(`/api/portfolio/rename?portfolio=${encodeURIComponent(slug)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName }),
      })
      const data = await r.json()
      if (!r.ok) {
        setRenameErrors(e => ({ ...e, [slug]: data.message || 'Rename failed.' }))
        return
      }
      location.reload()
    } catch (err: any) {
      setRenameErrors(e => ({ ...e, [slug]: 'Error: ' + err.message }))
    }
  }

  async function handleRemovePortfolio(slug: string, name: string) {
    if (!await showConfirm(`Remove portfolio "${name}"? All positions, cash, and config will be deleted.`, 'Remove')) return
    try {
      const r = await fetch(`/api/portfolio/remove?portfolio=${encodeURIComponent(slug)}`, { method: 'DELETE' })
      const data = await r.json()
      if (!r.ok) { alert(data.message || 'Remove failed.'); return }
      location.reload()
    } catch (err: any) { alert('Error: ' + err.message) }
  }

  // ── Restore defaults ──────────────────────────────────────────────────────

  const GLOBAL_DEFAULTS: Record<string, string> = {
    openBrowser: 'true', dataDir: '', navUpdateInterval: '',
    exchangeSuffixes: 'SBF=.PA,LSEETF=.L', twsHost: '127.0.0.1', twsPort: '7496',
    ibkrRateInterval: '3600', autoUpdate: 'true', updateCheckInterval: '86400',
  }

  async function handleRestoreDefaults() {
    if (!await showConfirm('Restore all settings to defaults?', 'Restore')) return
    try {
      await fetch('/api/config/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(GLOBAL_DEFAULTS),
      })
      showStatus('Defaults restored.', 'ok')
      location.reload()
    } catch (err: any) { showStatus('Error: ' + err.message, 'error') }
  }

  // ── Update management ─────────────────────────────────────────────────────

  function startDownloadPoll() {
    if (downloadPollRef.current) return
    downloadPollRef.current = window.setInterval(async () => {
      try {
        const r = await fetch('/api/admin/update-info')
        const info = await r.json()
        const phase = info.download?.phase || 'IDLE'
        setCfg(prev => ({ ...prev, _downloadPhase: phase }))
        setUpdateProgress({ phase, received: info.download?.bytesReceived || 0, total: info.download?.totalBytes || 0 })
        if (phase !== 'DOWNLOADING') {
          clearInterval(downloadPollRef.current!)
          downloadPollRef.current = null
          if (phase === 'READY') setUpdateStatus({ msg: 'Download complete. Click "Apply Update & Restart" to install.', type: 'ok' })
        }
      } catch (_) {}
    }, 1000)
  }

  function setUpdateInfo(info: any) {
    if (info.hasUpdate != null)      setHasUpdate(info.hasUpdate)
    if (info.latestVersion != null)  setLatestVersion(info.latestVersion)
    if (info.download?.phase != null) setCfg(prev => ({ ...prev, _downloadPhase: info.download.phase }))
  }

  function attemptReconnect() {
    fetch('/').then(r => { if (r.ok) location.reload(); else setTimeout(attemptReconnect, 1000) }).catch(() => setTimeout(attemptReconnect, 1000))
  }

  async function handleCheckUpdate() {
    setUpdateStatus({ msg: 'Checking for updates…', type: 'ok' })
    try {
      const r = await fetch('/api/admin/check-update', { method: 'POST' })
      const info = await r.json()
      setUpdateInfo(info)
      if (info.lastCheckError) setUpdateStatus({ msg: 'Check failed: ' + info.lastCheckError, type: 'error' })
      else if (info.hasUpdate) setUpdateStatus({ msg: 'Update available: v' + info.latestVersion, type: 'warn' })
      else setUpdateStatus({ msg: 'You are up to date (v' + info.currentVersion + ').', type: 'ok' })
    } catch (err: any) { setUpdateStatus({ msg: 'Error: ' + err.message, type: 'error' }) }
  }

  async function handleDownloadUpdate() {
    setUpdateStatus({ msg: 'Starting download…', type: 'ok' })
    try {
      const r = await fetch('/api/admin/download-update', { method: 'POST' })
      if (r.status === 409) {
        const body = await r.json().catch(() => ({}))
        if (body.status === 'already-downloading') {
          setUpdateStatus({ msg: 'Download already in progress.', type: 'warn' })
          startDownloadPoll()
        } else {
          setUpdateStatus({ msg: 'Not supported on this install type.', type: 'error' })
        }
      } else {
        startDownloadPoll()
      }
    } catch (err: any) { setUpdateStatus({ msg: 'Error: ' + err.message, type: 'error' }) }
  }

  async function handleApplyUpdate() {
    setUpdateStatus({ msg: 'Applying update and restarting…', type: 'ok' })
    try {
      await fetch('/api/admin/apply-update', { method: 'POST' })
      setUpdateStatus({ msg: 'Restarting… reconnecting when ready.', type: 'ok' })
      setTimeout(attemptReconnect, 2000)
    } catch (err: any) { setUpdateStatus({ msg: 'Error: ' + err.message, type: 'error' }) }
  }

  async function handleRestart() {
    setUpdateStatus({ msg: 'Restarting app…', type: 'ok' })
    try {
      await fetch('/api/admin/restart', { method: 'POST' })
    } catch (_) {}
    setUpdateStatus({ msg: 'Restarting… reconnecting when ready.', type: 'ok' })
    setTimeout(attemptReconnect, 2000)
  }

  useEffect(() => () => { clearInterval(downloadPollRef.current!) }, [])

  const releaseUrl = cfg._releaseUrl ?? '#'
  const lastCheckError = cfg._lastCheckError ?? ''
  const currentDownloadPhase = cfg._downloadPhase ?? 'IDLE'

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group"><PageNavTabs active="/config" /></div>
        <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
      </div>

      <main className="config-page">
        <h1>App Settings</h1>

        {/* ── Authorized Devices ──────────────────────────────────────── */}
        <ConfigSection title="Authorized Devices">
          <div className="config-field">
            <div className="config-field-label-row">
              <span>Authorize New Device</span>
              <span className="config-badge config-badge-live">live</span>
            </div>
            <span className="config-field-description">
              Show this PIN on your screen and enter it in the Android app.<br />Expires in 5 minutes.
            </span>
            <PairingSection onPaired={() => setPairingRefreshKey(k => k + 1)} />
          </div>

          <div className="config-field">
            <DevicesList key={pairingRefreshKey} />
          </div>

          <div className="config-field">
            <SessionsList />
          </div>
        </ConfigSection>

        {/* ── cfg-dependent sections (deferred until loaded) ───────────── */}
        {!loaded ? (
          <p className="config-env-override-note">Loading…</p>
        ) : (<>

        {/* ── Display ─────────────────────────────────────────────────── */}
        <ConfigSection title="Display">
          <ConfigField label="P&L and Market Value in display currency" description="Convert per-stock P&L and Mkt Val columns to the selected display currency. Default: off (show in stock's native currency)." inputId="show-stock-display-currency">
            <input
              type="checkbox" id="show-stock-display-currency"
              defaultChecked={cfg.showStockDisplayCurrency === 'true'}
              onChange={e => saveField('showStockDisplayCurrency', String(e.target.checked))}
            />
          </ConfigField>

          <ConfigField label="Privacy Scaling %" description="Scale all managed assets (quantities and cash) by this percentage for display purposes. Leave empty to disable." inputId="privacy-scale-pct">
            <input
              type="number" id="privacy-scale-pct" placeholder="None"
              defaultValue={cfg.privacyScalePct ?? ''} min={1} max={999} step={1}
              onChange={e => saveField('privacyScalePct', e.target.value)}
            />
          </ConfigField>

          <ConfigField label="After-Hours Style: Gray" description="Show after-hours prices and changes as solid gray (default). When off, keeps positive/negative colors but dimmed." inputId="after-hours-gray">
            <input
              type="checkbox" id="after-hours-gray"
              defaultChecked={cfg.afterHoursGray !== 'false'}
              onChange={e => saveField('afterHoursGray', String(e.target.checked))}
            />
          </ConfigField>
        </ConfigSection>

        {/* ── Portfolio and IB TWS Settings ───────────────────────────── */}
        <ConfigSection title="Portfolio and IB TWS Settings">
          <table className="portfolio-config-table">
            <thead>
              <tr><th>Portfolio</th><th>IB Config</th><th>Virtual Balance</th><th /></tr>
            </thead>
            <tbody>
              {portfolios.map((p, i) => (
                <tr key={p.slug} data-portfolio-slug={p.slug}>
                  <td>
                    <div className="portfolio-name-cell">
                      <div className="portfolio-name-input-row">
                        <input
                          type="text" className="portfolio-name-input"
                          defaultValue={p.name} data-original-name={p.name} data-slug={p.slug}
                          autoComplete="off" maxLength={64}
                          onChange={e => setPendingRenames(prev => ({ ...prev, [p.slug]: e.target.value }))}
                          onKeyDown={e => { if (e.key === 'Enter') handleRename(p.slug) }}
                        />
                        {pendingRenames[p.slug] && pendingRenames[p.slug] !== p.name && (
                          <button
                            type="button" className="portfolio-rename-confirm-btn"
                            title="Apply rename" onClick={() => handleRename(p.slug)}
                          >
                            ✓
                          </button>
                        )}
                      </div>
                      {renameErrors[p.slug] && (
                        <span className="portfolio-rename-error">{renameErrors[p.slug]}</span>
                      )}
                    </div>
                  </td>
                  <td>
                    {ibkrConfigs[p.slug] && (ibkrConfigs[p.slug].twsAccount || ibkrConfigs[p.slug].queryId || ibkrConfigs[p.slug].token) && (
                      <div style={{ fontSize: '0.75rem', color: 'var(--color-text-muted, #888)', fontFamily: 'monospace', display: 'flex', flexDirection: 'column', gap: '0.1rem', marginBottom: '0.35rem' }}>
                        <span>{ibkrConfigs[p.slug].twsAccount || '—'}</span>
                        <span>QID: {ibkrConfigs[p.slug].queryId || '—'}</span>
                        <span>Token: {ibkrConfigs[p.slug].token ? '••••' : '—'}</span>
                      </div>
                    )}
                    <button
                      type="button" className="config-restore-btn"
                      onClick={() => setIbkrConfigSlug(p.slug)}
                    >
                      IB Config
                    </button>
                  </td>
                  <td className="portfolio-config-table-checkbox-col">
                    <input
                      type="checkbox"
                      defaultChecked={p.virtualBalance}
                      onChange={e => saveField('virtualBalance', String(e.target.checked), p.slug)}
                    />
                  </td>
                  <td className="portfolio-config-table-actions-col">
                    <div>
                      <button
                        type="button"
                        className="management-table-remove-btn portfolio-remove-btn"
                        disabled={i === 0}
                        style={i === 0 ? { visibility: 'hidden' } : undefined}
                        onClick={() => handleRemovePortfolio(p.slug, p.name)}
                      >
                        Remove
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="add-portfolio-form">
            <input
              type="text" id="new-portfolio-name" placeholder="New portfolio name" maxLength={64}
              value={newPortfolioName} onChange={e => setNewPortfolioName(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') handleAddPortfolio() }}
            />
            <button type="button" className="config-restore-btn" onClick={handleAddPortfolio}>Add Portfolio</button>
            {addStatus && <span className="config-env-override-note">{addStatus}</span>}
          </div>

          <ConfigField label="TWS Host" description="Hostname or IP address of the TWS / IB Gateway." inputId="tws-host">
            <input type="text" id="tws-host" placeholder="127.0.0.1" defaultValue={cfg.twsHost ?? ''} onChange={e => saveField('twsHost', e.target.value)} />
          </ConfigField>

          <ConfigField label="TWS Port" description="Port of the TWS / IB Gateway. Default: 7496 (live), 7497 (paper), 4001 (IB Gateway live)." inputId="tws-port">
            <input type="number" id="tws-port" placeholder="7496" defaultValue={cfg.twsPort ?? ''} min={1} max={65535} onChange={e => saveField('twsPort', e.target.value)} />
          </ConfigField>
        </ConfigSection>

        {/* ── Server ──────────────────────────────────────────────────── */}
        <ConfigSection title="Server">
          <ConfigField label="Open Browser on Start" description="Automatically open the browser when the app starts." inputId="open-browser" badge="next-launch">
            <input type="checkbox" id="open-browser" defaultChecked={cfg.openBrowser !== 'false'} onChange={e => saveField('openBrowser', String(e.target.checked))} />
          </ConfigField>
        </ConfigSection>

        {/* ── Updates & Restart ────────────────────────────────────────── */}
        <ConfigSection title="Updates &amp; Restart">
          <ReadOnlyField label="Current Version" description="The version of Portfolio Helper currently running." value={version ? `v${version}` : '—'} />

          <div className="config-field">
            <div className="config-field-label-row">
              <span>Latest Version</span>
              {hasUpdate && <span className="config-badge config-badge-update">update available</span>}
            </div>
            <span className="config-field-description">Latest release from GitHub.</span>
            <div className="config-field-input-col">
              {latestVersion
                ? <a href={releaseUrl} target="_blank" rel="noopener">v{latestVersion}</a>
                : lastCheckError
                  ? <span className="config-env-override-note">Check failed: {lastCheckError}</span>
                  : <span className="config-env-override-note">Not checked yet</span>
              }
            </div>
          </div>

          {updateProgress && (updateProgress.phase === 'DOWNLOADING' || updateProgress.phase === 'READY' || updateProgress.phase === 'APPLYING') && (
            <div className="update-progress-row visible">
              <div className="config-field-label-row"><span>Download Progress</span></div>
              <div className="update-progress-bar-container">
                <div
                  className="update-progress-bar"
                  style={{ width: updateProgress.phase === 'READY' ? '100%' : updateProgress.total > 0 ? `${Math.round(updateProgress.received / updateProgress.total * 100)}%` : '0%' }}
                />
              </div>
              <span className="config-field-description">
                {updateProgress.phase === 'READY' ? 'Download complete' : updateProgress.phase === 'APPLYING' ? 'Applying update…' : `${(updateProgress.received / 1024 / 1024).toFixed(1)} / ${(updateProgress.total / 1024 / 1024).toFixed(1)} MB`}
              </span>
            </div>
          )}

          {!isJpackage && (
            <div className="config-field">
              <span className="config-env-override-note">Running as portable JAR — download updates manually from GitHub.</span>
            </div>
          )}

          <ConfigField label="Update Check Interval (seconds)" description="How often to check GitHub for a new release. Default: 86400 (24 hours). Minimum: 60." inputId="update-check-interval">
            <input type="number" id="update-check-interval" placeholder="86400" defaultValue={cfg.updateCheckInterval ?? ''} min={60} onChange={e => saveField('updateCheckInterval', e.target.value)} />
          </ConfigField>

          {isJpackage && (
            <ConfigField label="Auto Update" description="Automatically download updates in the background when a new version is found. Requires manual apply &amp; restart." inputId="auto-update">
              <input type="checkbox" id="auto-update" defaultChecked={cfg.autoUpdate !== 'false'} onChange={e => saveField('autoUpdate', String(e.target.checked))} />
            </ConfigField>
          )}

          <div className="update-action-buttons">
            <button type="button" className="config-restore-btn" onClick={handleCheckUpdate}>Check for Updates</button>
            {isJpackage && (
              <>
                <button
                  type="button" className="config-restore-btn"
                  disabled={!hasUpdate || currentDownloadPhase !== 'IDLE'}
                  onClick={handleDownloadUpdate}
                >
                  Download Update
                </button>
                {currentDownloadPhase === 'READY' && (
                  <button type="button" className="config-save-btn" onClick={handleApplyUpdate}>
                    Apply Update &amp; Restart
                  </button>
                )}
              </>
            )}
            <button type="button" className="config-restore-btn" onClick={handleRestart}>Restart App</button>
          </div>

          {updateStatus.msg && (
            <div className={`config-status config-status-${updateStatus.type}`}>{updateStatus.msg}</div>
          )}
        </ConfigSection>

        {/* ── Market Data ──────────────────────────────────────────────── */}
        <ConfigSection title="Market Data">
          <ConfigField label="Exchange Suffixes" description="Comma-separated EXCHANGE=.SUFFIX mappings for TWS snapshot symbol resolution (e.g. SBF=.PA,LSEETF=.L)." inputId="exchange-suffixes" badge="live">
            <input type="text" id="exchange-suffixes" placeholder="SBF=.PA,LSEETF=.L" defaultValue={cfg.exchangeSuffixes ?? ''} onChange={e => saveField('exchangeSuffixes', e.target.value)} />
          </ConfigField>

          <ConfigField label="GitHub Repository" description="GitHub repo for update checks (owner/repo format)." inputId="github-repo">
            <input type="text" id="github-repo" placeholder="cyfung/portfolio-helper" defaultValue={cfg.githubRepo ?? ''} onChange={e => saveField('githubRepo', e.target.value)} />
          </ConfigField>

          <ConfigField label="NAV Update Interval (seconds)" description="How often to fetch NAV data. Leave blank to use the trading-day schedule." inputId="nav-update-interval" badge="restart">
            <input type="number" id="nav-update-interval" placeholder="trading-day schedule" defaultValue={cfg.navUpdateInterval ?? ''} min={10} onChange={e => saveField('navUpdateInterval', e.target.value)} />
          </ConfigField>

          <ConfigField label="IBKR Margin Rate Interval (seconds)" description="How often to refresh IB margin rates. Default: 3600 (1 hour). Takes effect on next fetch cycle." inputId="ibkr-rate-interval">
            <input type="number" id="ibkr-rate-interval" placeholder="3600" defaultValue={cfg.ibkrRateInterval ?? ''} min={60} onChange={e => saveField('ibkrRateInterval', e.target.value)} />
          </ConfigField>

          <ConfigField label="Dividend Safe Lag Days" description="Days before today to use as the safe end date for dividend calculations (avoids unreported recent events). Default: 5." inputId="dividend-safe-lag-days">
            <input type="number" id="dividend-safe-lag-days" placeholder="5" defaultValue={cfg.dividendSafeLagDays ?? ''} min={0} onChange={e => saveField('dividendSafeLagDays', e.target.value)} />
          </ConfigField>
        </ConfigSection>

        {/* ── Actions ──────────────────────────────────────────────────── */}
        <div className="config-actions">
          <button type="button" className="config-restore-btn" onClick={handleRestoreDefaults}>
            Restore Defaults
          </button>
          {status.msg && (
            <div className={`config-status config-status-${status.type} visible`}>{status.msg}</div>
          )}
        </div>
        </>)}
      </main>
      {ibkrConfigSlug && (
        <IbkrConfigDialog portfolioSlug={ibkrConfigSlug} onClose={() => { refreshIbkrConfig(ibkrConfigSlug); setIbkrConfigSlug(null) }} />
      )}
    </div>
  )
}
