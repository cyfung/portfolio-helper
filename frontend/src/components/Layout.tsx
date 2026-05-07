// ── Layout.tsx — V4 command-led header (nav breadcrumb, version chip, h-btn utilities)

import { Children, isValidElement, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { showConfirm } from '@/components/ConfirmDialog'
import { showRestartOverlay, attemptReconnect } from '@/lib/restartUtils'

// ── Section list (flattened — all pages as peers) ─────────────────────────────

const STRATEGY_NAV_STORAGE_KEY = 'portfolio-helper-active-strategy-page'

const ALL_SECTIONS = [
  { href: '/portfolio/', label: 'Portfolio Viewer',   icon: 'viewer',    group: null         },
  { href: '/analyst/',  label: 'Portfolio Analyst',   icon: 'analyst',   group: null         },
  { href: '/loan',      label: 'Loan Calculator',     icon: 'loan',      group: null         },
  { href: '/portfolio-builder',   label: 'Portfolio Builder',   icon: 'builder',   group: 'strategy' },
  { href: '/backtest',            label: 'Portfolio Backtest',  icon: 'backtest',  group: 'strategy' },
  { href: '/montecarlo',          label: 'Monte Carlo',         icon: 'monte',     group: 'strategy' },
  { href: '/rebalance-strategy',  label: 'Rebalance Strategy',  icon: 'rebalance', group: 'strategy' },
] as const

// ── Inline SVG helpers ────────────────────────────────────────────────────────

function SectionSvg({ name }: { name?: string }) {
  const p = { width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  switch (name) {
    case 'viewer':    return <svg {...p}><path d="M4 20V10"/><path d="M10 20V4"/><path d="M16 20v-7"/><path d="M22 20H2"/></svg>
    case 'analyst':   return <svg {...p}><path d="M3 17l5-5 4 3 8-9"/><circle cx="20" cy="6" r="1.6"/></svg>
    case 'loan':      return <svg {...p}><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 7h8M8 11h2M12 11h2M8 15h2M12 15h2"/></svg>
    case 'builder':   return <svg {...p}><path d="M3 21V8l9-5 9 5v13"/><path d="M9 21v-7h6v7"/></svg>
    case 'backtest':  return <svg {...p}><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/><path d="M12 7v5l3 2"/></svg>
    case 'monte':     return <svg {...p}><circle cx="6.5" cy="6.5" r="1.2" fill="currentColor"/><circle cx="17.5" cy="6.5" r="1.2" fill="currentColor"/><circle cx="12" cy="12" r="1.2" fill="currentColor"/><circle cx="6.5" cy="17.5" r="1.2" fill="currentColor"/><circle cx="17.5" cy="17.5" r="1.2" fill="currentColor"/><rect x="3" y="3" width="18" height="18" rx="3"/></svg>
    case 'rebalance': return <svg {...p}><path d="M3 7h13l-3-3M21 17H8l3 3"/></svg>
    default:          return null
  }
}

function ChevronDown() {
  return (
    <svg width={11} height={11} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 9l6 6 6-6"/>
    </svg>
  )
}

// ── PageNavTabs — breadcrumb with section + context dropdowns ─────────────────

interface PageNavTabsProps {
  active: string
  contextLabel?: string
  contextChildren?: ReactNode
}

export function PageNavTabs({ active, contextLabel, contextChildren }: PageNavTabsProps) {
  const location = useLocation()
  const [secOpen, setSecOpen] = useState(false)
  const [ctxOpen, setCtxOpen] = useState(false)
  const secRef = useRef<HTMLDivElement>(null)
  const ctxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const s = ALL_SECTIONS.find(s => s.group === 'strategy' && s.href === location.pathname)
    if (s) localStorage.setItem(STRATEGY_NAV_STORAGE_KEY, s.href)
  }, [location.pathname])

  useEffect(() => {
    function onDown(e: MouseEvent) {
      if (!secRef.current?.contains(e.target as Node)) setSecOpen(false)
      if (!ctxRef.current?.contains(e.target as Node)) setCtxOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [])

  function isActiveSection(href: string) {
    const p = location.pathname
    if (href === '/portfolio/') return p === '/portfolio/' || p.startsWith('/portfolio/')
    if (href === '/analyst/')   return p.startsWith('/analyst')
    return p === href
  }

  const activeSec =
    ALL_SECTIONS.find(s => isActiveSection(s.href)) ??
    ALL_SECTIONS.find(s => s.href === active) ??
    ALL_SECTIONS[0]

  return (
    <nav className="page-nav-tabs">
      <Link to="/portfolio/" className="h-logo">
        <img src="/static/favicon.svg" alt="" className="h-logo-icon" />
        <span className="header-brand-name">Portfolio Helper</span>
      </Link>

      <span className="v4-sep">/</span>

      {/* Section dropdown */}
      <div className="v4-crumb-wrap" ref={secRef}>
        <button className="v4-crumb" onClick={() => { setSecOpen(v => !v); setCtxOpen(false) }}>
          <SectionSvg name={activeSec.icon} />
          <span>{activeSec.label}</span>
          <ChevronDown />
        </button>
        {secOpen && (
          <div className="v4-pop">
            <div className="v4-pop-head">PORTFOLIO</div>
            {ALL_SECTIONS.filter(s => !s.group).map(s => (
              <Link key={s.href} to={s.href}
                    className={`v4-pop-item${isActiveSection(s.href) ? ' active' : ''}`}
                    onClick={() => setSecOpen(false)}>
                <SectionSvg name={s.icon} />
                <span>{s.label}</span>
              </Link>
            ))}
            <div className="v4-pop-head">STRATEGY TOOLS</div>
            {ALL_SECTIONS.filter(s => s.group === 'strategy').map(s => (
              <Link key={s.href} to={s.href}
                    className={`v4-pop-item${isActiveSection(s.href) ? ' active' : ''}`}
                    onClick={() => setSecOpen(false)}>
                <SectionSvg name={s.icon} />
                <span>{s.label}</span>
              </Link>
            ))}
          </div>
        )}
      </div>

      {/* Portfolio / page context dropdown */}
      {contextLabel && (
        <>
          <span className="v4-sep">/</span>
          <div className="v4-crumb-wrap" ref={ctxRef}>
            <button className="v4-crumb pf" onClick={() => { setCtxOpen(v => !v); setSecOpen(false) }}>
              <span>{contextLabel}</span>
              <ChevronDown />
            </button>
            {ctxOpen && contextChildren && (
              <div className="v4-pop">
                <div className="v4-pop-head">PORTFOLIOS</div>
                {contextChildren}
              </div>
            )}
          </div>
        </>
      )}
    </nav>
  )
}

// ── Config button ─────────────────────────────────────────────────────────────

export function ConfigButton() {
  const location = useLocation()
  const navigate = useNavigate()
  const isActive = location.pathname === '/config'

  function handleClick() {
    if (isActive) {
      const from = (location.state as { from?: string } | null)?.from
      if (from) navigate(from)
      else navigate(-1)
    } else {
      navigate('/config', { state: { from: location.pathname + location.search } })
    }
  }

  return (
    <button
      type="button"
      className={`h-btn icon-only subtle${isActive ? ' active-edit' : ''}`}
      aria-label="App settings"
      title="App settings"
      onClick={handleClick}
    >
      <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1A1.7 1.7 0 0 0 15 4.6a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>
      </svg>
    </button>
  )
}

// ── Privacy scale toggle ──────────────────────────────────────────────────────

export function PrivacyToggleButton() {
  const appConfig = usePortfolioStore(s => s.appConfig)
  const updateAppConfig = usePortfolioStore(s => s.updateAppConfig)

  if (!appConfig?.privacyScalePct) return null

  const enabled = appConfig.privacyScaleEnabled

  async function handleClick() {
    if (enabled) {
      const ok = await showConfirm('Disable privacy scaling? Real values will become visible.', 'Disable')
      if (!ok) return
    }
    await fetch('/api/config/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ privacyScaleEnabled: enabled ? 'false' : 'true' }),
    })
    updateAppConfig({ privacyScaleEnabled: !enabled })
  }

  return (
    <button
      type="button"
      className={`h-btn icon-only subtle${enabled ? ' active-edit' : ''}`}
      aria-label={enabled ? 'Privacy scaling on' : 'Privacy scaling off'}
      title={enabled ? 'Privacy scaling on — click to disable' : 'Privacy scaling off — click to enable'}
      onClick={handleClick}
    >
      {enabled ? (
        <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
          <path d="M2.5 12S6.5 6 12 6s9.5 6 9.5 6-4 6-9.5 6S2.5 12 2.5 12z"/>
          <circle cx="12" cy="12" r="3"/>
        </svg>
      ) : (
        <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 3l18 18"/>
          <path d="M10.6 6.1A11 11 0 0 1 12 6c5.5 0 9.5 6 9.5 6a17 17 0 0 1-3.2 3.7M6.6 6.6A17 17 0 0 0 2.5 12s4 6 9.5 6a10 10 0 0 0 4.5-1.1"/>
          <path d="M9.5 9.5a3.5 3.5 0 0 0 5 5"/>
        </svg>
      )}
    </button>
  )
}

// ── Theme toggle ──────────────────────────────────────────────────────────────

export function ThemeToggle() {
  const [, tick] = useState(0)
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'

  function toggle() {
    const next = isDark ? 'light' : 'dark'
    document.documentElement.setAttribute('data-theme', next)
    localStorage.setItem('portfolio-helper-theme', next)
    tick(n => n + 1)
  }

  return (
    <button className="h-btn icon-only subtle" id="theme-toggle" type="button" aria-label="Toggle theme" onClick={toggle}>
      {isDark ? (
        <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="4"/>
          <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
        </svg>
      ) : (
        <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>
        </svg>
      )}
    </button>
  )
}

// ── Header right (version chip + utility buttons + action buttons) ────────────

interface HeaderRightProps {
  children: ReactNode
}

export function HeaderRight({ children }: HeaderRightProps) {
  const appConfig = usePortfolioStore(s => s.appConfig)
  const updateAppConfig = usePortfolioStore(s => s.updateAppConfig)
  const [updOpen, setUpdOpen] = useState(false)
  const [isCheckingUpdate, setIsCheckingUpdate] = useState(false)
  const [updateToast, setUpdateToast] = useState({ msg: '', type: '' })
  const updateToastTimer = useRef<number | null>(null)

  const childArray = Children.toArray(children)
  const isUtility = (child: ReturnType<typeof Children.toArray>[number]) =>
    isValidElement(child) &&
    (child.type === PrivacyToggleButton || child.type === ConfigButton || child.type === ThemeToggle)
  const utilityControls = childArray.filter(isUtility)
  const actionControls = childArray.filter(child => !isUtility(child))

  if (!appConfig) {
    return (
      <div className="header-right">
        <div className="header-top-controls">{utilityControls}</div>
        {actionControls.length > 0 && <div className="header-buttons">{actionControls}</div>}
      </div>
    )
  }

  const { version, hasUpdate, latestVersion, downloadPhase, isJpackageInstall, autoUpdate } = appConfig
  const autoDownloads = isJpackageInstall && autoUpdate
  const isDownloading = downloadPhase === 'DOWNLOADING'
  const isReady = downloadPhase === 'READY' || downloadPhase === 'APPLYING'

  const showUpdateTag      = hasUpdate && !autoDownloads && !isDownloading && !isReady
  const showUpdateDot      = hasUpdate && autoDownloads && !isDownloading && !isReady
  const showDownloadingTag = isDownloading
  const showReadyTag       = isReady

  const hasAnyUpdate = showUpdateTag || showUpdateDot || showDownloadingTag || showReadyTag

  function showUpdateToast(msg: string, type: string) {
    setUpdateToast({ msg, type })
    if (updateToastTimer.current) clearTimeout(updateToastTimer.current)
    updateToastTimer.current = window.setTimeout(
      () => setUpdateToast({ msg: '', type: '' }),
      type === 'ok' ? 2500 : 5000
    )
  }

  async function handleCheckUpdate() {
    if (isCheckingUpdate) return
    setUpdOpen(false)
    setIsCheckingUpdate(true)
    showUpdateToast('Checking for updates...', 'ok')
    try {
      const r = await fetch('/api/admin/check-update', { method: 'POST' })
      if (!r.ok) throw new Error(r.statusText || `HTTP ${r.status}`)
      const info = await r.json()
      updateAppConfig({
        hasUpdate:     !!info.hasUpdate,
        latestVersion: info.latestVersion ?? null,
        downloadPhase: info.download?.phase ?? 'IDLE',
        autoUpdate:    info.autoUpdate ?? autoUpdate,
      })
      if (info.lastCheckError) {
        showUpdateToast(`Update check failed: ${info.lastCheckError}`, 'error')
      } else if (info.hasUpdate) {
        showUpdateToast(`Update available: v${info.latestVersion}`, 'warn')
      } else if (!info.hasUpdate) {
        showUpdateToast(`You are up to date on v${info.currentVersion ?? version}.`, 'ok')
      }
    } catch (err: any) {
      showUpdateToast(`Update check failed: ${err?.message || 'Unable to check for updates.'}`, 'error')
    } finally {
      setIsCheckingUpdate(false)
    }
  }

  function handleVersionClick() {
    if (hasAnyUpdate) {
      setUpdOpen(v => !v)
      return
    }
    handleCheckUpdate()
  }

  function handleVersionKeyDown(e: KeyboardEvent<HTMLSpanElement>) {
    if (e.key !== 'Enter' && e.key !== ' ') return
    e.preventDefault()
    handleVersionClick()
  }

  async function handleApplyUpdate() {
    showRestartOverlay()
    try { await fetch('/api/admin/apply-update', { method: 'POST' }) } catch (_) {}
    setTimeout(attemptReconnect, 2000)
  }

  function updPopBody() {
    if (showDownloadingTag) return `Downloading v${latestVersion}…`
    if (showReadyTag)       return `Version ${latestVersion} is ready. Click to apply.`
    return `Version ${latestVersion ?? ''} is available.`
  }

  function updPopTitle() {
    if (showDownloadingTag) return 'Downloading update'
    if (showReadyTag)       return 'Update ready'
    return 'Update available'
  }

  function versionTitle() {
    if (isCheckingUpdate) return 'Checking for updates...'
    if (hasAnyUpdate)     return 'Update available - click for details'
    return 'Check for updates'
  }

  return (
    <div className="header-right">
      <div className="header-top-controls">
        <span
          className={`h-version v4-version-btn${hasAnyUpdate ? ' has-update' : ''}${isCheckingUpdate ? ' is-checking' : ''}`}
          onClick={handleVersionClick}
          onKeyDown={handleVersionKeyDown}
          role="button"
          tabIndex={0}
          aria-label={`Version ${version}. ${versionTitle()}`}
          title={versionTitle()}
        >
          {(hasAnyUpdate || isCheckingUpdate) && <span className="dot" />}
          v{version}
          {updOpen && hasAnyUpdate && (
            <div className="v4-upd-pop" onClick={e => e.stopPropagation()} onMouseLeave={() => setUpdOpen(false)}>
              <div className="v4-upd-head">
                {(hasAnyUpdate || isCheckingUpdate) && <span className="dot" />}
                <span>{updPopTitle()}</span>
              </div>
              <div className="v4-upd-body">{updPopBody()}</div>
              <div className="v4-upd-foot">
                <button className="h-btn subtle" onClick={() => setUpdOpen(false)}>Later</button>
                {showReadyTag
                  ? <button className="h-btn primary" onClick={handleApplyUpdate}>Restart &amp; update</button>
                  : <Link to="/config" className="h-btn primary" onClick={() => setUpdOpen(false)}>Go to Settings</Link>
                }
              </div>
            </div>
          )}
        </span>
        <div className={`config-status config-status-${updateToast.type}${updateToast.msg ? ' visible' : ''}`}>
          {updateToast.msg}
        </div>
        {utilityControls}
      </div>
      {actionControls.length > 0 && <div className="header-buttons">{actionControls}</div>}
    </div>
  )
}
