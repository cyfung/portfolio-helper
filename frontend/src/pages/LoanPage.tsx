// ── LoanPage.tsx — Loan calculator (full React port) ─────────────────────────

import { useEffect, useState } from 'react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'

// ── Math ──────────────────────────────────────────────────────────────────────

function npv(rate: number, cashFlows: number[]): number {
  return cashFlows.reduce((sum, cf, t) => sum + cf / Math.pow(1 + rate, t), 0)
}

function findIRR(cashFlows: number[]): number | null {
  let lo = -0.9999, hi = 100.0
  let fLo = npv(lo, cashFlows), fHi = npv(hi, cashFlows)
  if (fLo * fHi > 0) return null

  let a = lo, b = hi, fa = fLo, fb = fHi
  let c = a, fc = fa, mflag = true
  let s = 0, d = 0
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
    const cond2 = mflag  && Math.abs(s - b) >= Math.abs(b - c) / 2
    const cond3 = !mflag && Math.abs(s - b) >= Math.abs(c - d) / 2
    const cond4 = mflag  && Math.abs(b - c) < tol
    const cond5 = !mflag && Math.abs(c - d) < tol
    if (cond1 || cond2 || cond3 || cond4 || cond5) { s = (a + b) / 2; mflag = true } else { mflag = false }
    const fs = npv(s, cashFlows)
    d = c; c = b; fc = fb
    if (fa * fs < 0) { b = s; fb = fs } else { a = s; fa = fs }
    if (Math.abs(fa) < Math.abs(fb)) {
      let tmp = a; a = b; b = tmp; tmp = fa; fa = fb; fb = tmp
    }
  }
  return b
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
}

function entryLabel(p: LoanParams): string {
  const loan = parseFloat(p.loanAmount)
  const loanFmt = isNaN(loan)
    ? p.loanAmount
    : loan.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 0, maximumFractionDigits: 0 })
  const ppy = p.periodLength
  const ppyLabel = PPY_LABELS[ppy] || `${ppy}/yr`
  const rateInfo = p.rateApy  ? `${p.rateApy}% APY`
    : p.rateFlat ? `${p.rateFlat}% flat`
    : p.payment  ? `$${parseFloat(p.payment).toFixed(2)}/period`
    : ''
  return `${loanFmt} · ${p.numPeriods}×${ppyLabel}${rateInfo ? ' · ' + rateInfo : ''}`
}

// ── Extra cashflow row ────────────────────────────────────────────────────────

interface CfRow { id: string; amount: string; period: string }

