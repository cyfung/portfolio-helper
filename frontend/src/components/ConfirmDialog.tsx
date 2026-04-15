// ── ConfirmDialog.tsx — Custom confirm overlay (replaces window.confirm) ──────
// CSS lives in index.css under "Custom Confirm Overlay"

import { useEffect } from 'react'
import { createRoot } from 'react-dom/client'

interface Props {
  message: string
  confirmText: string
  onResult: (result: boolean) => void
}

function ConfirmDialog({ message, confirmText, onResult }: Props) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onResult(false)
      if (e.key === 'Enter')  onResult(true)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onResult])

  return (
    <div
      id="confirm-overlay-backdrop"
      onClick={e => { if (e.target === e.currentTarget) onResult(false) }}
    >
      <div id="confirm-overlay-box">
        <p id="confirm-overlay-message">{message}</p>
        <div id="confirm-overlay-actions">
          <button className="confirm-overlay-btn" id="confirm-overlay-cancel" onClick={() => onResult(false)}>Cancel</button>
          <button className="confirm-overlay-btn" id="confirm-overlay-ok" onClick={() => onResult(true)}>{confirmText}</button>
        </div>
      </div>
    </div>
  )
}

export function showConfirm(message: string, confirmText = 'Confirm'): Promise<boolean> {
  return new Promise(resolve => {
    const el = document.createElement('div')
    document.body.appendChild(el)
    const root = createRoot(el)
    function cleanup(result: boolean) {
      root.unmount()
      el.remove()
      resolve(result)
    }
    root.render(<ConfirmDialog message={message} confirmText={confirmText} onResult={cleanup} />)
  })
}
