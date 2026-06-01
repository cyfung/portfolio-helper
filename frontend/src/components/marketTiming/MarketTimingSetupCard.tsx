import type { RefObject } from 'react'
import { RunButton } from '@/components/backtest/CommonBacktestSections'
import DateFieldWithQuickSelect from '@/components/backtest/DateFieldWithQuickSelect'
import PortfolioBlock from '@/components/backtest/PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from '@/components/backtest/SavedPortfoliosBar'
import { isValidNumberInput } from '@/lib/numberInputs'
import type { BlockState } from '@/types/backtest'
import type { InterestMode, ReferenceSource } from '@/types/marketTiming'

interface MarketTimingSetupCardProps {
  portfolio: BlockState
  fromDate: string
  toDate: string
  drawdownConfigs: string
  referenceSource: ReferenceSource
  referenceTicker: string
  interestMode: InterestMode
  annualSpread: string
  annualSpreadTouched: boolean
  fixedAnnualRate: string
  importCode: string
  configError: string
  running: boolean
  dateRangeError: string
  savedBarRef: RefObject<SavedPortfoliosBarRef>
  onPortfolioChange: (value: BlockState) => void
  onFromDateChange: (value: string) => void
  onToDateChange: (value: string) => void
  onDrawdownConfigsChange: (value: string) => void
  onReferenceSourceChange: (value: ReferenceSource) => void
  onReferenceTickerChange: (value: string) => void
  onInterestModeChange: (value: InterestMode) => void
  onAnnualSpreadChange: (value: string) => void
  onAnnualSpreadTouched: () => void
  onFixedAnnualRateChange: (value: string) => void
  onImportCodeChange: (value: string) => void
  onImport: () => void
  onExport: () => void
  onRun: () => void
  onSavedRefresh: () => void
}

export default function MarketTimingSetupCard({
  portfolio,
  fromDate,
  toDate,
  drawdownConfigs,
  referenceSource,
  referenceTicker,
  interestMode,
  annualSpread,
  annualSpreadTouched,
  fixedAnnualRate,
  importCode,
  configError,
  running,
  dateRangeError,
  savedBarRef,
  onPortfolioChange,
  onFromDateChange,
  onToDateChange,
  onDrawdownConfigsChange,
  onReferenceSourceChange,
  onReferenceTickerChange,
  onInterestModeChange,
  onAnnualSpreadChange,
  onAnnualSpreadTouched,
  onFixedAnnualRateChange,
  onImportCodeChange,
  onImport,
  onExport,
  onRun,
  onSavedRefresh,
}: MarketTimingSetupCardProps) {
  const spreadInvalid = interestMode === 'SPREAD' && annualSpreadTouched && !isValidNumberInput(annualSpread, { min: 0 })

  return (
    <div className="backtest-form-card">
      <div className="backtest-section backtest-config-row">
        <div className="backtest-date-range-controls">
          <DateFieldWithQuickSelect label="From Date" inputId="market-timing-from-date" value={fromDate} onChange={onFromDateChange} />
          <DateFieldWithQuickSelect label="To Date" inputId="market-timing-to-date" value={toDate} onChange={onToDateChange} />
          {dateRangeError && (
            <div className="backtest-date-range-error" role="alert">
              {dateRangeError}
            </div>
          )}
        </div>
        <div className="backtest-config-controls">
          <label htmlFor="market-timing-import-code">Config Code</label>
          <div className="backtest-config-group">
            <input
              id="market-timing-import-code"
              type="text"
              spellCheck={false}
              placeholder="Paste code..."
              value={importCode}
              onChange={e => onImportCodeChange(e.target.value)}
            />
            <button className="backtest-config-btn" type="button" onClick={onImport}>Import</button>
            <button className="backtest-config-btn" type="button" onClick={onExport}>Export</button>
            {configError && <div className="backtest-config-error">{configError}</div>}
          </div>
        </div>
      </div>

      <div className="backtest-section market-timing-config">
        <div className="market-timing-config-row">
          <div className="market-timing-field market-timing-field-drawdown">
            <label htmlFor="market-timing-dd-pcts">Drawdown % - Zero Window (month)</label>
            <input
              id="market-timing-dd-pcts"
              type="text"
              value={drawdownConfigs}
              onChange={e => onDrawdownConfigsChange(e.target.value)}
              title="Use comma-separated drawdown-window pairs, e.g. 5-0, 10-36."
            />
          </div>
          <div className="market-timing-field market-timing-field-reference">
            <label htmlFor="market-timing-reference-source">Reference</label>
            <select
              id="market-timing-reference-source"
              value={referenceSource}
              onChange={e => onReferenceSourceChange(e.target.value as ReferenceSource)}
            >
              <option value="PORTFOLIO">Portfolio</option>
              <option value="TICKER">Ticker</option>
            </select>
          </div>
          {referenceSource === 'TICKER' && (
            <div className="market-timing-field market-timing-field-ticker">
              <label htmlFor="market-timing-reference-ticker">Ticker</label>
              <input
                id="market-timing-reference-ticker"
                type="text"
                value={referenceTicker}
                onChange={e => onReferenceTickerChange(e.target.value)}
              />
            </div>
          )}
        </div>
        <div className="market-timing-config-row market-timing-config-row-interest">
          <div className="market-timing-field market-timing-field-interest">
            <label htmlFor="market-timing-interest-mode">Interest</label>
            <select
              id="market-timing-interest-mode"
              value={interestMode}
              onChange={e => onInterestModeChange(e.target.value as InterestMode)}
            >
              <option value="SPREAD">EFFR + spread</option>
              <option value="FIXED">Fixed rate</option>
            </select>
          </div>
          <div className="market-timing-field market-timing-field-rate">
            <label htmlFor="market-timing-interest-rate">{interestMode === 'SPREAD' ? 'Spread %' : 'Fixed %'}</label>
            <input
              id="market-timing-interest-rate"
              type="number"
              step="0.05"
              value={interestMode === 'SPREAD' ? annualSpread : fixedAnnualRate}
              onChange={e => interestMode === 'SPREAD' ? onAnnualSpreadChange(e.target.value) : onFixedAnnualRateChange(e.target.value)}
              onBlur={() => { if (interestMode === 'SPREAD') onAnnualSpreadTouched() }}
              className={spreadInvalid ? 'input-error' : undefined}
              aria-invalid={spreadInvalid}
              title={spreadInvalid ? 'Enter a valid non-negative spread percent' : undefined}
            />
          </div>
        </div>
      </div>

      <SavedPortfoliosBar ref={savedBarRef} />
      <div className="portfolio-blocks">
        <PortfolioBlock idx={0} value={portfolio} onChange={onPortfolioChange} onSavedRefresh={onSavedRefresh} />
      </div>

      <RunButton label="Run Market Timing" running={running} disabled={running || !!dateRangeError} onClick={onRun} />
    </div>
  )
}
