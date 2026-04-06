// ── PortfolioBlock.tsx — Port of portfolioBlock() from common.kt ──────────────

interface Props {
  idx: number
}

const DEFAULT_TICKERS = [
  { ticker: 'VT', weight: '60' },
  { ticker: 'KMLM', weight: '40' },
]

export default function PortfolioBlock({ idx }: Props) {
  return (
    <div className="portfolio-block" data-portfolio-index={idx}>
      {/* Label */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <input type="text" className="portfolio-label" placeholder="Label" />
          <button className="overwrite-portfolio-btn save-portfolio-btn" disabled>Save</button>
          <button className="save-portfolio-btn" disabled>Save New</button>
          <button className="clear-portfolio-btn" type="button" title="Clear portfolio">✕</button>
        </div>
      </div>

      {/* Tickers */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <span>Tickers &amp; Weights</span>
          <button className="add-ticker-btn" type="button">+ Add Ticker</button>
        </div>
        <div className="backtest-weight-hint" />
        <div className="ticker-rows">
          {idx === 0 && DEFAULT_TICKERS.map(({ ticker, weight }) => (
            <div key={ticker} className="backtest-ticker-row">
              <input
                type="text"
                className="ticker-input"
                placeholder="e.g. VT or: 1 KMLM 1 VT S=1.5"
                defaultValue={ticker}
              />
              <input
                type="text"
                className="weight-input"
                placeholder="Weight %"
                defaultValue={weight}
              />
              <span className="weight-unit">%</span>
              <button className="remove-ticker-btn" type="button" title="Remove">✕</button>
            </div>
          ))}
        </div>
      </div>

      {/* Rebalance strategy */}
      <div className="backtest-section">
        <label>Rebalance Strategy</label>
        <select className="rebalance-select">
          <option value="NONE">None</option>
          <option value="MONTHLY">Monthly</option>
          <option value="QUARTERLY">Quarterly</option>
          <option value="YEARLY" defaultChecked>Yearly</option>
        </select>
      </div>

      {/* Margin */}
      <div className="backtest-section">
        <div className="backtest-section-header">
          <span>Margin</span>
          <div className="margin-header-btns">
            <button className="include-no-margin-btn" type="button" data-include="true">
              Unlevered: On
            </button>
            <button className="add-margin-btn" type="button">+ Add Margin</button>
          </div>
        </div>
        <div className="margin-col-headers">
          <span /><span>Ratio%</span><span>Spread%</span>
          <span title="Upper deviation band: if the margin ratio rises above target + this %, a rebalance is triggered (market fell → over-leveraged)">Dev%↑</span>
          <span title="Lower deviation band: if the margin ratio falls below target − this %, a rebalance is triggered (market rose → under-leveraged)">Dev%↓</span>
          <span title="What to do when the upper band is breached (market fell, margin ratio too high)">Mode↑</span>
          <span title="What to do when the lower band is breached (market rose, margin ratio too low)">Mode↓</span>
          <span />
        </div>
        <div className="margin-config-rows" />
      </div>
    </div>
  )
}
