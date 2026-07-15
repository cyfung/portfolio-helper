import { Fragment, useEffect, useMemo, useState, type ReactNode } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import { buildSortedCcys, getCcyClass } from '@/lib/ccy-colors'
import {
  formatCurrency, formatQty, formatSignedQty, convertFromUsd,
  parseLetfAttr, formatSignedCurrency,
  weightDiffCls, actionCls, hasFxRate,
} from '@/lib/portfolio-utils'
import { computeDisplay } from '@/lib/rebalance'
import { computeFlexibleStockDisplay, parseFlexibleWeightMappings } from '@/lib/flexibleWeights'
import {
  getPortfolioColumnMode,
  normalizePortfolioColumnModes,
  PORTFOLIO_STOCK_COLUMNS,
  type PortfolioColumnId,
} from '@/lib/portfolioColumns'

function getMainGroup(groups: string): string {
  if (!groups) return ''
  const first = groups.split(';')[0].trim()
  const sp = first.indexOf(' ')
  return sp >= 0 ? first.slice(sp + 1).trim() : ''
}

const COLUMN_LABELS = new Map(PORTFOLIO_STOCK_COLUMNS.map(column => [column.id, column.label]))

function headerClassName(columnId: PortfolioColumnId): string {
  return [
    ['qty', 'lastNav', 'est', 'last', 'mark', 'change', 'pnl', 'mktVal', 'weight', 'flexWeight'].includes(columnId) ? 'col-num' : '',
    ['lastNav', 'est', 'last', 'mark', 'change', 'pnl', 'mktVal'].includes(columnId) ? 'col-market-data' : '',
    ['qty', 'lastNav', 'last', 'mktVal', 'rebalQty', 'flexRebalQty', 'allocQty'].includes(columnId) ? 'col-moreinfo' : '',
    ['rebalQty', 'rebalDollars', 'flexRebalQty', 'flexRebalDollars'].includes(columnId) ? 'rebal-column' : '',
    ['allocQty', 'allocDollars'].includes(columnId) ? 'alloc-column' : '',
    columnId === 'ccy' ? 'col-ccy' : '',
  ].filter(Boolean).join(' ')
}

function columnHeader(columnId: PortfolioColumnId): ReactNode {
  const className = headerClassName(columnId) || undefined
  if (columnId === 'est') {
    return (
      <th className={className} id="th-est-val">
        EST <span className="col-info-hint" title="Hover a cell to see price targets">(i)</span>
      </th>
    )
  }
  if (columnId === 'weight') {
    return (
      <th className={className}>
        Weight <span className="th-sub">Cur / Tgt / Dev</span>
      </th>
    )
  }
  if (columnId === 'flexWeight') {
    return (
      <th className={className}>
        <span className="flex-column-marker">F</span> Weight <span className="th-sub">Cur / Flex / Dev</span>
      </th>
    )
  }
  if (columnId === 'flexRebalQty') {
    return (
      <th className={className}>
        <span className="flex-column-marker">F</span> Rebal Qty
      </th>
    )
  }
  if (columnId === 'flexRebalDollars') {
    return (
      <th className={className}>
        <span className="flex-column-marker">F</span> Rebal💰
      </th>
    )
  }
  return <th className={className}>{COLUMN_LABELS.get(columnId) ?? columnId}</th>
}

