import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import ResultViewControls from './ResultViewControls'

describe('result view controls', () => {
  it('groups instant result adjustments and explains unavailable inflation data', () => {
    const markup = renderToStaticMarkup(
      <ResultViewControls
        inflationAdjusted
        onInflationAdjustedChange={() => undefined}
        unavailableReason="Inflation adjustment is unavailable before 1947-01-01."
      >
        <label>NAV adjusted</label>
      </ResultViewControls>,
    )

    expect(markup).toContain('aria-label="Result view controls"')
    expect(markup).toContain('Inflation adjusted')
    expect(markup).toContain('NAV adjusted')
    expect(markup).toContain('disabled=""')
    expect(markup).toContain('unavailable before 1947-01-01')
  })
})
