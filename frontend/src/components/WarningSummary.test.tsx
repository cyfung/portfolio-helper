// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import WarningSummary from './WarningSummary'

describe('warning summary disclosure', () => {
  afterEach(cleanup)

  it('shows grouped occurrence counts while individual warnings remain collapsed', () => {
    render(
      <WarningSummary
        resultKey="run-1"
        warnings={[
          { category: 'NULL_DATA', message: 'Two prices were missing.', occurrences: 2 },
          { category: 'SPLIT_REPAIR', message: 'Three split breaks were repaired.', occurrences: 3 },
          'A warning from a cached legacy result.',
        ]}
      />,
    )

    expect(screen.getByText('2 null data issues')).toBeTruthy()
    expect(screen.getByText('3 split repairs')).toBeTruthy()
    expect(screen.getByText('1 other warning')).toBeTruthy()
    expect(screen.queryByText('Two prices were missing.')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Show warning details' }))

    expect(screen.getByRole('heading', { name: 'Null data issues' })).toBeTruthy()
    expect(screen.getByText('Two prices were missing.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Split repairs' })).toBeTruthy()
    expect(screen.getByText('Three split breaks were repaired.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Other warnings' })).toBeTruthy()
    expect(screen.getByText('A warning from a cached legacy result.')).toBeTruthy()
  })

  it('collapses details when a new result replaces the current result', () => {
    const view = render(
      <WarningSummary
        resultKey="run-1"
        warnings={[{ category: 'FILLED_DATA', message: 'Filled one point.', occurrences: 1 }]}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Show warning details' }))
    expect(screen.getByText('Filled one point.')).toBeTruthy()

    view.rerender(
      <WarningSummary
        resultKey="run-2"
        warnings={[{ category: 'FILLED_DATA', message: 'Filled another point.', occurrences: 1 }]}
      />,
    )

    expect(screen.queryByText('Filled another point.')).toBeNull()
    expect(screen.getByRole('button', { name: 'Show warning details' })).toBeTruthy()
  })
})
