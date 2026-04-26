// ── LoanPage.tsx — Loan calculator (base + update mode) ───────────────────────

import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'

// ── Math ──────────────────────────────────────────────────────────────────────

function npv(rate: number, cashFlows: number[]): number {
  return cashFlows.reduce((sum, cf, t) => sum + cf / Math.pow(1 + rate, t), 0)
}

function xnpv(rate: number, cfs: { amount: number; t: number }[]): number {
  return cfs.reduce((sum, cf) => sum + cf.amount / Math.pow(1 + rate, cf.t), 0)
}

function brent(f: (x: number) => number, lo: number, hi: number): number | null {
  let fLo = f(lo), fHi = f(hi)
  if (fLo * fHi > 0) return null
  let a = lo, b = hi, fa = fLo, fb = fHi
  let c = a, fc = fa, mflag = true, s = 0, d = 0
  const tol = 1e-10
  for (let i = 0; i < 200; i++) {
    if (Math.abs(b - a) < tol) break
    if (fa !== fc && fb !== fc) {
      s = (a * fb * fc) / ((fa - fb) * (fa - fc))
        + (b * fa * fc) / ((fb - fa) * (fb - fc))
        + (c * fa * fb) / ((fc - fa) * (fc - fb))
    } else {
      s = b - fb * (b - a) / (fb - fa)
    }
    const m = (3 * a + b) / 4
    const cond1 = !((m < s && s < b) || (b < s && s < m))
    const cond2 =  mflag && Math.abs(s - b) >= Math.abs(b - c) / 2
    const cond3 = !mflag && Math.abs(s - b) >= Math.abs(c - d) / 2
    const cond4 =  mflag && Math.abs(b - c) < tol
    const cond5 = !mflag && Math.abs(c - d) < tol
    if (cond1 || cond2 || cond3 || cond4 || cond5) { s = (a + b) / 2; mflag = true } else { mflag = false }
    const fs = f(s)
    d = c; c = b; fc = fb
    if (fa * fs < 0) { b = s; fb = fs } else { a = s; fa = fs }
    if (Math.abs(fa) < Math.abs(fb)) {
      let tmp = a; a = b; b = tmp; tmp = fa; fa = fb; fb = tmp
    }
  }
  return b
}

function findIRR(cashFlows: number[]): number | null {
  return brent(r => npv(r, cashFlows), -0.9999, 100.0)
}

function findXIRR(cfs: { amount: number; t: number }[]): number | null {
  return brent(r => xnpv(r, cfs), -0.9999, 100.0)
}

// ── History helpers ───────────────────────────────────────────────────────────

const PPY_LABELS: Record<string, string> = {
  '365': 'Daily', '52': 'Weekly', '26': 'Bi-wkly', '12': 'Mo', '4': 'Qtr', '2': 'Semi-yr', '1': 'Yr',
}

interface LoanParams {
  loanAmount: string
  numPeriods: string
  periodLength: string
  payment: string
  rateApy: string
  rateFlat: string
  extraCashflows: { amount: number; period: number }[]
  savedAt?: number
  // Update mode
  mode?: 'base' | 'update'
  totalNewLoanAmount?: string
  existingRemainingPeriods?: string
  existingPaymentAmt?: string
  existingCfRows?: { amount: number; period: number }[]
  existingPeriodLength?: string
  loanReceiveDate?: string
  nextExistingPaymentDate?: string
  firstNewPaymentDate?: string
}

function entryLabel(p: LoanParams): string {
  const loan = parseFloat(p.loanAmount)
  const loanFmt = isNaN(loan)
    ? p.loanAmount
    : loan.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 0, maximumFractionDigits: 0 })
  const ppyLabel = PPY_LABELS[p.periodLength] || `${p.periodLength}/yr`
  if (p.mode === 'update') {
    const existPer = p.existingRemainingPeriods
    return `[Upd] ${loanFmt} · ${p.numPeriods}×${ppyLabel}${existPer ? ` from ${existPer}×${ppyLabel}` : ''}`
  }
  const rateInfo = p.rateApy  ? `${p.rateApy}% APY`
    : p.rateFlat ? `${p.rateFlat}% flat`
    : p.payment  ? `$${parseFloat(p.payment).toFixed(2)}/period`
    : ''
  return `${loanFmt} · ${p.numPeriods}×${ppyLabel}${rateInfo ? ' · ' + rateInfo : ''}`
}