let _cfId = 0
function newCfId() { return String(++_cfId) }

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
  const [loanAmount,  setLoanAmount]  = useState('')
  const [numPeriods,  setNumPeriods]  = useState('')
  const [periodLength, setPeriodLength] = useState('12')
  const [payment,     setPayment]     = useState('')
  const [rateApy,     setRateApy]     = useState('')
  const [rateFlat,    setRateFlat]    = useState('')
  const [cfRows,      setCfRows]      = useState<CfRow[]>([])
  const [results,     setResults]     = useState<LoanResults | null>(null)
  const [error,       setError]       = useState('')
  const [history,     setHistory]     = useState<LoanParams[]>([])

  useEffect(() => { loadHistory() }, [])

  async function loadHistory() {
    try {
      const res = await fetch('/api/loan/history')
      const data: LoanParams[] = await res.json()
      setHistory(data || [])
      if (data && data.length > 0) {
        applyParams(data[0])
        setTimeout(() => calculate(data[0]), 0)
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
  }

  function calculate(override?: LoanParams) {
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

    let pay: number
    if (apyV !== '') {
      const apy = parseFloat(apyV) / 100
      if (isNaN(apy) || apy < 0) { setError('Enter a valid APY %.'); return }
      const r = Math.pow(1 + apy, 1 / ppy) - 1
      pay = r === 0 ? la / np : la * r / (1 - Math.pow(1 + r, -np))
    } else if (flatV !== '') {
      const flat = parseFloat(flatV) / 100
      if (isNaN(flat) || flat < 0) { setError('Enter a valid Flat Rate %.'); return }
      pay = la * (1 + flat * np) / np
    } else {
      pay = parseFloat(payV)
      if (isNaN(pay) || pay < 0) { setError('Enter a valid payment amount ≥ 0.'); return }
    }

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
    saveHistory()
  }

  async function saveHistory() {
    const params: LoanParams = {
      loanAmount, numPeriods, periodLength, payment, rateApy, rateFlat,
      extraCashflows: cfRows
        .map(r => ({ amount: parseFloat(r.amount), period: parseInt(r.period, 10) }))
        .filter(r => !isNaN(r.amount) && !isNaN(r.period)),
      savedAt: Date.now(),
    }
    try {
      const res = await fetch('/api/loan/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      })
      const data: LoanParams[] = await res.json()
      setHistory(data || [])
    } catch (_) {}
  }

  function fmtPct(v: number) { return (v * 100).toFixed(4) + '%' }
  function fmtCur(v: number) { return v.toLocaleString('en-US', { style: 'currency', currency: 'USD' }) }

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
                onClick={() => { applyParams(p); setTimeout(() => calculate(p), 0) }}
              >
                Load
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="loan-card">
        <div className="loan-inputs">
          <div className="loan-col-left">
            <label htmlFor="loan-amount">Loan Amount</label>
            <input
              type="number" id="loan-amount" placeholder="100000" min={0} step="any"
              value={loanAmount}
              onChange={e => { setLoanAmount(e.target.value); setResults(null) }}
            />

            <label htmlFor="num-periods">Number of Periods</label>
            <input
              type="number" id="num-periods" placeholder="360" min={1}
              value={numPeriods}
              onChange={e => { setNumPeriods(e.target.value); setResults(null) }}
            />

            <label htmlFor="period-length">Period Length</label>
            <select
              id="period-length"
              value={periodLength}
              onChange={e => { setPeriodLength(e.target.value); setResults(null) }}
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
                onChange={e => { setPayment(e.target.value); setRateApy(''); setRateFlat(''); setResults(null) }}
              />

              <hr />

              <label htmlFor="rate-apy">Annual Rate (APY %)</label>
              <input
                type="number" id="rate-apy" placeholder="6.168" min={0} step="any"
                value={rateApy}
                onChange={e => { setRateApy(e.target.value); setPayment(''); setRateFlat(''); setResults(null) }}
              />

              <hr />

              <label htmlFor="rate-flat">Flat Rate (% / period)</label>
              <input
                type="number" id="rate-flat" placeholder="0.25" min={0} step="any"
                value={rateFlat}
                onChange={e => { setRateFlat(e.target.value); setPayment(''); setRateApy(''); setResults(null) }}
              />
            </fieldset>
          </div>
        </div>

        {/* Extra cashflows */}
        <div className="extra-cashflows">
          <div className="extra-cashflows-header">
            <span>Extra Cash Flows</span>
            <button
              className="add-cashflow-btn" id="add-cashflow" type="button"
              onClick={() => setCfRows(r => [...r, { id: newCfId(), amount: '', period: '' }])}
            >
              + Add
            </button>
          </div>
          <p className="cashflow-hint">positive = extra received (e.g. rebate), negative = extra payment</p>
          <div id="cashflow-rows">
            {cfRows.map(row => (
              <div key={row.id} className="cashflow-row">
                <input
                  type="number" className="cf-amount" placeholder="Amount" step="any"
                  value={row.amount}
                  onChange={e => { setCfRows(rs => rs.map(r => r.id === row.id ? { ...r, amount: e.target.value } : r)); setResults(null) }}
                />
                <span className="cf-label">at period</span>
                <input
                  type="number" className="cf-period" placeholder="Period" min={0} step={1}
                  value={row.period}
                  onChange={e => { setCfRows(rs => rs.map(r => r.id === row.id ? { ...r, period: e.target.value } : r)); setResults(null) }}
                />
                <button
                  type="button" className="cf-remove" aria-label="Remove row"
                  onClick={() => { setCfRows(rs => rs.filter(r => r.id !== row.id)); setResults(null) }}
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        </div>

        <p className="cashflow-hint">
          Fill in Payment, Annual Rate (APY), or Flat Rate — the others will be cleared automatically.
        </p>

        <button
          className="calculate-btn" id="calculate-btn" type="button"
          onClick={() => calculate()}
          onKeyDown={e => { if (e.key === 'Enter') calculate() }}
        >
          Calculate
        </button>

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
