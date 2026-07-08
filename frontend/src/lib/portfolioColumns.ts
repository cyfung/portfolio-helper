export const PORTFOLIO_STOCK_COLUMNS = [
  { id: 'symbol', label: 'Symbol' },
  { id: 'qty', label: 'Qty' },
  { id: 'lastNav', label: 'Last NAV' },
  { id: 'est', label: 'EST' },
  { id: 'last', label: 'Last' },
  { id: 'mark', label: 'Mark' },
  { id: 'change', label: 'CHG' },
  { id: 'pnl', label: 'P&L' },
  { id: 'mktVal', label: 'Mkt Val' },
  { id: 'weight', label: 'Weight' },
  { id: 'rebalQty', label: 'Rebal Qty' },
  { id: 'rebalDollars', label: 'Rebal💰' },
  { id: 'allocQty', label: 'Alloc Qty' },
  { id: 'allocDollars', label: 'Alloc💰' },
  { id: 'ccy', label: 'CCY' },
] as const

export type PortfolioColumnId = typeof PORTFOLIO_STOCK_COLUMNS[number]['id']

export interface PortfolioColumnMode {
  id: string
  name: string
  columns: PortfolioColumnId[]
}

export const DEFAULT_PORTFOLIO_COLUMN_MODES: PortfolioColumnMode[] = [
  {
    id: 'mode-1',
    name: 'Compact',
    columns: ['symbol', 'est', 'mark', 'change', 'pnl', 'weight', 'allocDollars', 'ccy'],
  },
  {
    id: 'mode-2',
    name: 'Rebalance',
    columns: ['symbol', 'est', 'mark', 'change', 'pnl', 'weight', 'rebalDollars', 'allocDollars', 'ccy'],
  },
  {
    id: 'mode-3',
    name: 'Full',
    columns: ['symbol', 'qty', 'lastNav', 'est', 'last', 'mark', 'change', 'pnl', 'mktVal', 'weight', 'rebalQty', 'rebalDollars', 'allocQty', 'allocDollars', 'ccy'],
  },
]

const VALID_COLUMNS = new Set<PortfolioColumnId>(PORTFOLIO_STOCK_COLUMNS.map(c => c.id))

export function isPortfolioColumnId(value: unknown): value is PortfolioColumnId {
  return typeof value === 'string' && VALID_COLUMNS.has(value as PortfolioColumnId)
}

export function normalizePortfolioColumnModes(raw?: string | PortfolioColumnMode[] | null): PortfolioColumnMode[] {
  let parsed: unknown = raw
  if (typeof raw === 'string') {
    if (!raw.trim()) return DEFAULT_PORTFOLIO_COLUMN_MODES
    try {
      parsed = JSON.parse(raw)
    } catch (_) {
      return DEFAULT_PORTFOLIO_COLUMN_MODES
    }
  }
  if (!Array.isArray(parsed)) return DEFAULT_PORTFOLIO_COLUMN_MODES

  const usedIds = new Set<string>()
  const modes = parsed.map((item, index) => {
    const obj = item && typeof item === 'object' ? item as Partial<PortfolioColumnMode> : {}
    const baseId = String(obj.id || `mode-${index + 1}`).trim() || `mode-${index + 1}`
    let id = baseId
    let suffix = 2
    while (usedIds.has(id)) {
      id = `${baseId}-${suffix}`
      suffix += 1
    }
    usedIds.add(id)

    const seenColumns = new Set<PortfolioColumnId>()
    const columns = Array.isArray(obj.columns)
      ? obj.columns.filter((col): col is PortfolioColumnId => {
          if (!isPortfolioColumnId(col) || seenColumns.has(col)) return false
          seenColumns.add(col)
          return true
        })
      : []

    return {
      id,
      name: String(obj.name || `Mode ${index + 1}`).trim() || `Mode ${index + 1}`,
      columns: columns.length ? columns : DEFAULT_PORTFOLIO_COLUMN_MODES[0].columns,
    }
  }).filter(mode => mode.name)

  return modes.length ? modes : DEFAULT_PORTFOLIO_COLUMN_MODES
}

export function serializePortfolioColumnModes(modes: PortfolioColumnMode[]): string {
  return JSON.stringify(normalizePortfolioColumnModes(modes))
}

export function getPortfolioColumnMode(modes: PortfolioColumnMode[], id?: string | null): PortfolioColumnMode {
  return modes.find(mode => mode.id === id) ?? modes[0] ?? DEFAULT_PORTFOLIO_COLUMN_MODES[0]
}

export function legacyVisibilityToDefaultModeId(moreInfoVisible: boolean, rebalVisible: boolean): string {
  if (moreInfoVisible && rebalVisible) return 'mode-3'
  if (rebalVisible) return 'mode-2'
  return 'mode-1'
}

export function portfolioModeHasMoreInfo(columns: PortfolioColumnId[]): boolean {
  return columns.some(col => ['qty', 'lastNav', 'last', 'mktVal', 'rebalQty', 'allocQty'].includes(col))
}

export function portfolioModeHasRebal(columns: PortfolioColumnId[]): boolean {
  return columns.some(col => col === 'rebalQty' || col === 'rebalDollars')
}
