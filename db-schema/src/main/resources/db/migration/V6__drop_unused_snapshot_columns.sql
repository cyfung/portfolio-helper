-- Drop entire unused tables
DROP TABLE IF EXISTS snapshot_cash_balances;
DROP TABLE IF EXISTS snapshot_interest_accruals;

-- Drop unused columns from portfolio_snapshots
ALTER TABLE portfolio_snapshots DROP COLUMN stock_base;
ALTER TABLE portfolio_snapshots DROP COLUMN interest_accruals_base;

-- Drop unused columns from snapshot_positions
ALTER TABLE snapshot_positions DROP COLUMN currency;
ALTER TABLE snapshot_positions DROP COLUMN position;
ALTER TABLE snapshot_positions DROP COLUMN mark_price;

-- Drop unused column from snapshot_cash_flows
ALTER TABLE snapshot_cash_flows DROP COLUMN currency;
