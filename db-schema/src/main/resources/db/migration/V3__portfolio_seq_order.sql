ALTER TABLE portfolios ADD COLUMN seq_order REAL NOT NULL DEFAULT 0;
UPDATE portfolios SET seq_order = CAST(id AS REAL);
