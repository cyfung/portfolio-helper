export const CURVE_LABEL_SEPARATOR = ' \u2013 '

export type RealCurveKey = 'real-nav' | 'real-twr' | 'real-mwr' | 'real-pos'

export const REAL_CURVE_KEYS = {
  nav: 'real-nav',
  twr: 'real-twr',
  mwr: 'real-mwr',
  position: 'real-pos',
} as const satisfies Record<string, RealCurveKey>

export const REAL_CURVE_LABELS: Record<RealCurveKey, string> = {
  [REAL_CURVE_KEYS.nav]: curveDisplayLabel('Real', 'NAV'),
  [REAL_CURVE_KEYS.twr]: curveDisplayLabel('Real', 'TWR'),
  [REAL_CURVE_KEYS.mwr]: curveDisplayLabel('Real', 'MWR'),
  [REAL_CURVE_KEYS.position]: curveDisplayLabel('Real', 'Position'),
}

export const REAL_CURVE_KEY_BY_LABEL: Record<string, RealCurveKey> = Object.fromEntries(
  Object.entries(REAL_CURVE_LABELS).map(([key, label]) => [label, key]),
) as Record<string, RealCurveKey>

export function curveSelectionKey(portfolioIndex: number, curveIndex: number) {
  return `${portfolioIndex}-${curveIndex}`
}

export function curveDataKey(portfolioIndex: number, curveIndex: number) {
  return `p${portfolioIndex}-c${curveIndex}`
}

export function curveMetricDataKey(metricPrefix: string, portfolioIndex: number, curveIndex: number) {
  return `${metricPrefix}${portfolioIndex}-${curveIndex}`
}

export function curveDisplayLabel(portfolioLabel: string, curveLabel: string) {
  return `${portfolioLabel}${CURVE_LABEL_SEPARATOR}${curveLabel}`
}

export function percentileCurveLabel(percentile: number) {
  return `P${percentile}`
}

export function curveMetricLabel(portfolioLabel: string, curveLabel: string, metricLabel: string) {
  return `${curveDisplayLabel(portfolioLabel, curveLabel)} ${metricLabel}`
}
