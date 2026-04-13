// ── chartTooltip.tsx — Shared Recharts tooltip factory ────────────────────────

import { CSSProperties } from 'react'

interface Theme {
  isDark: boolean
  gridColor: string
  textColor: string
}

export function makeRechartsTooltip(
  { isDark, gridColor, textColor }: Theme,
  valueFmt: (v: number) => string,
  labelFmt?: (l: any) => string,
) {
  const contentStyle: CSSProperties = {
    background: isDark ? '#1e1e1e' : '#ffffff',
    border: `1px solid ${gridColor}`,
    borderRadius: 4,
    padding: '6px 10px',
    fontSize: '0.78em',
  }

  return ({ active, payload, label }: any) => {
    if (!active || !payload?.length) return null
    return (
      <div style={contentStyle}>
        <div style={{ color: textColor, marginBottom: 4, fontWeight: 600 }}>
          {labelFmt ? labelFmt(label) : label}
        </div>
        {payload.map((item: any) => (
          <div key={item.dataKey} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginTop: 2 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
              <span style={{ width: 8, height: 8, borderRadius: '50%', background: item.color, flexShrink: 0, display: 'inline-block' }} />
              <span style={{ color: textColor, opacity: 0.75 }}>{item.name}</span>
            </div>
            <span style={{ color: textColor, fontWeight: 600, textAlign: 'right' }}>{valueFmt(Number(item.value))}</span>
          </div>
        ))}
      </div>
    )
  }
}
