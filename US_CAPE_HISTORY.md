# US CAPE History Dataset

This project ships a local CSV at `frontend/public/data/us-cape-history.csv`.
It is designed as a US valuation reference next to the world CAPE history on
the Hold the Dip page.

Regenerate it with:

```bash
cd frontend
npm run generate:us-cape
```

The generator is `frontend/scripts/generate-us-cape-history.mjs`.

## Columns

- `date`: First day of the month, matching the source table convention.
- `us_cape`: US Shiller PE / CAPE value.
- `change`: Month-over-month point change from the source table.
- `month_over_month_pct`: Month-over-month percent change from the source table.
- `source_method`: Source construction method.
- `source_note`: Human-readable source note.

## Source

The generator scrapes the monthly Shiller PE table from:

```text
https://www.officialdata.org/us-economy/shiller-pe
```

The source page describes Shiller PE / CAPE as the S&P 500 price divided by the
inflation-adjusted moving average of earnings over the prior ten years, based on
Standard & Poor's data and Robert Shiller's work.

Rows are marked:

```text
OFFICIALDATA_SHILLER_MONTHLY
```

## Method

The generator:

1. Fetches the source page.
2. Isolates the monthly `Shiller PE Table`.
3. Parses `year`, `month`, `Shiller PE`, `change`, and `month-over-month (%)`.
4. Drops zero or non-numeric CAPE rows.
5. Writes the observations sorted oldest to newest.

No synthetic adjustment or calibration is applied.

## Important Limitations

- This is a scraped HTML table, not a direct Yale Excel parse.
- The official Shiller Excel workbook is binary `.xls`; this project avoids
  adding a spreadsheet parser dependency for now.
- If a stable official CSV endpoint becomes available, it should replace this
  scraper.
