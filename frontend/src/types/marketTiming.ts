export type InterestMode = 'SPREAD' | 'FIXED'
export type ReferenceSource = 'PORTFOLIO' | 'TICKER'

export interface MarketTimingPoint {
  date: string
  value?: number | null
  basePortfolioReturn?: number | null
  marginExcessReturn?: number | null
  triggerDate?: string | null
  daysToTrigger?: number | null
  referenceDrawdown?: number | null
  zeroingWindow?: boolean | null
  nonZeroWindowId?: number | null
}

export interface MarketTimingSummary {
  totalPoints: number
  triggeredPoints: number
  bestValue?: number | null
  worstValue?: number | null
  averageValue?: number | null
  medianValue?: number | null
  nonZeroAverageValue?: number | null
  nonZeroMedianValue?: number | null
  winRate?: number | null
  averageDaysToTrigger?: number | null
}

export interface MarketTimingResult {
  drawdownPct: number
  zeroWindowMonths?: number | null
  points: MarketTimingPoint[]
  summary: MarketTimingSummary
}

export interface MarketTimingResponse {
  referenceLabel: string
  referencePoints: { date: string; value: number }[]
  results: MarketTimingResult[]
  error?: string
}

export interface WorldCapePoint {
  date: string
  worldCape: number
  sourceMethod: string
}

export interface UsCapePoint {
  date: string
  usCape: number
}

export interface DrawdownConfigInput {
  drawdownPct: number
  zeroWindowMonths: number
}
