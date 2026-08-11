import type { StockData } from '@/types/portfolio'
import { instrumentSymbolKey } from './instrumentSymbols'

export interface TwsPosition {
  symbol: string
  qty: number
}

export function stageTwsStockSync(stocks: StockData[], positions: TwsPosition[]) {
  const qtyBySymbol = new Map(positions.map(position => [instrumentSymbolKey(position.symbol), position.qty]))
  const reportedManuallyManagedHoldings: string[] = []
  const stagedStocks = stocks.map(stock => {
    const key = instrumentSymbolKey(stock.label)
    if (stock.manualQty) {
      if (qtyBySymbol.has(key)) reportedManuallyManagedHoldings.push(instrumentSymbolKey(stock.label))
      return stock
    }
    return { ...stock, originalAmount: qtyBySymbol.get(key) ?? 0 }
  })
  const existingSymbols = new Set(stocks.map(stock => instrumentSymbolKey(stock.label)))
  for (const [symbol, qty] of qtyBySymbol) {
    if (!existingSymbols.has(symbol)) {
      stagedStocks.push({
        label: symbol,
        amount: qty,
        originalAmount: qty,
        targetWeight: 0,
        letf: '',
        groups: '',
        manualQty: false,
      })
    }
  }
  return {
    stocks: stagedStocks,
    reportedManuallyManagedHoldings: [...new Set(reportedManuallyManagedHoldings)].sort(),
  }
}
