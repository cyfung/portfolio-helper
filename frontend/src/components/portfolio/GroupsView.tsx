// ── GroupsView.tsx — Port of groups.js group aggregation ─────────────────────
import { useEffect } from 'react'
import { usePortfolioStore } from '@/stores/portfolioStore'
import {
  parseGroupsAttr, formatCurrency, formatSignedCurrency, convertFromUsd,
  weightDiffCls, actionCls, hasFxRate,
} from '@/lib/portfolio-utils'
import { getRebalTotal } from '@/lib/rebalance'

interface GroupEntry {
  mktVal: number
  prevMktVal: number
  targetWeight: number
  members: string[]
}

export default function GroupsView() {
  const {
    stocks, fxRates, currentDisplayCurrency,
    lastStockDisplay, lastGroupAllocData, lastPortfolioTotals,
    rebalTargetUsd, marginTargetPct, marginTargetUsd,
  } = usePortfolioStore()

  const stockGrossUsd = lastPortfolioTotals?.stockGrossUsd ?? 0
  const stockGrossKnown = lastPortfolioTotals?.stockGrossKnown ?? false
  const marginUsd = lastPortfolioTotals?.marginUsd ?? 0
  const rebalTotal = getRebalTotal(rebalTargetUsd, marginTargetPct, stockGrossUsd, marginUsd, marginTargetUsd)

  const liveBySymbol = new Map(
    (lastStockDisplay?.stocks ?? []).map(s => [s.symbol, s])
  )
  const allocBySymbol = new Map(
    (lastGroupAllocData?.stocks ?? []).map(s => [s.symbol, s])
  )

  const fmt = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatCurrency(convertFromUsd(usd, fxRates, currentDisplayCurrency))
      : '—'
  const fmtSignedDisplay = (usd: number) =>
    hasFxRate(fxRates, currentDisplayCurrency)
      ? formatSignedCurrency(convertFromUsd(usd, fxRates, currentDisplayCurrency))
      : '—'

  const stockBySym = new Map(stocks.map(s => [s.label, s]))

  // ── Build group map ──────────────────────────────────────────────────────
  const groupMap = new Map<string, GroupEntry>()

  for (const stock of stocks) {
    if (!stock.groups) continue
    const sym = stock.label
    const live = liveBySymbol.get(sym) ?? null
    const groupEntries = parseGroupsAttr(stock.groups, sym)
    const targetWeight = stock.targetWeight ?? 0
    const qty = stock.amount
    const stockCcy = live?.currency ?? 'USD'
    const fxRate = fxRates[stockCcy] ?? null
    const mktValUsd = live?.positionValueUsd ?? null
    const prevMktValUsd = live?.closePrice != null && fxRate !== null
      ? live.closePrice * qty * fxRate : null

    for (const { multiplier, name } of groupEntries) {
      if (!groupMap.has(name)) {
        groupMap.set(name, { mktVal: 0, prevMktVal: 0, targetWeight: 0, members: [] })
      }
      const g = groupMap.get(name)!
      if (mktValUsd !== null) g.mktVal += mktValUsd * multiplier
      if (prevMktValUsd !== null) g.prevMktVal += prevMktValUsd * multiplier
      g.targetWeight += targetWeight * multiplier
      if (!g.members.includes(sym)) g.members.push(sym)
    }
  }

  // ── Group row per-member hover tooltip ────────────────────────────────────
  useEffect(() => {
    let groupTooltip: HTMLElement | null = null
    function ensureTooltip() {
      if (!groupTooltip) {
        groupTooltip = document.createElement('div')
        groupTooltip.id = 'group-hover-tooltip'
        document.body.appendChild(groupTooltip)
      }
      return groupTooltip
    }
    function getCol(td: Element | null) {
      if (!td) return 'name'
      if (td.classList.contains('alloc-column'))    return 'alloc'
      if (td.classList.contains('rebal-column'))    return 'rebal'
      if (td.classList.contains('col-moreinfo'))    return 'mktval'
      if (td.classList.contains('col-market-data')) return 'daypct'
      if (td.classList.contains('price-change'))    return 'mktvalchg'
      if (td.classList.contains('value'))           return 'weight'
      return 'name'
    }
    function buildTooltipHtml(row: HTMLElement, col: string) {
      const members = (row.dataset.groupMembers ?? '').split(',').filter(Boolean)
      if (!members.length) return null
      // memberCols is pre-computed during render and stored as a data attribute
      const memberCols: Record<string, Record<string, string>> = JSON.parse(row.dataset.memberCols ?? '{}')
      const na = `<span class="group-tooltip-na">—</span>`
      const rows = members.map(sym => {
        const valHtml = col !== 'name' ? (memberCols[sym]?.[col] ?? na) : ''
        const valCell = valHtml ? `<td class="group-tooltip-alloc">${valHtml}</td>` : ''
        return `<tr><td class="group-tooltip-symbol">${sym}</td>${valCell}</tr>`
      }).join('')
      return `<table>${rows}</table>`
    }
    function positionTooltip(e: MouseEvent) {
      if (!groupTooltip) return
      const offset = 14
      const tw = groupTooltip.offsetWidth
      const th = groupTooltip.offsetHeight
      let x = e.clientX + offset
      let y = e.clientY + offset
      if (x + tw > window.innerWidth  - 8) x = e.clientX - tw - offset
      if (y + th > window.innerHeight - 8) y = e.clientY - th - offset
      groupTooltip.style.left = x + 'px'
      groupTooltip.style.top  = y + 'px'
    }
    function showTooltip(row: HTMLElement, e: MouseEvent) {
      const col = getCol((e.target as Element).closest('td'))
      const html = buildTooltipHtml(row, col)
      if (!html) return
      const tip = ensureTooltip()
      tip.innerHTML = html
      tip.dataset.hoverCol = col
      tip.style.display = 'block'
      positionTooltip(e)
    }
    function hideTooltip() {
      if (groupTooltip) {
        groupTooltip.style.display = 'none'
        groupTooltip.dataset.hoverCol = ''
      }
    }
    function onEnter(e: Event) {
      const row = (e.target as Element).closest('tr[data-group-name]') as HTMLElement | null
      if (row) showTooltip(row, e as MouseEvent)
    }
    function onMove(e: Event) {
      const me = e as MouseEvent
      const row = (e.target as Element).closest('tr[data-group-name]') as HTMLElement | null
      if (!row) return
      const cur = getCol((e.target as Element).closest('td'))
      const prev = groupTooltip?.dataset.hoverCol ?? ''
      if (cur !== prev) {
        showTooltip(row, me)
      } else {
        positionTooltip(me)
      }
    }
    const table = document.getElementById('group-view-table')
    if (!table) return
    table.addEventListener('mouseenter', onEnter, true)
    table.addEventListener('mousemove', onMove)
    table.addEventListener('mouseleave', hideTooltip)
    return () => {
      table.removeEventListener('mouseenter', onEnter, true)
      table.removeEventListener('mousemove', onMove)
      table.removeEventListener('mouseleave', hideTooltip)
      groupTooltip?.remove()
      groupTooltip = null
    }
  }, [groupMap.size])

  if (groupMap.size === 0) return null

  // ── Pre-compute per-member tooltip HTML for each group row ────────────────
  const na = `<span class="group-tooltip-na">—</span>`
  function buildMemberCols(members: string[]): Record<string, Record<string, string>> {
    const result: Record<string, Record<string, string>> = {}
    for (const sym of members) {
      const live = liveBySymbol.get(sym) ?? null
      const stock = stockBySym.get(sym)
      const targetWeight = stock?.targetWeight ?? 0
      const stockCcy = live?.currency ?? 'USD'
      const fxRate = fxRates[stockCcy] ?? null
      const posVal = live?.positionValueUsd ?? null

      // daypct: mark price + day % (mirrors mark-${sym} cell)
      const markPrice = live?.markPrice ?? null
      const dayPct = live?.dayChangePct ?? null
      const isAfterHours = live?.isMarketClosed ?? false
      const dayPctCls = `${dayPct === null ? '' : dayPct > 0 ? 'positive' : dayPct < 0 ? 'negative' : 'neutral'}${isAfterHours ? ' after-hours' : ''}`
      const dayPctStr = dayPct !== null ? `${dayPct >= 0 ? '+' : ''}${dayPct.toFixed(2)}%` : ''
      const markStr = markPrice !== null ? formatCurrency(markPrice) : '—'
      const daypctHtml = `<span class="mark-price-value">${markStr}</span>${dayPctStr ? `<span class="mark-day-pct ${dayPctCls}">${dayPctStr}</span>` : ''}`

      // mktvalchg: position P&L (mirrors position-change-${sym} cell)
      const dayCh = live?.dayChangeNative ?? null
      const liveQty = live?.qty ?? null
      const pnlUsd = dayCh !== null && liveQty !== null && fxRate !== null ? dayCh * liveQty * fxRate : null
      const pnlStr = pnlUsd !== null && hasFxRate(fxRates, currentDisplayCurrency)
        ? formatSignedCurrency(convertFromUsd(pnlUsd, fxRates, currentDisplayCurrency)) : null
      const pnlCls = pnlUsd === null ? 'neutral' : pnlUsd > 0 ? 'positive' : pnlUsd < 0 ? 'negative' : 'neutral'
      const mktvalchgHtml = pnlStr ? `<span class="price-change ${pnlCls}">${pnlStr}</span>` : na

      // mktval: position value in display currency
      const mktvalHtml = posVal !== null ? fmt(posVal) : '—'

      // weight: cur/tgt/diff pill (mirrors current-weight-${sym} cell)
      let weightHtml = na
      if (stockGrossKnown && stockGrossUsd > 0 && posVal !== null) {
        const curWeight = (posVal / stockGrossUsd) * 100
        const wDiff = curWeight - targetWeight
        const diffCls = weightDiffCls(wDiff)
        const pillSign = wDiff >= 0 ? '+' : ''
        weightHtml = `<span class="weight-cur">${curWeight.toFixed(1)}%</span><span class="weight-sep">/</span><span class="weight-tgt">${targetWeight.toFixed(1)}%</span><span class="weight-diff ${diffCls}">${pillSign}${wDiff.toFixed(1)}%</span>`
      }

      // rebal: rebal dollars in native currency (mirrors rebal-dollars-${sym} cell)
      let rebalHtml = na
      if (stockGrossKnown && targetWeight > 0 && fxRate !== null && posVal !== null) {
        const rebalDollars = (targetWeight / 100) * rebalTotal - posVal
        const rebalStr = formatSignedCurrency(rebalDollars / fxRate)
        const cls = Math.abs(rebalDollars) <= 0.5 ? 'action-neutral' : rebalDollars > 0 ? 'action-positive' : 'action-negative'
        rebalHtml = `<span class="${cls}">${rebalStr}</span>`
      }

      // alloc: alloc dollars in display currency
      const allocDollars = allocBySymbol.get(sym)?.allocDollars ?? null
      let allocHtml = na
      if (allocDollars !== null && hasFxRate(fxRates, currentDisplayCurrency)) {
        const allocStr = fmtSignedDisplay(allocDollars)
        const cls = allocDollars > 0.5 ? 'action-positive' : allocDollars < -0.5 ? 'action-negative' : 'action-neutral'
        allocHtml = `<span class="${cls}">${allocStr}</span>`
      }

      result[sym] = { daypct: daypctHtml, mktvalchg: mktvalchgHtml, mktval: mktvalHtml, weight: weightHtml, rebal: rebalHtml, alloc: allocHtml }
    }
    return result
  }

  return (
    <>
      <p style={{ fontSize: 'var(--font-size-md)', opacity: 0.7, margin: '0.5rem 0 0.75rem' }}>
        ⚠︎ Group values should be interpreted cautiously — their meaning depends heavily on how groups are defined.
      </p>
      <table className="portfolio-table" id="group-view-table">
        <thead>
          <tr>
            <th>Group</th>
            <th className="col-num col-market-data">CHG %</th>
            <th className="col-num col-market-data">P&amp;L</th>
            <th className="col-num col-market-data col-moreinfo">Mkt Val</th>
            <th className="col-num">
              Weight <span className="th-sub">Cur / Tgt / Dev</span>
            </th>
            <th className="rebal-column">Rebal💰</th>
            <th className="alloc-column">Alloc💰</th>
          </tr>
        </thead>
        <tbody>
          {Array.from(groupMap.entries()).map(([name, g]) => {
            const mktValChg = g.mktVal - g.prevMktVal
            const isZeroChg = Math.abs(mktValChg) < 0.01
            const chgCls = isZeroChg ? 'neutral' : mktValChg > 0 ? 'positive' : 'negative'

            const dayPct = g.prevMktVal > 0 ? (mktValChg / g.prevMktVal) * 100 : 0
            const isNeutralDayPct = Math.abs(dayPct) < 0.1
            const dayPctCls = isNeutralDayPct ? 'neutral' : dayPct > 0 ? 'positive' : 'negative'

            // Group alloc = sum of per-symbol allocs
            const groupAllocUsd = g.members.reduce((sum, sym) => {
              return sum + (allocBySymbol.get(sym)?.allocDollars ?? 0)
            }, 0)

            if (!stockGrossKnown || stockGrossUsd <= 0) {
              return (
                <tr key={name} data-group-name={name} data-group-members={g.members.join(',')} data-member-cols={JSON.stringify(buildMemberCols(g.members))}>
                  <td>{name}</td>
                  <td className={`col-num col-market-data price-change ${dayPctCls}`}>
                    <span className={`mark-day-pct ${dayPctCls}`}>
                      {dayPct >= 0 ? '+' : '−'}{Math.abs(dayPct).toFixed(2)}%
                    </span>
                  </td>
                  <td className={`price-change ${chgCls}`}>
                    {isZeroChg ? '—' : fmtSignedDisplay(mktValChg)}
                  </td>
                  <td className="col-num col-market-data value col-moreinfo">{fmt(g.mktVal)}</td>
                  <td className="col-num value">N/A</td>
                  <td className="action-neutral rebal-column">N/A</td>
                  <td className="action-neutral alloc-column">N/A</td>
                </tr>
              )
            }
            const memberColsJson = JSON.stringify(buildMemberCols(g.members))

            const weightPct = (g.mktVal / stockGrossUsd) * 100
            const targetWeightPct = g.targetWeight
            const weightDiff = weightPct - targetWeightPct
            const rebalDollars = (targetWeightPct / 100) * rebalTotal - g.mktVal
            const diffCls = weightDiffCls(weightDiff)
            const pillSign = weightDiff >= 0 ? '+' : ''

            return (
              <tr key={name} data-group-name={name} data-group-members={g.members.join(',')} data-member-cols={memberColsJson}>
                <td>{name}</td>
                <td className={`col-num col-market-data price-change ${dayPctCls}`}>
                  <span className={`mark-day-pct ${dayPctCls}`}>
                    {dayPct >= 0 ? '+' : '−'}{Math.abs(dayPct).toFixed(2)}%
                  </span>
                </td>
                <td className={`price-change ${chgCls}`}>
                  {isZeroChg ? '—' : fmtSignedDisplay(mktValChg)}
                </td>
                <td className="col-num col-market-data value col-moreinfo">{fmt(g.mktVal)}</td>
                <td className="col-num value">
                  <span className="weight-cur">{weightPct.toFixed(1)}%</span>
                  <span className="weight-sep">/</span>
                  <span className="weight-tgt">{targetWeightPct.toFixed(1)}%</span>
                  <span className={`weight-diff ${diffCls}`}>{pillSign}{weightDiff.toFixed(1)}%</span>
                </td>
                <td className={`action-neutral ${actionCls(rebalDollars)} rebal-column`}>
                  {fmtSignedDisplay(rebalDollars)}
                </td>
                <td className={`action-neutral ${actionCls(groupAllocUsd)} alloc-column`}>
                  {fmtSignedDisplay(groupAllocUsd)}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </>
  )
}
