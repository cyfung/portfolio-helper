import fs from 'node:fs/promises'
import path from 'node:path'

const SHILLER_CSV_URL = 'https://raw.githubusercontent.com/datasets/s-and-p-500/main/data/data.csv'
const OUT_PATH = path.resolve('public/data/world-cape-history.csv')

const SYNTHETIC_ANCHORS = [
  {
    date: '1988-03-31',
    targetCape: null,
    note: 'Start of modern global proxy window; raw synthetic value is preserved.',
  },
  {
    date: '2000-03-31',
    targetCape: 34.0,
    note: 'Dot-com peak sanity anchor; secondary references commonly place global CAPE around 33-35.',
  },
  {
    date: '2007-09-30',
    targetCape: 25.0,
    note: 'Nearest quarter-end anchor for the 2007 pre-GFC global CAPE estimate around 25.',
  },
  {
    date: '2008-12-31',
    targetCape: 13.0,
    note: 'GFC trough neighborhood anchor; secondary references place global CAPE in the low teens.',
  },
  {
    date: '2009-03-31',
    targetCape: 13.0,
    note: 'Early-2009 trough anchor; keeps the quarterly low near the cited global CAPE around 13.',
  },
  {
    date: '2019-12-31',
    targetCape: 25.0,
    note: 'Pre-2020 splice anchor, kept close to the raw synthetic value and Siblis 2020 level.',
  },
]

const SIBLIS_ANCHORS = [
  { date: '2020-12-31', price: '646.27', cape: 24.35 },
  { date: '2021-06-30', price: '719.97', cape: 26.35 },
  { date: '2021-12-31', price: '754.83', cape: 26.40 },
  { date: '2022-06-30', price: '596.77', cape: 19.68 },
  { date: '2022-12-31', price: '605.38', cape: 19.24 },
  { date: '2023-06-30', price: '682.84', cape: 21.08 },
  { date: '2023-12-31', price: '727.00', cape: 21.91 },
  { date: '2024-03-31', price: '783.58', cape: 23.28 },
  { date: '2024-06-30', price: '802.01', cape: 23.56 },
  { date: '2024-09-30', price: '851.78', cape: 24.84 },
  { date: '2024-12-31', price: '841.33', cape: 24.25 },
  { date: '2025-03-31', price: '827.16', cape: 23.49 },
  { date: '2025-06-30', price: '917.89', cape: 25.84 },
  { date: '2025-09-30', price: '984.78', cape: 27.28 },
  { date: '2025-12-31', price: '1014.62', cape: 27.71 },
]

const RA_CURRENT_REFERENCE = {
  date: '2026-05-23',
  cape: 29.4,
  historicalMedian: 23,
  note: 'User-supplied Research Affiliates AAI Global Total current snapshot; included as a current reference, not as a historical time series.',
}

function parseCsvLine(line) {
  const fields = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        field += '"'
        i++
      } else {
        inQuotes = !inQuotes
      }
    } else if (ch === ',' && !inQuotes) {
      fields.push(field)
      field = ''
    } else {
      field += ch
    }
  }
  fields.push(field)
  return fields
}

function parseCsv(text) {
  const lines = text.trim().split(/\r?\n/)
  const headers = parseCsvLine(lines[0])
  return lines.slice(1).map(line => {
    const values = parseCsvLine(line)
    return Object.fromEntries(headers.map((header, i) => [header, values[i] ?? '']))
  })
}

function quarterEndDate(year, month) {
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(last).padStart(2, '0')}`
}

function round(value, digits) {
  return Number(value).toFixed(digits)
}

function csvEscape(value) {
  const s = String(value ?? '')
  if (!/[",\n\r]/.test(s)) return s
  return `"${s.replaceAll('"', '""')}"`
}

function dmMultiplier(year) {
  if (year < 1988) return null
  if (year <= 1991) return 1.05
  if (year <= 1999) return 0.95 - ((year - 1992) / 7) * 0.17
  return 0.72
}

function emMultiplier(year) {
  if (year < 1988) return null
  if (year <= 1999) return 0.55
  if (year <= 2007) return 0.55 + ((year - 2000) / 7) * 0.10
  if (year <= 2015) return 0.65 - ((year - 2008) / 7) * 0.07
  return 0.58
}

function weights(year) {
  if (year < 1988) return { us: 1, dm: 0, em: 0 }
  const t = Math.min(1, Math.max(0, (year - 1988) / 31))
  const us = 0.42 + 0.14 * t
  const dm = 0.53 - 0.21 * t
  return { us, dm, em: 1 - us - dm }
}

function rawSyntheticWorldCape(usCape, year) {
  const w = weights(year)
  const dmCape = Math.max(9, Math.min(36, usCape * dmMultiplier(year)))
  const emCape = Math.max(7, Math.min(26, usCape * emMultiplier(year)))
  const worldCape = 1 / ((w.us / usCape) + (w.dm / dmCape) + (w.em / emCape))
  return { worldCape, dmCape, emCape, weights: w }
}

function toTime(date) {
  return new Date(`${date}T00:00:00Z`).getTime()
}

