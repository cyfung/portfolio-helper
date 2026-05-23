import fs from 'node:fs/promises'
import path from 'node:path'

const OFFICIALDATA_URL = 'https://www.officialdata.org/us-economy/shiller-pe'
const OUT_PATH = path.resolve('public/data/us-cape-history.csv')

function monthStartDate(year, month) {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-01`
}

function csvEscape(value) {
  const s = String(value ?? '')
  if (!/[",\n\r]/.test(s)) return s
  return `"${s.replaceAll('"', '""')}"`
}

function stripTags(value) {
  return value.replace(/<[^>]+>/g, '').trim()
}

async function main() {
  const response = await fetch(OFFICIALDATA_URL)
  if (!response.ok) throw new Error(`Failed to fetch US CAPE page: HTTP ${response.status}`)

  const html = await response.text()
  const monthlyHeading = 'Shiller PE Table, 1871-2026 (Monthly)'
  const start = html.indexOf(monthlyHeading)
  if (start < 0) throw new Error('Could not find monthly Shiller PE table')

  const section = html.slice(start)
  const tableStart = section.indexOf('<tbody>')
  const tableEnd = section.indexOf('</tbody>', tableStart)
  if (tableStart < 0 || tableEnd < 0) throw new Error('Could not isolate monthly Shiller PE table body')

  const tbody = section.slice(tableStart, tableEnd)
  const rows = [...tbody.matchAll(/<tr>(.*?)<\/tr>/g)]
    .map(match => [...match[1].matchAll(/<td>(.*?)<\/td>/g)].map(cell => stripTags(cell[1])))
    .map(cols => ({
      year: Number(cols[0]),
      month: Number(cols[1]),
      cape: Number(cols[2]),
      change: Number(cols[3]),
      monthOverMonthPct: Number(cols[4]),
    }))
    .filter(row => Number.isFinite(row.year) && Number.isFinite(row.month) && Number.isFinite(row.cape) && row.cape > 0)
    .map(row => ({
      date: monthStartDate(row.year, row.month),
      cape: row.cape,
      change: Number.isFinite(row.change) ? row.change : '',
      monthOverMonthPct: Number.isFinite(row.monthOverMonthPct) ? row.monthOverMonthPct : '',
      sourceMethod: 'OFFICIALDATA_SHILLER_MONTHLY',
      sourceNote: 'Monthly Shiller PE/CAPE table from OfficialData, based on Robert Shiller and S&P 500 data.',
    }))
    .sort((a, b) => a.date.localeCompare(b.date))

  if (!rows.length) throw new Error('No US CAPE rows parsed')

  const lines = [
    'date,us_cape,change,month_over_month_pct,source_method,source_note',
    ...rows.map(row => [
      row.date,
      row.cape.toFixed(2),
      row.change === '' ? '' : row.change.toFixed(2),
      row.monthOverMonthPct === '' ? '' : row.monthOverMonthPct.toFixed(2),
      row.sourceMethod,
      csvEscape(row.sourceNote),
    ].join(',')),
  ]

  await fs.mkdir(path.dirname(OUT_PATH), { recursive: true })
  await fs.writeFile(OUT_PATH, `${lines.join('\n')}\n`, 'utf8')
  console.log(`Wrote ${OUT_PATH} with ${rows.length} rows`)
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
