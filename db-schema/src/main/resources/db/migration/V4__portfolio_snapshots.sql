CREATE TABLE IF NOT EXISTS portfolio_snapshots (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    portfolio_id           INTEGER NOT NULL REFERENCES portfolios(id),
    snapshot_date          TEXT    NOT NULL,  -- YYYY-MM-DD
    net_liq_value          REAL    NOT NULL,
    cash_base              REAL    NOT NULL,
    stock_base             REAL    NOT NULL,
    interest_accruals_base REAL    NOT NULL,
    content_hash           TEXT    NOT NULL,
    created_at             INTEGER NOT NULL,
    UNIQUE (portfolio_id, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_ps_portfolio_date ON portfolio_snapshots (portfolio_id, snapshot_date);

CREATE TABLE IF NOT EXISTS snapshot_positions (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id    INTEGER NOT NULL REFERENCES portfolio_snapshots(id),
    symbol         TEXT    NOT NULL,
    currency       TEXT    NOT NULL,
    position       REAL    NOT NULL,
    mark_price     REAL    NOT NULL,
    position_value REAL    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sp_snapshot ON snapshot_positions (snapshot_id);

CREATE TABLE IF NOT EXISTS snapshot_cash_balances (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id INTEGER NOT NULL REFERENCES portfolio_snapshots(id),
    currency    TEXT    NOT NULL,
    amount      REAL    NOT NULL
);

CREATE TABLE IF NOT EXISTS snapshot_interest_accruals (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id            INTEGER NOT NULL REFERENCES portfolio_snapshots(id),
    currency               TEXT    NOT NULL,
    ending_accrual_balance REAL    NOT NULL
);

CREATE TABLE IF NOT EXISTS snapshot_cash_flows (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id     INTEGER NOT NULL REFERENCES portfolio_snapshots(id),
    currency        TEXT    NOT NULL,
    fx_rate_to_base REAL    NOT NULL,
    amount          REAL    NOT NULL,
    type            TEXT    NOT NULL
);