export default function StockTable() {
  const {
    stocks, fxRates, currentDisplayCurrency,
    config,
    lastStockDisplay, lastGroupAllocData, lastPortfolioTotals,
    rebalTargetUsd, marginTargetPct, marginTargetUsd,
    allocAddMode, allocReduceMode,
    showStockDisplayCurrency, groupViewActive,
    appConfig, stockGroupBy, portfolioColumnModeId,
  } = usePortfolioStore()
  const [freshAt, setFreshAt] = useState<Date | null>(null)

  useEffect(() => {
    if (lastStockDisplay) setFreshAt(new Date())
  }, [lastStockDisplay])

  const sortedCcys = useMemo(() => buildSortedCcys(
    appConfig?.displayCurrencies ?? [],
    (lastStockDisplay?.stocks ?? []).map(s => s.currency).filter(Boolean),
  ), [appConfig?.displayCurrencies, lastStockDisplay?.stocks])

  const columnModes = useMemo(
    () => normalizePortfolioColumnModes(appConfig?.portfolioColumnModes),
    [appConfig?.portfolioColumnModes],
  )
  const visibleColumnIds = getPortfolioColumnMode(columnModes, portfolioColumnModeId).columns
  const flexibleWeightMappings = useMemo(
    () => parseFlexibleWeightMappings(config.flexibleWeightMappings),
    [config.flexibleWeightMappings],
  )

  const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
  const stockGrossKnown = lastPortfolioTotals?.stockGrossKnown ?? false
  const marginUsd = lastPortfolioTotals?.marginUsd ?? 0

  const liveBySymbol = useMemo(() => new Map(
    (lastStockDisplay?.stocks ?? []).map(s => [s.symbol, s])
  ), [lastStockDisplay])

  const groupedStocks = useMemo(() => {
    if (stockGroupBy === 'none') return [{ key: null as string | null, stocks }]
    const map = new Map<string, typeof stocks>()
    for (const stock of stocks) {
      const key = stockGroupBy === 'ccy'
        ? (liveBySymbol.get(stock.label)?.currency ?? 'USD')
        : (getMainGroup(stock.groups) || 'No Group')
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(stock)
    }
    const entries = [...map.entries()].sort(([a], [b]) => {
      if (stockGroupBy === 'mainGroup') {
        if (a === 'No Group') return 1
        if (b === 'No Group') return -1
      }
      return a.localeCompare(b)
    })
    return entries.map(([key, stocks]) => ({ key, stocks }))
  }, [stocks, stockGroupBy, liveBySymbol])

  const hasGroups = stocks.some(s => s.groups)
  const serverAllocDollars = (hasGroups && groupViewActive)
    ? Object.fromEntries((lastGroupAllocData?.stocks ?? []).map(s => [s.symbol, s.allocDollars]))
    : undefined

  const computedAlloc = (stockGrossKnown && stockGrossUsd > 0)
    ? computeDisplay(
        stocks.map(s => ({
          symbol: s.label,
          qty: s.amount,
          targetWeight: s.targetWeight ?? 0,
          positionValueUsd: liveBySymbol.get(s.label)?.positionValueUsd ?? 0,
        })),
        rebalTargetUsd,
        marginTargetPct,
        allocAddMode,
        allocReduceMode,
        stockGrossUsd,
        marginUsd,
        marginTargetUsd,
        serverAllocDollars,
        appConfig?.hybridAllocStrategies,
      )
    : null
  const computedFlexible = (stockGrossKnown && stockGrossUsd > 0)
    ? computeFlexibleStockDisplay(
        stocks.map(s => {
          const sym = s.label
          const positionValueUsd = liveBySymbol.get(sym)?.positionValueUsd ?? 0
          return {
            symbol: sym,
            currentWeightPct: (positionValueUsd / stockGrossUsd) * 100,
            targetWeight: s.targetWeight ?? 0,
            rebalDollars: computedAlloc?.rebalDollars[sym] ?? 0,
          }
        }),
        flexibleWeightMappings,
      )
    : null

  const fmt = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatCurrency(convertFromUsd(usd, fxRates, currentDisplayCurrency))
      : '-'

  const totalTargetWeight = stocks.reduce((sum, s) => sum + (s.targetWeight ?? 0), 0)
  const showWeightWarning = totalTargetWeight > 0 && Math.abs(totalTargetWeight - 100) > 1
  const freshAtLabel = freshAt?.toLocaleTimeString(undefined, { hour12: false }) ?? 'Waiting for data'

  useEffect(() => {
    let tooltip: HTMLElement | null = null
    function getTooltip() {
      if (!tooltip) {
        tooltip = document.createElement('div')
        tooltip.id = 'est-val-tooltip'
        document.body.appendChild(tooltip)
      }
      return tooltip
    }
    function onEnter(e: Event) {
      const cell = (e.target as Element).closest('td[id^="est-val-"].loaded') as HTMLElement | null
      if (!cell) return
      const estVal = parseFloat(cell.dataset.estVal ?? '')
      if (isNaN(estVal)) return
      const deltas = [0.002, 0.001, 0, -0.001, -0.002]
      let html = ''
      for (const d of deltas) {
        const price = estVal * (1 + d)
        if (d === 0) {
          html += '<hr class="ladder-separator">'
        } else {
          const sign = d > 0 ? '+' : '-'
          const label = sign + Math.abs(d * 100).toFixed(1) + '%'
          const cls = d > 0 ? 'ladder-up' : 'ladder-down'
          html += `<span class="${cls}">${label}  ${price.toFixed(2)}</span>\n`
        }
      }
      const tip = getTooltip()
      tip.innerHTML = html
      tip.style.display = 'block'
      const rect = cell.getBoundingClientRect()
      tip.style.left = (rect.right + 8) + 'px'
      tip.style.top = rect.top + 'px'
    }
    function onLeave(e: Event) {
      const cell = (e.target as Element).closest('td[id^="est-val-"].loaded')
      if (!cell) return
      if (tooltip) tooltip.style.display = 'none'
    }
    document.addEventListener('mouseenter', onEnter, true)
    document.addEventListener('mouseleave', onLeave, true)
    return () => {
      document.removeEventListener('mouseenter', onEnter, true)
      document.removeEventListener('mouseleave', onLeave, true)
      tooltip?.remove()
      tooltip = null
    }
  }, [])

  return (
    <>
      <div className="stock-table-block">
        <table className="portfolio-table" id="stock-view-table">
          <thead>
            <tr>
              {visibleColumnIds.map(columnId => <Fragment key={columnId}>{columnHeader(columnId)}</Fragment>)}
            </tr>
          </thead>
          <tbody>
            {groupedStocks.map(({ key, stocks: groupStocks }) => (
              <Fragment key={key ?? '__all'}>
                {key !== null && (
                  <tr className="stock-group-header">
                    <td colSpan={visibleColumnIds.length}>
                      {stockGroupBy === 'ccy'
                        ? <span className={`ccy-pill ccy-color-${getCcyClass(key, sortedCcys)}`}>{key}</span>
                        : key}
                    </td>
                  </tr>
                )}
                {groupStocks.map((stock) => {
                  const sym = stock.label
                  const live = liveBySymbol.get(sym) ?? null
                  const targetWeight = stock.targetWeight ?? 0
                  const qty = stock.amount
                  const markPrice = live?.markPrice ?? null
                  const closePrice = live?.closePrice ?? null
                  const navPrice = live?.lastNav ?? null
                  const navDate = live?.lastNavDate ?? null
                  const estPrice = live?.estPriceNative ?? null
                  const posVal = live?.positionValueUsd ?? null
                  const dayCh = live?.dayChangeNative ?? null
                  const stockCcy = live?.currency ?? null
                  const fxRate = stockCcy ? (fxRates[stockCcy] ?? null) : null
                  const isAfterHours = live?.isMarketClosed ?? false
                  const markStr = markPrice !== null ? formatCurrency(markPrice) : '-'
                  const dayPct = live?.dayChangePct ?? null
                  const dayPctCls = `${dayPct === null ? '' : dayPct > 0 ? 'positive' : dayPct < 0 ? 'negative' : 'neutral'}${isAfterHours ? ' after-hours' : ''}`
                  const dayPctStr = dayPct !== null
                    ? `${dayPct >= 0 ? '+' : ''}${dayPct.toFixed(2)}%` : ''
                  const hasNativeRate = stockCcy === 'USD' || fxRate !== null
                  const mktValStr = posVal !== null
                    ? (showStockDisplayCurrency
                        ? fmt(posVal)
                        : (stockCcy && hasNativeRate) ? formatCurrency(convertFromUsd(posVal, fxRates, stockCcy)) : '-')
                    : '-'
                  const dayChStr = dayCh !== null ? formatSignedCurrency(dayCh) : ''
                  const dayChCls = `${dayCh === null ? 'neutral' : dayCh > 0 ? 'positive' : dayCh < 0 ? 'negative' : 'neutral'}${isAfterHours ? ' after-hours' : ''}`
                  const liveQty = live?.qty ?? null
                  const pnlUsd = (dayCh !== null && liveQty !== null && fxRate !== null)
                    ? dayCh * liveQty * fxRate : null
                  const pnlNative = (dayCh !== null && liveQty !== null) ? dayCh * liveQty : null
                  const pnlStr = showStockDisplayCurrency
                    ? (pnlUsd !== null
                        ? (hasFxRate(fxRates, currentDisplayCurrency)
                            ? formatSignedCurrency(convertFromUsd(pnlUsd, fxRates, currentDisplayCurrency))
                            : '-')
                        : '')
                    : (pnlNative !== null
                        ? (hasNativeRate ? formatSignedCurrency(pnlNative) : '-')
                        : '')
                  const pnlCls = `${pnlUsd === null ? 'neutral' : pnlUsd > 0 ? 'positive' : pnlUsd < 0 ? 'negative' : 'neutral'}${isAfterHours ? ' after-hours' : ''}`

                  const curWeight = stockGrossKnown && posVal !== null ? (posVal / stockGrossUsd) * 100 : 0
                  const weightDiff = curWeight - targetWeight
                  const diffCls = weightDiffCls(weightDiff)
                  const pillSign = weightDiff >= 0 ? '+' : ''
                  const rebalDollars = stockGrossKnown ? (computedAlloc?.rebalDollars[sym] ?? null) : null
                  const rebalQty = (rebalDollars !== null && markPrice && markPrice > 0 && fxRate)
                    ? rebalDollars / (markPrice * fxRate) : null
                  const flexTargetWeight = computedFlexible?.targetWeight[sym] ?? targetWeight
                  const flexWeightDiff = curWeight - flexTargetWeight
                  const flexDiffCls = weightDiffCls(flexWeightDiff)
                  const flexPillSign = flexWeightDiff >= 0 ? '+' : ''
                  const flexRebalDollars = stockGrossKnown ? (computedFlexible?.rebalDollars[sym] ?? rebalDollars) : null
                  const flexRebalQty = (flexRebalDollars !== null && markPrice && markPrice > 0 && fxRate)
                    ? flexRebalDollars / (markPrice * fxRate) : null
                  const allocDollars = stockGrossKnown ? (computedAlloc?.allocDollars[sym] ?? null) : null
                  const allocQty = (allocDollars !== null && markPrice && markPrice > 0 && fxRate)
                    ? allocDollars / (markPrice * fxRate) : null

                  const letfTokens = parseLetfAttr(stock.letf)
                  const letfAttr = letfTokens.map(t => `${t.mult},${t.sym}`).join(',')

                  const cells: Record<PortfolioColumnId, ReactNode> = {
                    symbol: <td>{sym}</td>,
                    qty: <td className="amount col-moreinfo" id={`amount-${sym}`}>{formatQty(qty)}</td>,
                    lastNav: (
                      <td className="col-market-data price muted col-moreinfo" id={`nav-${sym}`}>
                        {navPrice !== null ? (
                          <>
                            <span className="nav-price-value">{formatCurrency(navPrice)}</span>
                            {navDate && <span className="nav-date">as of {navDate}</span>}
                          </>
                        ) : '-'}
                      </td>
                    ),
                    est: (
                      <td
                        className={`col-market-data price${estPrice !== null ? ' loaded' : ''}${isAfterHours ? ' after-hours' : ''}`}
                        id={`est-val-${sym}`}
                        data-est-val={estPrice ?? undefined}
                      >
                        {estPrice !== null ? formatCurrency(estPrice) : '-'}
                      </td>
                    ),
                    last: <td className="col-market-data price col-moreinfo" id={`close-${sym}`}>{closePrice !== null ? formatCurrency(closePrice) : '-'}</td>,
                    mark: (
                      <td className={`col-market-data price${markPrice !== null ? ' loaded' : ''}${isAfterHours ? ' after-hours' : ''}`} id={`mark-${sym}`}>
                        <span className="mark-price-value">{markStr}</span>
                        {dayPctStr && (
                          <span className={`mark-day-pct ${dayPctCls}`} id={`day-percent-${sym}`}>
                            {dayPctStr}
                          </span>
                        )}
                      </td>
                    ),
                    change: <td className={`col-market-data price-change ${dayChCls}`} id={`day-change-${sym}`}>{dayChStr}</td>,
                    pnl: <td className={`col-market-data price-change ${pnlCls}`} id={`position-change-${sym}`}>{pnlStr}</td>,
                    mktVal: <td className="col-market-data value col-moreinfo" id={`value-${sym}`}>{mktValStr}</td>,
                    weight: (
                      <td className="weight-display col-num" id={`current-weight-${sym}`}>
                        {stockGrossKnown && (
                          <>
                            <span className="weight-cur">{curWeight.toFixed(1)}%</span>
                            <span className="weight-sep">/</span>
                            <span className="weight-tgt">{targetWeight.toFixed(1)}%</span>
                            <span className={`weight-diff ${diffCls}`}>{pillSign}{weightDiff.toFixed(1)}%</span>
                          </>
                        )}
                      </td>
                    ),
                    flexWeight: (
                      <td className="weight-display col-num" id={`flex-weight-${sym}`}>
                        {stockGrossKnown && (
                          <>
                            <span className="weight-cur">{curWeight.toFixed(1)}%</span>
                            <span className="weight-sep">/</span>
                            <span className="weight-tgt">{flexTargetWeight.toFixed(1)}%</span>
                            <span className={`weight-diff ${flexDiffCls}`}>{flexPillSign}{flexWeightDiff.toFixed(1)}%</span>
                          </>
                        )}
                      </td>
                    ),
                    rebalQty: <td className={`action-neutral rebal-column col-moreinfo ${actionCls(rebalDollars)}`} id={`rebal-qty-${sym}`}>{rebalQty !== null ? formatSignedQty(rebalQty) : ''}</td>,
                    rebalDollars: <td className={`action-neutral rebal-column ${actionCls(rebalDollars)}`} id={`rebal-dollars-${sym}`}>{rebalDollars !== null && fxRate !== null ? formatSignedCurrency(rebalDollars / fxRate) : '-'}</td>,
                    flexRebalQty: <td className={`action-neutral rebal-column col-moreinfo ${actionCls(flexRebalDollars)}`} id={`flex-rebal-qty-${sym}`}>{flexRebalQty !== null ? formatSignedQty(flexRebalQty) : ''}</td>,
                    flexRebalDollars: <td className={`action-neutral rebal-column ${actionCls(flexRebalDollars)}`} id={`flex-rebal-dollars-${sym}`}>{flexRebalDollars !== null && fxRate !== null ? formatSignedCurrency(flexRebalDollars / fxRate) : '-'}</td>,
                    allocQty: <td className={`action-neutral alloc-column col-moreinfo ${actionCls(allocDollars)}`} id={`alloc-qty-${sym}`}>{allocQty !== null ? formatSignedQty(allocQty) : ''}</td>,
                    allocDollars: <td className={`action-neutral alloc-column ${actionCls(allocDollars)}`} id={`alloc-dollars-${sym}`}>{allocDollars !== null && fxRate !== null ? formatSignedCurrency(allocDollars / fxRate) : '-'}</td>,
                    ccy: (
                      <td className="col-ccy text-center">
                        {stockCcy && (
                          <span className={`ccy-pill ccy-color-${getCcyClass(stockCcy, sortedCcys)}`}>
                            {stockCcy}
                          </span>
                        )}
                      </td>
                    ),
                  }

                  return (
                    <tr
                      key={sym}
                      className="leading-[1.4]"
                      data-symbol={sym}
                      data-qty={formatQty(qty)}
                      data-raw-qty={qty.toString()}
                      data-weight={targetWeight.toString()}
                      data-letf={letfAttr || undefined}
                      data-groups={stock.groups || undefined}
                    >
                      {visibleColumnIds.map(columnId => <Fragment key={columnId}>{cells[columnId]}</Fragment>)}
                    </tr>
                  )
                })}
              </Fragment>
            ))}
          </tbody>
        </table>

        <div className="stock-table-freshness">
          <span className="stock-table-freshness-label">Data freshness:</span>
          {freshAt ? (
            <time dateTime={freshAt.toISOString()}>{freshAtLabel}</time>
          ) : (
            <span className="stock-table-freshness-value">{freshAtLabel}</span>
          )}
        </div>
      </div>

      {showWeightWarning && (
        <div className="rebal-weight-warning" id="rebal-weight-warning">
          Target weights sum to {totalTargetWeight.toFixed(1)}% (should be 100%)
        </div>
      )}
    </>
  )
}
