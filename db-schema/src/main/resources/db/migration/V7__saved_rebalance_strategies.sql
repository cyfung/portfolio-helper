CREATE TABLE IF NOT EXISTS saved_rebalance_strategies (
    name       VARCHAR(256) NOT NULL PRIMARY KEY,
    config     TEXT         NOT NULL,
    created_at INTEGER      NOT NULL
);
