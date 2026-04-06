// ── ConfigPage.tsx — Port of ConfigRenderer.kt ───────────────────────────────
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { PageNavTabs, ThemeToggle, HeaderRight } from '@/components/Layout'
import { useScripts } from '@/hooks/useScripts'

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
  twsAccount: string
  virtualBalance: boolean
}

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
}: {
  label: string
  description: string
  inputId: string
  badge?: string | null
  children: React.ReactNode
}) {
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

export default function ConfigPage() {
  const [cfg, setCfg] = useState<ConfigValues>({})
  const [portfolios, setPortfolios] = useState<PortfolioRow[]>([])

  useScripts(['/static/config/config.js'])

  useEffect(() => {
    fetch('/api/admin/config-values')
      .then(r => r.json())
      .then((data: ConfigValues) => setCfg(data))
      .catch(() => {})

    fetch('/api/portfolio/data')
      .then(r => r.json())
      .then((data: { allPortfolios?: { slug: string; name: string }[] }) => {
        if (data.allPortfolios) {
          setPortfolios(data.allPortfolios.map(p => ({
            slug: p.slug, name: p.name, twsAccount: '', virtualBalance: false,
          })))
        }
      })
      .catch(() => {})
  }, [])

  const isJpackage = cfg._isJpackageInstall === 'true'
  const hasUpdate = cfg._hasUpdate === 'true'
  const downloadPhase = cfg._downloadPhase ?? 'IDLE'
  const version = cfg._version ?? ''
  const latestVersion = cfg._latestVersion ?? ''
  const releaseUrl = cfg._releaseUrl ?? '#'
  const lastCheckError = cfg._lastCheckError ?? ''

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/config" />
        </div>
        <HeaderRight>
          <ThemeToggle />
        </HeaderRight>
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
              Show this PIN on your screen and enter it in the Android app.<br />
              Expires in 5 minutes.
            </span>
            <div className="pairing-pin-container" id="pairing-pin-display">
              <p className="config-env-override-note">Waiting for requests...</p>
            </div>
          </div>

          <div className="paired-devices-list config-field" id="paired-devices-list">
            <p className="config-env-override-note">Loading devices...</p>
          </div>

          <div className="config-field" id="sessions-list">
            <p className="config-env-override-note">Loading…</p>
          </div>
        </ConfigSection>

        {/* ── Display ─────────────────────────────────────────────────── */}
        <ConfigSection title="Display">
          <ConfigField
            label="P&L and Market Value in display currency"
            description="Convert per-stock P&L and Mkt Val columns to the selected display currency. Default: off (show in stock's native currency)."
            inputId="show-stock-display-currency"
          >
            <input
              type="checkbox"
              id="show-stock-display-currency"
              defaultChecked={cfg.showStockDisplayCurrency === 'true'}
              data-config-key="showStockDisplayCurrency"
            />
          </ConfigField>

          <ConfigField
            label="Privacy Scaling %"
            description="Scale all managed assets (quantities and cash) by this percentage for display purposes. Leave empty to disable."
            inputId="privacy-scale-pct"
          >
            <input
              type="number"
              id="privacy-scale-pct"
              placeholder="None"
              defaultValue={cfg.privacyScalePct ?? ''}
              data-config-key="privacyScalePct"
              min={1} max={999} step={1}
            />
          </ConfigField>

          <ConfigField
            label="After-Hours Style: Gray"
            description="Show after-hours prices and changes as solid gray (default). When off, keeps positive/negative colors but dimmed."
            inputId="after-hours-gray"
          >
            <input
              type="checkbox"
              id="after-hours-gray"
              defaultChecked={cfg.afterHoursGray !== 'false'}
              data-config-key="afterHoursGray"
            />
          </ConfigField>
        </ConfigSection>

        {/* ── Portfolio and IB TWS Settings ───────────────────────────── */}
        <ConfigSection title="Portfolio and IB TWS Settings">
          <table className="portfolio-config-table">
            <thead>
              <tr>
                <th>Portfolio</th>
                <th>TWS Account</th>
                <th>Virtual Balance</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {portfolios.map((p, i) => (
                <tr key={p.slug} data-portfolio-slug={p.slug}>
                  <td>
                    <div className="portfolio-name-cell">
                      <div className="portfolio-name-input-row">
                        <input
                          type="text"
                          className="portfolio-name-input"
                          defaultValue={p.name}
                          data-original-name={p.name}
                          data-slug={p.slug}
                          autoComplete="off"
                          maxLength={64}
                        />
                        <button
                          type="button"
                          className="portfolio-rename-confirm-btn"
                          data-slug={p.slug}
                          hidden
                          title="Apply rename"
                        >
                          ✓
                        </button>
                      </div>
                      <span className="portfolio-rename-error" hidden />
                    </div>
                  </td>
                  <td>
                    <input
                      type="text"
                      placeholder="e.g. U1234567"
                      defaultValue={p.twsAccount}
                      data-config-key="twsAccount"
                      data-portfolio-id={p.slug}
                      autoComplete="off"
                    />
                  </td>
                  <td className="portfolio-config-table-checkbox-col">
                    <input
                      type="checkbox"
                      defaultChecked={p.virtualBalance}
                      data-config-key="virtualBalance"
                      data-portfolio-id={p.slug}
                    />
                  </td>
                  <td className="portfolio-config-table-actions-col">
                    <div>
                      <button
                        type="button"
                        className="management-table-remove-btn portfolio-remove-btn"
                        data-slug={p.slug}
                        disabled={i === 0}
                        style={i === 0 ? { visibility: 'hidden' } : undefined}
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
            <input type="text" id="new-portfolio-name" placeholder="New portfolio name" maxLength={64} />
            <button type="button" className="config-restore-btn" id="add-portfolio-btn">Add Portfolio</button>
            <span className="config-env-override-note" id="add-portfolio-status" />
          </div>

          <ConfigField label="TWS Host" description="Hostname or IP address of the TWS / IB Gateway." inputId="tws-host">
            <input
              type="text"
              id="tws-host"
              placeholder="127.0.0.1"
              defaultValue={cfg.twsHost ?? ''}
              data-config-key="twsHost"
            />
          </ConfigField>

          <ConfigField
            label="TWS Port"
            description="Port of the TWS / IB Gateway. Default: 7496 (live), 7497 (paper), 4001 (IB Gateway live)."
            inputId="tws-port"
          >
            <input
              type="number"
              id="tws-port"
              placeholder="7496"
              defaultValue={cfg.twsPort ?? ''}
              data-config-key="twsPort"
              min={1} max={65535}
            />
          </ConfigField>
        </ConfigSection>

        {/* ── Server ──────────────────────────────────────────────────── */}
        <ConfigSection title="Server">
          <ConfigField
            label="Open Browser on Start"
            description="Automatically open the browser when the app starts."
            inputId="open-browser"
            badge="next-launch"
          >
            <input
              type="checkbox"
              id="open-browser"
              defaultChecked={cfg.openBrowser !== 'false'}
              data-config-key="openBrowser"
            />
          </ConfigField>
        </ConfigSection>

        {/* ── Updates & Restart ────────────────────────────────────────── */}
        <ConfigSection title="Updates &amp; Restart">
          <ReadOnlyField
            label="Current Version"
            description="The version of Portfolio Helper currently running."
            value={version ? `v${version}` : '—'}
          />

          <div className="config-field">
            <div className="config-field-label-row">
              <span>Latest Version</span>
              <span
                className="config-badge config-badge-update"
                id="latest-version-badge"
                hidden={!hasUpdate}
              >
                update available
              </span>
            </div>
            <span className="config-field-description">Latest release from GitHub.</span>
            <div className="config-field-input-col" id="latest-version-value">
              {latestVersion ? (
                <a href={releaseUrl} target="_blank" rel="noopener">v{latestVersion}</a>
              ) : lastCheckError ? (
                <span className="config-env-override-note">Check failed: {lastCheckError}</span>
              ) : (
                <span className="config-env-override-note">Not checked yet</span>
              )}
            </div>
          </div>

          <div className="update-progress-row" id="update-progress-row">
            <div className="config-field-label-row"><span>Download Progress</span></div>
            <div className="update-progress-bar-container">
              <div className="update-progress-bar" id="update-progress-bar" />
            </div>
            <span className="config-field-description" id="update-progress-label">0 / 0 MB</span>
          </div>

          {!isJpackage && (
            <div className="config-field">
              <span className="config-env-override-note">
                Running as portable JAR — download updates manually from GitHub.
              </span>
            </div>
          )}

          <ConfigField
            label="Update Check Interval (seconds)"
            description="How often to check GitHub for a new release. Default: 86400 (24 hours). Minimum: 60."
            inputId="update-check-interval"
          >
            <input
              type="number"
              id="update-check-interval"
              placeholder="86400"
              defaultValue={cfg.updateCheckInterval ?? ''}
              data-config-key="updateCheckInterval"
              min={60}
            />
          </ConfigField>

          {isJpackage && (
            <ConfigField
              label="Auto Update"
              description="Automatically download updates in the background when a new version is found. Requires manual apply & restart."
              inputId="auto-update"
            >
              <input
                type="checkbox"
                id="auto-update"
                defaultChecked={cfg.autoUpdate !== 'false'}
                data-config-key="autoUpdate"
              />
            </ConfigField>
          )}

          <div className="update-action-buttons">
            <button type="button" className="config-restore-btn" id="check-update-btn">Check for Updates</button>
            {isJpackage && (
              <>
                <button
                  type="button"
                  className="config-restore-btn"
                  id="download-update-btn"
                  disabled={!hasUpdate || downloadPhase !== 'IDLE'}
                >
                  Download Update
                </button>
                <button
                  type="button"
                  className="config-save-btn"
                  id="apply-update-btn"
                  hidden={downloadPhase !== 'READY'}
                >
                  Apply Update &amp; Restart
                </button>
              </>
            )}
            <button type="button" className="config-restore-btn" id="restart-btn">Restart App</button>
          </div>

          <div className="config-status" id="update-status" />
        </ConfigSection>

        {/* ── Market Data ──────────────────────────────────────────────── */}
        <ConfigSection title="Market Data">
          <ConfigField
            label="Exchange Suffixes"
            description="Comma-separated EXCHANGE=.SUFFIX mappings for TWS snapshot symbol resolution (e.g. SBF=.PA,LSEETF=.L)."
            inputId="exchange-suffixes"
            badge="live"
          >
            <input
              type="text"
              id="exchange-suffixes"
              placeholder="SBF=.PA,LSEETF=.L"
              defaultValue={cfg.exchangeSuffixes ?? ''}
              data-config-key="exchangeSuffixes"
            />
          </ConfigField>

          <ConfigField
            label="GitHub Repository"
            description="GitHub repo for update checks (owner/repo format)."
            inputId="github-repo"
          >
            <input
              type="text"
              id="github-repo"
              placeholder="cyfung/portfolio-helper"
              defaultValue={cfg.githubRepo ?? ''}
              data-config-key="githubRepo"
            />
          </ConfigField>

          <ConfigField
            label="NAV Update Interval (seconds)"
            description="How often to fetch NAV data. Leave blank to use the trading-day schedule."
            inputId="nav-update-interval"
            badge="restart"
          >
            <input
              type="number"
              id="nav-update-interval"
              placeholder="trading-day schedule"
              defaultValue={cfg.navUpdateInterval ?? ''}
              data-config-key="navUpdateInterval"
              min={10}
            />
          </ConfigField>

          <ConfigField
            label="IBKR Margin Rate Interval (seconds)"
            description="How often to refresh IB margin rates. Default: 3600 (1 hour). Takes effect on next fetch cycle."
            inputId="ibkr-rate-interval"
          >
            <input
              type="number"
              id="ibkr-rate-interval"
              placeholder="3600"
              defaultValue={cfg.ibkrRateInterval ?? ''}
              data-config-key="ibkrRateInterval"
              min={60}
            />
          </ConfigField>

          <ConfigField
            label="Dividend Safe Lag Days"
            description="Days before today to use as the safe end date for dividend calculations (avoids unreported recent events). Default: 5."
            inputId="dividend-safe-lag-days"
          >
            <input
              type="number"
              id="dividend-safe-lag-days"
              placeholder="5"
              defaultValue={cfg.dividendSafeLagDays ?? ''}
              data-config-key="dividendSafeLagDays"
              min={0}
            />
          </ConfigField>
        </ConfigSection>

        {/* ── Actions ──────────────────────────────────────────────────── */}
        <div className="config-actions">
          <button type="button" className="config-restore-btn" id="config-restore-btn">
            Restore Defaults
          </button>
          <div className="config-status" id="config-status" />
        </div>
      </main>
    </div>
  )
}
