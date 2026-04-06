// ── useSSE.ts — Port of sse.js ────────────────────────────────────────────────
import { useEffect, useRef } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'

const SSE_URL = '/api/prices/stream'
const DISCONNECT_RELOAD_MS = 5 * 60 * 1000  // 5 minutes

export function useSSE() {
  const {
    setFxRates, setStockDisplay, setCashDisplay,
    setPortfolioTotals, setIbkrData, setAllocData, setSseStatus,
  } = usePortfolioStore()

  const esRef = useRef<EventSource | null>(null)
  const lastActivityRef = useRef(Date.now())
  const hadErrorRef = useRef(false)

  useEffect(() => {
    const es = new EventSource(SSE_URL)
    esRef.current = es

    es.onopen = () => {
      lastActivityRef.current = Date.now()
      if (hadErrorRef.current) {
        // Reconnected after error — reload to get fresh data
        window.location.reload()
        return
      }
      setSseStatus('live')
    }

    es.onmessage = (event) => {
      lastActivityRef.current = Date.now()
      try {
        if (event.data === 'heartbeat') return
        const data = JSON.parse(event.data)

        switch (data.type) {
          case 'reload':
            window.location.reload()
            break
          case 'fx-rates':
            setFxRates(data.rates)
            break
          case 'stock-display':
            setStockDisplay(data)
            break
          case 'cash-display':
            setCashDisplay(data)
            break
          case 'portfolio-totals':
            setPortfolioTotals(data)
            break
          case 'ibkr-display':
            setIbkrData(data)
            break
          case 'rebal-alloc':
            setAllocData(data)
            break
        }
      } catch (e) {
        console.error('Failed to parse SSE data:', e)
      }
    }

    es.onerror = () => {
      hadErrorRef.current = true
      setSseStatus('error')
    }

    // Reload if SSE broken for 5 minutes
    const watchdog = setInterval(() => {
      if (es.readyState !== EventSource.OPEN && Date.now() - lastActivityRef.current > DISCONNECT_RELOAD_MS) {
        window.location.reload()
      }
    }, 60_000)

    const close = () => es.close()
    window.addEventListener('pagehide', close)
    window.addEventListener('beforeunload', close)

    return () => {
      clearInterval(watchdog)
      es.close()
      esRef.current = null
      window.removeEventListener('pagehide', close)
      window.removeEventListener('beforeunload', close)
    }
  }, [setFxRates, setStockDisplay, setCashDisplay, setPortfolioTotals, setIbkrData, setAllocData, setSseStatus])
}
