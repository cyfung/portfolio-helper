# World CAPE History Dataset

This project ships a local CSV at `frontend/public/data/world-cape-history.csv`.
It is designed as a valuation-timing reference for the Market Timing page. It is
not a single official world CAPE download.

Regenerate it with:

```bash
cd frontend
npm run generate:world-cape
```

The generator is `frontend/scripts/generate-world-cape-history.mjs`.

## Columns

- `date`: Observation date.
- `world_cape`: Final CAPE value used by the app.
- `us_cape`: US Shiller CAPE input when available.
- `dm_ex_us_cape`: Developed-market ex-US proxy CAPE when modeled.
- `em_cape`: Emerging-market proxy CAPE when modeled.
- `us_weight`, `dm_ex_us_weight`, `em_weight`: Regional weights used in the synthetic blend.
- `source_method`: Which data construction method produced the row.
- `calibration_multiplier`: Multiplier applied to the raw synthetic world CAPE. Blank for hard anchors.
- `source_note`: Human-readable source and method note.

## Source Layers

### 1900-1987: US Shiller Proxy

Rows before 1988 use US Shiller CAPE as a world proxy:

```text
world_cape = us_cape
```

These rows are marked `US_SHILLER_PROXY`.

The input is the open Shiller-derived CSV from:

```text
https://raw.githubusercontent.com/datasets/s-and-p-500/main/data/data.csv
```

This early segment is useful for long-cycle valuation context, but it is not a
true global equity CAPE.

### 1988-2019: Calibrated Synthetic World CAPE

Rows from 1988 through 2019 are modeled from US Shiller CAPE plus developed
ex-US and emerging-market proxy CAPEs.

Proxy CAPEs:

```text
dm_ex_us_cape = clipped(us_cape * dm_multiplier)
em_cape       = clipped(us_cape * em_multiplier)
```

The blend is done in earnings-yield space:

```text
world_ep   = us_weight / us_cape
           + dm_ex_us_weight / dm_ex_us_cape
           + em_weight / em_cape

world_cape = 1 / world_ep
```

Regional weights transition linearly from approximately:

```text
1988: US 42%, developed ex-US 53%, EM 5%
2019: US 56%, developed ex-US 32%, EM 12%
```

The raw synthetic series is then calibrated with linearly interpolated
multipliers through these sanity anchors:

| Date | Target CAPE | Reason |
| --- | ---: | --- |
| 1988-03-31 | raw | Preserve the start of the modern synthetic window |
| 2000-03-31 | 34.0 | Dot-com global CAPE commonly cited around 33-35 |
| 2007-09-30 | 25.0 | Nearest quarter-end anchor for pre-GFC global CAPE around 25 |
| 2008-12-31 | 13.0 | GFC trough neighborhood, global CAPE in the low teens |
| 2009-03-31 | 13.0 | Early-2009 trough anchor |
| 2019-12-31 | 25.0 | Keeps the synthetic series close to the Siblis splice |

These rows are marked `SYNTHETIC_EP_BLEND_CALIBRATED`.

### 2020-2025: Siblis Free Anchors

Rows from 2020 onward use the free Siblis Global Stock Market CAPE table values
that were available when the dataset was built. These rows are hard anchors and
are marked `SIBLIS_FREE_ANCHOR`.

Source:

```text
https://siblisresearch.com/data/world-cape-ratio/
```

### 2026 Current Reference

The final current-reference row is marked `RA_CURRENT_REFERENCE`.

It reflects the user-supplied Research Affiliates AAI-style snapshot:

```text
Global Total current CAPE: 29.4
Historical median: 23
Snapshot date used in the CSV: 2026-05-23
```

This is included as a current valuation reference only. It is not used to infer
or backfill Research Affiliates historical time-series data.

## Important Limitations

- The file is a pragmatic valuation-timing dataset, not an official MSCI,
  Siblis, or Research Affiliates historical database.
- The 1988-2019 segment is model-based and should be treated as approximate.
- Median values depend heavily on the chosen window. A full 1900-present median
  is not directly comparable to a modern global-equity median such as the
  Research Affiliates `23` reference.
- If a paid or public machine-readable global CAPE time series becomes
  available, it should replace the synthetic segment.
