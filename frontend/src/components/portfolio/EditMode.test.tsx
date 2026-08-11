// @vitest-environment jsdom

import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import EditMode from './EditMode'

vi.mock('@/stores/portfolioStore', () => ({
  usePortfolioStore: () => ({
    stocks: [],
    portfolioId: 'test',
    config: { flexibleWeightMappings: '' },
    appConfig: null,
  }),
}))

vi.mock('@/lib/savedPortfolioCache', () => ({
  useSavedPortfolios: () => ({ savedPortfolios: [] }),
}))

afterEach(cleanup)

describe('portfolio edit mode', () => {
  it('renders weight and manual quantity controls under their matching labels', () => {
    const { container } = render(
      <EditMode
        saveKey={0}
        onSaved={() => undefined}
        initialStocks={[{
          label: 'SPY',
          amount: 5,
          originalAmount: 5,
          targetWeight: 60,
          manualQty: true,
        } as never]}
      />,
    )

    const cells = container.querySelectorAll('#stock-edit-table tbody tr:first-child td')

    expect(cells[3].querySelector('.edit-weight')).toBeTruthy()
    expect(cells[4].querySelector('input[type="checkbox"]')).toBeTruthy()
  })
})
