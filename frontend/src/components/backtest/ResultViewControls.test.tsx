import { renderToStaticMarkup } from 'react-dom/server'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import ResultViewControls from './ResultViewControls'

describe('result view controls', () => {
  it('uses the page theme tokens for its surface and interactive controls', () => {
    const css = readFileSync(new URL('../../styles/backtest-form.css', import.meta.url), 'utf8')
    const rule = css.match(/\.result-view-controls\s*\{([^}]+)\}/)?.[1] ?? ''

    expect(rule).toContain('background: var(--color-bg-elevated)')
    expect(rule).toContain('border: 1px solid var(--color-border-subtle)')
    expect(rule).toContain('color: var(--color-text-secondary)')
    expect(rule).not.toContain('var(--color-border)')
    expect(css).not.toMatch(/\.result-view-controls \.backtest-config-btn\s*\{[^}]*background:/s)
  })

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
