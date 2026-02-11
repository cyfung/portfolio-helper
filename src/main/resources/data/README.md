# Sample Portfolio Data

This is sample/demonstration data included in distributions.

**File**: `stocks.csv`

Contains example holdings for 10 popular tech stocks. This data is for demonstration purposes only.

## Format

```csv
stock_label,amount,target_weight
AAPL,50,15.0
```

**Columns**:
- `stock_label`: Stock ticker symbol
- `amount`: Number of shares
- `target_weight`: Target allocation percentage (optional)

## Using Your Own Data

To use your own portfolio data:

1. **Environment Variable** (recommended):
   ```bash
   export CSV_FILE_PATH=/path/to/your/portfolio.csv
   java -jar portfolio-helper-all.jar
   ```

2. **Replace the file**: Edit `data/stocks.csv` in the extracted directory

3. **System Property**:
   ```bash
   java -Dcsv.file.path=/custom/path.csv -jar portfolio-helper-all.jar
   ```

The application will automatically reload when you save changes to the CSV file.