function interpolateMultiplier(date, calibrationAnchors) {
  const t = toTime(date)
  let prev = calibrationAnchors[0]
  let next = calibrationAnchors[calibrationAnchors.length - 1]

  for (let i = 0; i < calibrationAnchors.length; i++) {
    const current = calibrationAnchors[i]
    if (toTime(current.date) <= t) prev = current
    if (toTime(current.date) >= t) {
      next = current
      break
    }
  }

  if (prev.date === next.date) return prev.multiplier
  const span = toTime(next.date) - toTime(prev.date)
  const position = (t - toTime(prev.date)) / span
  return prev.multiplier + (next.multiplier - prev.multiplier) * position
}

async function main() {
  const response = await fetch(SHILLER_CSV_URL)
  if (!response.ok) throw new Error(`Failed to fetch Shiller CSV: HTTP ${response.status}`)
  const shillerRows = parseCsv(await response.text())

  const rawRows = shillerRows
    .map(row => {
      const date = new Date(`${row.Date}T00:00:00Z`)
      const year = date.getUTCFullYear()
      const month = date.getUTCMonth() + 1
      const usCape = Number(row.PE10)
      if (!Number.isFinite(usCape) || usCape <= 0) return null
      if (year < 1900 || year > 2019) return null
      if (![3, 6, 9, 12].includes(month)) return null

      const outDate = quarterEndDate(year, month)
      if (year < 1988) {
        return {
          date: outDate,
          worldCape: usCape,
          usCape,
          dmCape: '',
          emCape: '',
          weights: weights(year),
          sourceMethod: 'US_SHILLER_PROXY',
          sourceNote: 'US Shiller CAPE used as world proxy before practical free global coverage.',
        }
      }

      const synthetic = rawSyntheticWorldCape(usCape, year)
      return {
        date: outDate,
        rawWorldCape: synthetic.worldCape,
        worldCape: synthetic.worldCape,
        usCape,
        dmCape: synthetic.dmCape,
        emCape: synthetic.emCape,
        weights: synthetic.weights,
        sourceMethod: 'SYNTHETIC_EP_BLEND_CALIBRATED',
        sourceNote: 'Synthetic earnings-yield blend calibrated to public sanity anchors for 2000, 2007, 2009, and the pre-2020 Siblis splice.',
      }
    })
    .filter(Boolean)

  const rawByDate = new Map(rawRows.map(row => [row.date, row]))
  const calibrationAnchors = SYNTHETIC_ANCHORS.map(anchor => {
    const raw = rawByDate.get(anchor.date)
    if (!raw?.rawWorldCape && !raw?.worldCape) throw new Error(`Missing anchor row ${anchor.date}`)
    const rawCape = raw.rawWorldCape ?? raw.worldCape
    return {
      ...anchor,
      multiplier: anchor.targetCape == null ? 1 : anchor.targetCape / rawCape,
      rawCape,
    }
  })

  const outputRows = rawRows.map(row => {
    if (row.sourceMethod !== 'SYNTHETIC_EP_BLEND_CALIBRATED') return row
    const calibrationMultiplier = interpolateMultiplier(row.date, calibrationAnchors)
    return {
      ...row,
      worldCape: row.rawWorldCape * calibrationMultiplier,
      calibrationMultiplier,
    }
  })

  for (const siblis of SIBLIS_ANCHORS) {
    outputRows.push({
      date: siblis.date,
      worldCape: siblis.cape,
      usCape: '',
      dmCape: '',
      emCape: '',
      weights: { us: '', dm: '', em: '' },
      sourceMethod: 'SIBLIS_FREE_ANCHOR',
      sourceNote: `Siblis free Global Stock Market CAPE table; price level ${siblis.price}.`,
      calibrationMultiplier: '',
    })
  }

  outputRows.push({
    date: RA_CURRENT_REFERENCE.date,
    worldCape: RA_CURRENT_REFERENCE.cape,
    usCape: '',
    dmCape: '',
    emCape: '',
    weights: { us: '', dm: '', em: '' },
    sourceMethod: 'RA_CURRENT_REFERENCE',
    sourceNote: `${RA_CURRENT_REFERENCE.note} Historical median reference: ${RA_CURRENT_REFERENCE.historicalMedian}.`,
    calibrationMultiplier: '',
  })

  outputRows.sort((a, b) => a.date.localeCompare(b.date))

  const lines = [
    'date,world_cape,us_cape,dm_ex_us_cape,em_cape,us_weight,dm_ex_us_weight,em_weight,source_method,calibration_multiplier,source_note',
    ...outputRows.map(row => [
      row.date,
      round(row.worldCape, 2),
      row.usCape === '' ? '' : round(row.usCape, 2),
      row.dmCape === '' ? '' : round(row.dmCape, 2),
      row.emCape === '' ? '' : round(row.emCape, 2),
      row.weights.us === '' ? '' : round(row.weights.us, 4),
      row.weights.dm === '' ? '' : round(row.weights.dm, 4),
      row.weights.em === '' ? '' : round(row.weights.em, 4),
      row.sourceMethod,
      row.calibrationMultiplier === '' || row.calibrationMultiplier == null ? '' : round(row.calibrationMultiplier, 4),
      csvEscape(row.sourceNote),
    ].join(',')),
  ]

  await fs.mkdir(path.dirname(OUT_PATH), { recursive: true })
  await fs.writeFile(OUT_PATH, `${lines.join('\n')}\n`, 'utf8')
  console.log(`Wrote ${OUT_PATH} with ${outputRows.length} rows`)
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
