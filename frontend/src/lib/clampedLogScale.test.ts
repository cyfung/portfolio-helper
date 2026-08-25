import { describe, expect, it } from 'vitest'
import { createClampedLogScale, LOG_SCALE_FLOOR } from './clampedLogScale'

function freshScale(domain: [number, number] = [1, 1000], range: [number, number] = [200, 0]) {
  const scale = createClampedLogScale()
  scale.domain(domain)
  scale.range(range)
  return scale
}

describe('createClampedLogScale', () => {
  it('maps values monotonically like a log scale within the positive domain', () => {
    const scale = freshScale()
    const y1 = scale(1)!
    const y10 = scale(10)!
    const y100 = scale(100)!
    const y1000 = scale(1000)!
    expect(y1).toBeCloseTo(200)
    expect(y1000).toBeCloseTo(0)
    expect(y10).toBeLessThan(y1)
    expect(y100).toBeLessThan(y10)
    expect(y1000).toBeLessThan(y100)
  })

  it('clamps zero and negative inputs to the floor position instead of returning NaN/Infinity', () => {
    const scale = freshScale()
    const floorY = scale(LOG_SCALE_FLOOR)
    expect(scale(0)).toBe(floorY)
    expect(scale(-50)).toBe(floorY)
    expect(Number.isFinite(scale(0)!)).toBe(true)
    expect(Number.isFinite(scale(-50)!)).toBe(true)
  })

  it('clamps sub-floor positive inputs to the same floor position', () => {
    const scale = freshScale()
    const floorY = scale(LOG_SCALE_FLOOR)
    expect(scale(0.5)).toBe(floorY)
    expect(scale(0.01)).toBe(floorY)
  })

  it('returns undefined for non-finite or non-numeric input', () => {
    const scale = freshScale()
    expect(scale(NaN)).toBeUndefined()
    expect(scale(Infinity)).toBeUndefined()
    expect(scale('5' as unknown as number)).toBeUndefined()
  })

  it('clamps a domain minimum at or below the floor instead of producing -Infinity math', () => {
    const scale = createClampedLogScale()
    scale.domain([0, 1000])
    scale.range([200, 0])
    expect(scale.domain()).toEqual([LOG_SCALE_FLOOR, 1000])
    expect(Number.isFinite(scale(0)!)).toBe(true)
    expect(Number.isFinite(scale(1000)!)).toBe(true)
  })

  it('produces finite, in-range ticks', () => {
    const scale = freshScale([1, 100000])
    const ticks = scale.ticks!(6)
    expect(ticks.length).toBeGreaterThan(0)
    for (const t of ticks) {
      expect(Number.isFinite(t)).toBe(true)
      expect(t).toBeGreaterThanOrEqual(1)
      expect(t).toBeLessThanOrEqual(100000)
    }
  })

  it('degenerates to a flat line without throwing when domain collapses at the floor', () => {
    const scale = freshScale([0, 0])
    expect(scale.domain()).toEqual([LOG_SCALE_FLOOR, LOG_SCALE_FLOOR])
    expect(scale(0)).toBe(200)
    expect(scale(5)).toBe(200)
  })

  it('round-trips finite values through invert', () => {
    const scale = freshScale([1, 1000])
    const y = scale(50)!
    expect(scale.invert!(y)).toBeCloseTo(50, 5)
  })

  it('copy() produces an independent scale unaffected by later mutation of the original', () => {
    const scale = freshScale([1, 1000])
    const copy = scale.copy() as ReturnType<typeof createClampedLogScale>
    scale.domain([1, 10])
    expect(copy.domain()).toEqual([1, 1000])
  })
})
