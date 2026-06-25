import { useCallback, useEffect, useRef, useState } from 'react'

export type ToastType = 'ok' | 'warn' | 'error' | ''

export interface TransientToast {
  msg: string
  type: ToastType
}

export function useTransientToast(defaultDurationMs = 2500) {
  const [toast, setToast] = useState<TransientToast>({ msg: '', type: '' })
  const timerRef = useRef<number | null>(null)

  const clearToast = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = null
    setToast({ msg: '', type: '' })
  }, [])

  const showToast = useCallback((msg: string, type: ToastType = 'ok', durationMs = defaultDurationMs) => {
    if (timerRef.current) clearTimeout(timerRef.current)
    setToast({ msg, type })
    timerRef.current = window.setTimeout(() => {
      setToast({ msg: '', type: '' })
      timerRef.current = null
    }, durationMs)
  }, [defaultDurationMs])

  useEffect(() => clearToast, [clearToast])

  return { toast, showToast, clearToast }
}
