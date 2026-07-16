import { useCallback, useEffect, useRef, useState } from 'react'

export type ToastType = 'ok' | 'warn' | 'error' | ''

export interface TransientToast {
  msg: string
  type: ToastType
}

type ToastDurationMs = number | null

export function durationForToastType(type: ToastType, okDurationMs = 2500, otherDurationMs = 5000) {
  return type === 'ok' ? okDurationMs : otherDurationMs
}

export function useTransientToast(defaultDurationMs: ToastDurationMs = 2500) {
  const [toast, setToast] = useState<TransientToast>({ msg: '', type: '' })
  const timerRef = useRef<number | null>(null)

  const clearToast = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = null
    setToast({ msg: '', type: '' })
  }, [])

  const showToast = useCallback((msg: string, type: ToastType = 'ok', durationMs: ToastDurationMs = defaultDurationMs) => {
    if (timerRef.current) clearTimeout(timerRef.current)
    setToast({ msg, type })
    timerRef.current = null
    if (durationMs !== null) {
      timerRef.current = window.setTimeout(() => {
        setToast({ msg: '', type: '' })
        timerRef.current = null
      }, durationMs)
    }
  }, [defaultDurationMs])

  useEffect(() => clearToast, [clearToast])

  return { toast, showToast, clearToast }
}
