import { createContext, useContext } from 'react'

export interface MarginWheelAdjustContextValue {
  enabled: boolean
  unlock: () => void
}

export const MarginWheelAdjustContext = createContext<MarginWheelAdjustContextValue>({
  enabled: true,
  unlock: () => {},
})

export function useMarginWheelAdjustEnabled() {
  return useContext(MarginWheelAdjustContext).enabled
}

export function useUnlockMarginWheelAdjust() {
  return useContext(MarginWheelAdjustContext).unlock
}
