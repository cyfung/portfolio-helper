// ── BacktestPage.tsx — Port of BacktestRenderer.kt ───────────────────────────
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight } from '@/components/Layout'
import { useScripts } from '@/hooks/useScripts'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'

export default function BacktestPage() {
  useScripts([
    '/static/backtest/backtest-chart.js',
    '/static/backtest/backtest-run.js',
    '/static/backtest/backtest-main.js',
  ])

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/backtest" />
        </div>
        <HeaderRight>
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      <div className="backtest-form-card">
        <div className="backtest-section backtest-grid-2">
          <DateFieldWithQuickSelect label="From Date" inputId="from-date" />
          <DateFieldWithQuickSelect label="To Date" inputId="to-date" />

          <div className="backtest-config-controls">
            <label htmlFor="backtest-import-code">Config Code</label>
            <div className="backtest-config-group">
              <input
                type="text"
                id="backtest-import-code"
                placeholder="Paste code…"
                spellCheck={false}
              />
              <button className="backtest-config-btn" id="backtest-import-btn">Import</button>
              <button className="backtest-config-btn" id="backtest-export-btn">Export</button>
              <div id="backtest-config-error" className="backtest-config-error" />
            </div>
          </div>
        </div>

        <div id="saved-portfolios-bar" style={{ display: 'none' }} />

        <div className="portfolio-blocks">
          {[0, 1, 2].map(idx => <PortfolioBlock key={idx} idx={idx} />)}
        </div>

        <button className="run-backtest-btn" id="run-backtest-btn" type="button">
          Run Backtest
        </button>
      </div>

      <div id="error-msg" style={{ display: 'none' }} className="backtest-error" />
      <div id="stats-container" style={{ display: 'none' }} />

      <div className="backtest-chart-container" id="chart-container" style={{ display: 'none' }}>
        <button className="chart-scale-toggle" id="log-scale-toggle" type="button">Log</button>
        <canvas id="backtest-chart" />
      </div>

      <div className="backtest-chart-container" id="drawdown-container" style={{ display: 'none' }}>
        <canvas id="drawdown-chart" />
      </div>

      <div className="backtest-chart-container" id="rtr-container" style={{ display: 'none' }}>
        <canvas id="rtr-chart" />
      </div>
    </div>
  )
}
