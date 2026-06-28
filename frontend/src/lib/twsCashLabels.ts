export const TWS_CASH_LABEL = {
  CASH: 'Cash',
  MTD_INTEREST: 'MTD Interest',
  PENDING_DIVIDEND: 'Pending Dividend',
} as const

export const TWS_MANAGED_CASH_LABELS = Object.values(TWS_CASH_LABEL)

export function isTwsManagedCashLabel(label: string): boolean {
  const normalized = label.trim().toLowerCase()
  return TWS_MANAGED_CASH_LABELS.some(managed => managed.toLowerCase() === normalized)
}
