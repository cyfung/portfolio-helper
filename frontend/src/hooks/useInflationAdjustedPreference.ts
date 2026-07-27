import { useEffect, useState } from 'react'

const STORAGE_KEY = 'portfolio-helper-inflation-adjusted'
const EVENT_NAME = 'portfolio-helper-inflation-adjusted-change'

function readPreference() {
  try { return localStorage.getItem(STORAGE_KEY) === 'true' } catch { return false }
}

export function useInflationAdjustedPreference() {
  const [inflationAdjusted, setValue] = useState(readPreference)

  useEffect(() => {
    const sync = () => setValue(readPreference())
    window.addEventListener(EVENT_NAME, sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener(EVENT_NAME, sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  const setInflationAdjusted = (value: boolean) => {
    try { localStorage.setItem(STORAGE_KEY, String(value)) } catch {}
    setValue(value)
    window.dispatchEvent(new Event(EVENT_NAME))
  }

  return { inflationAdjusted, setInflationAdjusted }
}
