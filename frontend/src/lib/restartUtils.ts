export function showRestartOverlay() {
  const el = document.createElement('div')
  el.className = 'restart-overlay'
  el.innerHTML = `<div class="restart-dialog"><span class="restart-spinner"></span><span class="restart-dialog-text">Restarting…</span></div>`
  document.body.appendChild(el)
}

export function attemptReconnect() {
  fetch('/').then(r => { if (r.ok) location.reload(); else setTimeout(attemptReconnect, 1000) }).catch(() => setTimeout(attemptReconnect, 1000))
}
