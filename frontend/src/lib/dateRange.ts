export const DATE_RANGE_ERROR_MESSAGE = 'From date must be on or before to date.'

function todayIsoDate(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function parseIsoDate(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) return null
  return date.getTime()
}

export function validateDateRange(fromDate: string, toDate: string): string {
  if (!fromDate) return ''
  const fromTime = parseIsoDate(fromDate)
  const toTime = parseIsoDate(toDate || todayIsoDate())
  if (fromTime == null || toTime == null) return ''
  return fromTime > toTime ? DATE_RANGE_ERROR_MESSAGE : ''
}
