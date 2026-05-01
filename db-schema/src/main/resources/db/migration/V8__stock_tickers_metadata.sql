CREATE TABLE IF NOT EXISTS stock_tickers (
    symbol   VARCHAR(32) NOT NULL PRIMARY KEY,
    letf     TEXT        NOT NULL DEFAULT '',
    "groups" TEXT        NOT NULL DEFAULT ''
);

INSERT OR IGNORE INTO stock_tickers (symbol, letf, "groups")
SELECT
    symbol,
    COALESCE(MAX(NULLIF(letf, '')), ''),
    COALESCE(MAX(NULLIF("groups", '')), '')
FROM positions
GROUP BY symbol;

CREATE TABLE positions_new (
    portfolio_id   INTEGER     NOT NULL,
    symbol         VARCHAR(32) NOT NULL,
    amount         DOUBLE      NOT NULL,
    target_weight  DOUBLE      NOT NULL DEFAULT 0.0,
    PRIMARY KEY (portfolio_id, symbol)
);

INSERT INTO positions_new (portfolio_id, symbol, amount, target_weight)
SELECT portfolio_id, symbol, amount, target_weight
FROM positions;

DROP TABLE positions;
ALTER TABLE positions_new RENAME TO positions;
