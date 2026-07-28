// @vitest-environment jsdom

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import DataRangeSummary from './DataRangeSummary'

describe('analysis data range summary', () => {
  it('renders elapsed calendar duration with every unit', () => {
    render(
      <DataRangeSummary
        dataRange={{
          fromDate: '2015-06-18',
          toDate: '2025-06-17',
          startLimiters: [],
          endLimiters: [],
        }}
      />,
    )

    expect(screen.getByText(/9 years, 11 months, 30 days/)).toBeTruthy()
  })

  it('sorts, deduplicates, and truncates limiter identifiers', () => {
    render(
      <DataRangeSummary
        dataRange={{
          fromDate: '2020-01-01',
          toDate: '2021-01-01',
          startLimiters: ['D', 'A', 'C', 'B', 'A'],
          endLimiters: ['USDHKD=X'],
        }}
      />,
    )

    expect(screen.getByText(/Start limited by: A, B, C and 1 other/)).toBeTruthy()
    expect(screen.getByText(/End limited by: USDHKD=X/)).toBeTruthy()
  })

  it('uses singular duration and overflow wording correctly', () => {
    render(
      <DataRangeSummary
        dataRange={{
          fromDate: '2020-01-01',
          toDate: '2021-02-02',
          startLimiters: ['A', 'B', 'C', 'D', 'E'],
          endLimiters: [],
        }}
      />,
    )

    expect(screen.getByText(/1 year, 1 month, 1 day/)).toBeTruthy()
    expect(screen.getByText(/A, B, C and 2 others/)).toBeTruthy()
  })

  it('accepts cached data-range payloads without limiter arrays', () => {
    const cachedDataRange = {
      fromDate: '2020-01-01',
      toDate: '2021-01-01',
    }

    let view: ReturnType<typeof render> | undefined
    expect(() => {
      view = render(<DataRangeSummary dataRange={cachedDataRange} />)
    }).not.toThrow()
    expect(view?.container.textContent).toContain('1 year, 0 months, 0 days')
  })
})
