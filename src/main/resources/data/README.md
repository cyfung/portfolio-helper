# Sample Portfolio Data

This is the bundled sample/demonstration data. If `data/stocks.csv` does not exist when the
application starts, this file is automatically copied there as a starting template.

## File: `stocks.csv`

```csv
stock_label,amount,target_weight,letf
AAPL,10,25.0,
MSFT,8,25.0,
```

**Columns**:
- `stock_label`: Stock ticker symbol (e.g., "AAPL", "AVGS.L")
- `amount`: Number of shares held (integer)
- `target_weight`: Target allocation percentage (optional, decimal, e.g., 25.0 for 25%)
- `letf`: LETF component formula (optional). Space-separated pairs of `multiplier symbol`.
  - Example: `2 IVV` means estimated intraday value = 2× IVV daily % applied to last NAV/close price
  - Example: `1 CTA 1 IVV` means 1× CTA + 1× IVV component tracking
  - Leave blank (empty field) for regular stocks and ETFs

## File: `cash.txt`

**Format**: `Label.CURRENCY[.M]=amount`

```
# Cash balances — format: Label.CURRENCY[.M]=amount
# Append .M to mark entry as a margin (borrowed) balance
Cash.USD=1234
Loan.HKD=123
Interest.USD=-700
```

**Key format rules**:
- `Label`: Any descriptive name (e.g., "Cash", "Loan", "Interest")
- `CURRENCY`: ISO currency code (e.g., USD, HKD). Non-USD currencies are auto-converted using Yahoo Finance FX rates.
- `.M` suffix: Marks the entry as a margin/borrowing balance, shown separately in the Margin row.
- Lines starting with `#` are comments; empty lines are ignored.

## Using Your Own Data

To use your own portfolio data, replace `data/stocks.csv` and optionally `data/cash.txt`
in the application directory, or configure the path via:

1. **Environment Variable** (recommended):
   ```bash
   export CSV_FILE_PATH=/path/to/your/portfolio.csv
   java -jar portfolio-helper-all.jar
   ```

2. **Replace the file**: Edit `data/stocks.csv` in the extracted/run directory

3. **System Property**:
   ```bash
   java -Dcsv.file.path=/custom/path.csv -jar portfolio-helper-all.jar
   ```

The application will automatically reload when you save changes to either CSV or cash.txt.
