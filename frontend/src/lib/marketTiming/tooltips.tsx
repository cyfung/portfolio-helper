import type { CSSProperties } from 'react'
import type { useChartTheme } from '@/lib/chartTheme'
import { money, pct } from '@/lib/statsFormatters'

export function makeMarketTimingTooltip(
  { isDark, gridColor, textColor }: ReturnType<typeof useChartTheme>,
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
        <div style={{ color: textColor, marginBottom: 4, fontWeight: 600 }}>{label}</div>
        {payload.map((item: any) => {
          const value = Number(item.value)
          const formatted = item.dataKey === 'reference' ? money(value) : pct(value)
          return (
            <div key={item.dataKey} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginTop: 2 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                <span style={{ width: 16, height: 2, background: item.color, flexShrink: 0, display: 'inline-block' }} />
                <span style={{ color: textColor, opacity: 0.9 }}>{item.name}</span>
              </div>
              <span style={{ color: textColor, fontWeight: 600, textAlign: 'right' }}>{formatted}</span>
            </div>
          )
        })}
      </div>
    )
  }
}
