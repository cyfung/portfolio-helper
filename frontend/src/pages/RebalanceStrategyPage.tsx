import {
  BacktestPageHeader,
  RunButton,
  ScenarioSetupControls,
} from '@/components/backtest/CommonBacktestSections'
import ImportDependenciesDialog from '@/components/backtest/ImportDependenciesDialog'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar from '@/components/backtest/SavedPortfoliosBar'
import TickerMappingControl from '@/components/backtest/TickerMappingControl'
import RebalanceStrategyBlock from '@/components/rebalance/RebalanceStrategyBlock'
import RebalanceStrategyResults from '@/components/rebalance/RebalanceStrategyResults'
import SavedStrategiesBar from '@/components/rebalance/SavedStrategiesBar'
import { useRebalanceStrategyPage } from '@/hooks/useRebalanceStrategyPage'

export default function RebalanceStrategyPage() {
  const page = useRebalanceStrategyPage()

  return (
    <div className="container">
      <BacktestPageHeader active="/rebalance-strategy" />

      <div className="backtest-form-card">
        <ScenarioSetupControls
          idPrefix="rs"
          fromLabel="From Date"
          fromInputId="rs-from-date"
          fromDate={page.fromDate}
          toLabel="To Date"
          toInputId="rs-to-date"
          toDate={page.toDate}
          importInputId="rs-import-code"
          importCode={page.importCode}
          configError={page.configError}
          dateRangeError={page.dateRangeError}
          startingBalance={page.startingBalance}
          cashflowAmount={page.cashflowAmount}
          cashflowFrequency={page.cashflowFrequency}
          onFromDateChange={page.setFromDate}
          onToDateChange={page.setToDate}
          onImportCodeChange={page.setImportCode}
          onImport={page.handleImport}
          onExport={page.handleExport}
          onStartingBalanceChange={page.setStartingBalance}
          onCashflowAmountChange={page.setCashflowAmount}
          onCashflowFrequencyChange={page.setCashflowFrequency}
        />

        <TickerMappingControl
          idPrefix="rs"
          value={page.tickerMappingSettings}
          onChange={page.setTickerMappingSettings}
        />

        <SavedPortfoliosBar ref={page.savedBarRef} />
        <SavedStrategiesBar ref={page.savedStrategiesBarRef} />

        <div className="strategy-options-panel">
          <label className="strategy-options-toggle" htmlFor="rs-action-diagnostics">
            <input
              id="rs-action-diagnostics"
              type="checkbox"
              checked={page.includeActionDiagnostics}
              disabled={page.running}
              onChange={e => page.setIncludeActionDiagnostics(e.target.checked)}
            />
            Action diagnostics
          </label>
        </div>

        <div className="rebalance-strategy-layout">
          <PortfolioBlock idx={0} value={page.portfolio} onChange={page.setPortfolio} onSavedRefresh={page.refreshSaved} />
          {page.strategies.map((strategy, i) => (
            <RebalanceStrategyBlock
              key={i}
              ref={el => { page.strategyBlockRefs.current[i] = el }}
              idx={i}
              value={strategy}
              onChange={page.strategyHandlers[i]}
              onSavedRefresh={page.refreshSavedStrategies}
            />
          ))}
        </div>

        <RunButton
          label="Run Rebalance Strategy"
          running={page.running}
          disabled={page.running || !!page.dateRangeError}
          onClick={page.handleRun}
        />
      </div>

      {page.error && <div className="backtest-error">{page.error}</div>}
      {page.results && (
        <RebalanceStrategyResults
          results={page.results}
          selected={page.selected}
          setSelected={page.setSelected}
          zeroMarginInterestResults={page.zeroMarginInterestResults}
          zeroMarginInterestRunning={page.zeroMarginInterestRunning}
          onLoadZeroMarginInterestResults={page.loadZeroMarginInterestResults}
        />
      )}
      {page.pendingImport && (
        <ImportDependenciesDialog
          preview={page.pendingImport.preview}
          applying={page.importDependencyApplying}
          error={page.importDependencyError}
          onCancel={() => page.setPendingImport(null)}
          onConfirm={page.confirmPendingImport}
        />
      )}
    </div>
  )
}
