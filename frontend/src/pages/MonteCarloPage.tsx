// ── MonteCarloPage.tsx — Port of MonteCarloRenderer.kt ───────────────────────
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight } from '@/components/Layout'
import { useScripts } from '@/hooks/useScripts'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'

function McNumberField({ label, inputId, defaultVal }: { label: string; inputId: string; defaultVal: string }) {
  return (
    <div className="backtest-date-field">
      <label htmlFor={inputId}>{label}</label>
      <input type="text" id={inputId} defaultValue={defaultVal} inputMode="decimal" />
    </div>
  )
}

const MC_PERCENTILES = [5, 10, 25, 50, 75, 90, 95]

export default function MonteCarloPage() {
  useScripts([
    '/static/montecarlo/montecarlo-chart.js',
    '/static/montecarlo/montecarlo-run.js',
    '/static/montecarlo/montecarlo-main.js',
  ])

  return (
    <div className="container">
      <div className="portfolio-header">
        <div className="header-title-group">
          <PageNavTabs active="/montecarlo" />
        </div>
        <HeaderRight>
          <ConfigButton />
          <ThemeToggle />
        </HeaderRight>
      </div>

      <div className="backtest-form-card">
        <div className="backtest-section backtest-grid-2">
          <DateFieldWithQuickSelect label="From Date (pool)" inputId="mc-from-date" />
          <DateFieldWithQuickSelect label="To Date (pool)" inputId="mc-to-date" />

          <div className="backtest-config-controls">
            <label>Config Code</label>
            <div className="backtest-config-group">
              <input type="text" id="mc-import-code" placeholder="Paste code…" spellCheck={false} />
              <button className="backtest-config-btn" id="mc-import-btn">Import</button>
              <button className="backtest-config-btn" id="mc-export-btn">Export</button>
              <div className="backtest-config-error" id="mc-config-error" />
            </div>
          </div>
        </div>

        <div className="backtest-section mc-params-grid">
          <McNumberField label="Min Chunk Years" inputId="mc-min-chunk" defaultVal="3" />
          <McNumberField label="Max Chunk Years" inputId="mc-max-chunk" defaultVal="8" />
          <McNumberField label="Simulated Years" inputId="mc-sim-years" defaultVal="20" />
          <McNumberField label="Simulations" inputId="mc-num-sims" defaultVal="500" />
        </div>

        <div id="saved-portfolios-bar" style={{ display: 'none' }} />

        <div className="portfolio-blocks">
          {[0, 1, 2].map(idx => <PortfolioBlock key={idx} idx={idx} />)}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <button className="run-backtest-btn" id="run-mc-btn" type="button">Run Simulation</button>
          <button
            className="run-backtest-btn"
            id="rerun-mc-btn"
            type="button"
            style={{ display: 'none', opacity: 0.75 }}
          >
            Rerun (same seed)
          </button>
          <span id="mc-progress" style={{ display: 'none', marginLeft: '0.25rem', fontSize: '0.85em', opacity: 0.7 }} />
        </div>
      </div>

      <div id="error-msg" style={{ display: 'none' }} className="backtest-error" />

      <div id="mc-metrics-desc" style={{ display: 'none', opacity: 0.7, margin: '0.5rem 0 1rem', lineHeight: 1.5 }}>
        <p style={{ fontSize: 'var(--font-size-md)', margin: 0 }}>
          ⚠︎ Each metric is independently ranked across all simulations.
        </p>
        <p style={{ fontSize: '0.82em', margin: 0 }}>
          At P50, CAGR shows the median CAGR outcome, Max DD shows the median worst drawdown (ranked by drawdown), and so on.
        </p>
        <p style={{ fontSize: '0.82em', margin: 0 }}>
          The chart always shows the path at the selected percentile when simulations are ranked by CAGR.
        </p>
      </div>

      <div id="mc-percentile-bar" className="mc-percentile-tabs" style={{ display: 'none' }}>
        {MC_PERCENTILES.map(pct => (
          <button
            key={pct}
            type="button"
            className={`mc-pct-tab${pct === 50 ? ' active' : ''}`}
            data-pct={pct}
          >
            {pct}th
          </button>
        ))}
      </div>

      <div id="stats-container" style={{ display: 'none' }} />

      <div className="backtest-chart-container" id="chart-container" style={{ display: 'none' }}>
        <button className="chart-scale-toggle" id="log-scale-toggle" type="button">Log</button>
        <canvas id="mc-chart" />
      </div>
    </div>
  )
}
