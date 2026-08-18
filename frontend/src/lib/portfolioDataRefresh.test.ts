// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from 'vitest'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { refreshPortfolioData } from './portfolioDataRefresh'

afterEach(() => vi.restoreAllMocks())

describe('portfolio data refresh', () => {
  it('coalesces simultaneous refreshes for one portfolio', async () => {
    const data = {
      portfolioId: 'main',
      portfolioName: 'Main',
      allPortfolios: [],
      stocks: [],
      cash: [],
      config: {
        rebalTargetUsd: null,
        marginTargetPct: null,
        marginTargetUsd: null,
        allocAddMode: 'PROPORTIONAL',
        allocReduceMode: 'PROPORTIONAL',
        virtualBalanceEnabled: false,
        dividendCalcUpToDate: '',
        dividendStartDate: '',
        flexibleWeightMappings: '',
      },
      appConfig: {
        displayCurrencies: ['USD'],
        portfolioColumnModes: [],
        afterHoursGray: false,
        showStockDisplayCurrency: false,
      },
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    const loadSpy = vi.spyOn(usePortfolioStore.getState(), 'loadPortfolioData')

    const first = refreshPortfolioData('main')
    const second = refreshPortfolioData('main')

    expect(first).toBe(second)
    await first
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(loadSpy).toHaveBeenCalledTimes(1)
  })
})
