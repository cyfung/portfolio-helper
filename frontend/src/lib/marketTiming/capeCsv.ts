import type { UsCapePoint, WorldCapePoint } from '@/types/marketTiming'

function splitCsvLine(line: string) {
  const fields: string[] = []
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

export function parseWorldCapeCsv(text: string): WorldCapePoint[] {
  return text
    .trim()
    .split(/\r?\n/)
    .slice(1)
    .map(splitCsvLine)
    .map(cols => ({
      date: cols[0],
      worldCape: Number(cols[1]),
      sourceMethod: cols[8],
    }))
    .filter(row => row.date && Number.isFinite(row.worldCape))
}

export function parseUsCapeCsv(text: string): UsCapePoint[] {
  return text
    .trim()
    .split(/\r?\n/)
    .slice(1)
    .map(splitCsvLine)
    .map(cols => ({
      date: cols[0],
      usCape: Number(cols[1]),
    }))
    .filter(row => row.date && Number.isFinite(row.usCape))
}
