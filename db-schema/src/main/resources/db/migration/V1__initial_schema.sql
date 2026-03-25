CREATE TABLE IF NOT EXISTS portfolios (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    slug VARCHAR(64) NOT NULL,
    UNIQUE (slug)
);

CREATE TABLE IF NOT EXISTS positions (
    portfolio_id   INTEGER NOT NULL,
    symbol         VARCHAR(32) NOT NULL,
    amount         DOUBLE NOT NULL,
    target_weight  DOUBLE NOT NULL DEFAULT 0.0,
    letf           TEXT NOT NULL DEFAULT '',
    "groups"       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (portfolio_id, symbol)
);

CREATE TABLE IF NOT EXISTS cash (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    portfolio_id     INTEGER NOT NULL,
    label            VARCHAR(128) NOT NULL,
    currency         VARCHAR(16) NOT NULL,
    margin_flag      BOOLEAN NOT NULL DEFAULT FALSE,
    amount           DOUBLE NOT NULL,
    portfolio_ref_id INTEGER NULL
);

CREATE TABLE IF NOT EXISTS portfolio_cfg (
    portfolio_id INTEGER NOT NULL,
    key          VARCHAR(64) NOT NULL,
    value        TEXT NOT NULL,
    PRIMARY KEY (portfolio_id, key)
);

CREATE TABLE IF NOT EXISTS paired_devices (
    server_assigned_id VARCHAR(64) NOT NULL,
    client_id          VARCHAR(128) NOT NULL,
    display_name       VARCHAR(256) NOT NULL,
    paired_at          BIGINT NOT NULL,
    last_ip            VARCHAR(64) NOT NULL,
    aes_key            VARCHAR(512) NOT NULL DEFAULT '',
    use_count          INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (server_assigned_id)
);

CREATE TABLE IF NOT EXISTS admin_sessions (
    token      VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    ip         VARCHAR(64) NOT NULL DEFAULT '',
    user_agent VARCHAR(512) NOT NULL DEFAULT '',
    PRIMARY KEY (token)
);

CREATE TABLE IF NOT EXISTS global_settings (
    key   VARCHAR(128) NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (key)
);

CREATE TABLE IF NOT EXISTS saved_backtest_portfolios (
    name       VARCHAR(256) NOT NULL,
    config     TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (name)
);

CREATE TABLE IF NOT EXISTS portfolio_backups (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    portfolio_id INTEGER NOT NULL,
    created_at   BIGINT NOT NULL,
    label        VARCHAR(128) NOT NULL DEFAULT '',
    data         TEXT NOT NULL
);
