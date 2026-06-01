import type { Ref } from 'react'
import { PageNavTabs, ConfigButton, ThemeToggle, HeaderRight, PrivacyToggleButton } from '@/components/Layout'
import type { BlockState, CashflowFormState } from '@/types/backtest'
import CashflowControls from './CashflowControls'
import DateFieldWithQuickSelect from './DateFieldWithQuickSelect'
import PortfolioBlock from './PortfolioBlock'
import SavedPortfoliosBar, { type SavedPortfoliosBarRef } from './SavedPortfoliosBar'
import SavedStrategiesBar from '@/components/rebalance/SavedStrategiesBar'

type StringSetter = (value: string) => void

interface BacktestPageHeaderProps {
  active: string
}

export function BacktestPageHeader({ active }: BacktestPageHeaderProps) {
  return (
    <div className="portfolio-header">
      <div className="header-title-group"><PageNavTabs active={active} /></div>
      <HeaderRight><PrivacyToggleButton /><ConfigButton /><ThemeToggle /></HeaderRight>
    </div>
  )
}

interface ScenarioSetupControlsProps extends CashflowFormState {
  idPrefix: string
  fromLabel: string
  fromInputId: string
  fromDate: string
  toLabel: string
  toInputId: string
  toDate: string
  importInputId: string
  importCode: string
  configError: string
  dateRangeError?: string
  onFromDateChange: StringSetter
  onToDateChange: StringSetter
  onImportCodeChange: StringSetter
  onImport: () => void
  onExport: () => void
  onStartingBalanceChange: StringSetter
  onCashflowAmountChange: StringSetter
  onCashflowFrequencyChange: StringSetter
}

export function ScenarioSetupControls({
  idPrefix,
  fromLabel,
  fromInputId,
  fromDate,
  toLabel,
  toInputId,
  toDate,
  importInputId,
  importCode,
  configError,
  dateRangeError = '',
  startingBalance,
  cashflowAmount,
  cashflowFrequency,
  onFromDateChange,
  onToDateChange,
  onImportCodeChange,
  onImport,
  onExport,
  onStartingBalanceChange,
  onCashflowAmountChange,
  onCashflowFrequencyChange,
}: ScenarioSetupControlsProps) {
  return (
    <>
      <div className="backtest-section backtest-config-row">
        <div className="backtest-date-range-controls">
          <DateFieldWithQuickSelect label={fromLabel} inputId={fromInputId} value={fromDate} onChange={onFromDateChange} />
          <DateFieldWithQuickSelect label={toLabel} inputId={toInputId} value={toDate} onChange={onToDateChange} />
          {dateRangeError && (
            <div className="backtest-date-range-error" role="alert">
              {dateRangeError}
            </div>
          )}
        </div>

        <div className="backtest-config-controls">
          <label htmlFor={importInputId}>Config Code</label>
          <div className="backtest-config-group">
            <input
              type="text"
              id={importInputId}
              placeholder="Paste code..."
              spellCheck={false}
              value={importCode}
              onChange={e => onImportCodeChange(e.target.value)}
            />
            <button className="backtest-config-btn" onClick={onImport}>Import</button>
            <button className="backtest-config-btn" onClick={onExport}>Export</button>
            {configError && <div className="backtest-config-error">{configError}</div>}
          </div>
        </div>
      </div>

      <CashflowControls
        idPrefix={idPrefix}
        startingBalance={startingBalance}
        cashflowAmount={cashflowAmount}
        cashflowFrequency={cashflowFrequency}
        onStartingBalanceChange={onStartingBalanceChange}
        onCashflowAmountChange={onCashflowAmountChange}
        onCashflowFrequencyChange={onCashflowFrequencyChange}
      />
    </>
  )
}

interface SavedPortfolioBlocksSectionProps {
  savedBarRef: Ref<SavedPortfoliosBarRef>
  blocks: BlockState[]
  onBlockChange: (idx: number, value: BlockState) => void
  onSavedRefresh: () => void
  showSavedStrategies?: boolean
}

export function SavedPortfolioBlocksSection({
  savedBarRef,
  blocks,
  onBlockChange,
  onSavedRefresh,
  showSavedStrategies = false,
}: SavedPortfolioBlocksSectionProps) {
  return (
    <>
      <SavedPortfoliosBar ref={savedBarRef} />
      {showSavedStrategies && <SavedStrategiesBar />}

      <div className="portfolio-blocks">
        {blocks.map((block, idx) => (
          <PortfolioBlock
            key={idx}
            idx={idx}
            value={block}
            onChange={value => onBlockChange(idx, value)}
            onSavedRefresh={onSavedRefresh}
          />
        ))}
      </div>
    </>
  )
}

interface RunButtonProps {
  label: string
  running: boolean
  disabled?: boolean
  onClick: () => void
}

export function RunButton({ label, running, disabled, onClick }: RunButtonProps) {
  return (
    <button className="run-backtest-btn" type="button" onClick={onClick} disabled={disabled}>
      {running ? <>Running{'\u2026'}<span className="btn-spinner" /></> : label}
    </button>
  )
}
