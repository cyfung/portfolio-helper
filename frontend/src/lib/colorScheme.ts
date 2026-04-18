// ── colorScheme.ts — OKLCH-based dynamic chart color system ───────────────────
// Groups: A=Azure-Blue(~240°), B=Orange-Red(~40°), C=Seafoam(~165°)
// Accents: Near-black/White (NAV), Deep-rose (TWR), Deep-violet (MWR), Slate (Position)

function oklchToHex(L: number, C: number, H: number): string {
  const hRad = (H * Math.PI) / 180
  const a = C * Math.cos(hRad)
  const b = C * Math.sin(hRad)

  // OKLAB → LMS^(1/3)
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b
  const s_ = L - 0.0894841775 * a - 1.2914855480 * b

  const l = l_ * l_ * l_
  const m = m_ * m_ * m_
  const s = s_ * s_ * s_

  // LMS → linear sRGB
  const rLin =  4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
  const gLin = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
  const bLin = -0.0041960863 * l - 0.7034186147 * m + 1.6976559020 * s

  const toSRGB = (c: number): number => {
    const cl = Math.max(0, Math.min(1, c))
    return cl <= 0.0031308 ? 12.92 * cl : 1.055 * Math.pow(cl, 1 / 2.4) - 0.055
  }

  const R = Math.round(toSRGB(rLin) * 255)
  const G = Math.round(toSRGB(gLin) * 255)
  const B = Math.round(toSRGB(bLin) * 255)
  return '#' + [R, G, B].map(v => v.toString(16).padStart(2, '0')).join('')
}

function hexToOklch(hex: string): [number, number, number] {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  const toLinear = (c: number) => c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
  const rL = toLinear(r), gL = toLinear(g), bL = toLinear(b)
  const lms_l = 0.4122214708 * rL + 0.5363325363 * gL + 0.0514459929 * bL
  const lms_m = 0.2119034982 * rL + 0.6806995451 * gL + 0.1073969566 * bL
  const lms_s = 0.0883024619 * rL + 0.2817188376 * gL + 0.6299787005 * bL
  const l_ = Math.cbrt(lms_l), m_ = Math.cbrt(lms_m), s_ = Math.cbrt(lms_s)
  const L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
  const A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
  const B2 = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
  const C = Math.sqrt(A * A + B2 * B2)
  const H = ((Math.atan2(B2, A) * 180 / Math.PI) + 360) % 360
  return [L, C, H]
}

interface GroupAnchor { l1: number; c1: number; l2: number; c2: number; h: number; outlierHex: string }

// Build an anchor from explicit hex endpoints; hue taken from the light-end hex.
function makeAnchor(lightHex: string, darkHex: string, outlierHex: string): GroupAnchor {
  const [l1, c1, h] = hexToOklch(lightHex)
  const [l2, c2]    = hexToOklch(darkHex)
  return { l1, c1, l2, c2, h, outlierHex }
}

const ANCHORS_LIGHT: GroupAnchor[] = [
  makeAnchor('#93c5fd', '#1e40af', '#1d4ed8'),  // A: Blue (blue-300 → blue-800, outlier blue-700)
  makeAnchor('#fb923c', '#9a3412', '#c2410c'),  // B: Orange-Red (orange-400 → red-800, outlier red-700)
  makeAnchor('#2dd4bf', '#115e59', '#0f766e'),  // C: Seafoam (teal-400 → teal-900, outlier teal-700)
]

const ANCHORS_DARK: GroupAnchor[] = [
  makeAnchor('#bfdbfe', '#1e3a5f', '#60a5fa'),                                    // A: Blue
  makeAnchor('#fed7aa', '#4a1a00', '#fb923c'),                                    // B: Orange-Red
  { l1: 0.80, c1: 0.16, l2: 0.38, c2: 0.12, h: 165, outlierHex: '#2dd4bf' },   // C: Seafoam
]

/**
 * Returns n hex colors for a portfolio group, interpolated light→dark in OKLCH.
 * n=1: light anchor color. n>1: ramp from light anchor to dark anchor, then outlierHex as last.
 */
export function getGroupColors(groupId: number, n: number, isDark: boolean): string[] {
  if (n <= 0) return []
  const { l1, c1, l2, c2, h, outlierHex } = (isDark ? ANCHORS_DARK : ANCHORS_LIGHT)[groupId % 3]
  if (n === 1) return [oklchToHex(l1, c1, h)]
  const colors: string[] = []
  for (let i = 0; i < n - 1; i++) {
    const t = i / (n - 1)
    colors.push(oklchToHex(l1 + t * (l2 - l1), c1 + t * (c2 - c1), h))
  }
  colors.push(outlierHex)
  return colors
}

/**
 * Scale a SVG strokeDasharray string so dashes remain visible at high data density.
 * pixelsPerPoint = containerWidthPx / (numPoints - 1).
 * At 4+ px/pt no scaling is applied. minGap is only enforced when scale > 1
 * so base patterns render faithfully at low density.
 */
export function scaleDash(
  base: string | undefined,
  pixelsPerPoint: number,
  maxScale = Infinity,
  minGap = 6,
): string | undefined {
  if (!base) return base
  const scale = Math.min(maxScale, Math.max(1.0, 4.0 / Math.max(pixelsPerPoint, 0.01)))
  return base
    .trim()
    .split(/\s+/)
    .map((v, i) => {
      const n = Math.round(parseFloat(v) * scale)
      return i % 2 === 1 ? String(scale > 1 ? Math.max(minGap, n) : n) : String(n)
    })
    .join(' ')
}

// strokeWidth by variant index: index 0 = No Margin (thickest), higher = progressively thinner
const ROLE_WIDTH = [2, 1.75, 1.5, 1.25, 1.0] as const

export function getGroupStrokeWidths(n: number): number[] {
  if (n <= 0) return []
  return Array.from({ length: n }, (_, i) => ROLE_WIDTH[Math.min(i, ROLE_WIDTH.length - 1)])
}

// Accent colors for 4 standalone real-portfolio series.
// [0] NAV  [1] TWR  [2] MWR  [3] Position
export const ACCENT_LIGHT: string[] = [
  '#1e293b',  // Near black  → NAV
  '#be185d',  // Deep rose   → TWR
  '#6d28d9',  // Deep violet → MWR
  '#334155',  // Slate grey  → Position
]

export const ACCENT_DARK: string[] = [
  '#ffffff',  // White        → NAV
  '#f472b6',  // Rose pink    → TWR
  '#a78bfa',  // Soft violet  → MWR
  '#94a3b8',  // Cool slate   → Position
]
