// ── IbkrConfigDialog.tsx — Per-portfolio IB Flex Query config overlay ─────────
import { useEffect, useRef, useState } from 'react'

interface Props {
  portfolioSlug: string
  onClose: () => void
}

interface IbkrConfig {
  token: string
  queryId: string
  twsAccount: string
}

export default function IbkrConfigDialog({ portfolioSlug, onClose }: Props) {
  const [cfg, setCfg]       = useState<IbkrConfig>({ token: '', queryId: '', twsAccount: '' })
  const [saving, setSaving] = useState(false)
  const [error, setError]   = useState('')
  const [saved, setSaved]   = useState(false)
  const tokenRef            = useRef<HTMLInputElement>(null)

  useEffect(() => {
    fetch(`/api/portfolio/${portfolioSlug}/ibkr-config`)
      .then(r => r.json())
      .then((d: IbkrConfig) => setCfg(d))
      .catch(() => {})
    tokenRef.current?.focus()
  }, [portfolioSlug])

  function handleKey(e: React.KeyboardEvent) {
    if (e.key === 'Escape') onClose()
  }

  async function handleSave() {
    if (!cfg.token.trim() || !cfg.queryId.trim()) {
      setError('Token and Query ID are required.')
      return
    }
    setSaving(true)
    setError('')
    try {
      const r = await fetch(`/api/portfolio/${portfolioSlug}/ibkr-config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(cfg),
      })
      if (!r.ok) {
        const d = await r.json().catch(() => ({}))
        throw new Error((d as any).error ?? `HTTP ${r.status}`)
      }
      setSaved(true)
      setTimeout(onClose, 800)
    } catch (e: any) {
      setError(e.message ?? 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="backup-modal-overlay" onClick={onClose} onKeyDown={handleKey} tabIndex={-1}>
      <div className="backup-modal" onClick={e => e.stopPropagation()} style={{ maxWidth: 420 }}>
        <div className="backup-modal-header">
          <span className="backup-modal-title">IB Flex Query Config</span>
          <button className="backup-modal-close" onClick={onClose}>✕</button>
        </div>

        <div style={{ padding: '1rem', display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem', fontSize: '0.85rem' }}>
            <span>TWS Account</span>
            <input
              ref={tokenRef}
              type="text"
              className="backtest-config-btn"
              style={{ width: '100%', boxSizing: 'border-box', fontFamily: 'monospace' }}
              value={cfg.twsAccount}
              onChange={e => setCfg(c => ({ ...c, twsAccount: e.target.value }))}
              placeholder="e.g. U1234567"
              autoComplete="off"
            />
          </label>

          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem', fontSize: '0.85rem' }}>
            <span>Flex Query Token</span>
            <input
              type="password"
              className="backtest-config-btn"
              style={{ width: '100%', boxSizing: 'border-box', fontFamily: 'monospace' }}
              value={cfg.token}
              onChange={e => setCfg(c => ({ ...c, token: e.target.value }))}
              placeholder="IBKR Flex Query token"
              autoComplete="off"
            />
          </label>

          <label style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem', fontSize: '0.85rem' }}>
            <span>Query ID</span>
            <input
              type="text"
              className="backtest-config-btn"
              style={{ width: '100%', boxSizing: 'border-box', fontFamily: 'monospace' }}
              value={cfg.queryId}
              onChange={e => setCfg(c => ({ ...c, queryId: e.target.value }))}
              placeholder="e.g. 123456"
              autoComplete="off"
            />
          </label>

          {error && <div style={{ color: 'var(--color-error, #e05c5c)', fontSize: '0.82rem' }}>{error}</div>}

          <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end', marginTop: '0.25rem' }}>
            <button className="backtest-config-btn" onClick={onClose}>Cancel</button>
            <button
              className="run-backtest-btn"
              style={{ padding: '0.35rem 1rem', fontSize: '0.85rem' }}
              onClick={handleSave}
              disabled={saving}
            >
              {saved ? '✓ Saved' : saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
