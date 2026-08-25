import type { CustomScaleDefinition } from 'recharts'

// Below $1, a portfolio value has no meaningful distinction from "ruined" — and d3's log
// scale maps 0 (or negative) to -Infinity, which breaks the whole chart's SVG path, not
// just the offending point. Clamping the *plotted* position to this floor sidesteps that
// without touching the underlying data (tooltips still read the real, unclamped value).
export const LOG_SCALE_FLOOR = 1

export function createClampedLogScale(floor = LOG_SCALE_FLOOR): CustomScaleDefinition<number> {
  let domain: readonly number[] = [floor, floor * 10]
  let range: readonly number[] = [0, 1]

  const clampedLog = (value: number) => Math.log(Math.max(value, floor))

  function scale(input: number): number | undefined {
    if (typeof input !== 'number' || !Number.isFinite(input)) return undefined
    const [d0, d1] = domain
    const [r0, r1] = range
    const logSpan = clampedLog(d1) - clampedLog(d0)
    if (logSpan === 0) return r0
    const t = (clampedLog(input) - clampedLog(d0)) / logSpan
    return r0 + t * (r1 - r0)
  }

  function domainFn(newDomain?: readonly number[]) {
    if (newDomain) {
      domain = [Math.max(newDomain[0], floor), Math.max(newDomain[1], floor)]
      return scale
    }
    return domain
  }

  function rangeFn(newRange?: readonly number[]) {
    if (newRange) {
      range = newRange
      return scale
    }
    return range
  }

  function copy() {
    const next = createClampedLogScale(floor)
    next.domain(domain as number[])
    next.range(range as number[])
    return next
  }

  function ticks(count = 6) {
    const [d0, d1] = domain
    if (d1 <= d0) return [d0]
    const lo = Math.floor(Math.log10(d0))
    const hi = Math.ceil(Math.log10(d1))
    const candidates: number[] = []
    for (let exp = lo; exp <= hi; exp++) {
      for (const mult of [1, 2, 5]) {
        const value = mult * 10 ** exp
        if (value >= d0 - 1e-9 && value <= d1 + 1e-9) candidates.push(value)
      }
    }
    candidates.sort((a, b) => a - b)
    if (candidates.length === 0) return [d0, d1]
    if (candidates.length <= count + 2) return candidates
    const step = Math.ceil(candidates.length / count)
    return candidates.filter((_, i) => i % step === 0)
  }

  function invert(value: number) {
    const [d0, d1] = domain
    const [r0, r1] = range
    if (r1 === r0) return d0
    const logSpan = clampedLog(d1) - clampedLog(d0)
    const t = (value - r0) / (r1 - r0)
    return Math.exp(clampedLog(d0) + t * logSpan)
  }

  return Object.assign(scale, { domain: domainFn, range: rangeFn, copy, ticks, invert }) as unknown as CustomScaleDefinition<number>
}
