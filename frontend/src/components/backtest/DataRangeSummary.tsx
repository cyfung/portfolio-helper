import type { AnalysisDataRange } from '@/types/backtest'

interface Props {
  dataRange?: AnalysisDataRange | null
}

interface CalendarDuration {
  years: number
  months: number
  days: number
}

function parseIsoDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day))
}

function daysInUtcMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
}

function addUtcMonths(date: Date, months: number): Date {
  const monthIndex = date.getUTCMonth() + months
  const year = date.getUTCFullYear() + Math.floor(monthIndex / 12)
  const month = ((monthIndex % 12) + 12) % 12
  const day = Math.min(date.getUTCDate(), daysInUtcMonth(year, month))
  return new Date(Date.UTC(year, month, day))
}

function calendarDuration(fromDate: string, toDate: string): CalendarDuration {
  const from = parseIsoDate(fromDate)
  const to = parseIsoDate(toDate)
  let years = to.getUTCFullYear() - from.getUTCFullYear()
  let cursor = addUtcMonths(from, years * 12)
  if (cursor > to) {
    years--
    cursor = addUtcMonths(from, years * 12)
  }

  let months = (to.getUTCFullYear() - cursor.getUTCFullYear()) * 12
    + to.getUTCMonth() - cursor.getUTCMonth()
  let monthCursor = addUtcMonths(cursor, months)
  if (monthCursor > to) {
    months--
    monthCursor = addUtcMonths(cursor, months)
  }

  const days = Math.round((to.getTime() - monthCursor.getTime()) / 86_400_000)
  return { years, months, days }
}

function formatDurationUnit(value: number, singular: string): string {
  return `${value} ${singular}${value === 1 ? '' : 's'}`
}

function limiterList(identifiers: string[]): string {
  const sorted = [...new Set(identifiers)].sort((a, b) => a.localeCompare(b))
  const shown = sorted.slice(0, 3)
  const remaining = sorted.length - shown.length
  if (remaining === 0) return shown.join(', ')
  return `${shown.join(', ')} and ${remaining} ${remaining === 1 ? 'other' : 'others'}`
}

export default function DataRangeSummary({ dataRange }: Props) {
  if (!dataRange) return null
  const duration = calendarDuration(dataRange.fromDate, dataRange.toDate)
  const parts = [
    `Data used: ${dataRange.fromDate} to ${dataRange.toDate}`,
    [
      formatDurationUnit(duration.years, 'year'),
      formatDurationUnit(duration.months, 'month'),
      formatDurationUnit(duration.days, 'day'),
    ].join(', '),
  ]
  if (dataRange.startLimiters.length > 0) {
    parts.push(`Start limited by: ${limiterList(dataRange.startLimiters)}`)
  }
  if (dataRange.endLimiters.length > 0) {
    parts.push(`End limited by: ${limiterList(dataRange.endLimiters)}`)
  }

  return (
    <div className="analysis-data-range-summary">
      {parts.join(' · ')}
    </div>
  )
}
