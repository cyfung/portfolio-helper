// ── Layout.tsx — Shared header utilities (nav tabs, config button, theme toggle)
// Each page renders its own .portfolio-header div to match the Kotlin renderers exactly.
// This file exports helpers used by all pages.

import { useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { showConfirm } from '@/components/ConfirmDialog'
import { showRestartOverlay, attemptReconnect } from '@/lib/restartUtils'

// ── Page nav tabs (same as renderPageNavTabs in common.kt) ───────────────────

const STRATEGY_NAV_STORAGE_KEY = 'portfolio-helper-active-strategy-page'

const STRATEGY_PAGES = [
  { line1: 'Portfolio', line2: 'Backtest', href: '/backtest' },
  { line1: 'Monte Carlo', line2: 'Simulation', href: '/montecarlo' },
  { line1: 'Rebalance', line2: 'Strategy', href: '/rebalance-strategy' },
]

const NAV_PAGES = [
  { line1: 'Portfolio', line2: 'Viewer',   href: '/portfolio/' },
  { line1: 'Portfolio', line2: 'Analyst',  href: '/analyst/' },
  { line1: 'Loan',      line2: 'Calculator', href: '/loan' },
  { line1: 'Strategy', line2: 'Tools', href: '/backtest', children: STRATEGY_PAGES },
]

export function PageNavTabs({ active }: { active: string }) {
  const location = useLocation()

  useEffect(() => {
    const activeStrategyPage = STRATEGY_PAGES.find(page => page.href === location.pathname)
    if (!activeStrategyPage) return
    localStorage.setItem(STRATEGY_NAV_STORAGE_KEY, activeStrategyPage.href)
  }, [location.pathname])

  function getStrategyHref() {
    const storedHref = localStorage.getItem(STRATEGY_NAV_STORAGE_KEY)
    if (STRATEGY_PAGES.some(page => page.href === storedHref)) return storedHref
    return STRATEGY_PAGES[0].href
  }

  return (
    <div className="page-nav-tabs">
      {NAV_PAGES.map(page => {
        const isStrategyGroup = page.children?.some(child => child.href === active || child.href === location.pathname)
        const href = page.children ? getStrategyHref() : page.href
        const isActive = isStrategyGroup || page.href === active ||
          (page.href === '/portfolio/' && location.pathname.startsWith('/portfolio')) ||
          (page.href === '/analyst/' && location.pathname.startsWith('/analyst'))
        return (
          <div
            key={page.href}
            className={`page-nav-tab-wrapper${page.children ? ' has-subnav' : ''}`}
          >
            <Link
              to={href}
              className={`page-nav-tab${isActive ? ' active' : ''}`}
            >
              <span className="page-nav-tab-line1">{page.line1}</span>
              <span className="page-nav-tab-line2">{page.line2}</span>
            </Link>
            {page.children && (
              <div className="page-nav-submenu">
                {page.children.map(child => {
                  const isChildActive = child.href === active || child.href === location.pathname
                  return (
                    <Link
                      key={child.href}
                      to={child.href}
                      className={`page-nav-submenu-item${isChildActive ? ' active' : ''}`}
                    >
                      <span>{child.line1}</span>
                      <span>{child.line2}</span>
                    </Link>
                  )
                })}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ── Config gear button ────────────────────────────────────────────────────────

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
      className={`config-button${isActive ? ' active' : ''}`}
      aria-label="App settings"
      title="App settings"
      onClick={handleClick}
    >
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
      </svg>
    </button>
  )
}

// ── Privacy scale toggle button ───────────────────────────────────────────────

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
      className={`privacy-toggle${enabled ? ' active' : ''}`}
      aria-label={enabled ? 'Privacy scaling on' : 'Privacy scaling off'}
      title={enabled ? 'Privacy scaling on — click to disable' : 'Privacy scaling off — click to enable'}
      onClick={handleClick}
    >
      {enabled ? (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
          <circle cx="12" cy="12" r="3"/>
        </svg>
      ) : (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>
          <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>
          <line x1="1" y1="1" x2="23" y2="23"/>
        </svg>
      )}
    </button>
  )
}

// ── Theme toggle button ───────────────────────────────────────────────────────

export function ThemeToggle() {
  function toggle() {
    const current = document.documentElement.getAttribute('data-theme')
    const next = current === 'dark' ? 'light' : 'dark'
    document.documentElement.setAttribute('data-theme', next)
    localStorage.setItem('portfolio-helper-theme', next)
  }

  return (
    <button className="theme-toggle" id="theme-toggle" type="button" aria-label="Toggle theme" onClick={toggle}>
      <span className="icon-sun">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="5"/>
          <line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/>
          <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
          <line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/>
          <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
        </svg>
      </span>
      <span className="icon-moon">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
        </svg>
      </span>
    </button>
  )
}

// ── Header right (version badge + update indicators + buttons) ───────────────

interface HeaderRightProps {
  children: React.ReactNode
}

export function HeaderRight({ children }: HeaderRightProps) {
  const appConfig = usePortfolioStore(s => s.appConfig)

  if (!appConfig) {
    return (
      <div className="header-right">
        <div className="header-buttons">{children}</div>
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

  async function handleApplyUpdate() {
    showRestartOverlay()
    try {
      await fetch('/api/admin/apply-update', { method: 'POST' })
    } catch (_) {}
    setTimeout(attemptReconnect, 2000)
  }

  return (
    <div className="header-right">
      <div className="version-badge-wrapper">
        <span className="version-badge">v{version}</span>
        <Link
          to="/config"
          className="update-available-tag"
          id="header-update-available"
          title={latestVersion ? `Update available: v${latestVersion} — go to Settings` : 'Update available — go to Settings'}
          hidden={!showUpdateTag}
        >
          Update Available
        </Link>
        <span
          className="update-dot"
          id="header-update-dot"
          title={latestVersion ? `Update available: v${latestVersion}` : 'Update available'}
          hidden={!showUpdateDot}
        />
        <span
          className="update-downloading-tag"
          id="header-update-downloading"
          title={latestVersion ? `Downloading v${latestVersion}…` : 'Downloading update…'}
          hidden={!showDownloadingTag}
        >
          Downloading…
        </span>
        <span
          className="update-ready-tag"
          id="header-update-ready"
          title={`Update v${latestVersion} ready — click to apply`}
          hidden={!showReadyTag}
          onClick={handleApplyUpdate}
          style={{ cursor: 'pointer' }}
        >
          Update Is Ready
        </span>
      </div>
      <div className="header-buttons">{children}</div>
    </div>
  )
}
