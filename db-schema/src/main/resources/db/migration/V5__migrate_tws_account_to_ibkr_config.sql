-- Migrate standalone twsAccount key into ibkrConfig JSON blob.
-- After this migration, twsAccount is stored only inside ibkrConfig.

-- Step 1: Update existing ibkrConfig rows whose twsAccount is blank,
--         merging the value from the standalone key.
UPDATE portfolio_cfg
SET value = json_set(
    value,
    '$.twsAccount',
    (SELECT src.value FROM portfolio_cfg src
     WHERE src.portfolio_id = portfolio_cfg.portfolio_id AND src.key = 'twsAccount')
)
WHERE key = 'ibkrConfig'
  AND (json_extract(value, '$.twsAccount') IS NULL OR json_extract(value, '$.twsAccount') = '')
  AND EXISTS (
    SELECT 1 FROM portfolio_cfg src
    WHERE src.portfolio_id = portfolio_cfg.portfolio_id
      AND src.key = 'twsAccount'
      AND src.value != ''
  );

-- Step 2: Create ibkrConfig rows for portfolios that only have a standalone twsAccount.
INSERT INTO portfolio_cfg (portfolio_id, key, value)
SELECT portfolio_id,
       'ibkrConfig',
       json_set('{"token":"","queryId":"","twsAccount":""}', '$.twsAccount', value)
FROM portfolio_cfg
WHERE key = 'twsAccount'
  AND value != ''
  AND NOT EXISTS (
    SELECT 1 FROM portfolio_cfg p2
    WHERE p2.portfolio_id = portfolio_cfg.portfolio_id AND p2.key = 'ibkrConfig'
  );

-- Step 3: Drop all standalone twsAccount rows.
DELETE FROM portfolio_cfg WHERE key = 'twsAccount';
