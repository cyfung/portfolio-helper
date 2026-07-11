import { createContext, useContext } from 'react'

export const MarginWheelAdjustContext = createContext(true)

export function useMarginWheelAdjustEnabled() {
  return useContext(MarginWheelAdjustContext)
}
