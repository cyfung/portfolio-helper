import { useCallback, useEffect, useRef, useState } from 'react'

export function useChartContainerWidth(initialWidth = 1000) {
  const [chartWidth, setChartWidth] = useState(initialWidth)
  const chartObsRef = useRef<ResizeObserver | null>(null)

  const chartContainerRef = useCallback((node: HTMLDivElement | null) => {
    chartObsRef.current?.disconnect()
    chartObsRef.current = null
    if (!node) return

    const obs = new ResizeObserver(entries => setChartWidth(entries[0].contentRect.width))
    obs.observe(node)
    chartObsRef.current = obs
  }, [])

  useEffect(() => () => chartObsRef.current?.disconnect(), [])

  return { chartWidth, chartContainerRef }
}
