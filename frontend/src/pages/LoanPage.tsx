// ── LoanPage.tsx — Port of LoanCalculatorRenderer.kt ─────────────────────────
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight } from '@/components/Layout'
import { useScripts } from '@/hooks/useScripts'

export default function LoanPage() {
  useScripts(['/static/loan-calculator.js'])

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/loan" />
        </div>
        <HeaderRight>
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      <div id="loan-history" />

      <div className="loan-card">
        <div className="loan-inputs">
          <div className="loan-col-left">
            <label htmlFor="loan-amount">Loan Amount</label>
            <input type="number" id="loan-amount" placeholder="100000" min={0} step="any" />

            <label htmlFor="num-periods">Number of Periods</label>
            <input type="number" id="num-periods" placeholder="360" min={1} />

            <label htmlFor="period-length">Period Length</label>
            <select id="period-length">
              <option value="365">Daily</option>
              <option value="52">Weekly</option>
              <option value="26">Bi-weekly</option>
              <option value="12" defaultChecked>Monthly</option>
              <option value="4">Quarterly</option>
              <option value="2">Semi-annually</option>
              <option value="1">Annually</option>
            </select>
          </div>

          <div className="loan-col-right">
            <fieldset className="loan-exclusive-group">
              <legend>enter one</legend>

              <label htmlFor="payment">Payment / Period</label>
              <input type="number" id="payment" placeholder="536.82" min={0} step="any" />

              <hr />

              <label htmlFor="rate-apy">Annual Rate (APY %)</label>
              <input type="number" id="rate-apy" placeholder="6.168" min={0} step="any" />

              <hr />

              <label htmlFor="rate-flat">Flat Rate (% / period)</label>
              <input type="number" id="rate-flat" placeholder="0.25" min={0} step="any" />
            </fieldset>
          </div>
        </div>

        <div className="extra-cashflows">
          <div className="extra-cashflows-header">
            <span>Extra Cash Flows</span>
            <button className="add-cashflow-btn" id="add-cashflow" type="button">+ Add</button>
          </div>
          <p className="cashflow-hint">positive = extra received (e.g. rebate), negative = extra payment</p>
          <div id="cashflow-rows" />
        </div>

        <p className="cashflow-hint">
          Fill in Payment, Annual Rate (APY), or Flat Rate — the others will be cleared automatically.
        </p>

        <button className="calculate-btn" id="calculate-btn" type="button">Calculate</button>

        <div className="loan-results" id="loan-results" style={{ display: 'none' }}>
          <div className="result-row">
            <span>Periodic Rate</span><span id="result-periodic-rate" />
          </div>
          <div className="result-row">
            <span>Nominal APR</span><span id="result-apr" />
          </div>
          <div className="result-row result-highlight">
            <span>Effective APR (APY)</span><span id="result-apy" />
          </div>
          <div className="result-row">
            <span>Flat Rate</span><span id="result-flat-rate" />
          </div>
          <div className="result-divider" />
          <div className="result-row">
            <span>Payment / Period</span><span id="result-payment-per-period" />
          </div>
          <div className="result-row">
            <span>Total Payments</span><span id="result-total-payments" />
          </div>
          <div className="result-row">
            <span>Total Interest</span><span id="result-total-interest" />
          </div>
        </div>

        <div className="loan-error" id="loan-error" style={{ display: 'none' }} />
      </div>
    </div>
  )
}