// ── Shared sub-components ─────────────────────────────────────────────────────

interface CfRow { id: string; amount: string; period: string }

let _cfId = 0
function newCfId() { return String(++_cfId) }

interface CfSectionProps {
  label: string
  hint: string
  rows: CfRow[]
  setRows: Dispatch<SetStateAction<CfRow[]>>
  onModify: () => void
}

function CfSection({ label, hint, rows, setRows, onModify }: CfSectionProps) {
  return (
    <div className="extra-cashflows">
      <div className="extra-cashflows-header">
        <span>{label}</span>
        <button
          className="add-cashflow-btn" type="button"
          onClick={() => setRows(r => [...r, { id: newCfId(), amount: '', period: '' }])}
        >+ Add</button>
      </div>
      <p className="cashflow-hint">{hint}</p>
      <div>
        {rows.map(row => (
          <div key={row.id} className="cashflow-row">
            <input
              type="number" className="cf-amount" placeholder="Amount" step="any"
              value={row.amount}
              onChange={e => { setRows(rs => rs.map(r => r.id === row.id ? { ...r, amount: e.target.value } : r)); onModify() }}
            />
            <span className="cf-label">at period</span>
            <input
              type="number" className="cf-period" placeholder="Period" min={0} step={1}
              value={row.period}
              onChange={e => { setRows(rs => rs.map(r => r.id === row.id ? { ...r, period: e.target.value } : r)); onModify() }}
            />
            <button
              type="button" className="cf-remove" aria-label="Remove row"
              onClick={() => { setRows(rs => rs.filter(r => r.id !== row.id)); onModify() }}
            >✕</button>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Component ─────────────────────────────────────────────────────────────────

interface LoanResults {
  periodicRate: number
  apr: number
  apy: number
  flatRate: number
  payment: number
  totalPayments: number
  totalInterest: number
}

export default function LoanPage() {
  // Base + shared
  const [mode,         setMode]         = useState<'base' | 'update'>('base')
  const [loanAmount,   setLoanAmount]   = useState('')
  const [numPeriods,   setNumPeriods]   = useState('')
  const [periodLength, setPeriodLength] = useState('12')
  const [payment,      setPayment]      = useState('')
  const [rateApy,      setRateApy]      = useState('')
  const [rateFlat,     setRateFlat]     = useState('')
  const [cfRows,       setCfRows]       = useState<CfRow[]>([])
  // Update mode
  const [totalNewLoanAmount,       setTotalNewLoanAmount]       = useState('')
  const [existingRemainingPeriods, setExistingRemainingPeriods] = useState('')
  const [existingPaymentAmt,       setExistingPaymentAmt]       = useState('')
  const [existingPeriodLength,     setExistingPeriodLength]     = useState('12')
  const [existingCfRows,           setExistingCfRows]           = useState<CfRow[]>([])
  const [loanReceiveDate,          setLoanReceiveDate]          = useState('')
  const [nextExistingPaymentDate,  setNextExistingPaymentDate]  = useState('')
  const [firstNewPaymentDate,      setFirstNewPaymentDate]      = useState('')
  // Output
  const [results, setResults] = useState<LoanResults | null>(null)
  const [error,   setError]   = useState('')
  const [history, setHistory] = useState<LoanParams[]>([])

  useEffect(() => { loadHistory() }, [])

  async function loadHistory() {
    try {
      const res = await fetch('/api/loan/history')
      const data: LoanParams[] = await res.json()
      setHistory(data || [])
      if (data && data.length > 0) {
        applyParams(data[0])
        setTimeout(() => calculate(data[0], false), 0)
      }
    } catch (_) {}
  }

  function applyParams(p: LoanParams) {
    setLoanAmount(p.loanAmount || '')
    setNumPeriods(p.numPeriods || '')
    setPeriodLength(p.periodLength || '12')
    setPayment(p.payment || '')
    setRateApy(p.rateApy || '')
    setRateFlat(p.rateFlat || '')
    setCfRows((p.extraCashflows || []).map(cf => ({ id: newCfId(), amount: String(cf.amount), period: String(cf.period) })))
    const m = p.mode || 'base'
    setMode(m)
    if (m === 'update') {
      setTotalNewLoanAmount(p.totalNewLoanAmount || '')
      setExistingRemainingPeriods(p.existingRemainingPeriods || '')
      setExistingPaymentAmt(p.existingPaymentAmt || '')
      setExistingPeriodLength(p.existingPeriodLength || '12')
      setExistingCfRows((p.existingCfRows || []).map(cf => ({ id: newCfId(), amount: String(cf.amount), period: String(cf.period) })))
      setLoanReceiveDate(p.loanReceiveDate || '')
      setNextExistingPaymentDate(p.nextExistingPaymentDate || '')
      setFirstNewPaymentDate(p.firstNewPaymentDate || '')
    }
  }

  function resolveNewPay(
    la: number, np: number, ppy: number,
    payV: string, apyV: string, flatV: string,
  ): { pay: number } | { err: string } {
    if (apyV !== '') {
      const apy = parseFloat(apyV) / 100
      if (isNaN(apy) || apy < 0) return { err: 'Enter a valid APY %.' }
      const r = Math.pow(1 + apy, 1 / ppy) - 1
      return { pay: r === 0 ? la / np : la * r / (1 - Math.pow(1 + r, -np)) }
    }
    if (flatV !== '') {
      const flat = parseFloat(flatV) / 100
      if (isNaN(flat) || flat < 0) return { err: 'Enter a valid Flat Rate %.' }
      return { pay: la * (1 + flat * np) / np }
    }
    const pay = parseFloat(payV)
    if (isNaN(pay) || pay < 0) return { err: 'Enter a valid payment amount ≥ 0.' }
    return { pay }
  }

  function calculate(override?: LoanParams, save = true) {
    const m = override?.mode ?? mode
    if (m === 'update') { calculateUpdate(override, save); return }

    setError('')
    setResults(null)

    const la    = parseFloat(override?.loanAmount  ?? loanAmount)
    const np    = parseInt(override?.numPeriods    ?? numPeriods, 10)
    const ppy   = parseInt(override?.periodLength  ?? periodLength, 10)
    const payV  = (override?.payment  ?? payment).trim()
    const apyV  = (override?.rateApy  ?? rateApy).trim()
    const flatV = (override?.rateFlat ?? rateFlat).trim()

    if (isNaN(la) || la <= 0) { setError('Enter a valid loan amount > 0.'); return }
    if (isNaN(np) || np < 1)  { setError('Number of periods must be at least 1.'); return }

    const resolved = resolveNewPay(la, np, ppy, payV, apyV, flatV)
    if ('err' in resolved) { setError(resolved.err); return }
    const pay = resolved.pay

    const cashFlows = new Array(np + 1).fill(0)
    cashFlows[0] = la
    for (let t = 1; t <= np; t++) cashFlows[t] = -pay

    const activeCfRows = override
      ? (override.extraCashflows || []).map(cf => ({ amount: String(cf.amount), period: String(cf.period) }))
      : cfRows
    let extraPos = 0
    activeCfRows.forEach(row => {
      const amt = parseFloat(row.amount)
      const per = parseInt(row.period, 10)
      if (!isNaN(amt) && !isNaN(per) && per >= 0 && per <= np) {
        cashFlows[per] += amt
        if (amt > 0) extraPos += amt
      }
    })

    const r = findIRR(cashFlows)
    if (r === null || !isFinite(r)) {
      setError('Could not solve for rate. Check that total payments exceed the loan amount.')
      return
    }

    const apr = r * ppy
    const apy = Math.pow(1 + r, ppy) - 1
    const flatRate = (pay * np - la) / (la * np)
    const totalPayments = pay * np - extraPos
    const totalInterest = totalPayments - la

    setResults({ periodicRate: r, apr, apy, flatRate, payment: pay, totalPayments, totalInterest })
    if (save) saveHistory()
  }

  function calculateUpdate(override?: LoanParams, save = true) {
    setError('')
    setResults(null)

    const P         = parseInt(override?.existingRemainingPeriods ?? existingRemainingPeriods, 10)
    const existPay  = parseFloat(override?.existingPaymentAmt ?? existingPaymentAmt)
    if (isNaN(P)        || P < 1)         { setError('Existing remaining periods must be at least 1.'); return }
    if (isNaN(existPay) || existPay <= 0) { setError('Enter a valid existing payment amount > 0.'); return }

    const la      = parseFloat(override?.loanAmount  ?? loanAmount)
    const np      = parseInt(override?.numPeriods    ?? numPeriods, 10)
    const ppy     = parseInt(override?.periodLength  ?? periodLength, 10)
    const existPpy = parseInt(override?.existingPeriodLength ?? existingPeriodLength, 10)
    const payV  = (override?.payment  ?? payment).trim()
    const apyV  = (override?.rateApy  ?? rateApy).trim()
    const flatV = (override?.rateFlat ?? rateFlat).trim()
    const tnla  = parseFloat(override?.totalNewLoanAmount ?? totalNewLoanAmount)

    if (isNaN(la) || la <= 0) { setError('Enter a valid cash received amount > 0.'); return }
    if (isNaN(np) || np < 1)  { setError('Number of periods must be at least 1.'); return }
    if ((apyV !== '' || flatV !== '') && (isNaN(tnla) || tnla <= 0)) {
      setError('Enter Total New Loan Amount when using a rate (APY % or Flat Rate).')
      return
    }

    // Payment is computed from the full new principal; falls back to cash received when not set
    const payBase = !isNaN(tnla) && tnla > 0 ? tnla : la
    const resolved = resolveNewPay(payBase, np, ppy, payV, apyV, flatV)
    if ('err' in resolved) { setError(resolved.err); return }
    const newPay = resolved.pay

    const totalReceived = la

    const lrd  = override?.loanReceiveDate        ?? loanReceiveDate
    const nepd = override?.nextExistingPaymentDate ?? nextExistingPaymentDate
    const fnpd = override?.firstNewPaymentDate     ?? firstNewPaymentDate

    const activeExistCfRows = override
      ? (override.existingCfRows || []).map(cf => ({ id: '', amount: String(cf.amount), period: String(cf.period) }))
      : existingCfRows
    const activeNewCfRows = override
      ? (override.extraCashflows || []).map(cf => ({ id: '', amount: String(cf.amount), period: String(cf.period) }))
      : cfRows

    if (lrd && nepd) {
      // ── XIRR path — date-accurate ──────────────────────────────────────────
      const MS_PER_DAY    = 86_400_000
      const periodDays    = 365.25 / ppy
      const existPeriodDays = 365.25 / existPpy
      const refMs         = new Date(lrd).getTime()
      const existFirstMs  = new Date(nepd).getTime()
      const newFirstMs    = fnpd
        ? new Date(fnpd).getTime()
        : refMs + periodDays * MS_PER_DAY

      const cfs: { amount: number; t: number }[] = []
      cfs.push({ amount: totalReceived, t: 0 })

      for (let i = 0; i < P; i++) {
        const t = (existFirstMs + i * existPeriodDays * MS_PER_DAY - refMs) / (365.25 * MS_PER_DAY)
        cfs.push({ amount: existPay, t })
      }
      activeExistCfRows.forEach(row => {
        const amt = parseFloat(row.amount)
        const per = parseInt(row.period, 10)
        if (!isNaN(amt) && !isNaN(per) && per >= 1) {
          const t = (existFirstMs + (per - 1) * existPeriodDays * MS_PER_DAY - refMs) / (365.25 * MS_PER_DAY)
          cfs.push({ amount: amt, t })
        }
      })

      for (let i = 1; i <= np; i++) {
        const t = (newFirstMs + (i - 1) * periodDays * MS_PER_DAY - refMs) / (365.25 * MS_PER_DAY)
        cfs.push({ amount: -newPay, t })
      }
      activeNewCfRows.forEach(row => {
        const amt = parseFloat(row.amount)
        const per = parseInt(row.period, 10)
        if (!isNaN(amt) && !isNaN(per) && per >= 1 && per <= np) {
          const t = (newFirstMs + (per - 1) * periodDays * MS_PER_DAY - refMs) / (365.25 * MS_PER_DAY)
          cfs.push({ amount: amt, t })
        }
      })

      cfs.sort((a, b) => a.t - b.t)

      const annualRate = findXIRR(cfs)
      if (annualRate === null || !isFinite(annualRate)) {
        setError('Could not solve for rate. Check that total payments exceed the loan amount.')
        return
      }

      const apy          = annualRate
      const periodicRate = Math.pow(1 + apy, 1 / ppy) - 1
      const apr          = periodicRate * ppy
      const flatRate     = (newPay * np - totalReceived) / (totalReceived * np)
      const totalPayments = newPay * np
      const totalInterest = totalPayments - totalReceived

      setResults({ periodicRate, apr, apy, flatRate, payment: newPay, totalPayments, totalInterest })
    } else {
      // ── Integer-period path (k=0, payments assumed aligned) ────────────────
      const len = Math.max(P, np) + 1
      const cashFlows = new Array(len).fill(0)
      cashFlows[0] = totalReceived
      for (let i = 1; i <= P;  i++) cashFlows[i] += existPay
      for (let i = 1; i <= np; i++) cashFlows[i] -= newPay

      activeExistCfRows.forEach(row => {
        const amt = parseFloat(row.amount)
        const per = parseInt(row.period, 10)
        if (!isNaN(amt) && !isNaN(per) && per >= 0 && per < len) cashFlows[per] += amt
      })
      activeNewCfRows.forEach(row => {
        const amt = parseFloat(row.amount)
        const per = parseInt(row.period, 10)
        if (!isNaN(amt) && !isNaN(per) && per >= 0 && per < len) cashFlows[per] += amt
      })

      const r = findIRR(cashFlows)
      if (r === null || !isFinite(r)) {
        setError('Could not solve for rate. Check that total payments exceed the loan amount.')
        return
      }

      const apr       = r * ppy
      const apy       = Math.pow(1 + r, ppy) - 1
      const flatRate  = (newPay * np - totalReceived) / (totalReceived * np)
      const totalPayments = newPay * np
      const totalInterest = totalPayments - totalReceived

      setResults({ periodicRate: r, apr, apy, flatRate, payment: newPay, totalPayments, totalInterest })
    }

    if (save) saveHistory()
  }

  async function saveHistory() {
    const params: LoanParams = {
      loanAmount, numPeriods, periodLength, payment, rateApy, rateFlat,
      extraCashflows: cfRows
        .map(r => ({ amount: parseFloat(r.amount), period: parseInt(r.period, 10) }))
        .filter(r => !isNaN(r.amount) && !isNaN(r.period)),
      savedAt: Date.now(),
    }
    if (mode === 'update') {
      params.mode = 'update'
      params.totalNewLoanAmount = totalNewLoanAmount
      params.existingRemainingPeriods = existingRemainingPeriods
      params.existingPaymentAmt = existingPaymentAmt
      params.existingCfRows = existingCfRows
        .map(r => ({ amount: parseFloat(r.amount), period: parseInt(r.period, 10) }))
        .filter(r => !isNaN(r.amount) && !isNaN(r.period))
      params.existingPeriodLength = existingPeriodLength
      params.loanReceiveDate = loanReceiveDate
      params.nextExistingPaymentDate = nextExistingPaymentDate
      params.firstNewPaymentDate = firstNewPaymentDate
    }
    try {
      await fetch('/api/loan/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      })
      const res = await fetch('/api/loan/history')
      const data: LoanParams[] = await res.json()
      setHistory(data || [])
    } catch (_) {}
  }

  function fmtPct(v: number) { return (v * 100).toFixed(4) + '%' }
  function fmtCur(v: number) { return v.toLocaleString('en-US', { style: 'currency', currency: 'USD' }) }
  const clearResults = () => setResults(null)

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/loan" />
        </div>
        <HeaderRight>
          <PrivacyToggleButton />
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      {/* History */}
      {history.length > 0 && (
        <div className="loan-history-list">
          {history.map((p, i) => (
            <div key={i} className="loan-history-entry">
              <span className="loan-history-label">{entryLabel(p)}</span>
              <button
                type="button"
                className="loan-history-load-btn"
                onClick={() => { applyParams(p); setTimeout(() => calculate(p, false), 0) }}
              >Load</button>
            </div>
          ))}
        </div>
      )}

      <div className="loan-card">
        {/* Mode toggle */}
        <div className="loan-mode-toggle">
          <button
            type="button"
            className={`loan-mode-btn${mode === 'base' ? ' active' : ''}`}
            onClick={() => { setMode('base'); setResults(null) }}
          >Base</button>
          <button
            type="button"
            className={`loan-mode-btn${mode === 'update' ? ' active' : ''}`}
            onClick={() => { setMode('update'); setResults(null) }}
          >Update</button>
        </div>

        {mode === 'base' ? (
          <>
            <div className="loan-inputs">
              <div className="loan-col-left">
                <label htmlFor="loan-amount">Loan Amount</label>
                <input
                  type="number" id="loan-amount" placeholder="100000" min={0} step="any"
                  value={loanAmount}
                  onChange={e => { setLoanAmount(e.target.value); clearResults() }}
                />
                <label htmlFor="num-periods">Number of Periods</label>
                <input
                  type="number" id="num-periods" placeholder="360" min={1}
                  value={numPeriods}
                  onChange={e => { setNumPeriods(e.target.value); clearResults() }}
                />
                <label htmlFor="period-length">Period Length</label>
                <select
                  id="period-length"
                  value={periodLength}
                  onChange={e => { setPeriodLength(e.target.value); clearResults() }}
                >
                  <option value="365">Daily</option>
                  <option value="52">Weekly</option>
                  <option value="26">Bi-weekly</option>
                  <option value="12">Monthly</option>
                  <option value="4">Quarterly</option>
                  <option value="2">Semi-annually</option>
                  <option value="1">Annually</option>
                </select>
              </div>

              <div className="loan-col-right">
                <fieldset className="loan-exclusive-group">
                  <legend>enter one</legend>
                  <label htmlFor="payment">Payment / Period</label>
                  <input
                    type="number" id="payment" placeholder="536.82" min={0} step="any"
                    value={payment}
                    onChange={e => { setPayment(e.target.value); setRateApy(''); setRateFlat(''); clearResults() }}
                  />
                  <hr />
                  <label htmlFor="rate-apy">Annual Rate (APY %)</label>
                  <input
                    type="number" id="rate-apy" placeholder="6.168" min={0} step="any"
                    value={rateApy}
                    onChange={e => { setRateApy(e.target.value); setPayment(''); setRateFlat(''); clearResults() }}
                  />
                  <hr />
                  <label htmlFor="rate-flat">Flat Rate (% total)</label>
                  <input
                    type="number" id="rate-flat" placeholder="0.25" min={0} step="any"
                    value={rateFlat}
                    onChange={e => { setRateFlat(e.target.value); setPayment(''); setRateApy(''); clearResults() }}
                  />
                </fieldset>
              </div>
            </div>

            <div className="extra-cashflows">
              <div className="extra-cashflows-header">
                <span>Extra Cash Flows</span>
                <button
                  className="add-cashflow-btn" id="add-cashflow" type="button"
                  onClick={() => setCfRows(r => [...r, { id: newCfId(), amount: '', period: '' }])}
                >+ Add</button>
              </div>
              <p className="cashflow-hint">positive = extra received (e.g. rebate), negative = extra payment</p>
              <div id="cashflow-rows">
                {cfRows.map(row => (
                  <div key={row.id} className="cashflow-row">
                    <input
                      type="number" className="cf-amount" placeholder="Amount" step="any"
                      value={row.amount}
                      onChange={e => { setCfRows(rs => rs.map(r => r.id === row.id ? { ...r, amount: e.target.value } : r)); clearResults() }}
                    />
                    <span className="cf-label">at period</span>
                    <input
                      type="number" className="cf-period" placeholder="Period" min={0} step={1}
                      value={row.period}
                      onChange={e => { setCfRows(rs => rs.map(r => r.id === row.id ? { ...r, period: e.target.value } : r)); clearResults() }}
                    />
                    <button
                      type="button" className="cf-remove" aria-label="Remove row"
                      onClick={() => { setCfRows(rs => rs.filter(r => r.id !== row.id)); clearResults() }}
                    >✕</button>
                  </div>
                ))}
              </div>
            </div>
          </>
        ) : (
          /* ── Update mode ─────────────────────────────────────────────────── */
          <>
            {/* Section 1: Existing Loan */}
            <div className="loan-section">
              <div className="loan-section-label">Existing Loan</div>
              <div className="loan-inputs">
                <div className="loan-col-left">
                  <label htmlFor="exist-periods">Remaining Periods</label>
                  <input
                    type="number" id="exist-periods" placeholder="120" min={1}
                    value={existingRemainingPeriods}
                    onChange={e => { setExistingRemainingPeriods(e.target.value); clearResults() }}
                  />
                </div>
                <div className="loan-col-right">
                  <label htmlFor="exist-payment">Payment / Period</label>
                  <input
                    type="number" id="exist-payment" placeholder="500.00" min={0} step="any"
                    value={existingPaymentAmt}
                    onChange={e => { setExistingPaymentAmt(e.target.value); clearResults() }}
                  />
                </div>
              </div>
              <div className="loan-inputs">
                <div className="loan-col-left">
                  <label htmlFor="exist-period-length">Period Length</label>
                  <select
                    id="exist-period-length"
                    value={existingPeriodLength}
                    onChange={e => { setExistingPeriodLength(e.target.value); clearResults() }}
                  >
                    <option value="365">Daily</option>
                    <option value="52">Weekly</option>
                    <option value="26">Bi-weekly</option>
                    <option value="12">Monthly</option>
                    <option value="4">Quarterly</option>
                    <option value="2">Semi-annually</option>
                    <option value="1">Annually</option>
                  </select>
                </div>
              </div>
              <CfSection
                label="Extra Cash Flows (existing loan)"
                hint="positive = rebate; negative = extra payment — period 1 = next existing payment"
                rows={existingCfRows}
                setRows={setExistingCfRows}
                onModify={clearResults}
              />
            </div>

            <div className="loan-section-divider" />

            {/* Section 2: New Loan */}
            <div className="loan-section">
              <div className="loan-section-label">New Loan</div>
              <div className="loan-inputs">
                <div className="loan-col-left">
                  <label htmlFor="cash-received">Cash Received</label>
                  <input
                    type="number" id="cash-received" placeholder="50000" min={0} step="any"
                    value={loanAmount}
                    onChange={e => { setLoanAmount(e.target.value); clearResults() }}
                  />
                  <label htmlFor="total-new-loan">Total New Loan Amount</label>
                  <input
                    type="number" id="total-new-loan" placeholder="120000" min={0} step="any"
                    value={totalNewLoanAmount}
                    onChange={e => { setTotalNewLoanAmount(e.target.value); clearResults() }}
                  />
                  <label htmlFor="num-periods-upd">Number of Periods</label>
                  <input
                    type="number" id="num-periods-upd" placeholder="360" min={1}
                    value={numPeriods}
                    onChange={e => { setNumPeriods(e.target.value); clearResults() }}
                  />
                  <label htmlFor="period-length-upd">Period Length</label>
                  <select
                    id="period-length-upd"
                    value={periodLength}
                    onChange={e => { setPeriodLength(e.target.value); clearResults() }}
                  >
                    <option value="365">Daily</option>
                    <option value="52">Weekly</option>
                    <option value="26">Bi-weekly</option>
                    <option value="12">Monthly</option>
                    <option value="4">Quarterly</option>
                    <option value="2">Semi-annually</option>
                    <option value="1">Annually</option>
                  </select>
                </div>
                <div className="loan-col-right">
                  <fieldset className="loan-exclusive-group">
                    <legend>enter one</legend>
                    <label htmlFor="payment-upd">Payment / Period</label>
                    <input
                      type="number" id="payment-upd" placeholder="536.82" min={0} step="any"
                      value={payment}
                      onChange={e => { setPayment(e.target.value); setRateApy(''); setRateFlat(''); setTotalNewLoanAmount(''); clearResults() }}
                    />
                    <hr />
                    <label htmlFor="rate-apy-upd">Annual Rate (APY %)</label>
                    <input
                      type="number" id="rate-apy-upd" placeholder="6.168" min={0} step="any"
                      value={rateApy}
                      onChange={e => { setRateApy(e.target.value); setPayment(''); setRateFlat(''); clearResults() }}
                    />
                    <hr />
                    <label htmlFor="rate-flat-upd">Flat Rate (% total)</label>
                    <input
                      type="number" id="rate-flat-upd" placeholder="0.25" min={0} step="any"
                      value={rateFlat}
                      onChange={e => { setRateFlat(e.target.value); setPayment(''); setRateApy(''); clearResults() }}
                    />
                  </fieldset>
                </div>
              </div>
              <CfSection
                label="Extra Cash Flows (new loan)"
                hint="positive = rebate; negative = extra payment — period 1 = first new loan payment"
                rows={cfRows}
                setRows={setCfRows}
                onModify={clearResults}
              />
            </div>

            <div className="loan-section-divider" />

            {/* Section 3: Dates */}
            <div className="loan-section">
              <div className="loan-section-label">
                Dates <span className="loan-section-optional">(optional — enables date-accurate XIRR)</span>
              </div>
              {/* Row 1: Loan Receive Date | Next Existing Payment */}
              <div className="loan-inputs">
                <div className="loan-col-left">
                  <label htmlFor="loan-receive-date">Loan Receive Date</label>
                  <input
                    type="date" id="loan-receive-date"
                    value={loanReceiveDate}
                    onChange={e => { setLoanReceiveDate(e.target.value); clearResults() }}
                  />
                </div>
                <div className="loan-col-right">
                  <label htmlFor="next-exist-pay-date">Next Existing Payment</label>
                  <input
                    type="date" id="next-exist-pay-date"
                    value={nextExistingPaymentDate}
                    onChange={e => { setNextExistingPaymentDate(e.target.value); clearResults() }}
                  />
                </div>
              </div>
              {/* Row 2: First New Payment Date */}
              <div className="loan-inputs">
                <div className="loan-col-left">
                  <label htmlFor="first-new-pay-date">First New Payment Date</label>
                  <input
                    type="date" id="first-new-pay-date"
                    value={firstNewPaymentDate}
                    onChange={e => { setFirstNewPaymentDate(e.target.value); clearResults() }}
                  />
                  <p className="cashflow-hint" style={{ paddingBottom: '2px' }}>
                    {loanReceiveDate && !firstNewPaymentDate
                      ? `Default: ${(() => { const d = new Date(loanReceiveDate); d.setDate(d.getDate() + Math.round(365.25 / parseInt(periodLength, 10))); return d.toISOString().slice(0, 10) })()}`
                      : 'Defaults to Loan Receive Date + 1 period if blank'}
                  </p>
                </div>
              </div>
            </div>
          </>
        )}

        <p className="cashflow-hint">
          {mode === 'base'
            ? 'Fill in Payment, Annual Rate (APY), or Flat Rate — the others will be cleared automatically.'
            : 'IRR measures the effective rate on total cash received (Loan Proceeds + Entitle Amount).'}
        </p>

        <button
          className="calculate-btn" id="calculate-btn" type="button"
          onClick={() => calculate()}
          onKeyDown={e => { if (e.key === 'Enter') calculate() }}
        >Calculate</button>

        {error && (
          <div className="loan-error" style={{ display: 'block' }}>{error}</div>
        )}

        {results && (
          <div className="loan-results" style={{ display: 'block' }}>
            <div className="result-row"><span>Periodic Rate</span><span>{fmtPct(results.periodicRate)}</span></div>
            <div className="result-row"><span>Nominal APR</span><span>{fmtPct(results.apr)}</span></div>
            <div className="result-row result-highlight"><span>Effective APR (APY)</span><span>{fmtPct(results.apy)}</span></div>
            <div className="result-row"><span>Flat Rate</span><span>{fmtPct(results.flatRate)}</span></div>
            <div className="result-divider" />
            <div className="result-row"><span>Payment / Period</span><span>{fmtCur(results.payment)}</span></div>
            <div className="result-row"><span>Total Payments</span><span>{fmtCur(results.totalPayments)}</span></div>
            <div className="result-row"><span>Total Interest</span><span>{fmtCur(results.totalInterest)}</span></div>
          </div>
        )}
      </div>
    </div>
  )
}
